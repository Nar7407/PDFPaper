package com.pdfpaper.convert.media

import com.pdfpaper.convert.raster.PngWriter
import com.pdfpaper.engine.image.RasterImage
import java.util.LinkedHashMap

/**
 * Decoded page images turned into package parts, shared across pages: the same
 * [RasterImage] instance (a repeated form XObject, say) is written once.
 *
 * The same logic serves both converters — [partNamePrefix] is `ppt/media/image`
 * for a presentation and `word/media/image` for a document — so the encoding
 * rules (JPEG passthrough, hand-written PNG, soft-mask merge) live in one place.
 */
class ImageBag(private val partNamePrefix: String) {

    /** Part name → bytes, in insertion order. */
    val parts = LinkedHashMap<String, ByteArray>()

    private val byIdentity = HashMap<Int, String>()
    private var counter = 0

    fun nextNumber(): Int = ++counter

    fun add(partName: String, bytes: ByteArray) {
        parts[partName] = bytes
    }

    /**
     * Part name for [image], encoding it on first use, or null when the image
     * cannot be embedded without a platform decoder (JPX, CCITT, JBIG2, …).
     */
    fun partFor(image: RasterImage): String? {
        val key = System.identityHashCode(image)
        byIdentity[key]?.let { return it }
        val pixels = image.pixels
        val jpeg = image.externalData
        val extension: String
        val bytes: ByteArray
        if (image.externalFormat == "DCT" && jpeg != null && !image.stencil) {
            extension = "jpg"
            bytes = jpeg
        } else if (pixels != null && pixels.size >= image.width * image.height * 4) {
            extension = "png"
            bytes = PngWriter.encodeRgba(image.width, image.height, rgbaWithMask(image, pixels))
        } else {
            return null
        }
        val name = "$partNamePrefix${++counter}.$extension"
        parts[name] = bytes
        byIdentity[key] = name
        return name
    }

    private fun rgbaWithMask(image: RasterImage, pixels: ByteArray): ByteArray {
        val mask = image.softMask ?: return pixels
        val maskPixels = mask.pixels ?: return pixels
        val mw = mask.width
        val mh = mask.height
        if (mw <= 0 || mh <= 0 || maskPixels.size < mw * mh * 4) return pixels
        val out = pixels.copyOf()
        for (y in 0 until image.height) {
            val my = (y.toDouble() / image.height * mh).toInt().coerceIn(0, mh - 1)
            for (x in 0 until image.width) {
                val mx = (x.toDouble() / image.width * mw).toInt().coerceIn(0, mw - 1)
                val mo = (my * mw + mx) * 4
                val value = if (mask.stencil) maskPixels[mo + 3].toInt() and 0xFF
                else maskPixels[mo].toInt() and 0xFF
                val o = (y * image.width + x) * 4
                out[o + 3] = ((out[o + 3].toInt() and 0xFF) * value / 255).toByte()
            }
        }
        return out
    }

    companion object {

        /** Why [image] could not be embedded, for the loss report. */
        fun describe(image: RasterImage): String {
            val format = image.externalFormat ?: "unknown"
            return when {
                image.requiresExternalDecoder -> "$format image needs a platform decoder"
                image.externalData == null && image.pixels == null -> "image has no decodable data"
                else -> "$format image could not be embedded"
            }
        }
    }
}
