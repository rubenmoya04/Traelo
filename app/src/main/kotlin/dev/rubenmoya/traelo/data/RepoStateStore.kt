package dev.rubenmoya.traelo.data

import dev.rubenmoya.traelo.core.RepoRef
import dev.rubenmoya.traelo.core.SyncState
import org.json.JSONObject
import java.io.File

/**
 * Guarda en un JSON el [SyncState] de cada repo que se ha traido. Seguro entre hilos: todas las
 * operaciones van sincronizadas y la escritura es atomica (fichero temporal y renombrar).
 */
class RepoStateStore(private val file: File) {
    private var cache: MutableMap<String, SyncState>? = null

    @Synchronized
    fun get(repo: RepoRef): SyncState? = load()[repo.key]

    @Synchronized
    fun all(): Map<String, SyncState> = load().toMap()

    @Synchronized
    fun put(state: SyncState) {
        val map = load()
        map[state.repo.key] = state
        persist(map)
    }

    @Synchronized
    fun remove(repo: RepoRef) {
        val map = load()
        if (map.remove(repo.key) != null) persist(map)
    }

    private fun load(): MutableMap<String, SyncState> {
        cache?.let { return it }
        val map = LinkedHashMap<String, SyncState>()
        if (file.exists()) {
            try {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                for (key in root.keys()) {
                    parse(root.getJSONObject(key))?.let { map[key] = it }
                }
            } catch (e: Exception) {
                // Fichero roto: se empieza de cero (los repos saldran como "sin traer").
                map.clear()
            }
        }
        cache = map
        return map
    }

    private fun parse(json: JSONObject): SyncState? = try {
        SyncState(
            repo = RepoRef(json.getString("owner"), json.getString("name")),
            branch = json.getString("branch"),
            headSha = json.getString("headSha"),
            syncedAtMillis = json.getLong("syncedAt"),
            localDir = json.getString("localDir"),
        )
    } catch (e: Exception) {
        null
    }

    private fun persist(map: Map<String, SyncState>) {
        val root = JSONObject()
        for ((key, state) in map) {
            root.put(
                key,
                JSONObject()
                    .put("owner", state.repo.owner)
                    .put("name", state.repo.name)
                    .put("branch", state.branch)
                    .put("headSha", state.headSha)
                    .put("syncedAt", state.syncedAtMillis)
                    .put("localDir", state.localDir),
            )
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "No se pudo guardar el estado de los repos" }
        }
    }
}
