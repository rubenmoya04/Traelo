package dev.rubenmoya.traelo.core

import java.io.File

/** Un repo de GitHub: dueño y nombre. */
data class RepoRef(val owner: String, val name: String) {
    val fullName: String get() = "$owner/$name"

    /** Nombre seguro para usar como nombre de fichero. */
    val key: String get() = "${owner}__$name"

    companion object {
        fun parse(fullName: String): RepoRef {
            val parts = fullName.split("/")
            require(parts.size == 2 && parts.all { it.isNotBlank() }) { "Repo no valido: $fullName" }
            return RepoRef(parts[0], parts[1])
        }
    }
}

data class RemoteRepo(
    val ref: RepoRef,
    val isPrivate: Boolean,
    val defaultBranch: String,
    /** Fecha ISO del ultimo push, tal como la da GitHub. */
    val pushedAt: String?,
)

data class Branch(val name: String, val sha: String)

/** Un archivo del arbol de una rama. [sha] es la huella git (blob) del contenido. */
data class TreeEntry(val path: String, val sha: String, val size: Long)

data class TreeResult(val entries: List<TreeEntry>, val skipped: List<String>, val truncated: Boolean)

data class CommitInfo(val sha: String, val title: String, val author: String, val date: String)

/** Lo ultimo que se trajo de un repo. La lista de archivos va aparte, en el [ManifestStore]. */
data class SyncState(
    val repo: RepoRef,
    val branch: String,
    val headSha: String,
    val syncedAtMillis: Long,
    /** Carpeta del movil donde estan los archivos. */
    val localDir: String,
)

data class PlanCounts(val added: Int, val modified: Int, val removed: Int, val unchanged: Int) {
    val changes: Int get() = added + modified + removed
}

sealed interface CheckResult {
    val headSha: String

    /** La rama no tiene nada nuevo respecto a lo que hay en el movil. */
    data class UpToDate(override val headSha: String) : CheckResult

    /**
     * Hay cambios. [commits] es la lista de commits nuevos (mas reciente primero) o null si no se
     * pudo comparar (por ejemplo porque se reescribio el historial o se cambio de rama).
     */
    data class Outdated(
        override val headSha: String,
        val counts: PlanCounts,
        val commits: List<CommitInfo>?,
    ) : CheckResult

    /** Todavia no se ha traido nada de este repo. [files] es el numero de archivos de la rama. */
    data class NeverSynced(override val headSha: String, val files: Int) : CheckResult
}

data class PullResult(
    val newState: SyncState,
    val downloaded: Int,
    val removed: Int,
    /** Rutas que no se traen (enlaces simbolicos, submodulos). */
    val skipped: List<String>,
)

enum class SyncPhase { CHECKING, DOWNLOADING_ZIP, EXTRACTING, DOWNLOADING_FILES, REMOVING, SAVING }

data class SyncProgress(val phase: SyncPhase, val done: Int, val total: Int, val path: String? = null)

/** Archivos que ya no estan como en GitHub (los toco algo en el movil, por ejemplo Godot). */
data class LocalChanges(val modified: List<String>, val missing: List<String>) {
    val isEmpty: Boolean get() = modified.isEmpty() && missing.isEmpty()
}

/** Guarda, por repo, la lista ruta -> huella de los archivos que se trajeron. */
interface ManifestStore {
    fun load(repo: RepoRef): Map<String, String>

    fun save(repo: RepoRef, files: Map<String, String>)

    fun clear(repo: RepoRef)
}

/** Para borrar del motor: ruta de carpeta como File. */
fun SyncState.localFile(): File = File(localDir)
