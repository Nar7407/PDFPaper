package com.pdfpaper.engine.image

import com.pdfpaper.engine.graphics.buildDisplayList
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.arr
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.test.dictOf
import com.pdfpaper.engine.test.i
import com.pdfpaper.engine.test.n
import com.pdfpaper.engine.test.r
import com.pdfpaper.engine.test.str
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T8 acceptance: Flate/raw image decoding to an RGBA pixel buffer, bit depths
 * 1/2/4/8, Indexed/Separation colour spaces, /Decode remapping, /ImageMask
 * stencils, /Mask and /SMask, and the DCT (JPEG) platform-decoder hook.
 */
class ImageDecodeTest {

    private val doc: PdfDocument by lazy {
        PdfDocument.open(
            ByteArraySource(
                buildTestPdf(
                    listOf(
                        TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                        TestObj(2, 0, "<< /Type /Pages /Kids [] /Count 0 >>")
                    )
                )
            )
        ).getOrThrow()
    }

    // --- small object builders live in com.pdfpaper.engine.test.PdfObjects ---

    private fun decode(dict: com.pdfpaper.engine.model.PdfDict, data: ByteArray, decoder: ExternalImageDecoder? = null) =
        PdfImages.decodeImage(dict, data, null, doc, decoder)

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun grayImage(width: Long, height: Long, bpc: Long = 8) = dictOf(
        "Width" to i(width),
        "Height" to i(height),
        "ColorSpace" to n("DeviceGray"),
        "BitsPerComponent" to i(bpc)
    )

    // ------------------------------------------------------------ raw samples

    @Test
    fun gray8bpcUnfiltered() {
        val result = decode(grayImage(2, 2), byteArrayOf(0, 85, 170.toByte(), 255.toByte()))
        val image = assertNotNull(result.image)
        assertEquals(255, image.pixelAt(1, 1)!![0])
        assertEquals(85, image.pixelAt(1, 0)!![0])
        assertEquals(0, image.pixelAt(0, 0)!![0])
        assertEquals(255, image.pixelAt(0, 0)!![3])
        assertTrue(result.warnings.isEmpty(), "warnings: ${result.warnings}")
    }

    @Test
    fun gray8bpcThroughFlateFilter() {
        val samples = byteArrayOf(0, 85, 170.toByte(), 255.toByte())
        val dict = dictOf(
            "Width" to i(2), "Height" to i(2), "ColorSpace" to n("DeviceGray"),
            "BitsPerComponent" to i(8), "Filter" to n("FlateDecode")
        )
        val image = assertNotNull(decode(dict, deflate(samples)).image)
        assertEquals(170, image.pixelAt(0, 1)!![0])
        assertEquals(255, image.pixelAt(1, 1)!![0])
    }

    @Test
    fun gray1bpcPackedSamples() {
        // 0xAA = 10101010: white, black, white, black, ..., ending black.
        val image = assertNotNull(decode(grayImage(8, 1, 1), byteArrayOf(0xAA.toByte())).image)
        assertEquals(255, image.pixelAt(0, 0)!![0])
        assertEquals(0, image.pixelAt(1, 0)!![0])
        assertEquals(255, image.pixelAt(2, 0)!![0])
        assertEquals(0, image.pixelAt(7, 0)!![0])
    }

    @Test
    fun decodeArrayInvertsGray() {
        val dict = dictOf(
            "Width" to i(1), "Height" to i(1), "ColorSpace" to n("DeviceGray"),
            "BitsPerComponent" to i(8), "Decode" to arr(r(1.0), r(0.0))
        )
        val image = assertNotNull(decode(dict, byteArrayOf(0)).image)
        assertEquals(255, image.pixelAt(0, 0)!![0])
    }

    // --------------------------------------------------------- colour spaces

    @Test
    fun indexedPaletteLookup() {
        val dict = dictOf(
            "Width" to i(2), "Height" to i(1), "BitsPerComponent" to i(8),
            "ColorSpace" to arr(n("Indexed"), n("DeviceRGB"), i(1), str(0xFF, 0x00, 0x00, 0x00, 0xFF, 0x00))
        )
        val image = assertNotNull(decode(dict, byteArrayOf(0, 1)).image)
        assertEquals(255, image.pixelAt(0, 0)!![0])
        assertEquals(0, image.pixelAt(0, 0)!![1])
        assertEquals(0, image.pixelAt(1, 0)!![0])
        assertEquals(255, image.pixelAt(1, 0)!![1])
    }

