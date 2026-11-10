package dev.rubenmoya.traelo.core.bench

import dev.rubenmoya.traelo.core.Branch
import dev.rubenmoya.traelo.core.CommitInfo
import dev.rubenmoya.traelo.core.GitBlobHash
import dev.rubenmoya.traelo.core.GitHubApi
import dev.rubenmoya.traelo.core.GitHubException
import dev.rubenmoya.traelo.core.ManifestStore
import dev.rubenmoya.traelo.core.RemoteRepo
import dev.rubenmoya.traelo.core.RepoRef
import dev.rubenmoya.traelo.core.SyncEngine
import dev.rubenmoya.traelo.core.SyncState
import dev.rubenmoya.traelo.core.TreeEntry
import dev.rubenmoya.traelo.core.TreeResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

/**
 * Red simulada: cada peticion paga [latencyMs] (en paralelo) y los bytes comparten un unico enlace
 * de [bytesPerSec]. Asi se ve el efecto de la concurrencia, del zip y de no pedir nada.
 */
class SimulatedNetwork(val latencyMs: Long, val bytesPerSec: Long, val zipStartMs: Long) {
    private var busyUntil = 0L
    val requests = AtomicInteger()
    val bytes = java.util.concurrent.atomic.AtomicLong()

    fun request() {
        requests.incrementAndGet()
        if (latencyMs > 0) Thread.sleep(latencyMs)
    }

    fun transfer(count: Long) {
        bytes.addAndGet(count)
        if (bytesPerSec <= 0) return
        val duration = count * 1_000_000_000L / bytesPerSec
        val end: Long
        synchronized(this) {
            val start = maxOf(System.nanoTime(), busyUntil)
            end = start + duration
            busyUntil = end
        }
        val wait = end - System.nanoTime()
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
    }

    fun reset() {
        requests.set(0)
        bytes.set(0)
    }
}

/** Repo sintetico parecido a un juego de Godot: muchos .import, PNG, OGG y scripts. */
class SyntheticRepo(seed: Int = 7) {
    val files = LinkedHashMap<String, ByteArray>()

    init {
        val random = Random(seed)
        fun text(size: Int) = ByteArray(size) { "abcdefghij klmnop\n"[random.nextInt(18)].code.toByte() }
        fun noise(size: Int) = random.nextBytes(size)
        repeat(700) { i ->
            files["assets/sprites/s$i.png"] = noise(10_000 + random.nextInt(100_000))
            files["assets/sprites/s$i.png.import"] = text(500 + random.nextInt(200))
        }
        repeat(150) { i ->
            files["assets/audio/a$i.ogg"] = noise(100_000 + random.nextInt(300_000))
            files["assets/audio/a$i.ogg.import"] = text(500 + random.nextInt(200))
        }
        repeat(350) { i -> files["assets/fonts/f$i.png.import"] = text(600) }
        repeat(600) { i -> files["scripts/logic/m$i.gd"] = text(1_000 + random.nextInt(4_000)) }
        repeat(350) { i -> files["scenes/c$i.tscn"] = text(2_000 + random.nextInt(6_000)) }
        repeat(150) { i -> files["data/t$i.json"] = text(500 + random.nextInt(2_000)) }
        files["project.godot"] = text(800)
    }

    val totalBytes: Long get() = files.values.sumOf { it.size.toLong() }
}

/** GitHub falso con la red simulada. Busca blobs por huella en un mapa. */
class SlowGitHubApi(private val net: SimulatedNetwork) : GitHubApi {
    private val snapshots = HashMap<String, Map<String, ByteArray>>()
    private val trees = HashMap<String, List<TreeEntry>>()
    private val zips = HashMap<String, ByteArray>()
    private val blobs = HashMap<String, ByteArray>()
    private val heads = HashMap<String, String>()
    private var counter = 0

    fun commit(branch: String, files: Map<String, ByteArray>): String {
        val sha = "%040d".format(++counter)
        snapshots[sha] = files
        trees[sha] = files.map { (path, data) ->
            val hash = GitBlobHash.ofBytes(data)
            blobs[hash] = data
            TreeEntry(path, hash, data.size.toLong())
        }
        heads[branch] = sha
        return sha
    }

    private fun zipOf(sha: String): ByteArray = zips.getOrPut(sha) {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            for ((path, data) in snapshots.getValue(sha)) {
                zip.putNextEntry(ZipEntry("owner-repo-$sha/$path"))
                zip.write(data)
                zip.closeEntry()
            }
        }
        buffer.toByteArray()
    }

    override fun currentUser() = "bench"

    override fun listRepos() = emptyList<RemoteRepo>()

    override fun listBranches(repo: RepoRef) = heads.map { Branch(it.key, it.value) }

    override fun branchHead(repo: RepoRef, branch: String): String {
        net.request()
        return heads[branch] ?: throw GitHubException.NotFound(branch)
    }

    override fun tree(repo: RepoRef, sha: String): TreeResult {
        net.request()
        val entries = trees.getValue(sha)
        net.transfer(entries.size * 150L) // JSON del arbol
        return TreeResult(entries, emptyList(), false)
    }

    override fun downloadBlob(repo: RepoRef, sha: String, out: OutputStream) {
        net.request()
        val data = blobs[sha] ?: throw GitHubException.NotFound(sha)
        var offset = 0
        while (offset < data.size) {
            val n = minOf(CHUNK, data.size - offset)
            net.transfer(n.toLong())
            out.write(data, offset, n)
            offset += n
        }
    }

    override fun downloadZip(repo: RepoRef, ref: String, dest: File) {
        dest.outputStream().use { streamZip(ref, it) }
    }

    /** Manda el zip a trozos, como lo haria codeload. */
    fun streamZip(ref: String, out: OutputStream) {
        val data = zipOf(ref)
        net.request()
        if (net.zipStartMs > 0) Thread.sleep(net.zipStartMs)
        var offset = 0
        while (offset < data.size) {
            val n = minOf(CHUNK, data.size - offset)
            net.transfer(n.toLong())
            out.write(data, offset, n)
            offset += n
        }
    }

    override fun commitsBetween(repo: RepoRef, base: String, head: String): List<CommitInfo>? {
        net.request()
        return emptyList()
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}

