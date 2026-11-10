package dev.rubenmoya.traelo.core

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Motor de sincronizacion. De GitHub a una carpeta, nunca al reves: no hay push ni commits.
 *
 * Reglas que no se rompen:
 * - Solo se borran archivos que el propio motor trajo antes (estan en el manifiesto con su huella)
 *   y que ya no estan en la rama o que ahora ignora el `.traeloignore`. Lo demas de la carpeta no
 *   se borra nunca, y la cache `.godot` no se toca jamas.
 * - Nada del movil se pierde en silencio: si un archivo que hay que pisar o quitar se cambio en el
 *   movil (o no lo trajo Traelo), primero se mueve a la carpeta de conflictos, fuera del proyecto.
 * - Cada archivo se baja a un temporal, se comprueba su huella mientras se escribe y solo entonces
 *   sustituye al viejo.
 * - Las rutas se validan antes de escribir (nada fuera de la carpeta, nada de `..`).
 * - Una sola sincronizacion por repo a la vez; la siguiente espera su turno.
 * - El manifiesto (commit, rama y archivos) se guarda de una vez y solo al acabar bien. Si algo
 *   se corta, la siguiente vuelta compara huellas y retoma sin bajar otra vez lo que ya llego.
 * - Lo que el `.traeloignore` del repo ignora no se baja. Si Traelo lo trajo antes y sigue intacto,
 *   se quita; si se toco en el movil, se aparta. Lo ignorado sin huella conocida (estados de
 *   versiones viejas, archivos del usuario) solo se cuenta: se aparta si la persona lo pide.
 *
 * [onProgress] y [onConflict] se llaman desde varios hilos: tienen que aguantarlo.
 */
