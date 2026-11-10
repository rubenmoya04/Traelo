package dev.rubenmoya.traelo.data

import dev.rubenmoya.traelo.core.RemoteRepo
import dev.rubenmoya.traelo.core.RepoRef
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Ultima lista de repos que dio GitHub, guardada en el movil. Al abrir la app se ensena al momento
 * y se refresca por detras. Nada secreto: solo nombres, ramas y fechas.
 */
class RepoListCache(private val file: File) {
    @Synchronized
    fun load(): List<RemoteRepo>? {
        if (!file.isFile) return null
        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                RemoteRepo(
                    ref = RepoRef(item.getString("owner"), item.getString("name")),
                    isPrivate = item.optBoolean("private", false),
                    defaultBranch = item.optString("branch", "main"),
                    pushedAt = item.optString("pushedAt", "").ifEmpty { null },
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun save(repos: List<RemoteRepo>) {
        val array = JSONArray()
        for (repo in repos) {
            array.put(
                JSONObject()
                    .put("owner", repo.ref.owner)
                    .put("name", repo.ref.name)
                    .put("private", repo.isPrivate)
                    .put("branch", repo.defaultBranch)
                    .put("pushedAt", repo.pushedAt ?: ""),
            )
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(array.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }
}
