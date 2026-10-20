package com.pdfpaper.engine.graphics

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Image XObjects store their first sample row at the top (§8.9.5.2). The
 * display-list matrix must therefore put row 0 at the top of the unit square;
 * otherwise raster renderers paint every image upside down.
 */
class ImageOrientationTest {

    @Test
    fun firstSampleRowMapsToTheTopOfTheImageUnitSquare() {
        val content = "q 10 0 0 20 100 150 cm /Im1 Do Q"
        // 1x2 DeviceGray: first row black (top), second row white (bottom).
        val samples = byteArrayOf(0, 255.toByte())
        val objects = listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(
                3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] " +
                    "/Resources << /XObject << /Im1 5 0 R >> >> /Contents 4 0 R >>"
            ),
            streamObj(4, "", content.toByteArray(StandardCharsets.ISO_8859_1)),
            streamObj(
                5,
                "/Type /XObject /Subtype /Image /Width 1 /Height 2 " +
                    "/ColorSpace /DeviceGray /BitsPerComponent 8 ",
                samples
            )
        )

        PdfDocument.open(ByteArraySource(buildTestPdf(objects))).getOrThrow().use { doc ->
            val image = doc.pages().first().buildDisplayList().images.single()
            assertTrue(image.image.width == 1 && image.image.height == 2)

            // The CTM maps the unit square to x=100..110, y=150..170. With
            // image space y growing down, (0,0) is the top-left sample and
            // (0,1) the bottom-left sample.
            val topLeft = image.matrix.apply(0.0, 0.0)
            val bottomLeft = image.matrix.apply(0.0, 1.0)
            assertEquals(100.0, topLeft[0], 1e-9)
            assertEquals(170.0, topLeft[1], 1e-9)
            assertEquals(100.0, bottomLeft[0], 1e-9)
            assertEquals(150.0, bottomLeft[1], 1e-9)
        }
    }

    private fun streamObj(num: Int, dict: String, content: ByteArray): TestObj {
        val body = ("<< /Length ${content.size} $dict>>\nstream\n")
            .toByteArray(StandardCharsets.US_ASCII) +
            content + "\nendstream".toByteArray(StandardCharsets.US_ASCII)
        return TestObj(num, 0, body)
    }
}
