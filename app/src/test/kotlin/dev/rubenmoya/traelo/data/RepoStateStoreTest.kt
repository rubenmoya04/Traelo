package dev.rubenmoya.traelo.data

import dev.rubenmoya.traelo.core.RepoRef
import dev.rubenmoya.traelo.core.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepoStateStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun state(name: String, sha: String = "abc123") =
        SyncState(RepoRef("rubenmoya04", name), "main", sha, 1_700_000_000_000L, "/sdcard/Documents/Traelo/$name")

    @Test
    fun putAndGetSurviveReload() {
        val file = File(tmp.root, "sub/states.json")
        val store = RepoStateStore(file)
        store.put(state("socks-sauce"))
        store.put(state("traelo", "def456"))

        val reloaded = RepoStateStore(file)
        assertEquals(state("socks-sauce"), reloaded.get(RepoRef("rubenmoya04", "socks-sauce")))
        assertEquals("def456", reloaded.get(RepoRef("rubenmoya04", "traelo"))!!.headSha)
        assertEquals(2, reloaded.all().size)
    }

    @Test
    fun putReplacesAndRemoveDeletes() {
        val store = RepoStateStore(File(tmp.root, "states.json"))
        store.put(state("a", "one"))
        store.put(state("a", "two"))
        assertEquals("two", store.get(RepoRef("rubenmoya04", "a"))!!.headSha)
        store.remove(RepoRef("rubenmoya04", "a"))
        assertNull(store.get(RepoRef("rubenmoya04", "a")))
    }

    @Test
    fun brokenFileStartsEmpty() {
        val file = File(tmp.root, "states.json")
        file.writeText("{esto no es json")
        val store = RepoStateStore(file)
        assertEquals(0, store.all().size)
        store.put(state("a"))
        assertEquals(1, RepoStateStore(file).all().size)
    }

    @Test
    fun parallelWritesDoNotLoseEntries() {
        val store = RepoStateStore(File(tmp.root, "states.json"))
        val threads = (1..8).map { i -> Thread { store.put(state("repo$i")) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(8, RepoStateStore(File(tmp.root, "states.json")).all().size)
    }
}
