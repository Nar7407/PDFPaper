package com.pdfpaper.app.data

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.pdfpaper.engine.io.RandomAccessSource
import java.io.Closeable
import java.io.IOException

/**
 * A seekable engine source over a `ParcelFileDescriptor` (task T47).
 *
 * The descriptor stays owned by this class and is closed with [close]. Reads
 * are serialised because a POSIX descriptor has one file offset; the engine
 * already serialises document access, but a lock keeps this class safe if a
 * future caller does not.
 *
 * A provider can return a pipe even for `openFileDescriptor` (a cloud download
 * or a generated attachment). A pipe has no seekable position; [open] then
 * fails with [NonSeekableException] so the caller can copy through an input
 * stream instead of loading the whole file into memory.
 */
class ParcelFileDescriptorSource internal constructor(
    private val descriptor: ParcelFileDescriptor,
    override val size: Long
) : RandomAccessSource {

    private val lock = Any()

    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "Negative offset: $offset" }
        require(length >= 0) { "Negative length: $length" }
        synchronized(lock) {
            if (offset > size) throw IOException("Read past end: offset=$offset size=$size")
            if (length == 0 || offset == size) return ByteArray(0)
            val wanted = minOf(length.toLong(), size - offset).toInt()
            val buffer = ByteArray(wanted)
            try {
                Os.lseek(descriptor.fileDescriptor, offset, OsConstants.SEEK_SET)
            } catch (error: ErrnoException) {
                throw IOException("This provider cannot seek to $offset", error)
            }
            var total = 0
            while (total < wanted) {
                val count = try {
                    Os.read(descriptor.fileDescriptor, buffer, total, wanted - total)
                } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.EINTR) continue
                    throw IOException("Could not read the document", error)
                }
                if (count <= 0) break
                total += count
            }
            return if (total == buffer.size) buffer else buffer.copyOf(total)
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { descriptor.close() }
        }
    }
}

/** The provider handed out a pipe or another descriptor that cannot seek. */
class NonSeekableException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Opens [descriptor] for random access and verifies that it is seekable.
 * [statSize] is authoritative when it is non-negative; otherwise the end
 * offset is probed and the original position restored.
 */
fun openParcelFileDescriptorSource(
    descriptor: ParcelFileDescriptor
): ParcelFileDescriptorSource {
    val fd = descriptor.fileDescriptor
    val current = try {
        Os.lseek(fd, 0, OsConstants.SEEK_CUR)
    } catch (error: ErrnoException) {
        runCatching { descriptor.close() }
        throw NonSeekableException("This provider does not allow seeking", error)
    }
    val reported = descriptor.statSize
    val size = if (reported >= 0) {
        reported
    } else {
        try {
            Os.lseek(fd, 0, OsConstants.SEEK_END)
        } catch (error: ErrnoException) {
            runCatching { descriptor.close() }
            throw NonSeekableException("This provider does not allow seeking", error)
        }
    }
    try {
        Os.lseek(fd, current, OsConstants.SEEK_SET)
    } catch (error: ErrnoException) {
        runCatching { descriptor.close() }
        throw NonSeekableException("This provider does not allow seeking", error)
    }
    if (size < 0) {
        runCatching { descriptor.close() }
        throw NonSeekableException("This provider does not report a document size")
    }
    return ParcelFileDescriptorSource(descriptor, size)
}

/** Typealias-shaped helper so call sites read like the engine sources. */
fun ParcelFileDescriptor.asSeekableSource(): ParcelFileDescriptorSource =
    openParcelFileDescriptorSource(this)

/** Explicit close helper for Java-style try/finally blocks. */
fun closeQuietly(closeable: Closeable?) {
    runCatching { closeable?.close() }
}
