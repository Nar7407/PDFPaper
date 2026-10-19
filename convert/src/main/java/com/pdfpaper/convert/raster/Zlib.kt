package com.pdfpaper.convert.raster

import java.util.zip.Deflater
import java.util.zip.Inflater
import java.io.ByteArrayOutputStream

/**
 * zlib (RFC 1950) helpers over the JDK's `Deflater`/`Inflater`. PNG puts them
 * in IDAT chunks; PDF stream filters use the same wrapper (`/FlateDecode`).
 */
internal object Zlib {

    fun encode(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, false) // zlib wrapper
        try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(raw.size / 3 + 64)
            val buffer = ByteArray(1 shl 16)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                if (n > 0) out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** Decodes a zlib stream, or null when it is truncated or corrupt. */
    fun decode(compressed: ByteArray, expectedSize: Int): ByteArray? {
        val inflater = Inflater(false)
        try {
            inflater.setInput(compressed)
            val out = ByteArrayOutputStream(expectedSize.coerceAtLeast(64))
            val buffer = ByteArray(1 shl 16)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) return null
                } else {
                    out.write(buffer, 0, n)
                }
                if (out.size() > expectedSize.coerceAtLeast(64) * 64L) return null // zip bomb
            }
            return out.toByteArray()
        } catch (e: Exception) {
            return null
        } finally {
            inflater.end()
        }
    }
}