class MemoryStore : ManifestStore {
    private val data = HashMap<String, Map<String, String>>()

    override fun load(repo: RepoRef) = data[repo.key] ?: emptyMap()

    override fun save(repo: RepoRef, files: Map<String, String>) {
        data[repo.key] = HashMap(files)
    }

    override fun clear(repo: RepoRef) {
        data.remove(repo.key)
    }
}

private val repo = RepoRef("bench", "game")

private fun <T> timed(block: () -> T): Pair<T, Long> {
    val start = System.nanoTime()
    val result = block()
    return result to (System.nanoTime() - start) / 1_000_000
}

private class Scenario(val name: String, val ms: Long, val requests: Int, val megabytes: Double)

/**
 * Benchmark de sincronizacion con 3000 archivos y red simulada.
 * Uso: ./gradlew :core:benchmark (argumentos opcionales: latencia ms, MB/s).
 */
fun main(args: Array<String>) {
    val latency = args.getOrNull(0)?.toLongOrNull() ?: 120L
    val mbps = args.getOrNull(1)?.toDoubleOrNull() ?: 8.0
    val net = SimulatedNetwork(latency, (mbps * 1024 * 1024).toLong(), zipStartMs = 1500)
    val fast = SimulatedNetwork(0, 0, 0)
    val source = SyntheticRepo()
    println("Repo sintetico: ${source.files.size} archivos, ${"%.1f".format(source.totalBytes / 1048576.0)} MB")
    println("Red simulada: $latency ms por peticion, $mbps MB/s compartidos, 1500 ms hasta el primer byte del zip")
    val root = File(System.getProperty("java.io.tmpdir"), "traelo-bench-${System.nanoTime()}")
    val results = ArrayList<Scenario>()

    fun measure(name: String, api: SlowGitHubApi, block: () -> Unit) {
        net.reset()
        val (_, ms) = timed(block)
        results += Scenario(name, ms, net.requests.get(), net.bytes.get() / 1048576.0)
        println("  $name: $ms ms, ${net.requests.get()} peticiones")
    }

    // 1. Primera vez: carpeta vacia.
    run {
        val api = SlowGitHubApi(net)
        api.commit("main", source.files)
        val dir = File(root, "first")
        measure("primera descarga (3000 archivos)", api) {
            runBlocking { SyncEngine(api, MemoryStore()).pull(repo, "main", dir, null) }
        }
    }

    // Prepara una copia ya traida (sin red lenta) para los demas casos.
    fun prepared(name: String): Triple<SlowGitHubApi, MemoryStore, SyncState> {
        val setupApi = SlowGitHubApi(fast)
        val store = MemoryStore()
        setupApi.commit("main", source.files)
        val state = runBlocking { SyncEngine(setupApi, store).pull(repo, "main", File(root, name), null).newState }
        val api = SlowGitHubApi(net)
        api.commit("main", source.files)
        return Triple(api, store, state)
    }

    // 2. Sin cambios.
    run {
        val (api, store, state) = prepared("same")
        measure("traer sin cambios", api) {
            runBlocking { SyncEngine(api, store).pull(repo, "main", File(state.localDir), state) }
        }
    }

    fun changed(count: Int): Map<String, ByteArray> {
        val random = Random(count)
        val next = LinkedHashMap(source.files)
        val imports = next.keys.filter { it.endsWith(".import") }.take(count * 2 / 3)
        val pngs = next.keys.filter { it.endsWith(".png") }.take(count - imports.size)
        for (path in imports + pngs) next[path] = next.getValue(path) + random.nextBytes(16)
        return next
    }

    for (count in listOf(40, 150, 600)) {
        val (api, store, state) = prepared("change$count")
        api.commit("main", changed(count))
        measure("traer $count cambios", api) {
            runBlocking { SyncEngine(api, store).pull(repo, "main", File(state.localDir), state) }
        }
    }

    // 5. Buscar cambios locales (hash de los 3000 archivos).
    run {
        val (api, store, state) = prepared("local")
        measure("buscar cambios locales", api) {
            runBlocking { SyncEngine(api, store).findLocalChanges(state) }
        }
    }

    root.deleteRecursively()
    println()
    println("| Caso | Tiempo | Peticiones | MB bajados |")
    println("|---|---:|---:|---:|")
    for (r in results) println("| ${r.name} | ${"%.2f".format(r.ms / 1000.0)} s | ${r.requests} | ${"%.1f".format(r.megabytes)} |")
}
