package com.pdfpaper.engine.fit

import com.pdfpaper.engine.create.PageOrientation
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.export.PageRaster
import com.pdfpaper.engine.export.PageRasterizer
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.PageRenderPlan
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.edit.Raster
import com.pdfpaper.engine.graphics.PdfColor
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T57: content bounds, the document-level fit box and its fallbacks, checked
 * on the five document shapes named in the task (single column, two column,
 * wide margins, scanned book, slides).
 */
class ContentFitTest {

    // ---------------------------------------------------------------- fixtures

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private fun builder(size: PageSize = PageSize.A4, orientation: PageOrientation = PageOrientation.PORTRAIT) =
        PdfBuilder(size, orientation, metadata = com.pdfpaper.engine.create.PdfMetadata())

    /** Header and footer text a reader does not want in the fit box. */
    private fun withRunningHeads(page: com.pdfpaper.engine.create.PdfPageBuilder) {
        page.drawText("Chapter 3 - Running head", 72.0, 30.0, 9.0)
        page.drawText("Page 12", 72.0, page.height - 24.0, 9.0)
    }

    /** A single-column body: 18 lines from [baselineTop] at [x], [chars] wide. */
    private fun body(
        page: com.pdfpaper.engine.create.PdfPageBuilder,
        x: Double,
        baselineTop: Double,
        lines: Int,
        chars: Int,
        size: Double = 11.0
    ) {
        val line = "The quick brown fox jumps over the lazy dog and keeps on running".take(chars)
        for (i in 0 until lines) {
            page.drawText(line, x, baselineTop + i * 18.0, size)
        }
    }

    private fun singleColumn(): ByteArray {
        val pdf = builder()
        val page = pdf.addPage()
        withRunningHeads(page)
        body(page, 90.0, 140.0, 20, 46)
        return pdf.build()
    }

    private fun twoColumn(): ByteArray {
        val pdf = builder()
        val page = pdf.addPage()
        withRunningHeads(page)
        body(page, 60.0, 140.0, 18, 24)
        body(page, 340.0, 140.0, 18, 24)
        return pdf.build()
    }

    private fun wideMargins(): ByteArray {
        val pdf = builder()
        val page = pdf.addPage()
        body(page, 150.0, 260.0, 12, 34)
        return pdf.build()
    }

    /** Ten pages whose body width and length vary, as a real book does. */
    private fun varyingBook(): ByteArray {
        val pdf = builder()
        for (i in 0 until 10) {
            val page = pdf.addPage()
            withRunningHeads(page)
            body(page, 70.0 + (i % 3) * 4.0, 130.0, 14 + (i % 5), 30 + i)
        }
        return pdf.build()
    }

    private fun slides(): ByteArray {
        val pdf = builder(PageSize.A4, PageOrientation.LANDSCAPE)
        for (i in 0 until 3) {
            val page = pdf.addPage()
            page.drawText("Quarterly review $i", 60.0, 70.0, 30.0)
            page.drawRect(60.0, 130.0, page.width - 120.0, page.height - 240.0, fill = PdfColor(0.2, 0.4, 0.8))
            page.drawText("Revenue grew 12 %", 60.0, page.height - 70.0, 14.0)
        }
        return pdf.build()
    }

    private val fakePng: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    private fun image(width: Int, height: Int): com.pdfpaper.engine.edit.ImageData =
        EditImages.decode(
            fakePng,
            object : com.pdfpaper.engine.edit.RasterDecoder {
                override fun decode(bytes: ByteArray): Raster =
                    Raster(width, height, IntArray(width * height) { -1 })
            }
        ).getOrThrow()

    private fun scannedPage(width: Double = 0.0, height: Double = 0.0): ByteArray {
        val pdf = builder()
        val page = pdf.addPage()
        page.drawImage(
            image(32, 32),
            0.0,
            0.0,
            if (width > 0.0) width else page.width,
            if (height > 0.0) height else page.height
        )
        return pdf.build()
    }

