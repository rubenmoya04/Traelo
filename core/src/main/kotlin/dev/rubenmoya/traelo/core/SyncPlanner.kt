package dev.rubenmoya.traelo.core

import java.io.File

enum class PlanKind { ADDED, MODIFIED, RESTORED }

data class PlannedFile(val entry: TreeEntry, val kind: PlanKind)

data class SyncPlan(
    val download: List<PlannedFile>,
    /** Rutas que se trajeron antes y ya no estan en la rama. Solo salen del manifiesto, nunca del disco a ciegas. */
    val remove: List<String>,
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
        )
}

/** Decide que bajar y que quitar. Es pura: solo lee el disco para comparar huellas. */
object SyncPlanner {
    /**
     * [verifyAll] mira la huella de todos los archivos del disco. Si no, solo se mira la de los que el
     * manifiesto no conoce. Los demas se dan por buenos si existen: un pull normal no pisa lo editado
     * en el movil, eso lo hace "restaurar".
     */
    fun plan(remote: List<TreeEntry>, manifest: Map<String, String>, localDir: File, verifyAll: Boolean): SyncPlan {
        val download = ArrayList<PlannedFile>()
        val upToDate = ArrayList<TreeEntry>()
        val unsafe = ArrayList<String>()
        for (entry in remote) {
            val file = SafePaths.resolve(localDir, entry.path)
            if (file == null) {
                unsafe += entry.path
                continue
            }
            val known = manifest[entry.path]
            val exists = file.isFile
            when {
                known == entry.sha && !verifyAll ->
                    if (exists) upToDate += entry else download += PlannedFile(entry, PlanKind.RESTORED)
                known == entry.sha ->
                    if (matches(file, entry)) upToDate += entry
                    else download += PlannedFile(entry, PlanKind.RESTORED)
                matches(file, entry) -> upToDate += entry // ya esta igual (adoptar)
                known == null -> download += PlannedFile(entry, PlanKind.ADDED)
                else -> download += PlannedFile(entry, PlanKind.MODIFIED)
            }
        }
        val remotePaths = remote.mapTo(HashSet()) { it.path }
        val remove = manifest.keys.filter { it !in remotePaths }.sorted()
        return SyncPlan(download, remove, upToDate, unsafe)
    }

    private fun matches(file: File, entry: TreeEntry): Boolean =
        file.isFile && file.length() == entry.size && GitBlobHash.ofFile(file) == entry.sha
}
