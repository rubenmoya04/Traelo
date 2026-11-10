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
        SyncEngine(
            api, manifests, ZipPolicy.atLeast(zipThreshold), parallelism,
            progressIntervalMs = 0, retryDelayMs = 0, clock = { 42L },
        )

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
        assertEquals(base.keys, manifests.files(repo).keys)
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
        manifests.save(repo, manifests.load(repo).let { it.copy(files = it.files + ("carpeta" to "x".repeat(40))) })
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
        assertEquals(base.keys, manifests.files(repo).keys)
    }

    @Test fun aBigBatchOfChangesLaterAlsoUsesTheZipAndOnlyWritesWhatChanged() = runBlocking {
        val state = firstPull(engine(zipThreshold = 5)).newState
        File(dir, "README.md").writeText("editado en el movil\n")
        val more = base + (1..6).associate { "assets/sfx/s$it.wav" to "sonido $it\n" } +
            ("game/scripts/a.gd" to "extends Node2D\n")
        api.commit("main", more)
        api.blobDownloads.clear()
        val result = engine(zipThreshold = 5).pull(repo, "main", dir, state)
        assertEquals(1, api.zipDownloads)
        assertTrue(api.blobDownloads.isEmpty())
        assertEquals(7, result.downloaded)
        assertEquals("sonido 3\n", read("assets/sfx/s3.wav"))
        assertEquals("extends Node2D\n", read("game/scripts/a.gd"))
        assertEquals("editado en el movil\n", read("README.md"), "lo que no cambio en GitHub no se pisa")
        assertEquals(more.keys, manifests.files(repo).keys)
    }

    @Test fun aSmallBatchOfChangesStillGoesFileByFile() = runBlocking {
        val state = firstPull(engine(zipThreshold = 5)).newState
        api.commit("main", base + ("game/scripts/a.gd" to "extends Node2D\n"))
        api.blobDownloads.clear()
        engine(zipThreshold = 5).pull(repo, "main", dir, state)
        assertEquals(0, api.zipDownloads)
        assertEquals(1, api.blobDownloads.size)
    }

    @Test fun ignoredPathsAreNotDownloadedNorDeleted() = runBlocking {
        val withTests = base + ("tests/test_a.gd" to "# test\n")
        api.commit("main", withTests)
        val state = engine().pull(repo, "main", dir, null).newState
        api.commit(
            "main",
            withTests + (".traeloignore" to "# solo para jugar\ntests/\n*.md\n") + ("tests/test_b.gd" to "# b\n"),
        )
        val check = engine().check(repo, "main", state)
        assertIs<CheckResult.Outdated>(check)
        assertEquals(1, check.counts.added, "solo el propio .traeloignore es nuevo")
        val result = engine().pull(repo, "main", dir, state)
        assertEquals(3, result.ignored)
        assertTrue(File(dir, "tests/test_a.gd").isFile, "lo ignorado que ya estaba no se borra")
        assertTrue(File(dir, "README.md").isFile)
        assertFalse(File(dir, "tests/test_b.gd").exists(), "lo ignorado nuevo no se baja")
        val tracked = manifests.files(repo).keys
        assertFalse("tests/test_a.gd" in tracked)
        assertFalse("README.md" in tracked)
        assertTrue(".traeloignore" in tracked)
    }

    @Test fun ignoredFilesDoNotCountForTheFirstSync() = runBlocking {
        api.commit("main", base + (".traeloignore" to "game/\n"))
        val check = engine().check(repo, "main", null)
        assertIs<CheckResult.NeverSynced>(check)
        assertEquals(2, check.files)
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
        assertEquals(base.keys, manifests.files(repo).keys)
    }

    @Test fun corruptDownloadIsRejectedAndLeavesNothingBehind() {
        api.commit("main", base)
        api.corruptBlobs = true
        assertFailsWith<GitHubException.Integrity> { runBlocking { engine().pull(repo, "main", dir, null) } }
        assertTrue(dir.walkTopDown().none { it.isFile }, "no se escribio ningun archivo")
        assertTrue(manifests.files(repo).isEmpty())
    }

    @Test fun anInterruptedFirstPullSavesNothingAndResumesWithoutRedownloading() = runBlocking {
        api.commit("main", base)
        api.failBlobsAfter = 2
        assertFailsWith<IOException> { engine(parallelism = 1).pull(repo, "main", dir, null) }
        assertTrue(manifests.files(repo).isEmpty(), "nada guardado hasta acabar bien")
        assertTrue(leftovers().isEmpty())
        api.failBlobsAfter = -1
        api.blobDownloads.clear()
        val state = engine(parallelism = 1).pull(repo, "main", dir, null).newState
        assertEquals(2, api.blobDownloads.size, "solo lo que faltaba: lo ya bajado se reconoce por su huella")
        assertEquals(api.head("main"), state.headSha)
        for ((path, content) in base) assertEquals(content, read(path))
        assertTrue(conflictCopies().isEmpty())
    }

    @Test fun cancellingStopsAndLeavesNoHalfWrittenFiles() = runBlocking {
        api.commit("main", base)
        lateinit var job: Deferred<PullResult>
        job = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
            engine(parallelism = 1).pull(repo, "main", dir, null) { progress ->
                if (progress.phase == SyncPhase.DOWNLOADING_FILES && progress.done == 2) job.cancel()
            }
        }
        job.start()
        assertFailsWith<CancellationException> { job.await() }
        assertTrue(manifests.files(repo).isEmpty())
        assertTrue(leftovers().isEmpty())
        assertTrue(dir.walkTopDown().filter { it.isFile }.count() in 2..3)
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
        assertEquals(base.keys, manifests.files(repo).keys)
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

    // ------------------------------------------------------------ conflictos y estado

    private fun conflictCopies(): Map<String, String> {
        val root = File(tmp.root, "proyecto-conflictos")
        if (!root.isDirectory) return emptyMap()
        return root.walkTopDown().filter { it.isFile }.associate { it.name to it.readText() }
    }

    @Test fun aPullDoesNotSilentlyOverwriteAFileEditedOnThePhone() = runBlocking {
        val state = firstPull().newState
        File(dir, "game/scripts/a.gd").writeText("EDITADO EN EL MOVIL\n")
        api.commit("main", base + files("game/scripts/a.gd" to "extends Node2D\n"))
        engine().pull(repo, "main", dir, state)
        assertEquals("extends Node2D\n", read("game/scripts/a.gd"), "llega lo de GitHub")
        assertEquals("EDITADO EN EL MOVIL\n", conflictCopies()["a.gd"], "lo del movil se guarda aparte")
    }

    @Test fun aBigBatchFromTheZipAlsoKeepsPhoneEdits() = runBlocking {
        val state = firstPull(engine(zipThreshold = 3)).newState
        File(dir, "game/scripts/a.gd").writeText("EDITADO EN EL MOVIL\n")
        api.commit("main", base.mapValues { it.value + "v2\n" })
        api.zipDownloads = 0
        engine(zipThreshold = 3).pull(repo, "main", dir, state)
        assertEquals(1, api.zipDownloads)
        assertEquals("extends Node\nv2\n", read("game/scripts/a.gd"))
        assertEquals("EDITADO EN EL MOVIL\n", conflictCopies()["a.gd"])
    }

    @Test fun aFileRemovedOnGitHubButEditedOnThePhoneIsKeptAside() = runBlocking {
        val state = firstPull().newState
        File(dir, "game/scripts/sub/b.gd").writeText("lo cambie yo\n")
        api.commit("main", base - "game/scripts/sub/b.gd")
        engine().pull(repo, "main", dir, state)
        assertFalse(File(dir, "game/scripts/sub/b.gd").exists())
        assertEquals("lo cambie yo\n", conflictCopies()["b.gd"])
    }

    @Test fun anUntrackedPhoneFileInTheWayOfANewFileIsKeptAside() = runBlocking {
        val state = firstPull().newState
        File(dir, "game/nuevo.gd").writeText("mio, sin subir\n")
        api.commit("main", base + files("game/nuevo.gd" to "de GitHub\n"))
        engine().pull(repo, "main", dir, state)
        assertEquals("de GitHub\n", read("game/nuevo.gd"))
        assertEquals("mio, sin subir\n", conflictCopies()["nuevo.gd"])
    }

    @Test fun twoPullsOfTheSameRepoRunOneAfterTheOther() = runBlocking {
        api.commit("main", base)
        val firstBlocked = java.util.concurrent.CountDownLatch(1)
        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        api.beforeBlob = {
            if (calls.incrementAndGet() == 1) {
                firstBlocked.countDown()
                releaseFirst.await()
                throw IOException("se corta la primera")
            }
        }
        val e = engine(parallelism = 1)
        val first = async(Dispatchers.Default) { runCatching { e.pull(repo, "main", dir, null) } }
        firstBlocked.await()
        val target = api.commit("main", base + files("extra.txt" to "extra\n"))
        val second = async(Dispatchers.Default) { e.pull(repo, "main", dir, null) }
        Thread.sleep(300)
        val callsWhileFirstRuns = calls.get()
        releaseFirst.countDown()
        assertEquals(1, callsWhileFirstRuns, "la segunda espera a que acabe la primera")
        assertTrue(first.await().isFailure)
        val result = second.await()
        assertEquals(target, result.newState.headSha)
        assertEquals((base + files("extra.txt" to "extra\n")).keys, manifests.files(repo).keys)
        for ((path, sha) in manifests.files(repo)) assertEquals(sha, GitBlobHash.ofFile(File(dir, path)))
        assertTrue(leftovers().isEmpty())
    }

    @Test fun aFailedPullLeavesTheSavedStateUntouched() = runBlocking {
        val state = firstPull().newState
        val before = manifests.files(repo)
        api.commit("main", base + files("README.md" to "v2\n", "game/x.gd" to "x\n", "game/y.gd" to "y\n"))
        api.failBlobsAfter = api.blobDownloads.size + 2
        assertFailsWith<IOException> { engine(parallelism = 1).pull(repo, "main", dir, state) }
        assertEquals(before, manifests.files(repo), "solo se guarda al final de una sincronizacion correcta")
        api.failBlobsAfter = -1
        api.blobDownloads.clear()
        val done = engine(parallelism = 1).pull(repo, "main", dir, state)
        assertEquals(1, api.blobDownloads.size, "retoma: solo baja lo que faltaba")
        assertEquals("v2\n", read("README.md"))
        assertEquals(api.head("main"), done.newState.headSha)
        assertTrue(conflictCopies().isEmpty(), "lo bajado a medias no cuenta como cambio del movil")
    }

    // ------------------------------------------------------------ velocidad y avisos

    @Test fun nothingNewDoesNotEvenAskForTheTree() = runBlocking {
        val state = firstPull().newState
        api.treeCalls = 0
        val result = engine().pull(repo, "main", dir, state)
        assertTrue(result.fastPath)
        assertEquals(0, api.treeCalls)
        File(dir, "README.md").delete()
        val again = engine().pull(repo, "main", dir, state)
        assertFalse(again.fastPath, "si falta algo se mira el arbol y se repone")
        assertEquals("hola\n", read("README.md"))
    }

    @Test fun checkThenPullAsksForTheTreeOnce() = runBlocking {
        val state = firstPull().newState
        api.commit("main", base + files("x.txt" to "x\n"))
        api.treeCalls = 0
        val e = engine()
        e.check(repo, "main", state)
        e.pull(repo, "main", dir, state)
        assertEquals(1, api.treeCalls)
    }

    @Test fun conflictsAreReportedWithWhereTheyWereSaved() = runBlocking {
        val state = firstPull().newState
        File(dir, "game/scripts/a.gd").writeText("mio\n")
        File(dir, "game/scripts/sub/b.gd").writeText("mio tambien\n")
        api.commit("main", base - "game/scripts/sub/b.gd" + files("game/scripts/a.gd" to "nuevo\n"))
        val check = engine().check(repo, "main", state)
        assertIs<CheckResult.Outdated>(check)
        assertEquals(2, check.counts.conflicts, "se avisa antes de traer")
        val seen = java.util.concurrent.ConcurrentLinkedQueue<ConflictFile>()
        val result = engine().pull(repo, "main", dir, state, onConflict = { seen += it })
        assertEquals(
            listOf("game/scripts/a.gd" to ConflictReason.CHANGED_BOTH, "game/scripts/sub/b.gd" to ConflictReason.REMOVED_UPSTREAM),
            result.conflicts.map { it.path to it.reason },
        )
        assertEquals(result.conflicts.toSet(), seen.toSet())
        val conflictDir = assertNotNull(result.conflictDir)
        assertTrue(conflictDir.startsWith(File(tmp.root, "proyecto-conflictos").path), "fuera del proyecto")
        for (c in result.conflicts) assertTrue(File(c.savedAs).isFile && c.savedAs.startsWith(conflictDir))
        assertEquals("mio\n", File(result.conflicts[0].savedAs).readText())
        assertFalse(File(dir, "proyecto-conflictos").exists())
    }

    @Test fun pristineFilesAreNotReportedAsConflicts() = runBlocking {
        val state = firstPull().newState
        api.commit("main", base - "README.md" + files("game/scripts/a.gd" to "nuevo\n"))
        val result = engine().pull(repo, "main", dir, state)
        assertTrue(result.conflicts.isEmpty())
        assertNull(result.conflictDir)
        assertTrue(conflictCopies().isEmpty())
    }

    @Test fun restoreKeepsACopyOfWhatItReplaces() = runBlocking {
        val state = firstPull().newState
        File(dir, "game/scripts/a.gd").writeText("EDITADO\n")
        val result = engine().pull(repo, "main", dir, state, verifyLocal = true)
        assertEquals("extends Node\n", read("game/scripts/a.gd"))
        assertEquals(listOf(ConflictReason.RESTORED), result.conflicts.map { it.reason })
        assertEquals("EDITADO\n", conflictCopies()["a.gd"])
    }

    @Test fun twoConflictsOnTheSamePathDoNotOverwriteEachOther() = runBlocking {
        var state = firstPull().newState
        File(dir, "README.md").writeText("primera edicion\n")
        api.commit("main", base + files("README.md" to "v2\n"))
        state = engine().pull(repo, "main", dir, state).newState
        File(dir, "README.md").writeText("segunda edicion\n")
        api.commit("main", base + files("README.md" to "v3\n"))
        engine().pull(repo, "main", dir, state)
        val copies = File(tmp.root, "proyecto-conflictos").walkTopDown().filter { it.isFile }.map { it.readText() }.toSet()
        assertEquals(setOf("primera edicion\n", "segunda edicion\n"), copies)
    }

    @Test fun aZipCutHalfwayFallsBackToFileByFile() = runBlocking {
        api.commit("main", base + (1..20).associate { "assets/s$it.txt" to "sonido $it ".repeat(50) })
        api.zipBreaksAfterBytes = 900
        val result = engine(zipThreshold = 2).pull(repo, "main", dir, null)
        assertEquals(1, api.zipDownloads)
        assertTrue(api.blobDownloads.isNotEmpty(), "lo que faltaba se bajo suelto")
        assertEquals(24, result.downloaded)
        assertEquals("sonido 7 ".repeat(50), read("assets/s7.txt"))
        assertTrue(leftovers().isEmpty())
    }

    @Test fun flakyNetworkIsRetried() = runBlocking {
        api.commit("main", base)
        api.flakyBlobs = 2
        engine().pull(repo, "main", dir, null)
        for ((path, content) in base) assertEquals(content, read(path))
    }

    @Test fun progressCountsFilesAndBytes() = runBlocking {
        api.commit("main", base)
        val events = java.util.concurrent.ConcurrentLinkedQueue<SyncProgress>()
        engine().pull(repo, "main", dir, null) { events += it }
        val downloads = events.filter { it.phase == SyncPhase.DOWNLOADING_FILES }
        val total = base.values.sumOf { it.toByteArray().size.toLong() }
        assertTrue(downloads.all { it.total == 4 && it.bytesTotal == total })
        assertEquals(4, downloads.maxOf { it.done })
        assertEquals(total, downloads.maxOf { it.bytesDone })
        assertEquals(SyncPhase.SAVING, events.last().phase)
    }

    @Test fun zipProgressAndBytesAreReported() = runBlocking {
        api.commit("main", base)
        val events = java.util.concurrent.ConcurrentLinkedQueue<SyncProgress>()
        val result = engine(zipThreshold = 2).pull(repo, "main", dir, null) { events += it }
        val zip = events.filter { it.phase == SyncPhase.DOWNLOADING_ZIP }
        assertEquals(4, zip.maxOf { it.done })
        assertTrue(zip.maxOf { it.bytesDone } > 0)
        assertTrue(result.bytesDownloaded > 0)
    }

    @Test fun aSecondPullWaitsAndSaysSo() = runBlocking {
        api.commit("main", base)
        val blocked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        api.beforeBlob = { blocked.countDown(); release.await() }
        val e = engine(parallelism = 1)
        val first = async(Dispatchers.Default) { e.pull(repo, "main", dir, null) }
        blocked.await()
        assertTrue(e.isBusy(repo))
        val phases = java.util.concurrent.ConcurrentLinkedQueue<SyncPhase>()
        val second = async(Dispatchers.Default) { e.pull(repo, "main", dir, null) { phases += it.phase } }
        Thread.sleep(200)
        api.beforeBlob = {}
        release.countDown()
        first.await()
        second.await()
        assertEquals(SyncPhase.WAITING, phases.first())
        assertFalse(e.isBusy(repo))
    }
}
