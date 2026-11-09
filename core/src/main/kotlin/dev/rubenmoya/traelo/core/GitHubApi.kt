package dev.rubenmoya.traelo.core

import java.io.File
import java.io.OutputStream

/** Errores de GitHub ya traducidos a cosas que se le pueden explicar a la persona. */
sealed class GitHubException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** El token no vale o ha caducado. */
    class Unauthorized : GitHubException("Token no valido o caducado")

    /** No existe, o el token no tiene permiso para verlo. */
    class NotFound(what: String) : GitHubException("No se encuentra $what (o el token no tiene permiso)")

    /** Demasiadas peticiones. [resetAtEpochSec] es cuando se reinicia el limite, si GitHub lo dice. */
    class RateLimited(val resetAtEpochSec: Long?) : GitHubException("Limite de peticiones de GitHub alcanzado")

    class Network(cause: Throwable) : GitHubException("Sin conexion con GitHub", cause)

    class Http(val code: Int, detail: String) : GitHubException("GitHub respondio $code: $detail")

    class Integrity(path: String) : GitHubException("El archivo $path llego danado")

    class TooBig : GitHubException("El repo es demasiado grande para traerlo archivo a archivo")
}

/** Todo lo que se necesita de GitHub. Las llamadas bloquean, el motor las lanza en hilos de IO. */
interface GitHubApi {
    /** Nombre de usuario del token (sirve para comprobar que el token vale). */
    fun currentUser(): String

    fun listRepos(): List<RemoteRepo>

    fun listBranches(repo: RepoRef): List<Branch>

    fun branchHead(repo: RepoRef, branch: String): String

    fun tree(repo: RepoRef, sha: String): TreeResult

    /** Escribe el contenido tal cual (sin base64) en [out]. */
    fun downloadBlob(repo: RepoRef, sha: String, out: OutputStream)

    /** Baja el zip de la rama a [dest]. */
    fun downloadZip(repo: RepoRef, ref: String, dest: File)

    /** Commits entre [base] y [head], mas reciente primero, o null si GitHub no puede compararlos. */
    fun commitsBetween(repo: RepoRef, base: String, head: String): List<CommitInfo>?
}
