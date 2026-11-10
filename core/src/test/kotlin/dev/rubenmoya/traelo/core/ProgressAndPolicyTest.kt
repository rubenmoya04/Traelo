package dev.rubenmoya.traelo.core

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ProgressAndPolicyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun meterGivesSpeedAndTimeLeft() {
        val meter = TransferMeter(windowMs = 4_000)
        assertEquals(0, meter.bytesPerSecond())
        assertNull(meter.etaSeconds(1000))
        meter.sample(0, 0)
        meter.sample(1_000, 2_000_000)
        meter.sample(2_000, 4_000_000)
        assertEquals(2_000_000, meter.bytesPerSecond())
        assertEquals(3, meter.etaSeconds(10_000_000))
    }

    @Test fun meterForgetsOldSamples() {
        val meter = TransferMeter(windowMs = 2_000)
        meter.sample(0, 0)
        meter.sample(1_000, 10_000_000) // arranque muy rapido
        meter.sample(5_000, 11_000_000)
        meter.sample(6_000, 12_000_000)
        assertEquals(1_000_000, meter.bytesPerSecond())
    }

    @Test fun trackerThrottlesButAlwaysSendsPhaseChanges() {
        var now = 0L
        val sent = ArrayList<SyncProgress>()
        val tracker = ProgressTracker({ sent += it }, intervalMs = 100, clock = { now })
        tracker.phase(SyncPhase.DOWNLOADING_FILES, total = 3, bytesTotal = 300)
        repeat(10) { tracker.bytes(10) }
        assertEquals(1, sent.size, "sin pasar el intervalo no se manda nada nuevo")
        now = 150
        tracker.bytes(10)
        assertEquals(110, sent.last().bytesDone)
        tracker.phase(SyncPhase.SAVING)
        assertEquals(SyncPhase.SAVING, sent.last().phase)
    }

    @Test fun costModelPicksTheZipOnlyWhenItPays() {
        val policy = ZipPolicy.DEFAULT
        val mb = 1024L * 1024
        // Juego de 3000 archivos y 80 MB.
        assertTrue(policy.useZip(files = 3000, bytes = 80 * mb, zipBytes = 80 * mb), "primera descarga")
        assertFalse(policy.useZip(files = 150, bytes = 4 * mb, zipBytes = 80 * mb), "una tanda normal va suelta")
        assertFalse(policy.useZip(files = 600, bytes = 12 * mb, zipBytes = 80 * mb))
        assertFalse(policy.useZip(files = 50, bytes = 1 * mb, zipBytes = 1 * mb), "pocos archivos nunca")
    }

    @Test fun hashingStreamChecksSizeAndSha() {
        val data = "hola mundo".toByteArray()
        val sha = GitBlobHash.ofBytes(data)
        val ok = BlobHashingOutputStream(ByteArrayOutputStream(), data.size.toLong())
        ok.write(data, 0, 4)
        ok.write(data, 4, data.size - 4)
        assertTrue(ok.matches(sha))
        val short = BlobHashingOutputStream(ByteArrayOutputStream(), data.size.toLong())
        short.write(data, 0, 4)
        assertFalse(short.matches(sha))
    }

    @Test fun manifestStoreKeepsHeadAndBranchAndReadsTheOldFormat() {
        val store = FileManifestStore(tmp.root)
        val repo = RepoRef("o", "r")
        store.save(repo, Manifest(mapOf("a.txt" to "s1"), head = "h1", branch = "main"))
        assertEquals(Manifest(mapOf("a.txt" to "s1"), "h1", "main"), FileManifestStore(tmp.root).load(repo))
        store.save(repo, Manifest(mapOf("a.txt" to "s1"), head = "h1", branch = "main", ignore = "tests/\n"))
        assertEquals("tests/\n", store.load(repo).ignore)
        store.save(repo, Manifest(mapOf("a.txt" to "s1"), head = "h1", branch = "main", ignore = ""))
        assertEquals("", store.load(repo).ignore, "sin .traeloignore no es lo mismo que no saberlo")
        File(tmp.root, "o__r.json").writeText("""{"files":{"b.txt":"s2"}}""")
        assertEquals(Manifest(mapOf("b.txt" to "s2")), store.load(repo))
        File(tmp.root, "o__r.json").writeText("roto")
        assertEquals(Manifest.EMPTY, store.load(repo))
        assertFalse(File(tmp.root, "o__r.json.tmp").exists())
    }

    @Test fun tooManyFilesAlwaysGoByZipToRespectGitHubLimits() {
        val mb = 1024L * 1024
        assertTrue(ZipPolicy.DEFAULT.useZip(files = 1200, bytes = 5 * mb, zipBytes = 500 * mb))
    }

    @Test fun readAheadDeliversEverythingInOrder() {
        val data = ByteArray(1_000_003) { (it * 31).toByte() }
        val out = ReadAheadInputStream(java.io.ByteArrayInputStream(data), chunkSize = 4096, maxChunks = 3).use { it.readBytes() }
        assertTrue(data.contentEquals(out))
        val empty = ReadAheadInputStream(java.io.ByteArrayInputStream(ByteArray(0))).use { it.readBytes() }
        assertEquals(0, empty.size)
    }

    @Test fun readAheadPassesErrorsAlong() {
        val broken = object : java.io.InputStream() {
            var served = 0

            override fun read(): Int = if (served++ < 10) 1 else throw java.io.IOException("corte")
        }
        val error = runCatching { ReadAheadInputStream(broken, chunkSize = 4).use { it.readBytes() } }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
    }

    @Test fun closingReadAheadEarlyDoesNotHang() {
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 7
        }
        val stream = ReadAheadInputStream(endless, chunkSize = 1024, maxChunks = 2)
        stream.read()
        stream.close()
    }
}
