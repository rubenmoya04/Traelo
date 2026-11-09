package dev.rubenmoya.traelo.core

import java.io.File
import org.json.JSONObject

/** Guarda cada manifiesto en un JSON dentro de [dir], con escritura atomica. */
class FileManifestStore(private val dir: File) : ManifestStore {
    private fun fileFor(repo: RepoRef) = File(dir, "${repo.key}.json")

    @Synchronized
    override fun load(repo: RepoRef): Map<String, String> {
        val file = fileFor(repo)
        if (!file.isFile) return emptyMap()
        return try {
            val files = JSONObject(file.readText(Charsets.UTF_8)).getJSONObject("files")
            val result = LinkedHashMap<String, String>()
            for (key in files.keys()) result[key] = files.getString(key)
            result
        } catch (e: Exception) {
            // Si el manifiesto esta roto se empieza de cero: el motor vuelve a comparar con el disco.
            emptyMap()
        }
    }

    @Synchronized
    override fun save(repo: RepoRef, files: Map<String, String>) {
        dir.mkdirs()
        val json = JSONObject().put("files", JSONObject(files))
        val target = fileFor(repo)
        val temp = File(dir, "${repo.key}.json.tmp")
        temp.writeText(json.toString(), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "No se pudo guardar el manifiesto de ${repo.fullName}" }
        }
    }

    @Synchronized
    override fun clear(repo: RepoRef) {
        fileFor(repo).delete()
    }
}
