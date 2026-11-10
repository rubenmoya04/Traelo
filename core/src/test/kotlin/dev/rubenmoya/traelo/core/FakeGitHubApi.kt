package dev.rubenmoya.traelo.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** GitHub de mentira en memoria: ramas, commits, arbol, blobs y zip. */
class FakeGitHubApi : GitHubApi {
    private val snapshots = HashMap<String, Map<String, ByteArray>>() // commit -> archivos
    private val heads = LinkedHashMap<String, String>() // rama -> commit
    private var counter = 0

    // Contadores y palancas de fallo
    val blobDownloads = ArrayList<String>()
    var zipDownloads = 0
    var corruptBlobs = false
    var failBlobsAfter = -1
    var truncatedTree = false
    var compareReturnsNull = false
    var extraTreeEntries: List<TreeEntry> = emptyList()
    var extraSkipped: List<String> = emptyList()
    var extraZipEntries: Map<String, ByteArray> = emptyMap()
    var zipOverrides: Map<String, ByteArray> = emptyMap()

    /** Se llama antes de servir cada blob (para bloquear o fallar a mitad de una descarga). */
    @Volatile var beforeBlob: (sha: String) -> Unit = {}

    fun commit(branch: String, files: Map<String, String>): String {
        val sha = "c%03d".format(++counter) + "0".repeat(36)
        snapshots[sha] = files.mapValues { it.value.toByteArray() }
        heads[branch] = sha
        return sha
    }

    fun head(branch: String): String = heads.getValue(branch)

    override fun currentUser() = "tester"

    override fun listRepos() = emptyList<RemoteRepo>()

    override fun listBranches(repo: RepoRef) = heads.map { Branch(it.key, it.value) }

    override fun branchHead(repo: RepoRef, branch: String): String =
        heads[branch] ?: throw GitHubException.NotFound("la rama $branch")

    var treeCalls = 0

    /** Fallos de red de mentira: los primeros N blobs fallan una vez y luego van bien. */
    var flakyBlobs = 0

    override fun tree(repo: RepoRef, sha: String): TreeResult {
        treeCalls++
        val files = snapshots.getValue(sha)
        val entries = files.map { (path, data) -> TreeEntry(path, GitBlobHash.ofBytes(data), data.size.toLong()) }
        return TreeResult(entries.sortedBy { it.path } + extraTreeEntries, extraSkipped, truncatedTree)
    }

    @Synchronized
    private fun registerDownload(sha: String): Int {
        blobDownloads += sha
        return blobDownloads.size
    }

    override fun downloadBlob(repo: RepoRef, sha: String, out: OutputStream) {
        val number = registerDownload(sha)
        beforeBlob(sha)
        synchronized(this) {
            if (flakyBlobs > 0) {
                flakyBlobs--
                throw GitHubException.Network(IOException("corte de red"))
            }
        }
        if (failBlobsAfter in 0 until number) throw IOException("fallo simulado")
        val data = snapshots.values.flatMap { it.values }.firstOrNull { GitBlobHash.ofBytes(it) == sha }
            ?: throw GitHubException.NotFound("el blob $sha")
        out.write(if (corruptBlobs) data + byteArrayOf(1) else data)
    }

    /** Corta el zip (como un fallo de red) despues de servir estos bytes. -1 para no cortarlo. */
    var zipBreaksAfterBytes = -1

    override fun <T> openZip(repo: RepoRef, ref: String, read: (InputStream) -> T): T {
        zipDownloads++
        val files = snapshots.getValue(ref)
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("owner-repo-abc1234/"))
            zip.closeEntry()
            for ((path, data) in files) put(zip, "owner-repo-abc1234/$path", zipOverrides[path] ?: data)
            for ((name, data) in extraZipEntries) put(zip, name, data)
        }
        val bytes = buffer.toByteArray()
        val limit = zipBreaksAfterBytes
        val input: InputStream = if (limit < 0) ByteArrayInputStream(bytes) else BreakingStream(bytes, limit)
        return input.use(read)
    }

    private class BreakingStream(bytes: ByteArray, private val limit: Int) : FilterInputStream(ByteArrayInputStream(bytes)) {
        private var served = 0

        override fun read(): Int {
            if (served >= limit) throw GitHubException.Network(IOException("zip cortado"))
            served++
            return super.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= limit) throw GitHubException.Network(IOException("zip cortado"))
            val n = super.read(b, off, minOf(len, limit - served))
            if (n > 0) served += n
            return n
        }
    }

    private fun put(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }

    override fun commitsBetween(repo: RepoRef, base: String, head: String): List<CommitInfo>? =
        if (compareReturnsNull) null else listOf(CommitInfo(head, "cambio de prueba", "tester", "2026-11-09T10:00:00Z"))
}

/** Manifiestos en memoria. */
class MemoryManifests : ManifestStore {
    val data = HashMap<String, Manifest>()

    override fun load(repo: RepoRef): Manifest = data[repo.key] ?: Manifest.EMPTY

    override fun save(repo: RepoRef, manifest: Manifest) {
        data[repo.key] = manifest.copy(files = HashMap(manifest.files))
    }

    override fun clear(repo: RepoRef) {
        data.remove(repo.key)
    }

    fun files(repo: RepoRef): Map<String, String> = load(repo).files
}
