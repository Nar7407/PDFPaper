package com.pdfpaper.app.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.pdfpaper.engine.image.ExternalImageDecoder

/**
 * Platform codec hook for images the pure-Kotlin engine cannot decode
 * (DCTDecode/JPEG and JPXDecode/JPEG 2000, §8.9.5.4). The engine calls this
 * while building the display list, so images arrive at the renderer already
 * decoded to RGBA.
 */
object AndroidImageDecoder : ExternalImageDecoder {

    override fun decode(format: String, data: ByteArray, width: Int, height: Int): ByteArray? {
        val bitmap = try {
            BitmapFactory.decodeByteArray(data, 0, data.size)
        } catch (t: Throwable) {
            null
        } ?: return null

        return try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return null
            val argb = IntArray(w * h)
            bitmap.getPixels(argb, 0, w, 0, 0, w, h)
            val out = ByteArray(w * h * 4)
            for (i in argb.indices) {
                val c = argb[i]
                val o = i * 4
                out[o] = ((c shr 16) and 0xFF).toByte()      // R
                out[o + 1] = ((c shr 8) and 0xFF).toByte()   // G
                out[o + 2] = (c and 0xFF).toByte()           // B
                out[o + 3] = ((c ushr 24) and 0xFF).toByte() // A
            }
            out
        } finally {
            bitmap.recycle()
        }
    }
}