    @Test
    fun separationTintTransform() {
        val tint = dictOf(
            "FunctionType" to i(2), "Domain" to arr(r(0.0), r(1.0)),
            "C0" to arr(r(0.0), r(0.0), r(0.0), r(0.0)),
            "C1" to arr(r(0.0), r(0.0), r(0.0), r(1.0)),
            "N" to i(1)
        )
        val dict = dictOf(
            "Width" to i(2), "Height" to i(1), "BitsPerComponent" to i(8),
            "ColorSpace" to arr(n("Separation"), n("Spot"), n("DeviceCMYK"), tint)
        )
        val image = assertNotNull(decode(dict, byteArrayOf(0, 255.toByte())).image)
        assertEquals(255, image.pixelAt(0, 0)!![0]) // tint 0 → no ink → white
        assertEquals(0, image.pixelAt(1, 0)!![0]) // full black ink
    }

    // ---------------------------------------------------------------- masks

    @Test
    fun colorKeyMaskClearsMatchingPixels() {
        val dict = dictOf(
            "Width" to i(2), "Height" to i(1), "ColorSpace" to n("DeviceGray"),
            "BitsPerComponent" to i(8), "Mask" to arr(r(0.0), r(0.0))
        )
        val image = assertNotNull(decode(dict, byteArrayOf(0, 255.toByte())).image)
        assertEquals(0, image.pixelAt(0, 0)!![3])
        assertEquals(255, image.pixelAt(1, 0)!![3])
    }

    @Test
    fun imageMaskBecomesStencilAlpha() {
        val dict = dictOf("Width" to i(4), "Height" to i(1), "ImageMask" to com.pdfpaper.engine.model.PdfBoolean(true))
        val image = assertNotNull(decode(dict, byteArrayOf(0x90.toByte())).image)
        assertTrue(image.stencil)
        assertEquals(255, image.pixelAt(0, 0)!![3]) // sample 1 → paint
        assertEquals(0, image.pixelAt(1, 0)!![3])
        assertEquals(0, image.pixelAt(2, 0)!![3])
        assertEquals(255, image.pixelAt(3, 0)!![3])
    }

    @Test
    fun imageMaskDecodeArrayFlips() {
        val dict = dictOf(
            "Width" to i(4), "Height" to i(1),
            "ImageMask" to com.pdfpaper.engine.model.PdfBoolean(true),
            "Decode" to arr(r(1.0), r(0.0))
        )
        val image = assertNotNull(decode(dict, byteArrayOf(0x90.toByte())).image)
        assertEquals(0, image.pixelAt(0, 0)!![3])
        assertEquals(255, image.pixelAt(1, 0)!![3])
    }

    // ----------------------------------------------------------- page-level

    @Test
    fun softMaskIsDecodedAndAttached() {
        val image = streamObj(
            5,
            "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceGray " +
                "/BitsPerComponent 8 /SMask 6 0 R ",
            byteArrayOf(200.toByte())
        )
        val mask = streamObj(
            6,
            "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8 ",
            byteArrayOf(128.toByte())
        )
        val dl = displayWithImage(image, mask)
        val raster = dl.images.first().image
        assertEquals(200, raster.pixelAt(0, 0)!![0])
        val soft = assertNotNull(raster.softMask)
        assertEquals(128, soft.pixelAt(0, 0)!![0])
    }

    @Test
    fun stencilMaskReferenceMultipliesAlpha() {
        val image = streamObj(
            5,
            "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceGray " +
                "/BitsPerComponent 8 /Mask 6 0 R ",
            byteArrayOf(255.toByte())
        )
        val mask = streamObj(
            6,
            "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8 ",
            byteArrayOf(0x80.toByte())
        )
        val raster = displayWithImage(image, mask).images.first().image
        assertEquals(255, raster.pixelAt(0, 0)!![0])
        assertEquals(128, raster.pixelAt(0, 0)!![3])
    }

    // ------------------------------------------------------------------ DCT

    @Test
    fun jpegHeaderGivesDimensions() {
        val info = assertNotNull(PdfImages.jpegInfo(jpegBytes(3, 2)))
        assertEquals(3, info[0])
        assertEquals(2, info[1])
        assertEquals(3, info[2])
    }

