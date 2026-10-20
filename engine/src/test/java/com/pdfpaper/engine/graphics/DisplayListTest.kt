package com.pdfpaper.engine.graphics

import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T8 acceptance: the display list for known PDFs contains the expected draw
 * commands — paths with their user-space geometry and transform, painting
 * operators, clipping, graphics-state save/restore, colour operators,
 * ExtGState alpha/line style, images, form XObject expansion and text runs.
 */
class DisplayListTest {

    // ------------------------------------------------------------- helpers

    private fun openPdf(objects: List<TestObj>): PdfDocument =
        PdfDocument.open(ByteArraySource(buildTestPdf(objects))).getOrThrow()

    private fun streamObj(num: Int, dict: String, content: String): TestObj =
        streamObj(num, dict, content.toByteArray(StandardCharsets.ISO_8859_1))

    private fun streamObj(num: Int, dict: String, content: ByteArray): TestObj {
        val body = ("<< /Length ${content.size} $dict>>\nstream\n").toByteArray(StandardCharsets.US_ASCII) +
            content + "\nendstream".toByteArray(StandardCharsets.US_ASCII)
        return TestObj(num, 0, body)
    }

    /** One-page PDF: catalog, pages, page, content stream; [extra] from object 5. */
    private fun page(content: String, resourceEntries: String = "", extra: List<TestObj> = emptyList()): List<TestObj> =
        listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(
                3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << $resourceEntries >> /Contents 4 0 R >>"
            ),
            streamObj(4, "", content)
        ) + extra

    private fun display(objects: List<TestObj>): DisplayList =
        openPdf(objects).use { doc -> doc.pages().first().buildDisplayList() }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-9) {
        assertTrue(abs(expected - actual) < tolerance, "expected $expected, got $actual")
    }

    // ------------------------------------------------------------- paths

    @Test
    fun rectangleFillThenStrokeMatchesOperators() {
        val dl = display(page("10 20 30 40 re f 1 0 0 RG 2 w 5 5 m 100 5 l S"))

        val fill = dl.commands[0] as FillPath
        assertEquals(
            listOf(
                MoveTo(10.0, 20.0), LineTo(40.0, 20.0),
                LineTo(40.0, 60.0), LineTo(10.0, 60.0), ClosePath
            ),
            fill.path.segments
        )
        assertEquals(PdfColor.BLACK, fill.color)
        assertEquals(1.0, fill.alpha)
        assertTrue(!fill.evenOdd)
        assertEquals(PdfMatrix.IDENTITY, fill.matrix)

        val stroke = dl.commands[1] as StrokePath
        assertEquals(listOf(MoveTo(5.0, 5.0), LineTo(100.0, 5.0)), stroke.path.segments)
        assertEquals(PdfColor(1.0, 0.0, 0.0), stroke.color)
        assertEquals(2.0, stroke.lineStyle.width)
    }

    @Test
    fun evenOddFillAndCurveOperators() {
        val dl = display(page("0 0 10 10 re f* 1 2 m 3 4 l 5 6 7 8 9 10 c 11 12 13 14 v 15 16 17 18 y h S"))

        assertTrue((dl.commands[0] as FillPath).evenOdd)

        val path = (dl.commands[1] as StrokePath).path
        assertEquals(
            listOf(
                MoveTo(1.0, 2.0),
                LineTo(3.0, 4.0),
                CurveTo(5.0, 6.0, 7.0, 8.0, 9.0, 10.0),
                // v: first control point is the current point (9, 10).
                CurveTo(9.0, 10.0, 11.0, 12.0, 13.0, 14.0),
                // y: last control point equals the end point.
                CurveTo(15.0, 16.0, 17.0, 18.0, 17.0, 18.0),
                ClosePath
            ),
            path.segments
        )
    }

    @Test
    fun fillStrokeAndNoPaintOperators() {
        val dl = display(page("0 0 10 10 re B 0 0 10 10 re b* 0 0 10 10 re n"))
        assertTrue(dl.commands[0] is FillStrokePath)
        assertTrue(!(dl.commands[0] as FillStrokePath).evenOdd)
        assertTrue((dl.commands[1] as FillStrokePath).evenOdd)
        assertEquals(2, dl.commands.size) // n paints nothing
    }

    @Test
    fun tmWithFewerThanSixOperandsIsIgnored() {
        val dl = display(page("BT 1 2 3 Tm ET"))
        assertTrue(dl.commands.isEmpty())
    }

    // ------------------------------------------------------------- clipping

    @Test
    fun clipEmitsClipPathBeforePainting() {
        val dl = display(page("0 0 50 50 re W n 10 10 20 20 re f"))

        val clip = dl.commands[0] as ClipPath
        assertEquals(PdfPath.rectangle(0.0, 0.0, 50.0, 50.0).segments, clip.path.segments)
        assertTrue(!clip.evenOdd)
        assertEquals(PdfMatrix.IDENTITY, clip.matrix)

        val fill = dl.commands[1] as FillPath
        assertEquals(PdfPath.rectangle(10.0, 10.0, 30.0, 30.0).segments, fill.path.segments)
    }

    @Test
    fun clipEvenOddVariant() {
        val dl = display(page("0 0 10 10 re W* n"))
        assertTrue((dl.commands[0] as ClipPath).evenOdd)
    }

    // ------------------------------------------------------- graphics state

    @Test
    fun saveAndRestoreRoundTrip() {
        val dl = display(page("1 w 0 0 10 10 re S q 5 w 1 0 0 RG 20 20 30 30 re S Q 0 0 10 10 re S"))

        assertTrue(dl.commands[0] is StrokePath)
        assertEquals(1.0, (dl.commands[0] as StrokePath).lineStyle.width)
        assertTrue(dl.commands[1] is SaveGraphicsState)
        assertEquals(5.0, (dl.commands[2] as StrokePath).lineStyle.width)
        assertEquals(PdfColor(1.0, 0.0, 0.0), (dl.commands[2] as StrokePath).color)
        assertTrue(dl.commands[3] is RestoreGraphicsState)
        val after = dl.commands[4] as StrokePath
        assertEquals(1.0, after.lineStyle.width)
        assertEquals(PdfColor.BLACK, after.color)
    }

    @Test
    fun transformMatrixIsCarriedWithGeometry() {
        val dl = display(page("2 0 0 3 10 20 cm 0 0 1 1 re f"))
        val fill = dl.commands[0] as FillPath
        assertEquals(PdfMatrix(2.0, 0.0, 0.0, 3.0, 10.0, 20.0), fill.matrix)
        // Geometry stays in the user space of the moment (unit square).
        assertEquals(MoveTo(0.0, 0.0), fill.path.segments[0])
    }

    @Test
    fun lineStyleOperatorsIncludingDash() {
        val dl = display(page("3 w 1 J 2 j 4 M [2 3] 1 d 0 0 10 10 re S"))
        val style = (dl.commands[0] as StrokePath).lineStyle
        assertEquals(3.0, style.width)
        assertEquals(1, style.cap)
        assertEquals(2, style.join)
        assertEquals(4.0, style.miterLimit)
        assertEquals(listOf(2.0, 3.0), style.dashArray.toList())
        assertEquals(1.0, style.dashPhase)
    }

    @Test
    fun extGStateSetsAlphaAndLineWidth() {
        val dl = display(
            page(
                "q /GS1 gs 0 0 10 10 re B Q",
                "/ExtGState << /GS1 << /ca 0.5 /CA 0.25 /LW 3 >> >>"
            )
        )
        val both = dl.commands[1] as FillStrokePath
        assertEquals(0.5, both.fillAlpha)
        assertEquals(0.25, both.strokeAlpha)
        assertEquals(3.0, both.lineStyle.width)
    }

    // ------------------------------------------------------------- colours

    @Test
    fun colourOperatorsMapToColorSpaces() {
        val dl = display(
            page(
                "0.25 g 0 0 1 1 re f 1 0 0 RG 0 0 2 2 re S " +
                    "0 1 1 0 k 0 0 3 3 re f 1 0 0 0 K 0 0 4 4 re S"
            )
        )
        assertEquals(PdfColor(0.25, 0.25, 0.25), (dl.commands[0] as FillPath).color)
        assertEquals(PdfColor(1.0, 0.0, 0.0), (dl.commands[1] as StrokePath).color)
        assertEquals(PdfColor(1.0, 0.0, 0.0), (dl.commands[2] as FillPath).color) // CMYK (0,1,1,0) → red
        assertEquals(PdfColor(0.0, 1.0, 1.0), (dl.commands[3] as StrokePath).color) // CMYK (1,0,0,0) → cyan
    }

    @Test
    fun colourSpaceOperatorAcceptsNamedSpace() {
        val dl = display(
            page(
                "q /CS1 cs 0.5 sc 0 0 10 10 re f Q",
                "/ColorSpace << /CS1 /DeviceGray >>"
            )
        )
        assertEquals(PdfColor(0.5, 0.5, 0.5), (dl.commands[1] as FillPath).color)
    }

    // ---------------------------------------------------------------- text

    @Test
    fun textOperatorsProduceTextRuns() {
        val dl = display(
            page(
                "BT /F1 12 Tf 10 20 Td (HI) Tj ET",
                "/Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica " +
                    "/FirstChar 72 /LastChar 73 /Widths [722 278] >> >>"
            )
        )
        val draw = dl.commands[0] as DrawTextRun
        assertEquals("HI", draw.run.text)
        assertEquals(12.0, draw.run.fontSize)
        assertEquals(PdfMatrix.IDENTITY, draw.matrix)
        assertClose(10.0, draw.run.x0)
        assertClose(20.0, draw.run.y0)
        assertClose(32.0, draw.run.y1)
        // Helvetica H=722 + I=278 = 1000/1000 em × 12 pt (§9.4.4).
        assertClose(10.0 + 12.0, draw.run.x1, 1e-6)
        assertEquals(listOf("HI"), dl.textRuns.map { it.text })
    }

    // --------------------------------------------------------- form XObject

    @Test
    fun formXObjectIsExpandedWithMatrixAndBBoxClip() {
        val form = streamObj(
            5,
            "/Subtype /Form /BBox [0 0 100 100] /Matrix [1 0 0 1 5 5] /Resources << >> ",
            "0 0 50 50 re f"
        )
        val dl = display(page("/Fm1 Do", "/XObject << /Fm1 5 0 R >>", listOf(form)))

        val save = dl.commands[0] as SaveGraphicsState
        assertEquals(PdfMatrix.translation(5.0, 5.0), save.matrix)

        val clip = dl.commands[1] as ClipPath
        assertEquals(PdfPath.rectangle(0.0, 0.0, 100.0, 100.0).segments, clip.path.segments)
        assertEquals(PdfMatrix.translation(5.0, 5.0), clip.matrix)

        val fill = dl.commands[2] as FillPath
        assertEquals(PdfMatrix.translation(5.0, 5.0), fill.matrix)
        assertEquals(PdfPath.rectangle(0.0, 0.0, 50.0, 50.0).segments, fill.path.segments)

        assertTrue(dl.commands[3] is RestoreGraphicsState)
    }

    // --------------------------------------------------------------- images

    @Test
    fun imageXObjectIsDecodedIntoTheDisplayList() {
        val image = streamObj(
            5,
            "/Type /XObject /Subtype /Image /Width 1 /Height 1 " +
                "/ColorSpace /DeviceGray /BitsPerComponent 8 ",
            byteArrayOf(0x80.toByte())
        )
        val dl = display(page("/Im1 Do", "/XObject << /Im1 5 0 R >>", listOf(image)))

        val draw = dl.commands[0] as DrawRasterImage
        // Image space has its origin at the top-left sample row and y growing
        // down; with an identity CTM the unit square is flipped vertically so
        // row 0 lands at the top (§8.9.5.2).
        assertEquals(PdfMatrix(1.0, 0.0, 0.0, -1.0, 0.0, 1.0), draw.matrix)
        assertEquals(1, draw.image.width)
        assertEquals(1, draw.image.height)
        val pixel = assertNotNull(draw.image.pixelAt(0, 0))
        assertEquals(128, pixel[0])
        assertEquals(255, pixel[3])
    }

    @Test
    fun inlineImageIsDecoded() {
        // BI /W 1 /H 1 /CS /G /BPC 8 ID <0xFF> EI  (§8.9.7)
        val data = byteArrayOf(0xFF.toByte())
        val content = "BI /W 1 /H 1 /CS /G /BPC 8 /F /AHx ID ".toByteArray(StandardCharsets.US_ASCII) +
            "FF>".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf('\n'.code.toByte()) +
            "EI".toByteArray(StandardCharsets.US_ASCII)
        val objects = listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> /Contents 4 0 R >>"),
            streamObj(4, "", content)
        )
        val dl = display(objects)
        val draw = dl.commands.filterIsInstance<DrawRasterImage>().first()
        assertEquals(255, draw.image.pixelAt(0, 0)!![0])
        assertTrue(dl.warnings.isEmpty(), "unexpected warnings: ${dl.warnings}")
    }

    // ------------------------------------------------------------ tolerance

    @Test
    fun unsupportedOperatorsWarnAndContinue() {
        val dl = display(page("/Sh1 sh 0 0 10 10 re f"))
        assertTrue(dl.warnings.any { it.contains("shading") }, "warnings: ${dl.warnings}")
        assertEquals(1, dl.commands.size)
        assertTrue(dl.commands[0] is FillPath)
    }

    @Test
    fun unbalancedQDoesNotThrow() {
        val dl = display(page("Q 0 0 10 10 re f"))
        assertTrue(dl.warnings.any { it.contains("without matching q") })
        assertEquals(1, dl.commands.size)
    }
}
