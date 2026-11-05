package com.pdfpaper.engine.write

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * zlib/Flate encoding (ISO 32000-1, §7.4.4) with a selectable level.
 *
 * The editor uses [com.pdfpaper.engine.edit.Flate] for the streams it creates;
 * the compressor needs level 9 for the streams it rewrites, and it needs the
 * same helper in the write package for the cross-reference stream.
 */
internal object Deflate {

    /** Encodes [data] as a zlib stream at the given level (0..9). */
    fun encode(data: ByteArray, level: Int = Deflater.BEST_COMPRESSION): ByteArray {
        val deflater = Deflater(level.coerceIn(Deflater.DEFAULT_COMPRESSION, Deflater.BEST_COMPRESSION))
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        val buffer = ByteArray(16 * 1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            if (count <= 0) break
            out.write(buffer, 0, count)
        }
        deflater.end()
        return out.toByteArray()
    }
}
