package com.pdfpaper.engine.io

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Random-access byte source for PDF input (ISO 32000-1, §7.5 file structure).
 *
 * Implementations must not load the whole file into memory unless the data is
 * already in memory ([ByteArraySource]), so large files stay usable.
 */
interface RandomAccessSource : Closeable {
    val size: Long

    @Throws(IOException::class)
    fun read(offset: Long, length: Int): ByteArray
}

/** In-memory source, used by tests and for already-loaded data. */
class ByteArraySource(private val data: ByteArray) : RandomAccessSource {
    override val size: Long get() = data.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "Negative offset: $offset" }
        require(length >= 0) { "Negative length: $length" }
        if (offset > data.size) throw IOException("Read past end: offset=$offset size=${data.size}")
        val end = minOf(offset + length, data.size.toLong()).toInt()
        return data.copyOfRange(offset.toInt(), end)
    }

    override fun close() {
        // Nothing to release.
    }
}

/** File-backed source using [RandomAccessFile]; reads on demand, never fully loaded. */
class FileSource(file: File) : RandomAccessSource {
    private val raf = RandomAccessFile(file, "r")

    override val size: Long get() = raf.length()

    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "Negative offset: $offset" }
        require(length >= 0) { "Negative length: $length" }
        synchronized(raf) {
            if (offset > raf.length()) throw IOException("Read past end: offset=$offset size=${raf.length()}")
            raf.seek(offset)
            val toRead = minOf(length.toLong(), raf.length() - offset).toInt()
            val buf = ByteArray(toRead)
            var got = 0
            while (got < toRead) {
                val n = raf.read(buf, got, toRead - got)
                if (n < 0) break
                got += n
            }
            return if (got == toRead) buf else buf.copyOf(got)
        }
    }

    override fun close() = raf.close()
}
