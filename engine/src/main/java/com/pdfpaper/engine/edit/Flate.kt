package com.pdfpaper.engine.edit

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/** zlib/Flate encoding (§7.4.4) for streams the editor creates. */
internal object Flate {

    fun encode(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        val buffer = ByteArray(16384)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            if (count <= 0) break
            out.write(buffer, 0, count)
        }
        deflater.end()
        return out.toByteArray()
    }
}
