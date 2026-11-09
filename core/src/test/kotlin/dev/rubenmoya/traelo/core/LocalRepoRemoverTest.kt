package dev.rubenmoya.traelo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class LocalRepoRemoverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Una "memoria del movil" con suficiente profundidad: tmp/a/b/Traelo y dentro los repos. */
    private fun base(): File = File(tmp.root, "a/b/Documents/Traelo").also { it.mkdirs() }

    private fun repoDir(base: File, name: String = "juego"): File {
        val dir = File(base, name)
        File(dir, "scripts/sub").mkdirs()
        File(dir, ".godot").mkdirs()
        File(dir, "scripts/a.gd").writeText("a")
        File(dir, "scripts/sub/b.gd").writeText("b")
        File(dir, ".godot/cache.bin").writeText("c")
        File(dir, "mio.txt").writeText("hecho en el movil")
        return dir
    }

    @Test
    fun removesTheWholeFolderIncludingUserFilesAndGodotCache() {
        val base = base()
        val dir = repoDir(base)
        val other = repoDir(base, "otro")
        val result = LocalRepoRemover.remove(dir, base)
        assertEquals(RemoveResult.Removed(4), result)
        assertFalse(dir.exists())
        assertTrue(File(other, "scripts/a.gd").exists())
        assertTrue(base.exists())
    }

    @Test
    fun missingFolderIsNotAnError() {
        val base = base()
        assertEquals(RemoveResult.NotThere, LocalRepoRemover.remove(File(base, "nada"), base))
    }

    @Test
    fun refusesTheBaseFolderAndAnythingAboveIt() {
        val base = base()
        repoDir(base)
        assertEquals(RemoveResult.Refused, LocalRepoRemover.remove(base, base))
        assertEquals(RemoveResult.Refused, LocalRepoRemover.remove(base.parentFile, base))
        assertEquals(RemoveResult.Refused, LocalRepoRemover.remove(File("/"), base))
        assertTrue(File(base, "juego/scripts/a.gd").exists())
    }

    @Test
    fun refusesShallowPaths() {
        val base = base()
        assertEquals(RemoveResult.Refused, LocalRepoRemover.remove(File("/storage/emulated/0"), base))
        assertEquals(RemoveResult.Refused, LocalRepoRemover.remove(File("/storage/emulated/0/Documents"), base))
    }

    @Test
    fun neverFollowsSymlinksOutOfTheFolder() {
        val base = base()
        val dir = repoDir(base)
        val outside = File(tmp.root, "fuera").also { it.mkdirs() }
        File(outside, "importante.txt").writeText("no tocar")
        Files.createSymbolicLink(File(dir, "enlace").toPath(), outside.toPath())
        LocalRepoRemover.remove(dir, base)
        assertFalse(dir.exists())
        assertTrue(File(outside, "importante.txt").exists())
    }

    @Test
    fun aSymlinkAsTheFolderOnlyLosesTheLink() {
        val base = base()
        val real = File(tmp.root, "real").also { File(it, "x").mkdirs(); File(it, "x/f.txt").writeText("f") }
        val link = File(base, "enlazado")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        assertEquals(RemoveResult.Removed(0), LocalRepoRemover.remove(link, base))
        assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(File(real, "x/f.txt").exists())
    }
}
