package com.pdfpaper.renderjvm

import com.pdfpaper.engine.image.EncodedImage
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.image.ImageEncoder
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.awt.image.DataBufferByte
import java.awt.image.Raster
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * The JVM host side of the PDF compressor: a JPEG encoder and a JPEG decoder
 * built on the JDK's own codecs (`javax.imageio`). No third-party dependency.
 *
 * The engine is platform-free: it can read and rewrite a PDF, but it cannot
 * write a JPEG and it cannot run a JPEG decoder either. Those two hooks
 * ([ImageEncoder], [ExternalImageDecoder]) are what this module fills in, so
 * `:engine`'s compressor can re-encode the images of a document.
 */

/**
 * Encodes 32-bit RGBA pixels as JPEG.
 *
 * The image is written with a [ComponentColorModel]: a single grey band for a
 * grey JPEG (`/DeviceGray` in the PDF) and three sRGB bands for a colour one
 * (`/DeviceRGB`). Both are never thrown at the caller: a failure returns null
 * and the engine keeps the original image bytes instead of losing the picture.
 */
object JvmImageEncoder : ImageEncoder {

    override fun encodeJpeg(
        width: Int,
        height: Int,
        rgba: ByteArray,
        quality: Int,
        grayscale: Boolean
    ): EncodedImage? = try {
        encode(width, height, rgba, quality, grayscale)
    } catch (t: Throwable) {
        null
    }

    private fun encode(
        width: Int,
        height: Int,
        rgba: ByteArray,
        quality: Int,
        grayscale: Boolean
    ): EncodedImage? {
        if (width <= 0 || height <= 0) return null
        if (rgba.size < width * height * 4) return null
        val image = buildImage(width, height, rgba, grayscale) ?: return null
        val bytes = writeJpeg(image, quality) ?: return null
        if (bytes.isEmpty()) return null
        return EncodedImage(bytes, width, height, grayscale)
    }

    /**
     * Builds the image the writer gets: an interleaved byte raster with one
     * grey band or three sRGB bands.
     */
    private fun buildImage(
        width: Int,
        height: Int,
        rgba: ByteArray,
        grayscale: Boolean
    ): BufferedImage? {
        val bands = if (grayscale) 1 else 3
        val space = if (grayscale) {
            ColorSpace.getInstance(ColorSpace.CS_GRAY)
        } else {
            ColorSpace.getInstance(ColorSpace.CS_sRGB)
        }
        val model = ComponentColorModel(
            space,
            IntArray(bands) { 8 },
            false,
            false,
            Transparency.OPAQUE,
            DataBuffer.TYPE_BYTE
        )
        val raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, width, height, bands, null)
        val data = (raster.dataBuffer as? DataBufferByte)?.data ?: return null
        var index = 0
        var pixel = 0
        val pixelStride = bands
        while (index + 3 < rgba.size && pixel < width * height) {
            val r = rgba[index].toInt() and 0xFF
            val g = rgba[index + 1].toInt() and 0xFF
            val b = rgba[index + 2].toInt() and 0xFF
            val target = pixel * pixelStride
            if (grayscale) {
                // ITU-R BT.601 luma, the same conversion the engine uses.
                data[target] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
            } else {
                data[target] = r.toByte()
                data[target + 1] = g.toByte()
                data[target + 2] = b.toByte()
            }
            index += 4
            pixel++
        }
        return BufferedImage(model, raster, false, null)
    }

    /** Writes the image as JPEG at the given quality (1..100). */
    private fun writeJpeg(image: BufferedImage, quality: Int): ByteArray? {
        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        if (!writers.hasNext()) return null
        val writer = writers.next()
        return try {
            val params = writer.defaultWriteParam
            if (params.canWriteCompressed()) {
                params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                params.compressionQuality = quality.coerceIn(1, 100) / 100f
            }
            val out = ByteArrayOutputStream()
            MemoryCacheImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(image, null, null), params)
                stream.flush()
            }
            out.toByteArray()
        } finally {
            writer.dispose()
        }
    }
}

/**
 * Decodes the JPEG images the engine cannot read on its own (a `/DCTDecode`
 * stream, ISO 32000-1 §8.9.5.4) into 32-bit RGBA.
 *
 * JPEG 2000 (`/JPXDecode`) and every other codec are not supported by the
 * JDK's standard ImageIO readers, so they return null and the engine keeps
 * the encoded bytes.
 */
object JvmImageDecoder : ExternalImageDecoder {

    override fun decode(format: String, data: ByteArray, width: Int, height: Int): ByteArray? = try {
        if (format == "DCT") decodeJpeg(data) else null
    } catch (t: Throwable) {
        null
    }

    private fun decodeJpeg(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val image = ImageIO.read(ByteArrayInputStream(data)) ?: return null
        if (image.width <= 0 || image.height <= 0) return null
        val argb = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
        val rgba = ByteArray(argb.size * 4)
        for (i in argb.indices) {
            val pixel = argb[i]
            rgba[i * 4] = (pixel ushr 16 and 0xFF).toByte()
            rgba[i * 4 + 1] = (pixel ushr 8 and 0xFF).toByte()
            rgba[i * 4 + 2] = (pixel and 0xFF).toByte()
            rgba[i * 4 + 3] = (pixel ushr 24 and 0xFF).toByte()
        }
        return rgba
    }
}
