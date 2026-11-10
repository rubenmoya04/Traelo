package dev.rubenmoya.traelo.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/**
 * Guarda cada manifiesto en un JSON dentro de [dir]. La escritura es atomica: se escribe un
 * temporal, se fuerza al disco y se renombra encima. Un corte a mitad deja el anterior intacto.
 */
class FileManifestStore(private val dir: File) : ManifestStore {
    private fun fileFor(repo: RepoRef) = File(dir, "${repo.key}.json")

    @Synchronized
    override fun load(repo: RepoRef): Manifest {
        val file = fileFor(repo)
        if (!file.isFile) return Manifest.EMPTY
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val files = json.getJSONObject("files")
            val result = HashMap<String, String>(files.length() * 2)
            for (key in files.keys()) result[key] = files.getString(key)
            Manifest(
                files = result,
                head = json.optString("head", "").ifEmpty { null },
                branch = json.optString("branch", "").ifEmpty { null },
            )
        } catch (e: Exception) {
            // Si el manifiesto esta roto se empieza de cero: el motor vuelve a comparar con el disco.
            Manifest.EMPTY
        }
    }

    @Synchronized
    override fun save(repo: RepoRef, manifest: Manifest) {
        dir.mkdirs()
        val json = JSONObject().put("files", JSONObject(manifest.files))
        manifest.head?.let { json.put("head", it) }
        manifest.branch?.let { json.put("branch", it) }
        val target = fileFor(repo)
        val temp = File(dir, "${repo.key}.json.tmp")
        FileOutputStream(temp).use { out ->
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized
    override fun clear(repo: RepoRef) {
        fileFor(repo).delete()
    }
}
