package dev.rubenmoya.traelo.data

import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Convierte la carpeta que devuelve el selector del sistema (un content://) en una ruta real,
 * que es lo que usa Tráelo (tiene permiso de todos los archivos). Solo vale para el
 * almacenamiento del móvil o una tarjeta SD; otras fuentes (Drive...) devuelven null.
 */
object FolderPicker {
    fun toPath(treeUri: Uri): String? = try {
        pathFromDocumentId(DocumentsContract.getTreeDocumentId(treeUri), File("/storage/emulated/0"))
    } catch (e: Exception) {
        null
    }

    /** `primary:Documents/Traelo` pasa a `/storage/emulated/0/Documents/Traelo`. Se puede probar sin Android. */
    fun pathFromDocumentId(docId: String, primaryRoot: File): String? {
        if (docId.startsWith("raw:")) return docId.removePrefix("raw:").takeIf { it.startsWith("/") }
        val sep = docId.indexOf(':')
        if (sep <= 0) return null
        val volume = docId.substring(0, sep)
        val relative = docId.substring(sep + 1).trim('/')
        if (relative.split('/').any { it == ".." }) return null
        val root = if (volume == "primary") primaryRoot else File("/storage/$volume")
        if (volume != "primary" && !volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"))) return null
        return if (relative.isEmpty()) root.path else File(root, relative).path
    }
}
