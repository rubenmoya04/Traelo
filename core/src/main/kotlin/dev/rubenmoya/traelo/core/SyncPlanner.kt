package dev.rubenmoya.traelo.core

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

enum class PlanKind { ADDED, MODIFIED, RESTORED }

/**
 * Un archivo que hay que bajar. [conflict] dice que lo que hay ahora en el movil es un cambio
 * hecho alli (no es lo que se trajo la ultima vez), asi que se guarda aparte antes de pisarlo.
 */
data class PlannedFile(val entry: TreeEntry, val kind: PlanKind, val conflict: Boolean = false)

/** Un archivo traido antes que ya no esta en la rama. Con [conflict] se guarda aparte en vez de borrarlo. */
data class PlannedRemoval(val path: String, val conflict: Boolean = false)

data class SyncPlan(
    val download: List<PlannedFile>,
    /** Rutas que se trajeron antes y ya no estan en la rama. Solo salen del manifiesto, nunca del disco a ciegas. */
    val remove: List<PlannedRemoval>,
    /** Archivos que ya estan bien (incluye los adoptados por tener la misma huella). */
    val upToDate: List<TreeEntry>,
    /** Rutas del arbol que no se pueden escribir con seguridad. */
    val unsafe: List<String>,
) {
    val counts: PlanCounts
        get() = PlanCounts(
            added = download.count { it.kind == PlanKind.ADDED },
            modified = download.count { it.kind != PlanKind.ADDED },
            removed = remove.size,
            unchanged = upToDate.size,
            conflicts = download.count { it.conflict } + remove.count { it.conflict },
        )

    val downloadBytes: Long get() = download.sumOf { it.entry.size }
}

/**
 * Decide que bajar, que quitar y que guardar aparte. Solo lee el disco para comparar huellas, y lo
 * hace en paralelo (como mucho [hashParallelism] archivos a la vez).
 */
object SyncPlanner {
    private class Item(val entry: TreeEntry, val file: File, val known: String?, val exists: Boolean)

    /**
     * [verifyAll] mira la huella de todos los archivos del disco. Si no, solo se mira la de los que
     * cambiaron en GitHub o que el manifiesto no conoce. Si un archivo no cambio en GitHub se deja
     * como esta aunque se haya tocado en el movil: eso solo lo repone "restaurar".
     */
    suspend fun plan(
        remote: List<TreeEntry>,
        manifest: Map<String, String>,
        localDir: File,
        verifyAll: Boolean,
        hashParallelism: Int = 4,
    ): SyncPlan {
        val unsafe = ArrayList<String>()
        val items = ArrayList<Item>(remote.size)
        val root = localDir.canonicalFile
        for (entry in remote) {
            val file = SafePaths.resolveUnder(root, entry.path)
            if (file == null) {
                unsafe += entry.path
                continue
            }
            items += Item(entry, file, manifest[entry.path], file.isFile)
        }
        val remotePaths = remote.mapTo(HashSet(remote.size * 2)) { it.path }
        val gone = manifest.keys.filter { it !in remotePaths }.sorted()

        // Huellas que hacen falta: lo que existe y no se puede dar por bueno sin mirar.
        val toHash = ArrayList<File>()
        for (item in items) if (item.exists && needsHash(item, verifyAll)) toHash += item.file
        val goneFiles = gone.associateWith { SafePaths.resolveUnder(root, it)?.takeIf(File::isFile) }
        goneFiles.values.filterNotNullTo(toHash)
        val hashes = hashAll(toHash, hashParallelism)

        val download = ArrayList<PlannedFile>()
        val upToDate = ArrayList<TreeEntry>()
        for (item in items) {
            val entry = item.entry
            val known = item.known
            if (!item.exists) {
                val kind = when (known) {
                    null -> PlanKind.ADDED
                    entry.sha -> PlanKind.RESTORED
                    else -> PlanKind.MODIFIED
                }
                download += PlannedFile(entry, kind)
                continue
            }
            if (!needsHash(item, verifyAll)) {
                upToDate += entry
                continue
            }
            val local = hashes[item.file]
            when {
                local == entry.sha -> upToDate += entry // ya esta igual (adoptar)
                known == null -> download += PlannedFile(entry, PlanKind.ADDED, conflict = true)
                known == entry.sha -> download += PlannedFile(entry, PlanKind.RESTORED, conflict = true)
                else -> download += PlannedFile(entry, PlanKind.MODIFIED, conflict = local != known)
            }
        }
        val remove = gone.map { path ->
            val file = goneFiles[path]
            PlannedRemoval(path, conflict = file != null && hashes[file] != manifest[path])
        }
        return SyncPlan(download, remove, upToDate, unsafe)
    }

    private fun needsHash(item: Item, verifyAll: Boolean) = verifyAll || item.known != item.entry.sha

    /** Huellas de [files] en paralelo. Un archivo que no se puede leer queda sin huella (null). */
    suspend fun hashAll(files: List<File>, parallelism: Int): Map<File, String?> {
        if (files.isEmpty()) return emptyMap()
        val results = arrayOfNulls<String>(files.size)
        val next = AtomicInteger(0)
        coroutineScope {
            repeat(parallelism.coerceIn(1, files.size)) {
                launch(Dispatchers.IO) {
                    while (true) {
                        val index = next.getAndIncrement()
                        if (index >= files.size) break
                        ensureActive()
                        results[index] = runCatching { GitBlobHash.ofFile(files[index]) }.getOrNull()
                    }
                }
            }
        }
        val map = HashMap<File, String?>(files.size * 2)
        for (i in files.indices) map[files[i]] = results[i]
        return map
    }
}
