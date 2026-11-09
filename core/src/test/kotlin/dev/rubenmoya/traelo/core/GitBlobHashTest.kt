package dev.rubenmoya.traelo.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class GitBlobHashTest {
    @get:Rule val tmp = TemporaryFolder()

    // Valores sacados de `git hash-object` real.
    @Test fun emptyFile() = assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", GitBlobHash.ofBytes(ByteArray(0)))

    @Test fun hello() = assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitBlobHash.ofBytes("hello\n".toByteArray()))

    @Test fun testContent() =
        assertEquals("d670460b4b4aece5915caf5c68d12f560a9fe3e4", GitBlobHash.ofBytes("test content\n".toByteArray()))

    @Test fun unicodeBytes() =
        assertEquals("bae099ec1dddab0373d2f356c623a029bc330038", GitBlobHash.ofBytes("ñandú ✓\n".toByteArray(Charsets.UTF_8)))

    @Test fun fileAndBytesAgreeAlsoOverSeveralBuffers() {
        val data = ByteArray(200_000) { (it * 31).toByte() }
        val file = File(tmp.root, "big.bin").also { it.writeBytes(data) }
        assertEquals(GitBlobHash.ofBytes(data), GitBlobHash.ofFile(file))
    }
}