class SyncEngine(
    private val api: GitHubApi,
    private val manifests: ManifestStore,
    private val zipPolicy: ZipPolicy = ZipPolicy.DEFAULT,
    private val parallelism: Int = 24,
    private val hashParallelism: Int = 4,
    private val progressIntervalMs: Long = 120,
    private val maxAttempts: Int = 3,
    private val retryDelayMs: Long = 400,
    private val clock: () -> Long = System::currentTimeMillis,
    private val conflictDirFor: (File) -> File = ::defaultConflictDir,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val views = object : LinkedHashMap<String, RemoteView>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RemoteView>?) = size > MAX_CACHED_VIEWS
    }

    private fun lockFor(repo: RepoRef): Mutex = locks.getOrPut(repo.key) { Mutex() }

    /** Olvida lo traido de [repo] (despues de borrar su carpeta). */
    suspend fun forget(repo: RepoRef) = lockFor(repo).withLock { manifests.clear(repo) }

    /** true si ahora mismo se esta trayendo [repo]. */
    fun isBusy(repo: RepoRef): Boolean = locks[repo.key]?.isLocked == true

    /** Mira que hay nuevo sin bajar archivos. [state] es null si todavia no se trajo nada. */
    suspend fun check(repo: RepoRef, branch: String, state: SyncState?): CheckResult =
        withContext(Dispatchers.IO) {
            val head = api.branchHead(repo, branch)
            if (state == null) {
                return@withContext CheckResult.NeverSynced(head, remoteView(repo, head).entries.size)
            }
            if (state.branch == branch && state.headSha == head) {
                return@withContext CheckResult.UpToDate(head)
            }
            val view = remoteView(repo, head)
            val known = knownFiles(manifests.load(repo))
            val plan = SyncPlanner.plan(view.entries, known, state.localFile(), verifyAll = false, hashParallelism)
            val commits = try {
                api.commitsBetween(repo, state.headSha, head)
            } catch (e: GitHubException.NotFound) {
                null
            } catch (e: GitHubException.Http) {
                null
            }
            CheckResult.Outdated(head, plan.counts, commits)
        }

    /**
     * Trae la rama a [localDir] (solo lo que cambio). Cambiar de [branch] usa el mismo camino.
     * Con [verifyLocal] mira todos los archivos del movil y repone los que no coincidan (lo del
     * movil se guarda aparte igualmente).
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun pull(
        repo: RepoRef,
        branch: String,
        localDir: File,
        state: SyncState?,
        verifyLocal: Boolean = false,
        onConflict: (ConflictFile) -> Unit = {},
        onProgress: (SyncProgress) -> Unit = {},
    ): PullResult {
        val lock = lockFor(repo)
        if (lock.isLocked) onProgress(SyncProgress(SyncPhase.WAITING, 0, 0))
        return lock.withLock {
            withContext(Dispatchers.IO) { pullLocked(repo, branch, localDir, verifyLocal, onConflict, onProgress) }
        }
    }

    private suspend fun pullLocked(
        repo: RepoRef,
        branch: String,
        localDir: File,
        verifyLocal: Boolean,
        onConflict: (ConflictFile) -> Unit,
        onProgress: (SyncProgress) -> Unit,
    ): PullResult {
        val progress = ProgressTracker(onProgress, progressIntervalMs, clock)
        progress.phase(SyncPhase.CHECKING)
        val head = api.branchHead(repo, branch)
        val manifest = manifests.load(repo)
        val newState = { SyncState(repo, branch, head, clock(), localDir.absolutePath) }

        // Salida rapida: la rama no cambio y todo lo traido sigue en su sitio. Ni se pide el arbol.
        // Un manifiesto antiguo (sin el `.traeloignore` apuntado) mira el arbol una vez.
        val fast = manifest.head == head && manifest.branch == branch && manifest.ignore != null
        if (!verifyLocal && fast && allPresent(localDir, manifest.files)) {
            return PullResult(newState(), downloaded = 0, removed = 0, skipped = emptyList(), fastPath = true)
        }

        val view = remoteView(repo, head)
        if (!localDir.isDirectory && !localDir.mkdirs()) {
            throw IOException("No se puede crear la carpeta ${localDir.path}")
        }
        val root = localDir.canonicalFile
        progress.phase(SyncPhase.COMPARING)
        val plan = SyncPlanner.plan(view.entries, knownFiles(manifest), root, verifyLocal, hashParallelism)
        val conflicts = ConflictSession(File(conflictDirFor(localDir.absoluteFile), stamp(clock())), onConflict)
        val network = AtomicLong()

        removeFiles(plan.remove, root, conflicts, progress) { path ->
            if (view.rules.ignores(path)) ConflictReason.IGNORED else ConflictReason.REMOVED_UPSTREAM
        }
        var pending = plan.download
        if (pending.isNotEmpty() && zipPolicy.useZip(pending.size, plan.downloadBytes, view.allBytes)) {
            pending = fetchFromZip(repo, head, root, pending, view.allBytes, conflicts, progress, network)
        }
        downloadFiles(repo, pending, root, conflicts, progress, network)

        progress.phase(SyncPhase.SAVING)
        val unsafe = plan.unsafe.toHashSet()
        val finalFiles = HashMap<String, String>(view.entries.size * 2)
        for (entry in view.entries) if (entry.path !in unsafe) finalFiles[entry.path] = entry.sha
        manifests.save(repo, Manifest(finalFiles, head, branch, view.ignoreText))

        val saved = conflicts.saved.toList().sortedBy { it.path }
        return PullResult(
            newState = newState(),
            downloaded = plan.download.size,
            removed = plan.remove.size,
            skipped = view.skipped + plan.unsafe,
            ignored = view.ignored,
            conflicts = saved,
            conflictDir = if (saved.isEmpty()) null else conflicts.dir.absolutePath,
            bytesDownloaded = network.get(),
        )
    }

    /** Archivos traidos que ya no estan como en GitHub. Las huellas se calculan en paralelo. */
    suspend fun findLocalChanges(state: SyncState): LocalChanges =
        withContext(Dispatchers.IO) {
            val root = state.localFile().canonicalFile
            val files = manifests.load(state.repo).files.toSortedMap()
            val present = ArrayList<Pair<String, File>>(files.size)
            val missing = ArrayList<String>()
            for (path in files.keys) {
                val file = SafePaths.resolveUnder(root, path) ?: continue
                if (file.isFile) present += path to file else missing += path
            }
            val hashes = SyncPlanner.hashAll(present.map { it.second }, hashParallelism)
            val modified = present.filter { (path, file) -> hashes[file] != files[path] }.map { it.first }
            LocalChanges(modified, missing)
        }

    /**
     * Archivos que el `.traeloignore` ignora y siguen en el movil sin que Traelo sepa su huella:
     * restos de versiones viejas (que no la guardaban) o cosas creadas en el movil. No se borran
     * solos; [moveIgnoredLeftovers] los aparta si la persona lo pide. Nunca mira dentro de `.godot`.
     * Usa las reglas de la ultima sincronizacion, sin red.
     */
    suspend fun findIgnoredLeftovers(state: SyncState): List<String> =
        withContext(Dispatchers.IO) { ignoredLeftovers(state.localFile(), manifests.load(state.repo)) }

    /**
     * "Quitar lo ignorado": mueve a la carpeta de conflictos los [paths] que sigan siendo restos
     * ignorados (lo demas no se toca) y quita las carpetas que se queden vacias. No borra nada.
     */
    suspend fun moveIgnoredLeftovers(
        state: SyncState,
        paths: Collection<String>,
        onConflict: (ConflictFile) -> Unit = {},
    ): MovedAside = lockFor(state.repo).withLock {
        withContext(Dispatchers.IO) {
            val localDir = state.localFile()
            val still = ignoredLeftovers(localDir, manifests.load(state.repo)).toHashSet()
            val root = localDir.canonicalFile
            val conflicts = ConflictSession(File(conflictDirFor(localDir.absoluteFile), stamp(clock())), onConflict)
            for (path in paths.distinct().sorted()) {
                if (path !in still) continue
                val file = SafePaths.resolveUnder(root, path) ?: continue
                if (!file.isFile) continue
                conflicts.keep(file, path, ConflictReason.IGNORED)
                pruneEmptyDirs(file.parentFile, root)
            }
            val saved = conflicts.saved.toList().sortedBy { it.path }
            MovedAside(saved, if (saved.isEmpty()) null else conflicts.dir.absolutePath)
        }
    }

    private fun ignoredLeftovers(localDir: File, manifest: Manifest): List<String> {
        val text = manifest.ignore ?: return emptyList()
        val rules = IgnoreRules.parse(text)
        if (rules.isEmpty || !localDir.isDirectory) return emptyList()
        val known = knownFiles(manifest)
        val out = ArrayList<String>()
        fun visit(dir: File, prefix: String) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) continue
                val relative = prefix + child.name
                if (child.isDirectory) {
                    if (child.name != SafePaths.GODOT_DIR && child.name != ".git") visit(child, "$relative/")
                } else if (child.isFile && rules.ignores(relative) && relative !in known) {
                    out += relative
                }
            }
        }
        visit(localDir, "")
        return out.sorted()
    }

    // ------------------------------------------------------------------ arbol remoto

    /** La rama tal como la gestiona Traelo: el arbol menos lo que ignora el `.traeloignore`. */
    private class RemoteView(
        val entries: List<TreeEntry>,
        val skipped: List<String>,
        val rules: IgnoreRules,
        /** El `.traeloignore` tal cual ("" si no hay): se apunta en el manifiesto. */
        val ignoreText: String,
        val ignored: Int,
        /** Lo que pesa la rama entera, ignorados incluidos: es lo que trae el zip. */
        val allBytes: Long,
    )

    /**
     * Lo traido con huella conocida. Lo ignorado tambien cuenta: como no esta en la rama que se
     * gestiona, el plan lo quita (si sigue intacto) o lo aparta (si se toco en el movil).
     */
    private fun knownFiles(manifest: Manifest): Map<String, String> =
        if (manifest.files.values.all { it.isNotBlank() }) manifest.files else manifest.files.filterValues { it.isNotBlank() }

    /** El arbol de un commit no cambia nunca, asi que se guarda un rato (comprobar y luego traer). */
    private fun remoteView(repo: RepoRef, head: String): RemoteView {
        val key = "${repo.key}@$head"
        synchronized(views) { views[key] }?.let { return it }
        val tree = api.tree(repo, head)
        if (tree.truncated) throw GitHubException.TooBig()
        val text = loadIgnoreText(repo, tree.entries)
        val rules = IgnoreRules.parse(text)
        val allBytes = tree.entries.sumOf { it.size }
        val view = if (rules.isEmpty) {
            RemoteView(tree.entries, tree.skipped, rules, text, 0, allBytes)
        } else {
            val kept = tree.entries.filter { !rules.ignores(it.path) }
            RemoteView(kept, tree.skipped, rules, text, tree.entries.size - kept.size, allBytes)
        }
        synchronized(views) { views[key] = view }
        return view
    }

    private fun loadIgnoreText(repo: RepoRef, entries: List<TreeEntry>): String {
        val entry = entries.firstOrNull { it.path == IgnoreRules.FILE_NAME } ?: return ""
        if (entry.size > MAX_IGNORE_BYTES) return ""
        val out = ByteArrayOutputStream()
        api.downloadBlob(repo, entry.sha, out)
        return out.toString(Charsets.UTF_8.name())
    }

    private fun allPresent(localDir: File, files: Map<String, String>): Boolean =
        localDir.isDirectory && files.keys.all { File(localDir, it).isFile }

    // ------------------------------------------------------------------ conflictos

    /** Carpeta de conflictos de esta sincronizacion; se crea solo si hace falta. */
    private class ConflictSession(val dir: File, private val onConflict: (ConflictFile) -> Unit) {
        val saved = ConcurrentLinkedQueue<ConflictFile>()

        fun keep(file: File, relative: String, reason: ConflictReason) {
            val dest = reserve(relative)
            moveFile(file, dest)
            val conflict = ConflictFile(relative, dest.absolutePath, reason)
            saved += conflict
            onConflict(conflict)
        }

        @Synchronized
        private fun reserve(relative: String): File {
            val wanted = File(dir, relative)
            val parent = wanted.parentFile ?: dir
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IOException("No se puede crear la carpeta de conflictos ${parent.path}")
            }
            if (!wanted.exists()) return wanted
            val base = wanted.name.substringBeforeLast('.', wanted.name)
            val ext = wanted.name.substring(base.length)
            var n = 2
            while (true) {
                val candidate = File(parent, "$base ($n)$ext")
                if (!candidate.exists()) return candidate
                n++
            }
        }
    }

    private fun reasonFor(planned: PlannedFile) = when (planned.kind) {
        PlanKind.ADDED -> ConflictReason.UNTRACKED
        PlanKind.MODIFIED -> ConflictReason.CHANGED_BOTH
        PlanKind.RESTORED -> ConflictReason.RESTORED
    }

    // ------------------------------------------------------------------ borrar

    private suspend fun removeFiles(
        removals: List<PlannedRemoval>,
        root: File,
        conflicts: ConflictSession,
        progress: ProgressTracker,
        reasonFor: (String) -> ConflictReason,
    ) {
        if (removals.isEmpty()) return
        progress.phase(SyncPhase.REMOVING, total = removals.size)
        for (removal in removals) {
            currentCoroutineContext().ensureActive()
            val file = SafePaths.resolveUnder(root, removal.path)
            if (file != null && file.isFile) {
                if (removal.conflict) conflicts.keep(file, removal.path, reasonFor(removal.path)) else file.delete()
                pruneEmptyDirs(file.parentFile, root)
            }
            progress.fileDone(removal.path)
        }
    }

    /** Quita carpetas que se quedaron vacias, sin salir de [root], sin tocar nada que no este vacio ni `.godot`. */
    private fun pruneEmptyDirs(start: File?, root: File) {
        var dir = start
        while (dir != null && dir != root && dir.path.startsWith(root.path + File.separator)) {
            if (dir.name == SafePaths.GODOT_DIR) return
            if (dir.list()?.isEmpty() != true) return
            dir.delete()
            dir = dir.parentFile
        }
    }

    // ------------------------------------------------------------------ bajar archivo a archivo

    private suspend fun downloadFiles(
        repo: RepoRef,
        files: List<PlannedFile>,
        root: File,
        conflicts: ConflictSession,
        progress: ProgressTracker,
        network: AtomicLong,
    ) {
        if (files.isEmpty()) return
        progress.phase(SyncPhase.DOWNLOADING_FILES, total = files.size, bytesTotal = files.sumOf { it.entry.size })
        val permits = Semaphore(parallelism)
        coroutineScope {
            for (planned in files) {
                launch { permits.withPermit { downloadOne(repo, planned, root, conflicts, progress, network) } }
            }
        }
    }

    private suspend fun downloadOne(
        repo: RepoRef,
        planned: PlannedFile,
        root: File,
        conflicts: ConflictSession,
        progress: ProgressTracker,
        network: AtomicLong,
    ) {
        val entry = planned.entry
        val target = SafePaths.resolveUnder(root, entry.path) ?: throw IOException("Ruta no segura: ${entry.path}")
        val job = currentCoroutineContext().job
        var attempt = 0
        while (true) {
            attempt++
            job.ensureActive()
            val temp = prepareTarget(target)
            var counted = 0L
            try {
                val ok = BufferedOutputStream(FileOutputStream(temp), WRITE_BUFFER).use { file ->
                    val hashing = BlobHashingOutputStream(
                        file,
                        entry.size,
                        beforeWrite = { job.ensureActive() },
                        onBytes = {
                            counted += it
                            network.addAndGet(it.toLong())
                            progress.bytes(it)
                        },
                    )
                    api.downloadBlob(repo, entry.sha, hashing)
                    hashing.flush()
                    hashing.matches(entry.sha)
                }
                if (!ok) throw GitHubException.Integrity(entry.path)
                place(temp, target, planned, conflicts)
                progress.fileDone(entry.path)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.bytes(-counted.toInt())
                if (attempt >= maxAttempts || !isRetryable(e)) throw e
                delay(retryDelayMs * attempt)
            } finally {
                temp.delete()
            }
        }
    }

    private fun isRetryable(e: Exception): Boolean = when (e) {
        is GitHubException.Network, is GitHubException.Integrity -> true
        is GitHubException.Http -> e.code >= 500
        else -> false
    }

    /** Pone el temporal en su sitio. Si lo de ahora es un cambio del movil, antes lo guarda aparte. */
    private fun place(temp: File, target: File, planned: PlannedFile, conflicts: ConflictSession) {
        if (planned.conflict && target.isFile) conflicts.keep(target, planned.entry.path, reasonFor(planned))
        moveInto(temp, target)
    }

    /** Prepara la carpeta destino y devuelve un temporal unico que va junto al archivo final. */
    private fun prepareTarget(target: File): File {
        val parent = target.parentFile ?: throw IOException("Ruta sin carpeta: ${target.path}")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("En el movil hay un archivo donde tendria que haber la carpeta ${parent.name}")
        }
        if (target.isDirectory) {
            throw IOException("En el movil hay una carpeta donde tendria que haber el archivo ${target.name}")
        }
        return File(parent, ".${target.name}.${tempCounter.incrementAndGet()}$PART_SUFFIX")
    }

    // ------------------------------------------------------------------ zip

    /**
     * Muchos archivos que bajar: un solo zip en vez de una peticion por archivo. Se lee mientras
     * llega (sin guardarlo entero) y solo se escribe lo que el plan necesita, con ruta segura y
     * huella comprobada al vuelo. Devuelve lo que falta: lo que no venia en el zip, lo que no
     * coincidia (saltos de linea, export-subst) o todo lo que quedara si el zip se corto.
     */
    private suspend fun fetchFromZip(
        repo: RepoRef,
        head: String,
        root: File,
        files: List<PlannedFile>,
        zipBytes: Long,
        conflicts: ConflictSession,
        progress: ProgressTracker,
        network: AtomicLong,
    ): List<PlannedFile> {
        val wanted = files.associateBy { it.entry.path }
        val placed = HashSet<String>()
        val job = currentCoroutineContext().job
        progress.phase(SyncPhase.DOWNLOADING_ZIP, total = files.size, bytesTotal = zipBytes)
        try {
            api.openZip(repo, head) { input ->
                val counted = CountingInputStream(input, network)
                ZipInputStream(ReadAheadInputStream(counted)).use { zip ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val item = zip.nextEntry ?: break
                        job.ensureActive()
                        if (item.isDirectory) continue
                        val relative = item.name.substringAfter('/', missingDelimiterValue = "")
                        val planned = wanted[relative]
                        val target = if (planned != null && relative !in placed) SafePaths.resolveUnder(root, relative) else null
                        if (planned == null || target == null) {
                            drain(zip, buffer, progress, job)
                        } else if (extract(zip, buffer, planned, target, conflicts, progress, job)) {
                            placed += relative
                            progress.fileDone(relative)
                        }
                    }
                }
            }
        } catch (e: GitHubException.Network) {
            // El zip se corto: lo que falte se baja archivo a archivo.
        } catch (e: ZipException) {
            // Zip roto: igual.
        } catch (e: GitHubException.Http) {
            // GitHub no pudo dar el zip: igual.
        }
        return files.filter { it.entry.path !in placed }
    }

    private fun drain(zip: ZipInputStream, buffer: ByteArray, progress: ProgressTracker, job: Job) {
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) return
            job.ensureActive()
            progress.bytes(n)
        }
    }

    private fun extract(
        zip: ZipInputStream,
        buffer: ByteArray,
        planned: PlannedFile,
        target: File,
        conflicts: ConflictSession,
        progress: ProgressTracker,
        job: Job,
    ): Boolean {
        val temp = prepareTarget(target)
        try {
            val ok = BufferedOutputStream(FileOutputStream(temp), WRITE_BUFFER).use { file ->
                val hashing = BlobHashingOutputStream(file, planned.entry.size)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    job.ensureActive()
                    hashing.write(buffer, 0, n)
                    progress.bytes(n)
                }
                hashing.flush()
                hashing.matches(planned.entry.sha)
            }
            if (!ok) return false
            place(temp, target, planned, conflicts)
            return true
        } finally {
            temp.delete()
        }
    }

    /** Cuenta los bytes que llegan de verdad por la red (el zip va comprimido). */
    private class CountingInputStream(input: InputStream, private val counter: AtomicLong) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) counter.incrementAndGet() }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) counter.addAndGet(it.toLong()) }
    }

    companion object {
        /** Un `.traeloignore` mas grande que esto no es un archivo de reglas: se ignora. */
        private const val MAX_IGNORE_BYTES = 64 * 1024L
        private const val MAX_CACHED_VIEWS = 4
        private const val WRITE_BUFFER = 128 * 1024
        private const val COPY_BUFFER = 64 * 1024
        const val PART_SUFFIX = ".traelo-part"

        private val tempCounter = AtomicLong()

        /** Carpeta de conflictos por defecto: al lado del proyecto, para que Godot no la vea. */
        fun defaultConflictDir(localDir: File): File =
            File(localDir.parentFile ?: localDir, "${localDir.name}-conflictos")

        private fun stamp(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date(millis))

        private fun moveInto(temp: File, target: File) {
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        /** Mueve un archivo; si esta en otro disco, lo copia y borra el original. */
        private fun moveFile(from: File, to: File) {
            try {
                Files.move(from.toPath(), to.toPath())
            } catch (e: IOException) {
                Files.copy(from.toPath(), to.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                Files.delete(from.toPath())
            }
        }
    }
}
