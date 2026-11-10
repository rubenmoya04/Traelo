package dev.rubenmoya.traelo.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SyncPlannerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun entry(path: String, content: String) =
        TreeEntry(path, GitBlobHash.ofBytes(content.toByteArray()), content.toByteArray().size.toLong())

    private fun write(path: String, content: String) {
        File(tmp.root, path).also { it.parentFile.mkdirs() }.writeText(content)
    }

    private fun plan(remote: List<TreeEntry>, manifest: Map<String, String>, verifyAll: Boolean = false) =
        runBlocking { SyncPlanner.plan(remote, manifest, tmp.root, verifyAll) }

    @Test fun knownAndPresentIsUpToDateWithoutHashing() {
        val a = entry("a.txt", "uno")
        write("a.txt", "uno")
        assertEquals(PlanCounts(0, 0, 0, 1), plan(listOf(a), mapOf("a.txt" to a.sha)).counts)
    }

    @Test fun knownButMissingIsRestored() {
        val a = entry("a.txt", "uno")
        val result = plan(listOf(a), mapOf("a.txt" to a.sha))
        assertEquals(listOf(PlanKind.RESTORED), result.download.map { it.kind })
        assertEquals(listOf(false), result.download.map { it.conflict })
    }

    @Test fun changedShaIsModifiedAndNewIsAdded() {
        val old = entry("a.txt", "uno")
        val new = entry("a.txt", "dos")
        val added = entry("b.txt", "tres")
        write("a.txt", "uno")
        val result = plan(listOf(new, added), mapOf("a.txt" to old.sha))
        assertEquals(listOf(PlanKind.MODIFIED, PlanKind.ADDED), result.download.map { it.kind })
        assertEquals(listOf(false, false), result.download.map { it.conflict }, "lo del movil era lo traido")
    }

    @Test fun changedOnBothSidesIsAConflict() {
        val old = entry("a.txt", "uno")
        val new = entry("a.txt", "dos")
        write("a.txt", "editado en el movil")
        val result = plan(listOf(new), mapOf("a.txt" to old.sha))
        assertEquals(listOf(PlannedFile(new, PlanKind.MODIFIED, conflict = true)), result.download)
        assertEquals(1, result.counts.conflicts)
    }

    @Test fun anUntrackedDifferentFileIsAConflict() {
        val a = entry("a.txt", "de GitHub")
        write("a.txt", "mio")
        assertEquals(listOf(PlannedFile(a, PlanKind.ADDED, conflict = true)), plan(listOf(a), emptyMap()).download)
    }

    @Test fun unknownButIdenticalOnDiskIsAdopted() {
        val a = entry("a.txt", "uno")
        write("a.txt", "uno")
        assertEquals(PlanCounts(0, 0, 0, 1), plan(listOf(a), emptyMap()).counts)
    }

    @Test fun verifyAllCatchesSameSizeEditsAndMarksThemToKeep() {
        val a = entry("a.txt", "uno")
        write("a.txt", "dos") // mismo tamano, otro contenido
        assertEquals(0, plan(listOf(a), mapOf("a.txt" to a.sha)).download.size)
        val strict = plan(listOf(a), mapOf("a.txt" to a.sha), verifyAll = true)
        assertEquals(listOf(PlannedFile(a, PlanKind.RESTORED, conflict = true)), strict.download)
    }

    @Test fun onlyManifestPathsMissingFromTheBranchAreRemoved() {
        val a = entry("a.txt", "uno")
        write("a.txt", "uno")
        write("ajeno.txt", "no es mio")
        val result = plan(listOf(a), mapOf("a.txt" to a.sha, "viejo.txt" to "x".repeat(40)))
        assertEquals(listOf(PlannedRemoval("viejo.txt", conflict = false)), result.remove)
    }

    @Test fun removingAFileEditedOnThePhoneIsAConflict() {
        val gone = entry("viejo.txt", "como vino")
        write("viejo.txt", "lo toque yo")
        val result = plan(emptyList(), mapOf("viejo.txt" to gone.sha))
        assertEquals(listOf(PlannedRemoval("viejo.txt", conflict = true)), result.remove)
        write("viejo.txt", "como vino")
        assertEquals(listOf(PlannedRemoval("viejo.txt", conflict = false)), plan(emptyList(), mapOf("viejo.txt" to gone.sha)).remove)
    }

    @Test fun parallelHashingMatchesSequential() = runBlocking {
        val files = (1..200).map { i -> File(tmp.root, "f$i.bin").also { it.writeBytes(ByteArray(i * 37) { b -> (b * i).toByte() }) } }
        val hashes = SyncPlanner.hashAll(files, parallelism = 4)
        assertEquals(files.size, hashes.size)
        assertTrue(files.all { hashes[it] == GitBlobHash.ofFile(it) })
    }
}
