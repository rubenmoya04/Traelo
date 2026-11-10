package dev.rubenmoya.traelo.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class HttpGitHubApiTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val seen = ArrayList<Map<String, String?>>() // path, authorization, accept, agent, etag
    private val ports = HashSet<Int>() // puerto de origen de cada peticion: uno solo = conexion reutilizada
    private var handler: (HttpExchange) -> Unit = { reply(it, 404, "{}") }
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val secret = "ghp_SECRETO_NO_DEBE_SALIR"

    private fun api() = HttpGitHubApi({ secret }, base)

    @BeforeTest fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            seen += mapOf(
                "path" to ex.requestURI.toString(),
                "auth" to ex.requestHeaders.getFirst("Authorization"),
                "accept" to ex.requestHeaders.getFirst("Accept"),
                "agent" to ex.requestHeaders.getFirst("User-Agent"),
                "etag" to ex.requestHeaders.getFirst("If-None-Match"),
            )
            ports += ex.remoteAddress.port
            handler(ex)
        }
        server.start()
    }

    @AfterTest fun stop() = server.stop(0)

    private fun reply(ex: HttpExchange, code: Int, body: String, headers: Map<String, String> = emptyMap()) =
        reply(ex, code, body.toByteArray(), headers)

    private fun reply(ex: HttpExchange, code: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
        ex.close()
    }

    private val repo = RepoRef("o", "r")

    @Test fun sendsTokenAndHeadersOnlyToGitHub() {
        handler = { reply(it, 200, """{"login":"rubenmoya04"}""") }
        assertEquals("rubenmoya04", api().currentUser())
        val call = seen.single()
        assertEquals("Bearer $secret", call["auth"])
        assertEquals("application/vnd.github+json", call["accept"])
        assertEquals("Traelo", call["agent"])
    }

    @Test fun listReposFollowsPagesButNeverToAnotherHost() {
        handler = { ex ->
            when {
                ex.requestURI.path == "/user/repos" && ex.requestURI.query.contains("page=2") ->
                    reply(ex, 200, """[{"full_name":"o/dos","private":false,"default_branch":"dev","pushed_at":null}]""")
                ex.requestURI.path == "/user/repos" -> reply(
                    ex, 200,
                    """[{"full_name":"o/uno","private":true,"default_branch":"main","pushed_at":"2026-11-09T10:00:00Z"}]""",
                    mapOf("Link" to """<$base/user/repos?page=2>; rel="next", <$base/user/repos?page=9>; rel="last""""),
                )
                else -> reply(ex, 404, "{}")
            }
        }
        val repos = api().listRepos()
        assertEquals(listOf("o/uno", "o/dos"), repos.map { it.ref.fullName })
        assertTrue(repos[0].isPrivate)
        assertEquals("dev", repos[1].defaultBranch)
        assertNull(repos[1].pushedAt)
        // Un enlace a otro servidor no se sigue (ahi iria el token).
        seen.clear()
        handler = { reply(it, 200, "[]", mapOf("Link" to """<http://evil.example/steal>; rel="next"""")) }
        assertTrue(api().listRepos().isEmpty())
        assertEquals(1, seen.size)
    }

    @Test fun branchWithSlashIsEncodedPerSegment() {
        handler = { reply(it, 200, """{"commit":{"sha":"abc"}}""") }
        assertEquals("abc", api().branchHead(repo, "feat/mi rama"))
        assertEquals("/repos/o/r/branches/feat/mi%20rama", seen.single()["path"])
    }

    @Test fun listBranches() {
        handler = { reply(it, 200, """[{"name":"main","commit":{"sha":"s1"}},{"name":"dev","commit":{"sha":"s2"}}]""") }
        assertEquals(listOf(Branch("main", "s1"), Branch("dev", "s2")), api().listBranches(repo))
    }

    @Test fun errorsAreTranslated() {
        handler = { reply(it, 401, """{"message":"Bad credentials"}""") }
        val unauthorized = assertFailsWith<GitHubException.Unauthorized> { api().currentUser() }
        assertFalse(unauthorized.message.orEmpty().contains(secret))

        handler = { reply(it, 404, """{"message":"Not Found"}""") }
        assertFailsWith<GitHubException.NotFound> { api().branchHead(repo, "main") }

        handler = { reply(it, 403, """{"message":"API rate limit exceeded"}""", mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "1790000000")) }
        val limited = assertFailsWith<GitHubException.RateLimited> { api().currentUser() }
        assertEquals(1790000000L, limited.resetAtEpochSec)

        handler = { reply(it, 403, """{"message":"Resource not accessible by personal access token"}""") }
        assertFailsWith<GitHubException.NotFound> { api().branchHead(repo, "main") }

        handler = { reply(it, 500, """{"message":"boom"}""") }
        val http = assertFailsWith<GitHubException.Http> { api().currentUser() }
        assertEquals(500, http.code)
        assertTrue(http.message.orEmpty().contains("boom"))
    }

    @Test fun noServerIsANetworkError() {
        val dead = HttpGitHubApi({ secret }, "http://127.0.0.1:1", connectTimeoutMs = 500)
        val error = assertFailsWith<GitHubException.Network> { dead.currentUser() }
        assertFalse(error.message.orEmpty().contains(secret))
    }

    @Test fun treeKeepsFilesAndSkipsSymlinksAndSubmodules() {
        handler = {
            reply(
                it, 200,
                """{"truncated":true,"tree":[
                    {"path":"a.txt","mode":"100644","type":"blob","sha":"s1","size":5},
                    {"path":"run.sh","mode":"100755","type":"blob","sha":"s2","size":9},
                    {"path":"ln","mode":"120000","type":"blob","sha":"s3","size":1},
                    {"path":"sub","mode":"160000","type":"commit","sha":"s4"},
                    {"path":"dir","mode":"040000","type":"tree","sha":"s5"}]}""",
            )
        }
        val tree = api().tree(repo, "head")
        assertEquals(listOf("a.txt", "run.sh"), tree.entries.map { it.path })
        assertEquals(5L, tree.entries[0].size)
        assertEquals(listOf("ln", "sub"), tree.skipped)
        assertTrue(tree.truncated)
        assertEquals("/repos/o/r/git/trees/head?recursive=1", seen.single()["path"])
    }

    @Test fun blobIsStreamedRawWithRawAccept() {
        val bytes = ByteArray(300_000) { it.toByte() }
        handler = { reply(it, 200, bytes) }
        val out = ByteArrayOutputStream()
        api().downloadBlob(repo, "abc", out)
        assertTrue(bytes.contentEquals(out.toByteArray()))
        assertEquals("application/vnd.github.raw+json", seen.single()["accept"])
    }

    @Test fun zipFollowsTheRedirectWithoutSendingTheToken() {
        val zipBytes = ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { z ->
                z.putNextEntry(ZipEntry("top/a.txt"))
                z.write("hola".toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        handler = { ex ->
            if (ex.requestURI.path.startsWith("/repos/")) {
                reply(ex, 302, "", mapOf("Location" to "$base/codeload/firmado?token=xyz"))
            } else {
                reply(ex, 200, zipBytes)
            }
        }
        val received = api().openZip(repo, "abc") { it.readBytes() }
        assertTrue(zipBytes.contentEquals(received))
        assertEquals("Bearer $secret", seen[0]["auth"])
        assertNull(seen[1]["auth"], "el segundo servidor no recibe el token")
    }

    @Test fun zipServedDirectlyIsStreamedToo() {
        handler = { reply(it, 200, "PK-datos".toByteArray()) }
        assertEquals("PK-datos", api().openZip(repo, "abc") { String(it.readBytes()) })
    }

    @Test fun zipErrorsAreTranslated() {
        handler = { reply(it, 404, """{"message":"Not Found"}""") }
        assertFailsWith<GitHubException.NotFound> { api().openZip(repo, "abc") { it.readBytes() } }
    }

    @Test fun branchHeadUsesETagsAndA304CostsNothing() {
        handler = { ex ->
            if (ex.requestHeaders.getFirst("If-None-Match") == "\"v1\"") {
                reply(ex, 304, "")
            } else {
                reply(ex, 200, """{"commit":{"sha":"abc"}}""", mapOf("ETag" to "\"v1\""))
            }
        }
        val client = api()
        assertEquals("abc", client.branchHead(repo, "main"))
        assertEquals("abc", client.branchHead(repo, "main"))
        assertEquals(2, seen.size)
        assertEquals("\"v1\"", seen[1]["etag"], "la segunda pregunta va condicionada")
        // Otro token no reutiliza lo guardado.
        val other = HttpGitHubApi({ "otro" }, base)
        assertEquals("abc", other.branchHead(repo, "main"))
        assertNull(seen[2]["etag"])
    }

    @Test fun blobsReuseTheConnection() {
        handler = { reply(it, 200, "x".toByteArray()) }
        val client = api()
        repeat(5) { client.downloadBlob(repo, "s$it", ByteArrayOutputStream()) }
        assertEquals(1, ports.size, "cinco descargas por la misma conexion (keep-alive)")
    }

    @Test fun commitsComeNewestFirstAndFailedCompareIsNull() {
        handler = {
            reply(
                it, 200,
                """{"commits":[
                    {"sha":"old","commit":{"message":"primero\n\ncuerpo","author":{"name":"Ruben","date":"2026-11-08T10:00:00Z"}}},
                    {"sha":"new","commit":{"message":"segundo","author":{"name":"Silvia","date":"2026-11-09T10:00:00Z"}}}]}""",
            )
        }
        val commits = assertNotNull(api().commitsBetween(repo, "a", "b"))
        assertEquals(listOf("new", "old"), commits.map { it.sha })
        assertEquals("primero", commits[1].title)
        assertEquals("Silvia", commits[0].author)
        handler = { reply(it, 404, """{"message":"Not Found"}""") }
        assertNull(api().commitsBetween(repo, "a", "b"))
    }
}
