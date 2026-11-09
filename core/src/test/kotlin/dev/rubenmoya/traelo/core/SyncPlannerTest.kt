package dev.rubenmoya.traelo.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SyncPlannerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun entry(path: String, content: String) =
        TreeEntry(path, GitBlobHash.ofBytes(content.toByteArray()), content.toByteArray().size.toLong())

    private fun write(path: String, content: String) {
        File(tmp.root, path).also { it.parentFile.mkdirs() }.writeText(content)
    }

    @Test fun knownAndPresentIsUpToDateWithoutHashing() {
        val a = entry("a.txt", "uno")
        write("a.txt", "uno")
        val plan = SyncPlanner.plan(listOf(a), mapOf("a.txt" to a.sha), tmp.root, verifyAll = false)
        assertEquals(PlanCounts(0, 0, 0, 1), plan.counts)
    }

    @Test fun knownButMissingIsRestored() {
        val a = entry("a.txt", "uno")
        val plan = SyncPlanner.plan(listOf(a), mapOf("a.txt" to a.sha), tmp.root, verifyAll = false)
        assertEquals(listOf(PlanKind.RESTORED), plan.download.map { it.kind })
    }

    @Test fun changedShaIsModifiedAndNewIsAdded() {
        val old = entry("a.txt", "uno")
        val new = entry("a.txt", "dos")
        val added = entry("b.txt", "tres")
        write("a.txt", "uno")
        val plan = SyncPlanner.plan(listOf(new, added), mapOf("a.txt" to old.sha), tmp.root, verifyAll = false)
        assertEquals(listOf(PlanKind.MODIFIED, PlanKind.ADDED), plan.download.map { it.kind })
    }

    @Test fun unknownButIdenticalOnDiskIsAdopted() {
        val a = entry("a.txt", "uno")
        write("a.txt", "uno")
        val plan = SyncPlanner.plan(listOf(a), emptyMap(), tmp.root, verifyAll = false)
        assertEquals(PlanCounts(0, 0, 0, 1), plan.counts)
    }

    @Test fun verifyAllCatchesSameSizeEdits() {
        val a = entry("a.txt", "uno")
        write("a.txt", "dos") // mismo tamano, otro contenido
        val loose = SyncPlanner.plan(listOf(a), mapOf("a.txt" to a.sha), tmp.root, verifyAll = false)
        assertEquals(0, loose.download.size)
        val strict = SyncPlanner.plan(listOf(a), mapOf("a.txt" to a.sha), tmp.root, verifyAll = true)
        assertEquals(listOf(PlanKind.RESTORED), strict.download.map { it.kind })
    }

    @Test fun onlyManifestPathsMissingFromTheBranchAreRemoved() {
        val a = entry("a.txt", "uno")
        write("a.txt", "uno")
        write("ajeno.txt", "no es mio")
        val plan = SyncPlanner.plan(listOf(a), mapOf("a.txt" to a.sha, "viejo.txt" to "x".repeat(40)), tmp.root, false)
        assertEquals(listOf("viejo.txt"), plan.remove)
    }
}
