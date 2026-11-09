package dev.rubenmoya.traelo.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SafePathsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun root() = File(tmp.root, "proj").also { it.mkdirs() }

    @Test fun normalPathsResolveInsideRoot() {
        val file = SafePaths.resolve(root(), "game/scripts/a.gd")
        assertNotNull(file)
        assertEquals(File(root().canonicalFile, "game/scripts/a.gd"), file)
        assertNotNull(SafePaths.resolve(root(), ".gitignore"))
    }

    @Test fun dangerousPathsAreRejected() {
        val bad = listOf(
            "", "/etc/passwd", "../x", "a/../../x", "a/./b", "a//b", "a/", "a\\b", "..", ".", ".git/config",
            "a\u0000b",
        )
        for (path in bad) assertNull(SafePaths.resolve(root(), path), "debia rechazar '$path'")
    }

    @Test fun symlinkLeavingTheRootIsRejected() {
        val outside = File(tmp.root, "outside").also { it.mkdirs() }
        val dir = root()
        Files.createSymbolicLink(File(dir, "link").toPath(), outside.toPath())
        assertNull(SafePaths.resolve(dir, "link/file.txt"))
    }
}
