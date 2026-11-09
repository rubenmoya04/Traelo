package dev.rubenmoya.traelo.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cliente de la API REST de GitHub con HttpURLConnection (sin librerias extra).
 * El token solo se manda a [baseUrl]; nunca se escribe en logs ni en mensajes de error.
 */
class HttpGitHubApi(
    private val tokenProvider: () -> String,
    private val baseUrl: String = "https://api.github.com",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : GitHubApi {
    private class Reply(val code: Int, val body: String, val link: String?, val remaining: String?, val reset: String?)

    override fun currentUser(): String = JSONObject(get("$baseUrl/user", "tu usuario").body).getString("login")

    override fun listRepos(): List<RemoteRepo> {
        val result = ArrayList<RemoteRepo>()
        val first = "$baseUrl/user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator,organization_member"
        pages(first, "tus repos") { page ->
            for (i in 0 until page.length()) {
                val item = page.getJSONObject(i)
                result += RemoteRepo(
                    ref = RepoRef.parse(item.getString("full_name")),
                    isPrivate = item.optBoolean("private", false),
                    defaultBranch = item.optString("default_branch", "main"),
                    pushedAt = item.optString("pushed_at", "").ifEmpty { null },
                )
            }
        }
        return result
    }

    override fun listBranches(repo: RepoRef): List<Branch> {
        val result = ArrayList<Branch>()
        pages("${repoUrl(repo)}/branches?per_page=100", "las ramas de ${repo.fullName}") { page ->
            for (i in 0 until page.length()) {
                val item = page.getJSONObject(i)
                result += Branch(item.getString("name"), item.getJSONObject("commit").getString("sha"))
            }
        }
        return result
    }

    override fun branchHead(repo: RepoRef, branch: String): String {
        val url = "${repoUrl(repo)}/branches/${encodePath(branch)}"
        return JSONObject(get(url, "la rama $branch de ${repo.fullName}").body)
            .getJSONObject("commit").getString("sha")
    }

    override fun tree(repo: RepoRef, sha: String): TreeResult {
        val json = JSONObject(get("${repoUrl(repo)}/git/trees/$sha?recursive=1", "el arbol de ${repo.fullName}").body)
        val array = json.getJSONArray("tree")
        val entries = ArrayList<TreeEntry>()
        val skipped = ArrayList<String>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val type = item.getString("type")
            if (type == "tree") continue
            val path = item.getString("path")
            if (type == "blob" && item.getString("mode") != "120000") {
                entries += TreeEntry(path, item.getString("sha"), item.optLong("size", 0))
            } else {
                skipped += path
            }
        }
        return TreeResult(entries, skipped, json.optBoolean("truncated", false))
    }

    override fun downloadBlob(repo: RepoRef, sha: String, out: OutputStream) {
        val url = "${repoUrl(repo)}/git/blobs/$sha"
        val connection = open(url, RAW, authorize = true)
        try {
            val code = connection.responseCode
            if (code >= 400) throw mapError(readError(connection), "un archivo de ${repo.fullName}")
            connection.inputStream.use { it.copyTo(out) }
        } catch (e: IOException) {
            throw GitHubException.Network(e)
        } finally {
            connection.disconnect()
        }
    }

    override fun downloadZip(repo: RepoRef, ref: String, dest: File) {
        val first = open("${repoUrl(repo)}/zipball/${encodePath(ref)}", JSON, authorize = true, redirects = false)
        try {
            val code = first.responseCode
            when (code) {
                200 -> save(first, dest)
                301, 302, 307, 308 -> {
                    // El enlace firmado de descarga va sin token: es otro servidor.
                    val location = first.getHeaderField("Location") ?: throw GitHubException.Http(code, "sin Location")
                    val second = openRaw(location)
                    try {
                        if (second.responseCode >= 400) {
                            throw GitHubException.Http(second.responseCode, "la descarga del zip fallo")
                        }
                        save(second, dest)
                    } finally {
                        second.disconnect()
                    }
                }
                else -> throw mapError(readError(first), "el zip de ${repo.fullName}")
            }
        } catch (e: IOException) {
            throw GitHubException.Network(e)
        } finally {
            first.disconnect()
        }
    }

    override fun commitsBetween(repo: RepoRef, base: String, head: String): List<CommitInfo>? {
        val url = "${repoUrl(repo)}/compare/$base...$head?per_page=100"
        val body = try {
            get(url, "la comparacion").body
        } catch (e: GitHubException.NotFound) {
            return null
        }
        val commits = JSONObject(body).optJSONArray("commits") ?: return null
        val result = ArrayList<CommitInfo>()
        for (i in commits.length() - 1 downTo 0) {
            val item = commits.getJSONObject(i)
            val commit = item.getJSONObject("commit")
            val author = commit.optJSONObject("author")
            result += CommitInfo(
                sha = item.getString("sha"),
                title = commit.optString("message", "").lineSequence().firstOrNull().orEmpty(),
                author = author?.optString("name", "") ?: "",
                date = author?.optString("date", "") ?: "",
            )
        }
        return result
    }

    private fun repoUrl(repo: RepoRef) = "$baseUrl/repos/${enc(repo.owner)}/${enc(repo.name)}"

    private fun save(connection: HttpURLConnection, dest: File) {
        FileOutputStream(dest).use { out -> connection.inputStream.use { it.copyTo(out) } }
    }

    private fun get(url: String, what: String): Reply {
        val connection = open(url, JSON, authorize = true)
        try {
            val reply = readError(connection)
            if (reply.code >= 400) throw mapError(reply, what)
            return reply
        } catch (e: IOException) {
            throw GitHubException.Network(e)
        } finally {
            connection.disconnect()
        }
    }

    /** Recorre las paginas siguiendo el enlace `next`, sin salir nunca de [baseUrl]. */
    private fun pages(first: String, what: String, onPage: (JSONArray) -> Unit) {
        var url: String? = first
        var guard = 0
        while (url != null && guard++ < 50) {
            val reply = get(url, what)
            onPage(JSONArray(reply.body))
            val next = reply.link?.let { NEXT.find(it)?.groupValues?.get(1) }
            url = next?.takeIf { it.startsWith(baseUrl) }
        }
    }

    private fun open(url: String, accept: String, authorize: Boolean, redirects: Boolean = true): HttpURLConnection {
        require(!authorize || url.startsWith(baseUrl)) { "El token solo se manda a GitHub" }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = redirects
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("User-Agent", "Traelo")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (authorize) connection.setRequestProperty("Authorization", "Bearer ${tokenProvider()}")
        return connection
    }

    private fun openRaw(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("User-Agent", "Traelo")
        return connection
    }

    /** Lee codigo, cabeceras y cuerpo (del error si lo hay) sin lanzar. */
    private fun readError(connection: HttpURLConnection): Reply {
        val code = connection.responseCode
        val stream = if (code >= 400) connection.errorStream else connection.inputStream
        val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return Reply(
            code,
            body,
            connection.getHeaderField("Link"),
            connection.getHeaderField("X-RateLimit-Remaining"),
            connection.getHeaderField("X-RateLimit-Reset"),
        )
    }

    private fun mapError(reply: Reply, what: String): GitHubException =
        when {
            reply.code == 401 -> GitHubException.Unauthorized()
            reply.code == 403 || reply.code == 429 ->
                if (reply.remaining == "0" || reply.body.contains("rate limit", ignoreCase = true)) {
                    GitHubException.RateLimited(reply.reset?.toLongOrNull())
                } else {
                    GitHubException.NotFound(what)
                }
            reply.code == 404 -> GitHubException.NotFound(what)
            else -> GitHubException.Http(reply.code, message(reply.body))
        }

    private fun message(body: String): String =
        runCatching { JSONObject(body).optString("message", "") }.getOrDefault("").ifEmpty { "sin detalle" }

    private fun enc(segment: String) = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private fun encodePath(path: String) = path.split('/').joinToString("/") { enc(it) }

    private companion object {
        const val JSON = "application/vnd.github+json"
        const val RAW = "application/vnd.github.raw+json"
        val NEXT = Regex("<([^>]+)>;\\s*rel=\"next\"")
    }
}
