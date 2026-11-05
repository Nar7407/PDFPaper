package com.pdfpaper.engine.edit

import com.pdfpaper.engine.image.PdfImages
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

enum class ImageFormat { JPEG, PNG }

/** A decoded raster in 32-bit ARGB, row-major with row 0 at the top. */
class Raster(val width: Int, val height: Int, val argb: IntArray)

/**
 * Host hook for decoding raster image bytes the pure-JVM engine cannot
 * interpret directly. `:app` can supply `android.graphics.ImageDecoder`;
 * the default uses the JDK ImageIO codec (PNG and friends).
 */
interface RasterDecoder {
    fun decode(bytes: ByteArray): Raster?
}

internal object ImageIoDecoder : RasterDecoder {
    override fun decode(bytes: ByteArray): Raster? = runCatching {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        if (image.width <= 0 || image.height <= 0) return null
        val argb = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
        Raster(image.width, image.height, argb)
    }.getOrNull()
}

/**
 * An image the editor will place on a page (§8.9.5).
 *
 * A JPEG keeps its compressed bytes and is embedded verbatim as /DCTDecode; a
 * PNG is decoded to 32-bit ARGB and embedded as Flate-compressed samples (with
 * an /SMask when it has transparency). [width]/[height] are the sample
 * dimensions, independent of the placement size.
 */
class ImageData internal constructor(
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    /** ARGB for decoded formats, null for JPEG. */
    val argb: IntArray?,
    /** Original bytes for JPEG, null for decoded formats. */
    val encoded: ByteArray?
)

/** Builds [ImageData] from JPEG or PNG bytes (task T17, image editing). */
object EditImages {

    /** The JDK ImageIO decoder used when no host decoder is supplied. */
    fun defaultDecoder(): RasterDecoder = ImageIoDecoder

    /** Sniffs the format and decodes accordingly; failure is a [Result], never a throw. */
    fun decode(bytes: ByteArray, decoder: RasterDecoder = ImageIoDecoder): Result<ImageData> = runCatching {
        require(bytes.size >= 8) { "image data is too short" }
        when {
            isJpeg(bytes) -> jpeg(bytes)
            isPng(bytes) -> png(bytes, decoder)
            else -> error("unsupported image format (expected JPEG or PNG)")
        }
    }

    private fun jpeg(bytes: ByteArray): ImageData {
        val info = PdfImages.jpegInfo(bytes) ?: error("JPEG header not recognised")
        val width = info[0]
        val height = info[1]
        require(width > 0 && height > 0) { "JPEG has non-positive dimensions" }
        return ImageData(ImageFormat.JPEG, width, height, argb = null, encoded = bytes)
    }

    private fun png(bytes: ByteArray, decoder: RasterDecoder): ImageData {
        val raster = decoder.decode(bytes) ?: error("PNG could not be decoded")
        return ImageData(ImageFormat.PNG, raster.width, raster.height, argb = raster.argb, encoded = null)
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8

    private fun isPng(bytes: ByteArray): Boolean =
        (bytes[0].toInt() and 0xFF) == 0x89 &&
            bytes[1].toInt() and 0xFF == 0x50 &&
            bytes[2].toInt() and 0xFF == 0x4E &&
            bytes[3].toInt() and 0xFF == 0x47
}
