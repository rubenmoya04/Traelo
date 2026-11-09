package dev.rubenmoya.traelo.launcher

import java.io.File

/** Donde esta el proyecto de Godot dentro de la carpeta de un repo. */
data class ProjectLocation(
    /** Carpeta que contiene `project.godot`. */
    val dir: File,
    /** Ruta de [dir] relativa a la carpeta del repo, vacia si es la propia carpeta del repo. */
    val relativePath: String,
    /** Cuantos `project.godot` se encontraron (hasta 3 niveles de profundidad). */
    val found: Int,
)

object ProjectFinder {
    const val PROJECT_FILE = "project.godot"
    const val MAX_DEPTH = 3

    /**
     * Busca `project.godot` en [root] y hasta [maxDepth] niveles por debajo, de menos a mas
     * profundo. Con varios, devuelve el menos profundo (a igual profundidad, el primero por
     * orden alfabetico). Las carpetas que empiezan por punto (.git, .godot) se saltan.
     */
    fun find(root: File, maxDepth: Int = MAX_DEPTH): ProjectLocation? {
        if (!root.isDirectory) return null
        val hits = ArrayList<File>()
        var level: List<File> = listOf(root)
        var depth = 0
        while (level.isNotEmpty() && depth <= maxDepth) {
            val next = ArrayList<File>()
            for (dir in level) {
                if (File(dir, PROJECT_FILE).isFile) hits.add(dir)
                if (depth < maxDepth) {
                    dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                        ?.sortedBy { it.name }
                        ?.let { next.addAll(it) }
                }
            }
            level = next
            depth++
        }
        val best = hits.firstOrNull() ?: return null
        val relative = best.relativeTo(root).path.replace(File.separatorChar, '/')
        return ProjectLocation(best, relative, hits.size)
    }
}
