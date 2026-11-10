package dev.rubenmoya.traelo.core

import java.io.File

/** Convierte rutas que vienen de fuera (GitHub, zips) en archivos dentro de una carpeta, o rechaza. */
object SafePaths {
    /** Cache de Godot: Traelo no borra ni mueve nada de aqui dentro. */
    const val GODOT_DIR = ".godot"

    /** true si [relative] es la carpeta `.godot` de la raiz o algo dentro de ella. */
    fun isGodotCache(relative: String): Boolean = relative == GODOT_DIR || relative.startsWith("$GODOT_DIR/")

    /**
     * Devuelve el archivo para [relative] dentro de [root], o null si la ruta es peligrosa:
     * absoluta, con "..", con barras invertidas, vacia, con ".git" o que acabe fuera de [root]
     * (por ejemplo por un enlace simbolico en el disco).
     */
    fun resolve(root: File, relative: String): File? = resolveUnder(root.canonicalFile, relative)

    /** Igual que [resolve], pero con la carpeta ya canonica (para no recalcularla en cada archivo). */
    fun resolveUnder(rootCanon: File, relative: String): File? {
        if (relative.isEmpty() || relative.startsWith("/")) return null
        if (relative.contains('\\') || relative.contains('\u0000')) return null
        val segments = relative.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        if (segments.first() == ".git") return null
        val target = File(rootCanon, relative).canonicalFile
        if (!target.path.startsWith(rootCanon.path + File.separator)) return null
        return target
    }
}
