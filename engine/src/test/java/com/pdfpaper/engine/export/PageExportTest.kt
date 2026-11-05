package com.pdfpaper.engine.export

import com.pdfpaper.engine.image.EncodedImage
import com.pdfpaper.engine.image.ImageEncoder
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.PageRenderPlan
import com.pdfpaper.engine.model.PdfDocument
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PageExportTest {

    /** Paints the requested raster deterministically: red top-left quarter, white elsewhere. */
    private class TestRasterizer : PageRasterizer {
        val plans = ArrayList<PageRenderPlan>()
        var failOnPage: Int? = null

        override fun rasterize(page: PdfPage, plan: PageRenderPlan): PageRaster? {
            if (page.index == failOnPage) return null
            plans += plan
            val rgba = ByteArray(plan.pixelWidth * plan.pixelHeight * 4)
            val halfW = plan.pixelWidth / 2
            val halfH = plan.pixelHeight / 2
            for (y in 0 until plan.pixelHeight) {
                for (x in 0 until plan.pixelWidth) {
                    val i = (y * plan.pixelWidth + x) * 4
                    val red = x < halfW && y < halfH
                    rgba[i] = if (red) 255.toByte() else 255.toByte()
                    rgba[i + 1] = if (red) 0 else 255.toByte()
                    rgba[i + 2] = if (red) 0 else 255.toByte()
                    rgba[i + 3] = 255.toByte()
                }
            }
            return PageRaster(plan.pixelWidth, plan.pixelHeight, rgba)
        }
    }

    private class StubJpegEncoder : ImageEncoder {
        override fun encodeJpeg(
            width: Int,
            height: Int,
            rgba: ByteArray,
            quality: Int,
            grayscale: Boolean
        ): EncodedImage = EncodedImage("JPEG:$width x $height q$quality".toByteArray(), width, height, false)
    }

    private fun letterDocument(pages: Int = 3): PdfDocument {
        val fixture = ExportFixtures.Doc()
        val bytes = fixture.build(List(pages) { ExportFixtures.page() to emptyList() })
        return ExportFixtures.open(bytes)
    }

    private fun options(
        documentName: String = "sample.pdf",
        dpi: Int = 72,
        format: ImageFormat = ImageFormat.PNG,
        pages: String = "all",
        maxPixels: Long = PageExportOptions.DEFAULT_MAX_PIXELS
    ) = PageExportOptions(
        documentName = documentName,
        dpi = dpi,
        format = format,
        pages = PageRange.parse(pages).getOrThrow(),
        maxPixels = maxPixels
    )

    @Test
    fun `pixel size follows the page box and the DPI`() {
        letterDocument(1).use { document ->
            val rasterizer = TestRasterizer()
            val sizes = ArrayList<Pair<Int, Int>>()
            for (dpi in listOf(72, 150, 300)) {
                sizes += PdfPageExport.pixelSize(
                    com.pdfpaper.engine.render.PageGeometry.boxOf(document.pages()[0]), dpi
                )
            }
            assertEquals(listOf(612 to 792, 1275 to 1650, 2550 to 3300), sizes)
            assertTrue(rasterizer.plans.isEmpty())
        }
    }

    @Test
    fun `exports at 72 150 and 300 DPI with matching pixels`() {
        letterDocument(1).use { document ->
            for (dpi in listOf(72, 150, 300)) {
                val rasterizer = TestRasterizer()
                val output = ArrayList<ExportedPage>()
                val summary = PdfPageExport.export(
                    document, options(dpi = dpi), rasterizer, null, { output += it }
                ).getOrThrow()
                assertEquals(1, summary.exported)
                assertEquals(1, rasterizer.plans.size)
                val page = output.single()
                assertEquals(PdfPageExport.pixelSize(
                    com.pdfpaper.engine.render.PageGeometry.boxOf(document.pages()[0]), dpi
                ), page.pixelWidth to page.pixelHeight)
                val png = PngCheck.read(page.bytes)
                assertEquals(page.pixelWidth, png.width)
                assertEquals(page.pixelHeight, png.height)
                // The red quarter survives at every resolution.
                val quarterX = png.width / 4
                val quarterY = png.height / 4
                assertContentEquals(byteArrayOf(255.toByte(), 0, 0), png.pixel(quarterX, quarterY))
                assertContentEquals(
                    byteArrayOf(255.toByte(), 255.toByte(), 255.toByte()),
                    png.pixel(png.width - 1, png.height - 1)
                )
            }
        }
    }

    @Test
    fun `rotation swaps the exported width and height`() {
        val fixture = ExportFixtures.Doc()
        val bytes = fixture.build(
            listOf(ExportFixtures.page() to emptyList()),
            rotates = listOf(90)
        )
        ExportFixtures.open(bytes).use { document ->
            val output = ArrayList<ExportedPage>()
            PdfPageExport.export(document, options(), TestRasterizer(), null, { output += it }).getOrThrow()
            assertEquals(792, output.single().pixelWidth)
            assertEquals(612, output.single().pixelHeight)
        }
    }

    @Test
    fun `page ranges and file names`() {
        letterDocument(5).use { document ->
            val output = ArrayList<ExportedPage>()
            PdfPageExport.export(
                document, options(pages = "1-2,5"), TestRasterizer(), null, { output += it }
            ).getOrThrow()
            assertEquals(listOf(0, 1, 4), output.map { it.pageIndex })
            assertEquals(
                listOf("sample-p001.png", "sample-p002.png", "sample-p005.png"),
                output.map { it.fileName }
            )
        }
    }

    @Test
    fun `a page over the pixel cap is refused with a suggestion`() {
        val fixture = ExportFixtures.Doc()
        val huge = fixture.build(
            listOf(ExportFixtures.page(doubleArrayOf(0.0, 0.0, 20_000.0, 20_000.0)) to emptyList())
        )
        ExportFixtures.open(huge).use { document ->
            val error = PdfPageExport.export(
                document, options(dpi = 300), TestRasterizer(), null, {}
            ).exceptionOrNull()
            assertNotNull(error)
            val message = error.message.orEmpty()
            assertTrue(message.contains("83333 × 83333"), "actual: $message")
            assertTrue(message.contains("MP limit"), "actual: $message")
            assertTrue(message.contains("lower DPI"), "actual: $message")
            assertTrue(message.contains("would fit"), "actual: $message")
        }
    }

    @Test
    fun `a custom cap refuses earlier and names the largest DPI that fits`() {
        letterDocument(1).use { document ->
            val error = PdfPageExport.export(
                document, options(dpi = 150, maxPixels = 100_000), TestRasterizer(), null, {}
            ).exceptionOrNull()
            assertNotNull(error)
            assertTrue(error.message.orEmpty().contains("0.1 MP limit"), error.message.orEmpty())
            assertTrue(error.message.orEmpty().contains("dpi would fit"), error.message.orEmpty())
        }
    }

    @Test
    fun `cancelling stops after the current page`() {
        letterDocument(4).use { document ->
            val output = ArrayList<ExportedPage>()
            var calls = 0
            val summary = PdfPageExport.export(
                document, options(), TestRasterizer(), null, { output += it },
                isCancelled = { calls++ > 0 }
            ).getOrThrow()
            assertTrue(summary.cancelled)
            assertEquals(1, summary.exported)
            assertEquals(1, output.size)
        }
    }

    @Test
    fun `JPEG goes through the host encoder and WebP is refused without one`() {
        letterDocument(1).use { document ->
            val output = ArrayList<ExportedPage>()
            PdfPageExport.export(
                document, options(format = ImageFormat.JPEG), TestRasterizer(), StubJpegEncoder(), { output += it }
            ).getOrThrow()
            assertTrue(output.single().fileName.endsWith(".jpg"))
            assertTrue(String(output.single().bytes).startsWith("JPEG:612 x 792 q90"))

            val error = PdfPageExport.export(
                document, options(format = ImageFormat.WEBP), TestRasterizer(), StubJpegEncoder(), {}
            ).exceptionOrNull()
            assertNotNull(error)
            assertTrue(error.message.orEmpty().contains("WebP encoding is not available"))
        }
    }

    @Test
    fun `a page that cannot be rendered fails the run`() {
        letterDocument(2).use { document ->
            val rasterizer = TestRasterizer().apply { failOnPage = 1 }
            val error = PdfPageExport.export(document, options(), rasterizer, null, {}).exceptionOrNull()
            assertNotNull(error)
            assertTrue(error.message.orEmpty().contains("Page 2 could not be rendered"))
        }
    }

    @Test
    fun `bad DPI and empty ranges fail with a message`() {
        letterDocument(2).use { document ->
            val badDpi = PdfPageExport.export(
                document, options(dpi = 0), TestRasterizer(), null, {}
            ).exceptionOrNull()
            assertNotNull(badDpi)
            assertTrue(badDpi.message.orEmpty().contains("DPI must be between"))

            val empty = PageExportOptions(
                documentName = "sample.pdf",
                pages = PageRange.Custom(setOf(9))
            )
            val error = PdfPageExport.export(document, empty, TestRasterizer(), null, {}).exceptionOrNull()
            assertNotNull(error)
            assertTrue(error.message.orEmpty().contains("No pages matched"))
        }
    }

    @Test
    fun `file names are sanitised`() {
        assertEquals("annual-report", PdfPageExport.sanitizeName("annual report.pdf"))
        assertEquals("my_file", PdfPageExport.sanitizeName("dir/my:file.pdf"))
        assertEquals("document", PdfPageExport.sanitizeName(".pdf"))
        assertEquals("sample-p012.png", PdfPageExport.pageFileName("sample.pdf", 12, ImageFormat.PNG))
    }
}
