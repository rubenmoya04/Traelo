package dev.rubenmoya.traelo.core

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cliente de la API REST de GitHub sobre OkHttp. Todas las instancias comparten conexiones
 * (keep-alive y HTTP/2 con api.github.com: muchas descargas en paralelo por un solo enchufe).
 *
 * Las peticiones JSON van con `If-None-Match`: si nada cambio GitHub responde 304, que es mas
 * rapido y no gasta cuota. El token solo se manda a [baseUrl]; nunca se escribe en logs ni en
 * mensajes de error.
 */
class HttpGitHubApi(
    private val tokenProvider: () -> String,
    private val baseUrl: String = "https://api.github.com",
    connectTimeoutMs: Int = 15_000,
    readTimeoutMs: Int = 60_000,
) : GitHubApi {
    private val client: OkHttpClient = sharedClient.newBuilder()
        .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private class Reply(val code: Int, val body: String, val link: String?, val remaining: String?, val reset: String?)

    private class Cached(val etag: String, val body: String, val link: String?)

    private val etags = object : LinkedHashMap<String, Cached>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > MAX_CACHED
    }

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
        val json = JSONObject(get("${repoUrl(repo)}/git/trees/$sha?recursive=1", "el arbol de ${repo.fullName}", cache = false).body)
        val array = json.getJSONArray("tree")
        val entries = ArrayList<TreeEntry>(array.length())
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
        val request = request("${repoUrl(repo)}/git/blobs/$sha", RAW, authorize = true).build()
        val response = execute(request)
        response.use {
            if (it.code >= 400) throw mapError(reply(it), "un archivo de ${repo.fullName}")
            val input = it.body?.byteStream() ?: return
            try {
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            } catch (e: IOException) {
                throw GitHubException.Network(e)
            }
        }
    }

    override fun <T> openZip(repo: RepoRef, ref: String, read: (InputStream) -> T): T {
        val response = openZipResponse(repo, ref)
        return response.use {
            val body = it.body ?: throw GitHubException.Http(it.code, "zip vacio")
            NetworkInputStream(body.byteStream()).use(read)
        }
    }

    /** Pide el zip. El enlace firmado de descarga es otro servidor: alli va sin token. */
    private fun openZipResponse(repo: RepoRef, ref: String): Response {
        val first = execute(request("${repoUrl(repo)}/zipball/${encodePath(ref)}", JSON, authorize = true).build())
        when (first.code) {
            200 -> return first
            301, 302, 307, 308 -> {
                val location = first.header("Location")
                val target = location?.let { first.request.url.resolve(it) }
                first.close()
                if (target == null) throw GitHubException.Http(first.code, "sin Location")
                val second = execute(Request.Builder().url(target).header("User-Agent", AGENT).build())
                if (second.code >= 400) {
                    second.close()
                    throw GitHubException.Http(second.code, "la descarga del zip fallo")
                }
                return second
            }
            else -> first.use { throw mapError(reply(it), "el zip de ${repo.fullName}") }
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

    private fun execute(request: Request): Response =
        try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw GitHubException.Network(e)
        }

    /** GET de JSON. Con [cache] manda la ETag de la ultima vez y, si GitHub dice 304, usa lo guardado. */
    private fun get(url: String, what: String, cache: Boolean = true): Reply {
        val key = cacheKey(url)
        val cached = if (cache) synchronized(etags) { etags[key] } else null
        val builder = request(url, JSON, authorize = true)
        cached?.let { builder.header("If-None-Match", it.etag) }
        val response = execute(builder.build())
        return response.use {
            if (it.code == 304 && cached != null) {
                return@use Reply(200, cached.body, cached.link, null, null)
            }
            val reply = try {
                reply(it)
            } catch (e: IOException) {
                throw GitHubException.Network(e)
            }
            if (reply.code >= 400) throw mapError(reply, what)
            val etag = it.header("ETag")
            if (cache && etag != null && reply.body.length <= MAX_CACHED_BODY) {
                synchronized(etags) { etags[key] = Cached(etag, reply.body, reply.link) }
            }
            reply
        }
    }

    /** La cache va por token: si cambias de cuenta no se mezclan respuestas. */
    private fun cacheKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(tokenProvider().toByteArray())
        return GitBlobHash.hex(digest).take(16) + " " + url
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

    private fun request(url: String, accept: String, authorize: Boolean): Request.Builder {
        require(!authorize || url.startsWith(baseUrl)) { "El token solo se manda a GitHub" }
        val builder = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", AGENT)
            .header("X-GitHub-Api-Version", "2022-11-28")
        if (authorize) builder.header("Authorization", "Bearer ${tokenProvider()}")
        return builder
    }

    /** Lee codigo, cabeceras y cuerpo (del error si lo hay). */
    private fun reply(response: Response): Reply =
        Reply(
            response.code,
            response.body?.string() ?: "",
            response.header("Link"),
            response.header("X-RateLimit-Remaining"),
            response.header("X-RateLimit-Reset"),
        )

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

    /** Un corte de red a mitad del zip llega como [GitHubException.Network], no como fallo del disco. */
    private class NetworkInputStream(input: InputStream) : FilterInputStream(input) {
        override fun read(): Int = guard { super.read() }

        override fun read(b: ByteArray, off: Int, len: Int): Int = guard { super.read(b, off, len) }

        override fun skip(n: Long): Long = guard { super.skip(n) }

        override fun close() {
            try {
                super.close()
            } catch (e: IOException) {
                // Cerrar un flujo cortado no aporta nada.
            }
        }

        private inline fun <T> guard(block: () -> T): T =
            try {
                block()
            } catch (e: IOException) {
                throw GitHubException.Network(e)
            }
    }

    private companion object {
        const val JSON = "application/vnd.github+json"
        const val RAW = "application/vnd.github.raw+json"
        const val AGENT = "Traelo"
        const val COPY_BUFFER = 64 * 1024
        const val MAX_CACHED = 64
        const val MAX_CACHED_BODY = 1_000_000
        val NEXT = Regex("<([^>]+)>;\\s*rel=\"next\"")

        /** Un solo cliente para toda la app: un pool de conexiones y un solo apreton TLS por servidor. */
        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
