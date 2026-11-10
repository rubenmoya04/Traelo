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

data class PlanCounts(
    val added: Int,
    val modified: Int,
    val removed: Int,
    val unchanged: Int,
    /** Archivos cambiados en el movil que se guardaran aparte antes de pisarlos o quitarlos. */
    val conflicts: Int = 0,
) {
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

/** Por que se guardo aparte un archivo del movil. */
enum class ConflictReason {
    /** Cambio en GitHub y tambien en el movil. */
    CHANGED_BOTH,

    /** Se borro en GitHub, pero en el movil estaba cambiado. */
    REMOVED_UPSTREAM,

    /** Habia un archivo del movil (no traido por Traelo) justo donde llega uno nuevo. */
    UNTRACKED,

    /** "Restaurar": se repone lo de GitHub y lo del movil se guarda por si acaso. */
    RESTORED,
}

/** Un archivo del movil que se movio a la carpeta de conflictos en vez de perderse. */
data class ConflictFile(val path: String, val savedAs: String, val reason: ConflictReason)

data class PullResult(
    val newState: SyncState,
    val downloaded: Int,
    val removed: Int,
    /** Rutas que no se traen (enlaces simbolicos, submodulos). */
    val skipped: List<String>,
    /** Archivos de la rama que no se bajan por el `.traeloignore` del repo. */
    val ignored: Int = 0,
    /** Archivos del movil guardados aparte para no perderlos. */
    val conflicts: List<ConflictFile> = emptyList(),
    /** Carpeta donde quedaron los [conflicts] de esta vez, o null si no hubo. */
    val conflictDir: String? = null,
    /** Bytes bajados de la red (archivos o zip). */
    val bytesDownloaded: Long = 0,
    /** true si no hizo falta ni pedir el arbol: la rama no cambio y todo estaba en su sitio. */
    val fastPath: Boolean = false,
)

enum class SyncPhase { WAITING, CHECKING, COMPARING, DOWNLOADING_ZIP, DOWNLOADING_FILES, REMOVING, SAVING }

/**
 * Por donde va una sincronizacion. [done] y [total] cuentan archivos; los bytes sirven para la
 * barra, la velocidad y el tiempo restante ([etaSeconds] es null si aun no se puede estimar).
 */
data class SyncProgress(
    val phase: SyncPhase,
    val done: Int,
    val total: Int,
    val path: String? = null,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val bytesPerSecond: Long = 0,
    val etaSeconds: Long? = null,
)

/** Archivos que ya no estan como en GitHub (los toco algo en el movil, por ejemplo Godot). */
data class LocalChanges(val modified: List<String>, val missing: List<String>) {
    val isEmpty: Boolean get() = modified.isEmpty() && missing.isEmpty()
}

/**
 * Lo que se trajo de un repo, todo junto para guardarlo de una vez: commit, rama y la lista
 * ruta -> huella. [head] es null en manifiestos antiguos.
 */
data class Manifest(val files: Map<String, String>, val head: String? = null, val branch: String? = null) {
    companion object {
        val EMPTY = Manifest(emptyMap())
    }
}

/** Guarda, por repo, el [Manifest] de lo traido. Cada guardado es atomico. */
interface ManifestStore {
    fun load(repo: RepoRef): Manifest

    fun save(repo: RepoRef, manifest: Manifest)

    fun clear(repo: RepoRef)
}

/** Para borrar del motor: ruta de carpeta como File. */
fun SyncState.localFile(): File = File(localDir)
