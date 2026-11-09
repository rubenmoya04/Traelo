package dev.rubenmoya.traelo.core

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Huella que git le pone a un archivo: sha1 de "blob <tamano>\0" seguido del contenido. */
object GitBlobHash {
    fun ofBytes(data: ByteArray): String {
        val digest = start(data.size.toLong())
        digest.update(data)
        return hex(digest.digest())
    }

    fun ofFile(file: File): String {
        val digest = start(file.length())
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun start(size: Long): MessageDigest {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob $size\u0000".toByteArray(Charsets.US_ASCII))
        return digest
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
