package dev.rubenmoya.traelo.core

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Huella que git le pone a un archivo: sha1 de "blob <tamano>\0" seguido del contenido. */
object GitBlobHash {
    private const val BUFFER = 256 * 1024

    private val buffers = ThreadLocal.withInitial { ByteArray(BUFFER) }

    fun ofBytes(data: ByteArray): String {
        val digest = start(data.size.toLong())
        digest.update(data)
        return hex(digest.digest())
    }

    fun ofFile(file: File): String {
        val digest = start(file.length())
        val buffer = buffers.get()
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    internal fun start(size: Long): MessageDigest {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob $size\u0000".toByteArray(Charsets.US_ASCII))
        return digest
    }

    private val HEX = "0123456789abcdef".toCharArray()

    internal fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }
}