    /**
     * A scan with slightly yellowed paper (238, 236, 228) and a dark printed
     * block inset by [inset] of each side. The paper is not white on purpose:
     * a real scan's background must be measured, not assumed.
     */
    private class InsetRasterizer(private val inset: Double) : PageRasterizer {
        override fun rasterize(page: PdfPage, plan: PageRenderPlan): PageRaster {
            val w = plan.pixelWidth
            val h = plan.pixelHeight
            val rgba = ByteArray(w * h * 4)
            for (i in 0 until w * h) {
                rgba[i * 4] = 238.toByte()
                rgba[i * 4 + 1] = 236.toByte()
                rgba[i * 4 + 2] = 228.toByte()
                rgba[i * 4 + 3] = 0xFF.toByte()
            }
            val x0 = (w * inset).toInt()
            val x1 = (w * (1.0 - inset)).toInt()
            val y0 = (h * inset).toInt()
            val y1 = (h * (1.0 - inset)).toInt()
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val i = (y * w + x) * 4
                    rgba[i] = 20
                    rgba[i + 1] = 20
                    rgba[i + 2] = 20
                }
            }
            return PageRaster(w, h, rgba)
        }
    }

    /** A raw page with /Rotate so rotation can be checked exactly. */
    private fun rotatedDoc(): ByteArray {
        fun stream(num: Int, text: String): TestObj {
            val body = text.toByteArray(StandardCharsets.ISO_8859_1)
            val out = java.io.ByteArrayOutputStream()
            out.write("<< /Length ${body.size} >>\nstream\n".toByteArray(StandardCharsets.US_ASCII))
            out.write(body)
            out.write("\nendstream".toByteArray(StandardCharsets.US_ASCII))
            return TestObj(num, 0, out.toByteArray())
        }
        return buildTestPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                TestObj(
                    3, 0,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 600] /Rotate 90 " +
                        "/Resources << /Font << /F1 20 0 R >> >> /Contents 4 0 R >>"
                ),
                stream(
                    4,
                    "0.2 0.4 0.8 rg 60 200 300 40 re f\n" +
                        "BT /F1 12 Tf 1 0 0 1 60 200 Tm (Rotated body text here) Tj ET"
                ),
                TestObj(20, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
            )
        )
    }

    private fun assertContains(box: PdfRect, inner: PdfRect, tolerance: Double = 0.01) {
        assertTrue(
            box.left <= inner.left + tolerance &&
                box.bottom <= inner.bottom + tolerance &&
                box.right >= inner.right - tolerance &&
                box.top >= inner.top - tolerance,
            "box $box must contain $inner"
        )
    }

    private fun assertSize(expected: FitSize?, actualWidth: Double, actualHeight: Double, tolerance: Double = 0.05) {
        assertNotNull(expected)
        assertTrue(
            abs(expected.width - actualWidth) < tolerance && abs(expected.height - actualHeight) < tolerance,
            "expected ${expected.width} x ${expected.height}, got $actualWidth x $actualHeight"
        )
    }

    // ------------------------------------------------------------ page filters

    @Test
    fun headerAndFooterAreIgnored() {
        open(singleColumn()).use { document ->
            val result = ContentFit.compute(document).getOrThrow()
            val page = result.pages[0]
            val text = assertNotNull(page.text)
            val box = assertNotNull(result.boxes[0])
            // The body starts at baseline 140 and ends below 140 + 19*18.
            assertTrue(text.top < 800.0, "the footer must be excluded, got $text")
            assertTrue(text.bottom > 100.0, "the header must be excluded, got $text")
            assertContains(box, text)
            assertTrue(box.width > 0.0 && box.height > 0.0)
        }
    }

    @Test
    fun aPageSizedBackgroundRectangleIsIgnored() {
        val pdf = builder()
        val page = pdf.addPage()
        page.drawRect(0.0, 0.0, page.width, page.height, fill = PdfColor(0.93, 0.93, 0.9))
        page.drawText("Only this line is content", 80.0, 300.0, 14.0)
        open(pdf.build()).use { document ->
            val result = ContentFit.compute(document).getOrThrow()
            val content = result.pages[0]
            assertNull(content.vectors, "a page-sized fill is background")
            val text = assertNotNull(content.text)
            val box = assertNotNull(result.boxes[0])
            assertContains(box, text)
            assertTrue(box.width < page.width / 2.0, "the background must not widen the box: $box")
        }
    }

    @Test
    fun invisibleTextIsNotContent() {
        fun stream(num: Int, text: String): TestObj {
            val body = text.toByteArray(StandardCharsets.ISO_8859_1)
            val out = java.io.ByteArrayOutputStream()
            out.write("<< /Length ${body.size} >>\nstream\n".toByteArray(StandardCharsets.US_ASCII))
            out.write(body)
            out.write("\nendstream".toByteArray(StandardCharsets.US_ASCII))
            return TestObj(num, 0, out.toByteArray())
        }
        val bytes = buildTestPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                TestObj(
                    3, 0,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 500 700] " +
                        "/Resources << /Font << /F1 20 0 R >> >> /Contents 4 0 R >>"
                ),
                stream(
                    4,
                    "BT /F1 10 Tf 1 0 0 1 40 300 Tm (Visible line) Tj ET\n" +
                        "BT 3 Tr /F1 40 Tf 1 0 0 1 20 600 Tm (INVISIBLE OCR LAYER) Tj ET"
                ),
                TestObj(20, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
            )
        )
        open(bytes).use { document ->
            val content = ContentFit.compute(document).getOrThrow().pages[0]
            val text = assertNotNull(content.text)
            assertTrue(text.top < 350.0, "render mode 3 text must be ignored, got $text")
        }
    }

    @Test
    fun aFullPagePictureKeepsThePageWhole() {
        open(scannedPage(595.0, 842.0)).use { document ->
            val result = ContentFit.compute(document).getOrThrow()
            val content = result.pages[0]
            assertTrue(content.fullBleed)
            val page = assertNotNull(result.boxes[0])
            assertEquals(content.page.width, page.width, 0.01)
            assertEquals(content.page.height, page.height, 0.01)
        }
    }

    @Test
    fun blankPageIsShownWhole() {
        val pdf = builder()
        val page = pdf.addPage()
        page.drawRect(0.0, 0.0, page.width, page.height, fill = PdfColor(0.5, 0.5, 0.5))
        open(pdf.build()).use { document ->
            val result = ContentFit.compute(document).getOrThrow()
            assertEquals(ContentSource.BLANK, result.pages[0].source)
            val box = assertNotNull(result.boxes[0])
            assertEquals(page.width, box.width, 0.01)
            assertEquals(page.height, box.height, 0.01)
        }
    }

    // -------------------------------------------------------- document shapes

    @Test
    fun singleColumnGetsNarrowerBoxAndKeepsAllText() {
        open(singleColumn()).use { document ->
            val result = ContentFit.compute(document).getOrThrow()
            val box = assertNotNull(result.boxes[0])
            val text = assertNotNull(result.pages[0].text)
            assertContains(box, text)
            // The screen shows the content at the box width, so the text gets
            // bigger by the ratio of the page to the box.
            assertTrue(
                box.width < document.pages()[0].let { 595.0 },
                "the box must be narrower than the page: $box"
            )
            assertTrue(box.width > text.width, "padding makes the box a little wider")
        }
    }

    @Test
    fun twoColumnsAreBothInsideTheBox() {
        open(twoColumn()).use { document ->
            val result = ContentFit.compute(document).getOrThrow()
            val text = assertNotNull(result.pages[0].text)
            val box = assertNotNull(result.boxes[0])
            assertContains(box, text)
            assertTrue(text.width > 380.0, "both columns must be in the text bounds: $text")
            assertTrue(text.left < 70.0 && text.right > 440.0, "both columns: $text")
        }
    }

    @Test
    fun wideMarginsAreTrimmed() {
        open(wideMargins()).use { document ->
            val result = ContentFit.compute(document).getOrThrow()
            val box = assertNotNull(result.boxes[0])
            val pageWidth = 595.28
            assertTrue(box.width < pageWidth * 0.75, "wide margins must shrink the box: $box")
            assertTrue(pageWidth / box.width > 1.3, "the text must grow on screen")
        }
    }

    @Test
    fun slidingPagesKeepTheWholePictureAndItsCaptions() {
        open(slides()).use { document ->
            val result = ContentFit.compute(document).getOrThrow()
            for (index in 0 until 3) {
                val content = result.pages[index]
                val box = assertNotNull(result.boxes[index])
                val text = assertNotNull(content.text)
                assertContains(box, text)
                // The big panel is vector art and must be inside the box too.
                val vectors = assertNotNull(content.vectors)
                assertContains(box, vectors)
            }
        }
    }

    // ------------------------------------------------------- document sizing

    @Test
    fun oneDisplayedSizeIsSharedAcrossTheBook() {
        open(varyingBook()).use { document ->
            val result = ContentFit.compute(document).getOrThrow()
            val sizes = (0 until 10).map { index ->
                val box = assertNotNull(result.boxes[index])
                val page = result.pages[index]
                if (page.rotate % 180 == 0) box.width to box.height else box.height to box.width
            }
            val first = sizes.first()
            for (size in sizes) {
                assertEquals(first.first, size.first, 0.01, "page widths must not jump")
                assertEquals(first.second, size.second, 0.01, "page heights must not jump")
            }
            assertSize(result.oddSize, first.first, first.second)
            // And every page's own text is inside the shared box.
            for (index in 0 until 10) {
                val text = result.pages[index].text
                if (text != null) assertContains(assertNotNull(result.boxes[index]), text)
            }
        }
    }

    @Test
    fun oddAndEvenPagesGetSeparateSizesWhenTheyDiffer() {
        val pdf = builder()
        for (i in 0 until 6) {
            val page = pdf.addPage()
            val chars = if (i % 2 == 0) 44 else 26
            body(page, 70.0, 150.0, 16, chars)
        }
        open(pdf.build()).use { document ->
            val result = ContentFit.compute(document).getOrThrow()
            val odd = assertNotNull(result.oddSize)
            val even = assertNotNull(result.evenSize)
            assertTrue(
                odd.width > even.width + 10.0,
                "odd ${odd.width} should be wider than even ${even.width}"
            )
            for (index in 0 until 6) {
                val box = assertNotNull(result.boxes[index])
                val expected = if (index % 2 == 0) odd else even
                assertSize(expected, box.width, box.height)
            }
        }
    }

    @Test
    fun perPageModeKeepsEveryPagesOwnBox() {
        val pdf = builder()
        body(pdf.addPage(), 70.0, 150.0, 14, 40)
        body(pdf.addPage(), 70.0, 150.0, 14, 22)
        open(pdf.build()).use { document ->
            val result = ContentFit.compute(
                document,
                ContentFitOptions(sharedSize = false)
            ).getOrThrow()
            val first = assertNotNull(result.boxes[0])
            val second = assertNotNull(result.boxes[1])
            assertNull(result.oddSize)
            assertTrue(
                first.width > second.width + 10.0,
                "each page keeps its own width: ${first.width} vs ${second.width}"
            )
            assertContains(first, assertNotNull(result.pages[0].text))
            assertContains(second, assertNotNull(result.pages[1].text))
        }
    }

    @Test
    fun headersCanBeKeptWhenTheReaderAsks() {
        open(singleColumn()).use { document ->
            val kept = ContentFit.compute(
                document,
                ContentFitOptions(ignoreHeaderFooter = false)
            ).getOrThrow()
            val text = assertNotNull(kept.pages[0].text)
            val filtered = ContentFit.compute(document).getOrThrow().pages[0].text
            assertNotNull(filtered)
            assertTrue(
                text.top > filtered.top + 10.0,
                "the header must widen the box when kept: $text vs $filtered"
            )
        }
    }

    @Test
    fun aLandscapeOutlierKeepsItsOwnBox() {
        val pdf = builder()
        for (i in 0 until 5) {
            body(pdf.addPage(), 80.0, 150.0, 18, 40)
        }
        val landscape = pdf.addPage(PageSize.A4, PageOrientation.LANDSCAPE)
        landscape.drawText("Figure", 100.0, 100.0, 14.0)
        open(pdf.build()).use { document ->
            val result = ContentFit.compute(document).getOrThrow()
            assertTrue(5 in result.fallbackPages, "the landscape page must fall back")
            val box = assertNotNull(result.boxes[5])
            val text = assertNotNull(result.pages[5].text)
            assertContains(box, text)
            assertTrue(box.width < 300.0, "the outlier uses its own content box: $box")
            assertFalse(result.uniform)
        }
    }

    @Test
    fun aRotatedPageKeepsItsTextAndRotation() {
        open(rotatedDoc()).use { document ->
            val result = ContentFit.compute(document).getOrThrow()
            val content = result.pages[0]
            assertEquals(90, content.rotate)
            val text = assertNotNull(content.text)
            val box = assertNotNull(result.boxes[0])
            assertContains(box, text)
            assertTrue(box.width < 400.0 && box.height < 600.0)
            // 400 x 600 page turned clockwise: the displayed width is the
            // user-space height and the displayed height the user-space width.
            val padding = 2 * ContentFitOptions.DEFAULT_PADDING
            assertEquals(box.width - padding, content.displayHeight, 0.01)
            assertEquals(box.height - padding, content.displayWidth, 0.01)
        }
    }

    @Test
    fun paddingIsAddedAndClampedToThePage() {
        val pdf = builder()
        pdf.addPage().drawText("Corner", 50.0, 300.0, 12.0)
        open(pdf.build()).use { document ->
            val result = ContentFit.compute(document, ContentFitOptions(padding = 10.0)).getOrThrow()
            val text = assertNotNull(result.pages[0].text)
            val box = assertNotNull(result.boxes[0])
            assertContains(box, text)
            assertEquals(10.0, text.left - box.left, 0.01)
            assertTrue(box.left >= 0.0, "the box stays inside the page")
        }
    }

    // --------------------------------------------------------------- scanning

    @Test
    fun aScannedPageUsesTheLowResolutionRender() {
        open(scannedPage(600.0, 800.0)).use { document ->
            val result = ContentFit.compute(
                document,
                rasterizer = InsetRasterizer(0.2)
            ).getOrThrow()
            assertEquals(listOf(0), result.scannedPages)
            val content = result.pages[0]
            val box = assertNotNull(result.boxes[0])
            // The printed block covers 60 % of the page, centred, plus padding.
            val padding = ContentFitOptions.DEFAULT_PADDING
            assertEquals(content.page.width * 0.6 + 2 * padding, box.width, 2.0)
            assertEquals(content.page.height * 0.6 + 2 * padding, box.height, 2.0)
            assertEquals(content.page.centerX, box.centerX, 2.0)
            assertEquals(content.page.centerY, box.centerY, 2.0)
            assertFalse(content.fullBleed)
        }
    }

    @Test
    fun aPageSizedPictureWithoutARasterizerStaysWhole() {
        open(scannedPage(600.0, 800.0)).use { document ->
            val result = ContentFit.compute(document).getOrThrow()
            assertTrue(result.scannedPages.isEmpty())
            val page = result.pages[0].page
            assertEquals(page.width, assertNotNull(result.boxes[0]).width, 0.01)
        }
    }

    // ---------------------------------------------------------------- failures

    @Test
    fun malformedContentIsReportedNotThrown() {
        // A page whose /Contents points at a missing object: the display list
        // build fails, and the run still returns a usable result.
        val bytes = buildTestPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                TestObj(3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Contents 9 0 R >>")
            )
        )
        open(bytes).use { document ->
            val result = ContentFit.compute(document).getOrThrow()
            val box = assertNotNull(result.boxes[0])
            assertEquals(300.0, box.width, 0.01)
            assertTrue(result.warnings.isNotEmpty())
        }
    }

    @Test
    fun pageWithoutPagesFails() {
        val bytes = buildTestPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, "<< /Type /Pages /Kids [] /Count 0 >>")
            )
        )
        open(bytes).use { document ->
            val result = ContentFit.compute(document)
            assertTrue(result.isFailure)
        }
    }

    @Test
    fun optionsRejectImpossibleValues() {
        runCatching { ContentFitOptions(padding = -1.0) }.let { assertTrue(it.isFailure) }
        runCatching { ContentFitOptions(headerBand = 0.9) }.let { assertTrue(it.isFailure) }
        runCatching { ContentFitOptions(backgroundCoverage = 0.1) }.let { assertTrue(it.isFailure) }
        runCatching { ContentFitOptions(outlierRatio = 0.5) }.let { assertTrue(it.isFailure) }
    }

    @Test
    fun noOpDecoderIsAccepted() {
        val decoder = object : ExternalImageDecoder {
            override fun decode(format: String, data: ByteArray, width: Int, height: Int): ByteArray? = null
        }
        open(singleColumn()).use { document ->
            val result = ContentFit.compute(document, decoder = decoder).getOrThrow()
            assertNotNull(result.boxes[0])
        }
    }
}
