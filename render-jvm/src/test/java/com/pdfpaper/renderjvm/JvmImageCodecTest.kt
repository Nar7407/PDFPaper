package com.pdfpaper.renderjvm

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host codec the engine's compressor needs: a JPEG writer and a JPEG
 * reader built on `javax.imageio`.
 */
class JvmImageCodecTest {

    @Test
    fun encodesAndDecodesAGradientWithinTolerance() {
        val width = 64
        val height = 48
        val rgba = gradient(width, height)

        val encoded = JvmImageEncoder.encodeJpeg(width, height, rgba, 90, false)
        assertNotNull("the encoder returned nothing", encoded)
        assertEquals(width, encoded!!.width)
        assertEquals(height, encoded.height)
        assertTrue("the encoder should not claim a grey image", !encoded.grayscale)
        assertTrue("the encoder wrote no bytes", encoded.bytes.isNotEmpty())

        val decoded = ImageIO.read(ByteArrayInputStream(encoded.bytes))
        assertNotNull("the JPEG could not be read back", decoded)
        assertEquals(width, decoded!!.width)
        assertEquals(height, decoded.height)

        val difference = meanAbsoluteDifference(rgba, decoded)
        assertTrue("mean absolute difference was $difference", difference < 12.0)
    }

    @Test
    fun encodesAGreyImageWithASingleBand() {
        val width = 32
        val height = 32
        val rgba = gradient(width, height)

        val encoded = JvmImageEncoder.encodeJpeg(width, height, rgba, 85, true)
        assertNotNull("the encoder returned nothing", encoded)
        assertTrue("the encoder should claim a grey image", encoded!!.grayscale)

        val decoded = ImageIO.read(ByteArrayInputStream(encoded.bytes))
        assertNotNull("the JPEG could not be read back", decoded)
        assertEquals("a grey JPEG must have one band", 1, decoded!!.raster.numBands)
        assertEquals("a grey JPEG must have one colour component", 1, decoded.colorModel.numComponents)
    }

    @Test
    fun decodesAJpegIntoRgba() {
        val width = 16
        val height = 16
        val rgba = gradient(width, height)
        val encoded = JvmImageEncoder.encodeJpeg(width, height, rgba, 90, false)
        assertNotNull(encoded)

        val pixels = JvmImageDecoder.decode("DCT", encoded!!.bytes, width, height)
        assertNotNull("the decoder returned nothing", pixels)
        assertEquals(width * height * 4, pixels!!.size)
        val first = ((pixels[0].toInt() and 0xFF) + (pixels[1].toInt() and 0xFF) +
            (pixels[2].toInt() and 0xFF)) / 3
        val last = ((pixels[pixels.size - 4].toInt() and 0xFF) +
            (pixels[pixels.size - 3].toInt() and 0xFF) +
            (pixels[pixels.size - 2].toInt() and 0xFF)) / 3
        assertTrue("the gradient should run from dark to light, got $first and $last", last > first)
    }

    @Test
    fun refusesUnsupportedFormatsAndBrokenData() {
        assertNull(JvmImageDecoder.decode("JPX", ByteArray(8), 2, 2))
        assertNull(JvmImageDecoder.decode("DCT", byteArrayOf(1, 2, 3, 4), 2, 2))
        assertNull(JvmImageEncoder.encodeJpeg(0, 0, ByteArray(0), 80, false))
        assertNull(JvmImageEncoder.encodeJpeg(4, 4, ByteArray(4), 80, false))
    }

    /** A diagonal red/green/blue gradient with an opaque alpha channel. */
    private fun gradient(width: Int, height: Int): ByteArray {
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = (y * width + x) * 4
                rgba[offset] = (x * 255 / (width - 1)).toByte()
                rgba[offset + 1] = (y * 255 / (height - 1)).toByte()
                rgba[offset + 2] = ((x + y) * 255 / (width + height - 2)).toByte()
                rgba[offset + 3] = 255.toByte()
            }
        }
        return rgba
    }

    /** The mean absolute difference per channel between RGBA and a picture. */
    private fun meanAbsoluteDifference(rgba: ByteArray, image: BufferedImage): Double {
        var total = 0L
        var count = 0L
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                val offset = (y * image.width + x) * 4
                total += kotlin.math.abs((pixel ushr 16 and 0xFF) - (rgba[offset].toInt() and 0xFF))
                total += kotlin.math.abs((pixel ushr 8 and 0xFF) - (rgba[offset + 1].toInt() and 0xFF))
                total += kotlin.math.abs((pixel and 0xFF) - (rgba[offset + 2].toInt() and 0xFF))
                count += 3
            }
        }
        return if (count == 0L) 0.0 else total.toDouble() / count
    }
}
