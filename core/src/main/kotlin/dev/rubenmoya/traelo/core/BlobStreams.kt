package dev.rubenmoya.traelo.core

import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Calcula la huella git de lo que pasa por aqui mientras se escribe, para no tener que volver a
 * leer el archivo del disco. [expectedSize] es el tamano que da el arbol: va en la cabecera.
 * [beforeWrite] se llama antes de cada trozo (sirve para cortar si se cancela) y [onBytes]
 * despues (sirve para el progreso).
 */
class BlobHashingOutputStream(
    out: OutputStream,
    private val expectedSize: Long,
    private val beforeWrite: () -> Unit = {},
    private val onBytes: (Int) -> Unit = {},
) : FilterOutputStream(out) {
    private val digest: MessageDigest = GitBlobHash.start(expectedSize)

    var count: Long = 0
        private set

    override fun write(b: Int) {
        beforeWrite()
        out.write(b)
        digest.update(b.toByte())
        count++
        onBytes(1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        beforeWrite()
        out.write(b, off, len)
        digest.update(b, off, len)
        count += len
        onBytes(len)
    }

    /** true si llego justo lo esperado: mismo tamano y misma huella. */
    fun matches(expectedSha: String): Boolean = count == expectedSize && GitBlobHash.hex(digest.digest()) == expectedSha
}

/**
 * Lee por delante en otro hilo (hasta [maxChunks] trozos de [chunkSize]) para que la red no se
 * pare mientras se escriben archivos en el disco. Los errores de lectura llegan a quien lee.
 */
class ReadAheadInputStream(
    private val source: java.io.InputStream,
    private val chunkSize: Int = 256 * 1024,
    maxChunks: Int = 16,
) : java.io.InputStream() {
    private class Chunk(val data: ByteArray, val size: Int, val error: Throwable? = null)

    private val queue = java.util.concurrent.ArrayBlockingQueue<Chunk>(maxChunks)

    @Volatile private var closed = false
    private var current: Chunk? = null
    private var position = 0
    private var finished = false

    private val producer = Thread({ produce() }, "traelo-read-ahead").apply {
        isDaemon = true
        start()
    }

    private fun produce() {
        try {
            while (!closed) {
                val buffer = ByteArray(chunkSize)
                var filled = 0
                var eof = false
                while (filled < chunkSize) {
                    val n = source.read(buffer, filled, chunkSize - filled)
                    if (n < 0) {
                        eof = true
                        break
                    }
                    filled += n
                }
                if (filled == 0) {
                    offer(Chunk(buffer, -1))
                    return
                }
                offer(Chunk(buffer, filled))
                if (eof) {
                    offer(Chunk(buffer, -1))
                    return
                }
            }
        } catch (e: Throwable) {
            if (!closed) offer(Chunk(ByteArray(0), -1, e))
        }
    }

    private fun offer(chunk: Chunk) {
        while (!closed) {
            if (queue.offer(chunk, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) return
        }
    }

    private fun next(): Chunk? {
        if (finished) return null
        val chunk = current?.takeIf { position < it.size } ?: queue.take().also {
            current = it
            position = 0
        }
        chunk.error?.let { throw it }
        if (chunk.size < 0) {
            finished = true
            return null
        }
        return chunk
    }

    override fun read(): Int {
        val chunk = next() ?: return -1
        return chunk.data[position++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val chunk = next() ?: return -1
        val n = minOf(len, chunk.size - position)
        System.arraycopy(chunk.data, position, b, off, n)
        position += n
        return n
    }

    override fun close() {
        if (closed) return
        closed = true
        queue.clear()
        try {
            source.close()
        } catch (e: java.io.IOException) {
            // Nada que hacer.
        }
        producer.interrupt()
    }
}
