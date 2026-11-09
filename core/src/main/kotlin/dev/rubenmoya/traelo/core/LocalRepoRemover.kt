package dev.rubenmoya.traelo.core

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Resultado de borrar la copia de un repo en el movil. */
sealed interface RemoveResult {
    /** Se borro la carpeta con [files] archivos dentro. */
    data class Removed(val files: Int) : RemoveResult

    /** La carpeta ya no estaba. */
    data object NotThere : RemoveResult

    /** No se borra: la ruta es demasiado general o contiene la carpeta base. */
    data object Refused : RemoveResult
}

/**
 * Borra entera la carpeta de un repo traido. Es lo unico del programa que borra una carpeta
 * completa, asi que es muy estricto: nunca sigue enlaces simbolicos y se niega a tocar rutas
 * generales (la raiz, la memoria interna) o que contengan la carpeta base de todos los repos.
 */
object LocalRepoRemover {
    /** Menos niveles que estos (por ejemplo `/storage/emulated/0/Documents`) y no se toca. */
    private const val MIN_DEPTH = 5

    fun isSafe(dir: File, baseDir: File): Boolean {
        val path = dir.absoluteFile.toPath().normalize()
        if (path.nameCount < MIN_DEPTH) return false
        val base = baseDir.absoluteFile.toPath().normalize()
        // La carpeta no puede ser la base ni estar por encima de ella.
        return !base.startsWith(path)
    }

    fun remove(dir: File, baseDir: File): RemoveResult {
        if (!isSafe(dir, baseDir)) return RemoveResult.Refused
        val root = dir.absoluteFile.toPath().normalize()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return RemoveResult.NotThere
        // Si la carpeta es un enlace se borra el enlace, nunca lo que hay al otro lado.
        if (Files.isSymbolicLink(root)) {
            Files.delete(root)
            return RemoveResult.Removed(0)
        }
        var count = 0
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    count++
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return RemoveResult.Removed(count)
    }
}
