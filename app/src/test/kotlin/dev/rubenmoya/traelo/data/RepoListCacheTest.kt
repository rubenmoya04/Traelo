package dev.rubenmoya.traelo.data

import dev.rubenmoya.traelo.core.RemoteRepo
import dev.rubenmoya.traelo.core.RepoRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepoListCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val repos = listOf(
        RemoteRepo(RepoRef("rubenmoya04", "Socks-Sauce"), isPrivate = true, defaultBranch = "main", pushedAt = "2026-11-09T10:00:00Z"),
        RemoteRepo(RepoRef("silvia", "nmj"), isPrivate = false, defaultBranch = "dev", pushedAt = null),
    )

    @Test
    fun savedListComesBackTheSame() {
        val file = File(tmp.root, "cache/repos.json")
        RepoListCache(file).save(repos)
        assertEquals(repos, RepoListCache(file).load())
    }

    @Test
    fun missingOrBrokenIsNull() {
        val file = File(tmp.root, "repos.json")
        assertNull(RepoListCache(file).load())
        file.writeText("[{roto")
        assertNull(RepoListCache(file).load())
    }

    @Test
    fun clearForgetsIt() {
        val cache = RepoListCache(File(tmp.root, "repos.json"))
        cache.save(repos)
        cache.clear()
        assertNull(cache.load())
    }
}
