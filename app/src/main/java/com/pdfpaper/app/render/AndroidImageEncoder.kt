package com.pdfpaper.app.render

import android.graphics.Bitmap
import com.pdfpaper.engine.image.EncodedImage
import com.pdfpaper.engine.image.ImageEncoder
import java.io.ByteArrayOutputStream

/**
 * [ImageEncoder] backed by Android's JPEG codec.
 *
 * The engine asks for a JPEG of the given size; this builds a bitmap from the
 * RGBA samples and lets the platform write the file. Alpha is flattened onto
 * white, because JPEG cannot carry transparency.
 *
 * Android's encoder always writes a three-channel JPEG, so a grey request
 * produces a grey *picture* in a colour file: the flag in the result stays
 * false, which keeps the image dictionary correct (/DeviceRGB).
 */
object AndroidImageEncoder : ImageEncoder {

    override fun encodeJpeg(
        width: Int,
        height: Int,
        rgba: ByteArray,
        quality: Int,
        grayscale: Boolean
    ): EncodedImage? {
        if (width <= 0 || height <= 0) return null
        if (rgba.size < width * height * 4) return null
        return try {
            val pixels = IntArray(width * height)
            var i = 0
            while (i < pixels.size) {
                val o = i * 4
                val r = rgba[o].toInt() and 0xFF
                val g = rgba[o + 1].toInt() and 0xFF
                val b = rgba[o + 2].toInt() and 0xFF
                val a = rgba[o + 3].toInt() and 0xFF
                // Flatten onto white: JPEG has no alpha channel.
                val blend = if (a >= 255) {
                    0
                } else {
                    (255 - a)
                }
                val rr = (r * a + 255 * blend) / 255
                val gg = (g * a + 255 * blend) / 255
                val bb = (b * a + 255 * blend) / 255
                pixels[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                i++
            }
            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            try {
                val out = ByteArrayOutputStream()
                val level = quality.coerceIn(1, 100)
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, level, out)) return null
                val bytes = out.toByteArray()
                if (bytes.isEmpty()) null else EncodedImage(bytes, width, height, false)
            } finally {
                bitmap.recycle()
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Encodes [rgba] as lossy WebP for page export. Android has had the codec
     * since API 14; `WEBP` is the lossy form. Alpha is flattened onto white
     * like the JPEG path (an exported page is opaque anyway).
     */
    override fun encodeWebp(
        width: Int,
        height: Int,
        rgba: ByteArray,
        quality: Int
    ): EncodedImage? {
        if (width <= 0 || height <= 0) return null
        if (rgba.size < width * height * 4) return null
        return try {
            val pixels = IntArray(width * height)
            var i = 0
            while (i < pixels.size) {
                val o = i * 4
                val a = rgba[o + 3].toInt() and 0xFF
                val r = (rgba[o].toInt() and 0xFF) * a + 255 * (255 - a)
                val g = (rgba[o + 1].toInt() and 0xFF) * a + 255 * (255 - a)
                val b = (rgba[o + 2].toInt() and 0xFF) * a + 255 * (255 - a)
                pixels[i] = (0xFF shl 24) or ((r / 255) shl 16) or ((g / 255) shl 8) or (b / 255)
                i++
            }
            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            try {
                val out = ByteArrayOutputStream()
                @Suppress("DEPRECATION")
                val ok = bitmap.compress(Bitmap.CompressFormat.WEBP, quality.coerceIn(0, 100), out)
                if (!ok) return null
                val bytes = out.toByteArray()
                if (bytes.isEmpty()) null else EncodedImage(bytes, width, height, false)
            } finally {
                bitmap.recycle()
            }
        } catch (t: Throwable) {
            null
        }
    }
}