    @Test
    fun dctWithoutPlatformDecoderKeepsEncodedBytes() {
        val jpeg = jpegBytes(3, 2)
        val dict = dictOf(
            "Width" to i(1), "Height" to i(1), "ColorSpace" to n("DeviceRGB"),
            "BitsPerComponent" to i(8), "Filter" to n("DCTDecode")
        )
        val result = decode(dict, jpeg)
        val image = assertNotNull(result.image)
        assertTrue(image.requiresExternalDecoder)
        assertEquals("DCT", image.externalFormat)
        assertEquals(3, image.width) // from the JPEG header, not the (wrong) /Width
        assertEquals(2, image.height)
        assertTrue(image.externalData!!.contentEquals(jpeg))
        assertTrue(result.warnings.any { it.contains("platform JPEG decoder") })
    }

    @Test
    fun dctWithPlatformDecoderProducesPixels() {
        val jpeg = jpegBytes(3, 2)
        val dict = dictOf(
            "Width" to i(3), "Height" to i(2), "ColorSpace" to n("DeviceRGB"),
            "BitsPerComponent" to i(8), "Filter" to n("DCTDecode")
        )
        val image = assertNotNull(decode(dict, jpeg, ImageIoDecoder).image)
        assertNotNull(image.pixels)
        assertEquals(3 * 2 * 4, image.pixels!!.size)
        val pixel = image.pixelAt(1, 1)!!
        assertTrue(pixel[0] > 180 && pixel[1] < 80 && pixel[2] < 80, "expected red-ish, got ${pixel.toList()}")
    }

    @Test
    fun unsupportedCcittReportsWarning() {
        val dict = dictOf(
            "Width" to i(1), "Height" to i(1), "ColorSpace" to n("DeviceGray"),
            "BitsPerComponent" to i(1), "Filter" to n("CCITTFaxDecode")
        )
        val result = decode(dict, byteArrayOf(0))
        assertNull(result.image)
        assertTrue(result.warnings.any { it.contains("CCITTFaxDecode") })
    }

    @Test
    fun inlineDictionaryAliasesAreExpanded() {
        val normalized = PdfImages.normalizeInlineDict(
            dictOf("W" to i(1), "H" to i(1), "BPC" to i(8), "CS" to n("DeviceGray"), "IM" to com.pdfpaper.engine.model.PdfBoolean(true))
        )
        assertEquals(i(1), normalized["Width"])
        assertEquals(i(1), normalized["Height"])
        assertEquals(i(8), normalized["BitsPerComponent"])
        assertEquals(n("DeviceGray"), normalized["ColorSpace"])
    }

    // -------------------------------------------------------------- helpers

    private fun displayWithImage(image: TestObj, mask: TestObj) =
        openPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                TestObj(
                    3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 10 10] " +
                        "/Resources << /XObject << /Im1 5 0 R >> >> /Contents 4 0 R >>"
                ),
                streamObj(4, "", "/Im1 Do"),
                image,
                mask
            )
        ).use { doc -> doc.pages().first().buildDisplayList() }

    private fun openPdf(objects: List<TestObj>) =
        PdfDocument.open(ByteArraySource(buildTestPdf(objects))).getOrThrow()

    private fun streamObj(num: Int, dict: String, content: ByteArray): TestObj {
        val body = ("<< /Length ${content.size} $dict>>\nstream\n").toByteArray(StandardCharsets.US_ASCII) +
            content + "\nendstream".toByteArray(StandardCharsets.US_ASCII)
        return TestObj(num, 0, body)
    }

    private fun streamObj(num: Int, dict: String, content: String): TestObj =
        streamObj(num, dict, content.toByteArray(StandardCharsets.ISO_8859_1))

    /** A solid-colour JPEG produced with the JDK encoder (test-only). */
    private fun jpegBytes(width: Int, height: Int): ByteArray {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = java.awt.Color(220, 20, 20)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }

    /** Test-only JPEG decoder standing in for `:app`'s BitmapFactory hook. */
    private object ImageIoDecoder : ExternalImageDecoder {
        override fun decode(format: String, data: ByteArray, width: Int, height: Int): ByteArray? {
            if (format != "DCT") return null
            val image = ImageIO.read(ByteArrayInputStream(data)) ?: return null
            val pixels = ByteArray(image.width * image.height * 4)
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val argb = image.getRGB(x, y)
                    val offset = (y * image.width + x) * 4
                    pixels[offset] = ((argb shr 16) and 0xFF).toByte()
                    pixels[offset + 1] = ((argb shr 8) and 0xFF).toByte()
                    pixels[offset + 2] = (argb and 0xFF).toByte()
                    pixels[offset + 3] = 255.toByte()
                }
            }
            return pixels
        }
    }
}
