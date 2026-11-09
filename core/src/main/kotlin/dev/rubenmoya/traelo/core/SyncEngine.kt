package dev.rubenmoya.traelo.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Motor de sincronizacion. De GitHub a una carpeta, nunca al reves: no hay push ni commits.
 *
 * Reglas que no se rompen:
 * - Solo se borran archivos que el propio motor trajo antes (estan en el manifiesto) y que ya no
 *   estan en la rama. Lo demas de la carpeta (por ejemplo la cache `.godot`) no se toca jamas.
 * - Cada archivo se baja a un temporal, se comprueba su huella y solo entonces sustituye al viejo.
 * - Las rutas se validan antes de escribir (nada fuera de la carpeta, nada de `..`).
 * - El manifiesto refleja lo que de verdad hay en disco, tambien si la descarga se corta a medias.
 * - Lo que el `.traeloignore` del repo ignora no se baja ni se gestiona, pero tampoco se borra.
 * - Si hay muchos archivos que bajar (primera vez o una tanda grande de cambios) se baja un solo zip
 *   y se sacan de el solo los archivos que hacen falta.
 *
 * [onProgress] se llama desde varios hilos: tiene que aguantarlo.
 */
class SyncEngine(
    private val api: GitHubApi,
    private val manifests: ManifestStore,
    private val zipThreshold: Int = 60,
    private val parallelism: Int = 8,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Olvida lo traido de [repo] (despues de borrar su carpeta). */
    fun forget(repo: RepoRef) = manifests.clear(repo)

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
            val plan = SyncPlanner.plan(view.entries, view.managed(manifests.load(repo)), state.localFile(), verifyAll = false)
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
     * Con [verifyLocal] mira todos los archivos del movil y repone los que no coincidan.
     */
    suspend fun pull(
        repo: RepoRef,
        branch: String,
        localDir: File,
        state: SyncState?,
        verifyLocal: Boolean = false,
        onProgress: (SyncProgress) -> Unit = {},
    ): PullResult =
        withContext(Dispatchers.IO) {
            onProgress(SyncProgress(SyncPhase.CHECKING, 0, 0))
            val head = api.branchHead(repo, branch)
            val view = remoteView(repo, head)
            if (!localDir.isDirectory && !localDir.mkdirs()) {
                throw IOException("No se puede crear la carpeta ${localDir.path}")
            }
            val manifest = LinkedHashMap(view.managed(manifests.load(repo)))
            var plan = SyncPlanner.plan(view.entries, manifest, localDir, verifyLocal)
            val fromZip = HashSet<String>()
            val applied = ConcurrentHashMap<String, String>()
            val removedPaths = ConcurrentHashMap.newKeySet<String>()
            try {
                if (plan.download.size >= zipThreshold) {
                    val wanted = plan.download.mapTo(HashSet()) { it.entry.path }
                    fromZip += fetchZip(repo, head, localDir, wanted, onProgress)
                    plan = SyncPlanner.plan(view.entries, manifest, localDir, verifyLocal)
                }
                removeFiles(plan, localDir, removedPaths, onProgress)
                downloadFiles(repo, plan, localDir, applied, onProgress)
                onProgress(SyncProgress(SyncPhase.SAVING, 0, 0))
                val unsafe = plan.unsafe.toSet()
                val finalManifest = LinkedHashMap<String, String>()
                for (entry in view.entries) if (entry.path !in unsafe) finalManifest[entry.path] = entry.sha
                manifests.save(repo, finalManifest)
                PullResult(
                    newState = SyncState(repo, branch, head, clock(), localDir.absolutePath),
                    downloaded = (fromZip + plan.download.map { it.entry.path }).size,
                    removed = plan.remove.size,
                    skipped = view.skipped + plan.unsafe,
                    ignored = view.ignored,
                )
            } catch (e: Throwable) {
                withContext(NonCancellable) {
                    savePartial(repo, manifest, plan, applied.toMap(), removedPaths.toSet())
                }
                throw e
            }
        }

    /** Archivos traidos que ya no estan como en GitHub. */
    suspend fun findLocalChanges(state: SyncState): LocalChanges =
        withContext(Dispatchers.IO) {
            val dir = state.localFile()
            val modified = ArrayList<String>()
            val missing = ArrayList<String>()
            for ((path, sha) in manifests.load(state.repo).toSortedMap()) {
                currentCoroutineContext().ensureActive()
                val file = SafePaths.resolve(dir, path) ?: continue
                when {
                    !file.isFile -> missing += path
                    GitBlobHash.ofFile(file) != sha -> modified += path
                }
            }
            LocalChanges(modified, missing)
        }

    private fun loadTree(repo: RepoRef, head: String): TreeResult {
        val tree = api.tree(repo, head)
        if (tree.truncated) throw GitHubException.TooBig()
        return tree
    }

    /** La rama tal como la gestiona Traelo: el arbol menos lo que ignora el `.traeloignore`. */
    private class RemoteView(val entries: List<TreeEntry>, val skipped: List<String>, val rules: IgnoreRules, val ignored: Int) {
        /** El manifiesto sin lo ignorado: eso deja de gestionarse, y por tanto nunca se borra. */
        fun managed(manifest: Map<String, String>): Map<String, String> =
            if (rules.isEmpty) manifest else manifest.filterKeys { !rules.ignores(it) }
    }

    private fun remoteView(repo: RepoRef, head: String): RemoteView {
        val tree = loadTree(repo, head)
        val rules = loadIgnoreRules(repo, tree.entries)
        if (rules.isEmpty) return RemoteView(tree.entries, tree.skipped, rules, 0)
        val kept = tree.entries.filter { !rules.ignores(it.path) }
        return RemoteView(kept, tree.skipped, rules, tree.entries.size - kept.size)
    }

    private fun loadIgnoreRules(repo: RepoRef, entries: List<TreeEntry>): IgnoreRules {
        val entry = entries.firstOrNull { it.path == IgnoreRules.FILE_NAME } ?: return IgnoreRules.NONE
        if (entry.size > MAX_IGNORE_BYTES) return IgnoreRules.NONE
        val out = ByteArrayOutputStream()
        api.downloadBlob(repo, entry.sha, out)
        return IgnoreRules.parse(out.toString(Charsets.UTF_8.name()))
    }

    private fun savePartial(
        repo: RepoRef,
        old: Map<String, String>,
        plan: SyncPlan,
        applied: Map<String, String>,
        removed: Set<String>,
    ) {
        val partial = LinkedHashMap(old)
        removed.forEach { partial.remove(it) }
        plan.upToDate.forEach { partial[it.path] = it.sha }
        partial.putAll(applied)
        runCatching { manifests.save(repo, partial) }
    }

    private suspend fun removeFiles(
        plan: SyncPlan,
        localDir: File,
        removedPaths: MutableSet<String>,
        onProgress: (SyncProgress) -> Unit,
    ) {
        val root = localDir.canonicalFile
        for ((index, path) in plan.remove.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(SyncProgress(SyncPhase.REMOVING, index, plan.remove.size, path))
            val file = SafePaths.resolve(localDir, path)
            if (file != null && file.isFile) {
                file.delete()
                pruneEmptyDirs(file.parentFile, root)
            }
            removedPaths += path
        }
    }

    /** Quita carpetas que se quedaron vacias, sin salir de [root] y sin tocar nada que no este vacio. */
    private fun pruneEmptyDirs(start: File?, root: File) {
        var dir = start
        while (dir != null && dir != root && dir.path.startsWith(root.path + File.separator)) {
            if (dir.list()?.isEmpty() != true) return
            dir.delete()
            dir = dir.parentFile
        }
    }

    private suspend fun downloadFiles(
        repo: RepoRef,
        plan: SyncPlan,
        localDir: File,
        applied: MutableMap<String, String>,
        onProgress: (SyncProgress) -> Unit,
    ) {
        val total = plan.download.size
        val done = AtomicInteger(0)
        val permits = Semaphore(parallelism)
        onProgress(SyncProgress(SyncPhase.DOWNLOADING_FILES, 0, total))
        coroutineScope {
            plan.download.map { planned ->
                async {
                    permits.withPermit {
                        currentCoroutineContext().ensureActive()
                        downloadOne(repo, planned.entry, localDir)
                        applied[planned.entry.path] = planned.entry.sha
                        onProgress(
                            SyncProgress(SyncPhase.DOWNLOADING_FILES, done.incrementAndGet(), total, planned.entry.path)
                        )
                    }
                }
            }.awaitAll()
        }
    }

    private fun downloadOne(repo: RepoRef, entry: TreeEntry, localDir: File) {
        val target = SafePaths.resolve(localDir, entry.path) ?: throw IOException("Ruta no segura: ${entry.path}")
        val temp = prepareTarget(target)
        try {
            FileOutputStream(temp).use { api.downloadBlob(repo, entry.sha, it) }
            if (GitBlobHash.ofFile(temp) != entry.sha) throw GitHubException.Integrity(entry.path)
            moveInto(temp, target)
        } finally {
            temp.delete()
        }
    }

    /** Prepara la carpeta destino y devuelve el temporal que va junto al archivo final. */
    private fun prepareTarget(target: File): File {
        val parent = target.parentFile ?: throw IOException("Ruta sin carpeta: ${target.path}")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("En el movil hay un archivo donde tendria que haber la carpeta ${parent.name}")
        }
        if (target.isDirectory) {
            throw IOException("En el movil hay una carpeta donde tendria que haber el archivo ${target.name}")
        }
        return File(parent, ".${target.name}.traelo-part")
    }

    private fun moveInto(temp: File, target: File) {
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Muchos archivos que bajar: un solo zip en vez de una peticion por archivo. Solo se escribe lo
     * de [allowed] (lo que el plan necesita) y con ruta segura. Despues el planificador compara
     * huellas y lo que no coincida (saltos de linea, export-ignore) se baja uno a uno.
     */
    private fun fetchZip(
        repo: RepoRef,
        head: String,
        localDir: File,
        allowed: Set<String>,
        onProgress: (SyncProgress) -> Unit,
    ): Set<String> {
        val written = HashSet<String>()
        val zipFile = File.createTempFile("traelo", ".zip")
        try {
            onProgress(SyncProgress(SyncPhase.DOWNLOADING_ZIP, 0, 0))
            api.downloadZip(repo, head, zipFile)
            var done = 0
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                var item = zip.nextEntry
                while (item != null) {
                    if (!item.isDirectory) {
                        val relative = item.name.substringAfter('/', missingDelimiterValue = "")
                        val target = if (relative in allowed) SafePaths.resolve(localDir, relative) else null
                        if (target != null) {
                            writeFromStream(zip, target)
                            written += relative
                        }
                        if (target != null) {
                            onProgress(SyncProgress(SyncPhase.EXTRACTING, ++done, allowed.size, relative))
                        }
                    }
                    zip.closeEntry()
                    item = zip.nextEntry
                }
            }
        } finally {
            zipFile.delete()
        }
        return written
    }

    private fun writeFromStream(input: InputStream, target: File) {
        val temp = prepareTarget(target)
        try {
            FileOutputStream(temp).use { input.copyTo(it) }
            moveInto(temp, target)
        } finally {
            temp.delete()
        }
    }

    private companion object {
        /** Un `.traeloignore` mas grande que esto no es un archivo de reglas: se ignora. */
        const val MAX_IGNORE_BYTES = 64 * 1024L
    }
}
