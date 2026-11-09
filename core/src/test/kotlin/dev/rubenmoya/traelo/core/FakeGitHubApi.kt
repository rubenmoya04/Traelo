package dev.rubenmoya.traelo.core

import java.io.File
import java.io.IOException
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

    override fun tree(repo: RepoRef, sha: String): TreeResult {
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
        if (failBlobsAfter in 0 until number) throw IOException("fallo simulado")
        val data = snapshots.values.flatMap { it.values }.firstOrNull { GitBlobHash.ofBytes(it) == sha }
            ?: throw GitHubException.NotFound("el blob $sha")
        out.write(if (corruptBlobs) data + byteArrayOf(1) else data)
    }

    override fun downloadZip(repo: RepoRef, ref: String, dest: File) {
        zipDownloads++
        val files = snapshots.getValue(ref)
        ZipOutputStream(dest.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("owner-repo-abc1234/"))
            zip.closeEntry()
            for ((path, data) in files) put(zip, "owner-repo-abc1234/$path", zipOverrides[path] ?: data)
            for ((name, data) in extraZipEntries) put(zip, name, data)
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
    val data = HashMap<String, Map<String, String>>()

    override fun load(repo: RepoRef): Map<String, String> = data[repo.key] ?: emptyMap()

    override fun save(repo: RepoRef, files: Map<String, String>) {
        data[repo.key] = HashMap(files)
    }

    override fun clear(repo: RepoRef) {
        data.remove(repo.key)
    }
}
