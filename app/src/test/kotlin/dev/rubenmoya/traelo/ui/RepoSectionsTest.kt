package dev.rubenmoya.traelo.ui

import dev.rubenmoya.traelo.core.RemoteRepo
import dev.rubenmoya.traelo.core.RepoRef
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class RepoSectionsTest {
    private fun repo(name: String, private: Boolean = false) =
        RemoteRepo(RepoRef("rubenmoya04", name), isPrivate = private, defaultBranch = "main", pushedAt = null)

    private val a = repo("a")
    private val b = repo("b", private = true)
    private val c = repo("c")

    @Test
    fun favoritesGoFirstKeepingOrder() {
        val s = splitRepos(listOf(a, b, c), setOf(c.ref.key, a.ref.key))
        assertEquals(listOf(a, c), s.favorites)
        assertEquals(listOf(b), s.others)
    }

    @Test
    fun noFavoritesLeavesEverythingInOthers() {
        val s = splitRepos(listOf(a, b), emptySet())
        assertTrue(s.favorites.isEmpty())
        assertEquals(listOf(a, b), s.others)
    }

    @Test
    fun unknownFavoriteIsIgnored() {
        val s = splitRepos(listOf(a), setOf("otro__repo"))
        assertTrue(s.favorites.isEmpty())
    }

    @Test
    fun toggleAddsAndRemoves() {
        val on = toggled(emptySet(), "x")
        assertEquals(setOf("x"), on)
        assertEquals(emptySet<String>(), toggled(on, "x"))
    }

    @Test
    fun publicOnlyDetection() {
        assertTrue(looksPublicOnly(listOf(a, c)))
        assertFalse(looksPublicOnly(listOf(a, b)))
        assertFalse(looksPublicOnly(emptyList()))
    }
}
