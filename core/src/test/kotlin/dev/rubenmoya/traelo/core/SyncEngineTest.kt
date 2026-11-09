package dev.rubenmoya.traelo.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SyncEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val repo = RepoRef("rubenmoya04", "Socks-Sauce")
    private lateinit var api: FakeGitHubApi
    private lateinit var manifests: MemoryManifests
    private lateinit var dir: File

    @Before fun setUp() {
        api = FakeGitHubApi()
        manifests = MemoryManifests()
        dir = File(tmp.root, "proyecto")
    }

    private fun engine(zipThreshold: Int = 60, parallelism: Int = 4) =
        SyncEngine(api, manifests, zipThreshold, parallelism, clock = { 42L })

    private fun files(vararg pairs: Pair<String, String>) = pairs.toMap()

    private val base = files(
        "README.md" to "hola\n",
        "game/project.godot" to "config_version=5\n",
        "game/scripts/a.gd" to "extends Node\n",
        "game/scripts/sub/b.gd" to "# b\n",
    )

    private fun read(path: String) = File(dir, path).readText()

    private fun leftovers() = dir.walkTopDown().filter { it.name.endsWith(".traelo-part") }.toList()

    private fun firstPull(e: SyncEngine = engine()): PullResult = runBlocking {
        api.commit("main", base)
        e.pull(repo, "main", dir, null)
    }

    @Test fun firstPullWritesEverythingAndRemembersIt() {
        val result = firstPull()
        for ((path, content) in base) assertEquals(content, read(path))
        assertEquals(4, result.downloaded)
        assertEquals(api.head("main"), result.newState.headSha)
        assertEquals("main", result.newState.branch)
        assertEquals(42L, result.newState.syncedAtMillis)
        assertEquals(base.keys, manifests.load(repo).keys)
        assertEquals(0, api.zipDownloads)
        assertTrue(leftovers().isEmpty())
    }

    @Test fun nothingNewMeansUpToDateAndNoDownloads() = runBlocking {
        val state = firstPull().newState
        val check = engine().check(repo, "main", state)
        assertIs<CheckResult.UpToDate>(check)
        api.blobDownloads.clear()
        val again = engine().pull(repo, "main", dir, state)
        assertEquals(0, again.downloaded)
        assertTrue(api.blobDownloads.isEmpty())
    }

    @Test fun neverSyncedCountsFiles() = runBlocking {
        api.commit("main", base)
        val check = engine().check(repo, "main", null)
        assertEquals(CheckResult.NeverSynced(api.head("main"), 4), check)
    }

    @Test fun onlyChangesAreDownloadedAndRemovedFilesGoAway() = runBlocking {
        val state = firstPull().newState
        api.commit(
            "main",
            base - "game/scripts/sub/b.gd" + files("README.md" to "hola v2\n", "game/new.gd" to "nuevo\n"),
        )
        val check = engine().check(repo, "main", state)
        assertIs<CheckResult.Outdated>(check)
        assertEquals(PlanCounts(added = 1, modified = 1, removed = 1, unchanged = 2), check.counts)
        assertEquals(1, check.commits?.size)
        api.blobDownloads.clear()
        val result = engine().pull(repo, "main", dir, state)
        assertEquals(2, api.blobDownloads.size)
        assertEquals(2, result.downloaded)
        assertEquals(1, result.removed)
        assertEquals("hola v2\n", read("README.md"))
        assertEquals("nuevo\n", read("game/new.gd"))
        assertFalse(File(dir, "game/scripts/sub/b.gd").exists())
        assertFalse(File(dir, "game/scripts/sub").exists(), "la carpeta vacia se limpia")
        assertTrue(File(dir, "game/scripts").exists())
    }

    @Test fun neverDeletesWhatTheEngineDidNotBring() = runBlocking {
        val state = firstPull().newState
        File(dir, ".godot/imported").mkdirs()
        File(dir, ".godot/imported/cache.bin").writeText("cache")
        File(dir, "notas-mias.txt").writeText("mias")
        File(dir, "game/scripts/sub/mio.txt").writeText("tambien mio")
        api.commit("main", base - "game/scripts/sub/b.gd")
        engine().pull(repo, "main", dir, state)
        assertEquals("cache", read(".godot/imported/cache.bin"))
        assertEquals("mias", read("notas-mias.txt"))
        assertEquals("tambien mio", read("game/scripts/sub/mio.txt"))
        assertFalse(File(dir, "game/scripts/sub/b.gd").exists())
    }

    @Test fun removalNeverDeletesADirectory() = runBlocking {
        val state = firstPull().newState
        manifests.save(repo, manifests.load(repo) + ("carpeta" to "x".repeat(40)))
        File(dir, "carpeta").mkdirs()
        File(dir, "carpeta/dentro.txt").writeText("hola")
        engine().pull(repo, "main", dir, state)
        assertEquals("hola", read("carpeta/dentro.txt"))
    }

    @Test fun plainPullLeavesLocalEditsButVerifyRestoresThem() = runBlocking {
        val state = firstPull().newState
        File(dir, "game/scripts/a.gd").writeText("EDITADO EN EL MOVIL\n")
        File(dir, "README.md").delete()
        val changes = engine().findLocalChanges(state)
        assertEquals(listOf("game/scripts/a.gd"), changes.modified)
        assertEquals(listOf("README.md"), changes.missing)
        // Un pull normal repone lo que falta pero no pisa lo editado.
        engine().pull(repo, "main", dir, state)
        assertEquals("hola\n", read("README.md"))
        assertEquals("EDITADO EN EL MOVIL\n", read("game/scripts/a.gd"))
        // Restaurar si lo repone.
        engine().pull(repo, "main", dir, state, verifyLocal = true)
        assertEquals("extends Node\n", read("game/scripts/a.gd"))
        assertTrue(engine().findLocalChanges(state).isEmpty)
    }

    @Test fun switchingBranchMovesOnlyTheDifferences() = runBlocking {
        val state = firstPull().newState
        api.commit("dev", base - "README.md" + files("game/scripts/a.gd" to "extends Node2D\n", "dev.txt" to "dev\n"))
        val check = engine().check(repo, "dev", state)
        assertIs<CheckResult.Outdated>(check)
        assertEquals(PlanCounts(added = 1, modified = 1, removed = 1, unchanged = 2), check.counts)
        api.blobDownloads.clear()
        val result = engine().pull(repo, "dev", dir, state)
        assertEquals(2, api.blobDownloads.size)
        assertEquals("dev", result.newState.branch)
        assertFalse(File(dir, "README.md").exists())
        assertEquals("extends Node2D\n", read("game/scripts/a.gd"))
        assertEquals("dev\n", read("dev.txt"))
    }

    @Test fun compareFailureGivesNullCommitsNotAnError() = runBlocking {
        val state = firstPull().newState
        api.commit("main", base + files("x.txt" to "x"))
        api.compareReturnsNull = true
        val check = engine().check(repo, "main", state)
        assertIs<CheckResult.Outdated>(check)
        assertNull(check.commits)
    }

    @Test fun manyFilesOnFirstPullUseTheZip() = runBlocking {
        api.commit("main", base)
        val result = engine(zipThreshold = 2).pull(repo, "main", dir, null)
        assertEquals(1, api.zipDownloads)
        assertTrue(api.blobDownloads.isEmpty(), "nada que bajar archivo a archivo si el zip coincide")
        for ((path, content) in base) assertEquals(content, read(path))
        assertEquals(4, result.downloaded)
        assertEquals(base.keys, manifests.load(repo).keys)
    }

    @Test fun zipFilesThatDifferFromTheTreeAreFetchedOneByOne() = runBlocking {
        api.commit("main", base)
        api.zipOverrides = mapOf("README.md" to "hola\r\n".toByteArray())
        engine(zipThreshold = 2).pull(repo, "main", dir, null)
        assertEquals(1, api.blobDownloads.size)
        assertEquals("hola\n", read("README.md"))
    }

    @Test fun zipCannotWriteOutsideTheFolderNorUnlistedFiles() = runBlocking {
        api.commit("main", base)
        api.extraZipEntries = mapOf(
            "owner-repo-abc1234/../../evil.txt" to "malo".toByteArray(),
            "owner-repo-abc1234/../evil2.txt" to "malo".toByteArray(),
            "owner-repo-abc1234/no-esta-en-la-rama.txt" to "intruso".toByteArray(),
            "/etc/absoluto.txt" to "malo".toByteArray(),
        )
        engine(zipThreshold = 2).pull(repo, "main", dir, null)
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(tmp.root, "evil2.txt").exists())
        assertFalse(File(tmp.root.parentFile, "evil.txt").exists())
        assertFalse(File(dir, "no-esta-en-la-rama.txt").exists())
        assertEquals(tmp.root.list()?.toSet(), setOf("proyecto"))
    }

    @Test fun treePathsThatAreNotSafeAreSkippedAndReported() = runBlocking {
        api.commit("main", base)
        api.extraTreeEntries = listOf(TreeEntry("../escape.txt", "a".repeat(40), 1), TreeEntry(".git/config", "b".repeat(40), 1))
        api.extraSkipped = listOf("enlace-simbolico")
        val result = engine().pull(repo, "main", dir, null)
        assertFalse(File(tmp.root, "escape.txt").exists())
        assertEquals(setOf("enlace-simbolico", "../escape.txt", ".git/config"), result.skipped.toSet())
        assertEquals(base.keys, manifests.load(repo).keys)
    }

    @Test fun corruptDownloadIsRejectedAndLeavesNothingBehind() {
        api.commit("main", base)
        api.corruptBlobs = true
        assertFailsWith<GitHubException.Integrity> { runBlocking { engine().pull(repo, "main", dir, null) } }
        assertTrue(dir.walkTopDown().none { it.isFile }, "no se escribio ningun archivo")
        assertTrue(manifests.load(repo).isEmpty())
    }

    @Test fun anInterruptedPullKeepsAnHonestManifestAndResumes() = runBlocking {
        api.commit("main", base)
        api.failBlobsAfter = 2
        assertFailsWith<IOException> { engine(parallelism = 1).pull(repo, "main", dir, null) }
        val partial = manifests.load(repo)
        assertEquals(2, partial.size)
        for ((path, sha) in partial) assertEquals(sha, GitBlobHash.ofFile(File(dir, path)), "$path esta en disco")
        assertTrue(leftovers().isEmpty())
        api.failBlobsAfter = -1
        api.blobDownloads.clear()
        val state = engine(parallelism = 1).pull(repo, "main", dir, null).newState
        assertEquals(2, api.blobDownloads.size, "solo lo que faltaba")
        assertEquals(api.head("main"), state.headSha)
        for ((path, content) in base) assertEquals(content, read(path))
    }

    @Test fun cancellingStopsAndKeepsTheManifestHonest() = runBlocking {
        api.commit("main", base)
        lateinit var job: Deferred<PullResult>
        job = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
            engine(parallelism = 1).pull(repo, "main", dir, null) { progress ->
                if (progress.phase == SyncPhase.DOWNLOADING_FILES && progress.done == 2) job.cancel()
            }
        }
        job.start()
        assertFailsWith<CancellationException> { job.await() }
        val partial = manifests.load(repo)
        assertTrue(partial.size in 2..3)
        for ((path, sha) in partial) assertEquals(sha, GitBlobHash.ofFile(File(dir, path)))
        assertTrue(leftovers().isEmpty())
    }

    @Test fun truncatedTreeIsRefused() {
        api.commit("main", base)
        api.truncatedTree = true
        assertFailsWith<GitHubException.TooBig> { runBlocking { engine().check(repo, "main", null) } }
        assertFailsWith<GitHubException.TooBig> { runBlocking { engine().pull(repo, "main", dir, null) } }
    }

    @Test fun fileWhereAFolderShouldBeIsAClearErrorNotACrash() {
        api.commit("main", base)
        dir.mkdirs()
        File(dir, "game").writeText("soy un archivo")
        val error = assertFailsWith<IOException> { runBlocking { engine().pull(repo, "main", dir, null) } }
        assertNotNull(error.message)
        assertEquals("soy un archivo", File(dir, "game").readText())
    }

    @Test fun folderWhereAFileShouldBeIsAClearError() {
        api.commit("main", base)
        File(dir, "README.md").mkdirs()
        assertFailsWith<IOException> { runBlocking { engine().pull(repo, "main", dir, null) } }
    }

    @Test fun existingIdenticalFilesAreAdoptedWithoutDownloading() = runBlocking {
        api.commit("main", base)
        for ((path, content) in base) File(dir, path).also { it.parentFile.mkdirs() }.writeText(content)
        val result = engine().pull(repo, "main", dir, null)
        assertTrue(api.blobDownloads.isEmpty())
        assertEquals(0, result.downloaded)
        assertEquals(base.keys, manifests.load(repo).keys)
    }

    @Test fun aSymlinkInsideTheFolderCannotRedirectWrites() = runBlocking {
        api.commit("main", base + files("enlace/dentro.txt" to "no debe salir\n"))
        val outside = File(tmp.root, "fuera").also { it.mkdirs() }
        dir.mkdirs()
        Files.createSymbolicLink(File(dir, "enlace").toPath(), outside.toPath())
        val result = engine().pull(repo, "main", dir, null)
        assertTrue(outside.list().isNullOrEmpty())
        assertTrue("enlace/dentro.txt" in result.skipped)
    }
}
