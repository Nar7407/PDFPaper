package com.pdfpaper.engine.ocr

import com.pdfpaper.engine.content.parseContent
import com.pdfpaper.engine.edit.PdfEditOps
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.buildDisplayList
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.text.TextRect
import com.pdfpaper.engine.text.extractText
import com.pdfpaper.engine.text.layout
import com.pdfpaper.engine.text.searchText
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T25 acceptance: makeSearchable adds invisible word runs whose boxes match
 * the provider's recognized boxes, skips pages that already have text, reports
 * confidence and failures, and keeps the original scan. Search and extraction
 * operate on the saved file, not on in-memory state.
 *
 * The fixtures are hand-built: a 100×50 gray scan drawn at [100,200]–[300,300]
 * (2 page units per sample), with optional visible text on the same or another
 * page.
 */
class PdfOcrTest {

    // ------------------------------------------------------------- fixtures

    private fun streamObj(num: Int, content: String): TestObj {
        val data = content.toByteArray(StandardCharsets.ISO_8859_1)
        val body = ByteArrayOutputStream()
        body.write("<< /Length ${data.size} >>\nstream\n".toByteArray(StandardCharsets.US_ASCII))
        body.write(data)
        body.write("\nendstream".toByteArray(StandardCharsets.US_ASCII))
        return TestObj(num, 0, body.toByteArray())
    }

    private fun formObj(num: Int, dict: String, content: String): TestObj {
        val data = content.toByteArray(StandardCharsets.ISO_8859_1)
        val body = ByteArrayOutputStream()
        body.write(("<< $dict /Length ${data.size} >>\nstream\n").toByteArray(StandardCharsets.US_ASCII))
        body.write(data)
        body.write("\nendstream".toByteArray(StandardCharsets.US_ASCII))
        return TestObj(num, 0, body.toByteArray())
    }

    private fun imageObj(num: Int, width: Int, height: Int): TestObj {
        val head = ("<< /Type /XObject /Subtype /Image /Width $width /Height $height " +
            "/ColorSpace /DeviceGray /BitsPerComponent 8 /Length ${width * height} >>\nstream\n")
        val body = ByteArrayOutputStream()
        body.write(head.toByteArray(StandardCharsets.US_ASCII))
        body.write(ByteArray(width * height) { 0x80.toByte() })
        body.write("\nendstream".toByteArray(StandardCharsets.US_ASCII))
        return TestObj(num, 0, body.toByteArray())
    }

    private val imageContent = "q 200 0 0 100 100 200 cm /Im0 Do Q"
    private val textContent = "BT /F1 12 Tf 72 700 Td (Visible) Tj ET"
    private val fontObj = TestObj(
        8, 0,
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
    )

    /** One page whose only content is the scan. */
    private fun scanPdf(): ByteArray = buildTestPdf(
        listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /XObject << /Im0 6 0 R >> >> /Contents 5 0 R >>"
            ),
            streamObj(5, imageContent),
            imageObj(6, 100, 50)
        )
    )

    /** Page 1 has visible text only, page 2 has the scan. */
    private fun textAndScanPdf(): ByteArray = buildTestPdf(
        listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>"),
            TestObj(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /Font << /F1 8 0 R >> >> /Contents 7 0 R >>"
            ),
            TestObj(
                4, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /XObject << /Im0 6 0 R >> >> /Contents 5 0 R >>"
            ),
            streamObj(5, imageContent),
            imageObj(6, 100, 50),
            streamObj(7, textContent),
            fontObj
        )
    )

    /** One page with both visible text and a scan. */
    private fun textAndScanOnOnePagePdf(): ByteArray = buildTestPdf(
        listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /Font << /F1 7 0 R >> /XObject << /Im0 6 0 R >> >> " +
                    "/Contents 5 0 R >>"
            ),
            streamObj(5, "$imageContent $textContent"),
            imageObj(6, 100, 50),
            TestObj(
                7, 0,
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
            )
        )
    )

    /** Two pages, each with the same scan. */
    private fun twoScanPdf(): ByteArray = buildTestPdf(
        listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>"),
            TestObj(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /XObject << /Im0 5 0 R >> >> /Contents 6 0 R >>"
            ),
            TestObj(
                4, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /XObject << /Im1 7 0 R >> >> /Contents 8 0 R >>"
            ),
            imageObj(5, 100, 50),
            streamObj(6, imageContent),
            imageObj(7, 100, 50),
            streamObj(8, imageContent.replace("Im0", "Im1"))
        )
    )

    /** One page with no image at all. */
    private fun blankPdf(): ByteArray = buildTestPdf(
        listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>")
        )
    )

    /** The scan is inside a form XObject, drawn at page CTM [2 0 0 1 50 100]. */
    private fun formScanPdf(): ByteArray = buildTestPdf(
        listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /XObject << /Fm0 5 0 R >> >> /Contents 4 0 R >>"
            ),
            streamObj(4, "q 2 0 0 1 50 100 cm /Fm0 Do Q"),
            formObj(
                5,
                "/Type /XObject /Subtype /Form /BBox [0 0 1 1] " +
                    "/Matrix [200 0 0 100 0 0] /Resources << /XObject << /Im0 6 0 R >> >>",
                "/Im0 Do"
            ),
            imageObj(6, 100, 50)
        )
    )

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private class RecordingProvider(
        private val answer: (OcrImage, String) -> List<OcrWord>
    ) : OcrProvider {
        val calls = ArrayList<Pair<Int, Int>>()
        val languages = ArrayList<String>()
        var lastImage: OcrImage? = null

        override fun recognize(image: OcrImage, language: String): List<OcrWord> {
            calls += image.width to image.height
            languages += language
            lastImage = image
            return answer(image, language)
        }
    }

    private val hello = OcrWord("Hello", OcrBounds(10.0, 10.0, 60.0, 30.0), 0.9)
    private val world = OcrWord("World", OcrBounds(70.0, 12.0, 95.0, 28.0), 0.7)

    /** Page box for [bounds] in a 100×50 scan placed at [placement]. */
    private fun expected(placement: PdfRect, bounds: OcrBounds): TextRect = TextRect(
        placement.left + bounds.left / 100.0 * placement.width,
        placement.top - bounds.bottom / 50.0 * placement.height,
        placement.left + bounds.right / 100.0 * placement.width,
        placement.top - bounds.top / 50.0 * placement.height
    )

    // ------------------------------------------------------------- tests

    @Test
    fun addsInvisibleWordsAtRecognizedPositions() {
        val provider = RecordingProvider { _, _ -> listOf(hello, world) }
        val document = open(scanPdf())
        val result = PdfOcr.makeSearchable(document, provider, "eng+hin").getOrThrow()
        document.close()

        val image = assertNotNull(provider.lastImage)
        assertEquals(100, image.width)
        assertEquals(50, image.height)
        assertNotNull(image.pixels, "Flate scan should decode to RGBA samples")
        assertEquals(listOf("eng+hin"), provider.languages)

        val report = result.pages.single()
        assertEquals(OcrPageStatus.ADDED, report.status)
        assertEquals(2, report.wordCount)
        assertEquals(100, report.imageWidth)
        assertEquals(50, report.imageHeight)
        assertEquals(0.8, report.meanConfidence!!, 1e-9)
        assertEquals(0.7, report.minConfidence!!, 1e-9)

        open(result.bytes).use { saved ->
            val page = saved.pages().single()
            val placement = PdfEditOps.listImages(saved, 0).single().bounds
            assertEquals(PdfRect(100.0, 200.0, 300.0, 300.0), placement)
            // The original scan is still drawn.
            assertEquals(1, page.buildDisplayList().images.size)

            val runs = page.extractText().runs
            assertEquals(2, runs.size)
            assertEquals(listOf("Hello", "World"), runs.map { it.text })
            val expectedHello = expected(placement, hello.bounds)
            assertEquals(expectedHello.x0, runs[0].x0, 0.001)
            assertEquals(expectedHello.y0, runs[0].y0, 0.001)
            assertEquals(expectedHello.x1, runs[0].x1, 0.001)
            assertEquals(expectedHello.y1, runs[0].y1, 0.001)
            assertEquals(40.0, runs[0].fontSize, 0.001) // 20 samples × 2 units
            assertEquals(3, runs[0].renderMode)

            val expectedWorld = expected(placement, world.bounds)
            assertEquals(expectedWorld.x0, runs[1].x0, 0.001)
            assertEquals(expectedWorld.y0, runs[1].y0, 0.001)
            assertEquals(expectedWorld.x1, runs[1].x1, 0.001)
            assertEquals(expectedWorld.y1, runs[1].y1, 0.001)
            assertEquals(32.0, runs[1].fontSize, 0.001)

            // Layout joins the words and search finds both a word and a phrase.
            assertEquals("Hello World", page.layout().text)
            val wordMatches = saved.searchText("hello")
            assertEquals(1, wordMatches.size)
            assertEquals(0, wordMatches[0].pageIndex)
            assertEquals(1, saved.searchText("Hello World").size)

            // The appended stream really uses text render mode 3.
            val contentBytes = page.contents().getOrNull()?.bytes ?: ByteArray(0)
            val commands = parseContent(contentBytes).commands
            assertTrue(
                commands.any {
                    it.operator == "Tr" && (it.operands.singleOrNull() as? PdfInteger)?.value == 3L
                }
            )
        }
    }

    @Test
    fun keepsUnicodeTextSearchable() {
        val hindi = OcrWord("\u0915\u0932\u092E", OcrBounds(20.0, 10.0, 60.0, 30.0), 0.99)
        val provider = RecordingProvider { _, _ -> listOf(hindi) }
        val document = open(scanPdf())
        val result = PdfOcr.makeSearchable(document, provider, "eng+hin").getOrThrow()
        document.close()

        open(result.bytes).use { saved ->
            val page = saved.pages().single()
            assertEquals(listOf("\u0915\u0932\u092E"), page.extractText().runs.map { it.text })
            assertEquals(1, saved.searchText("\u0915\u0932\u092E").size)
            val run = page.extractText().runs.single()
            assertTrue(run.x0 < run.x1 && run.y0 < run.y1)
        }
    }

    @Test
    fun skipsPagesThatAlreadyHaveText() {
        val provider = RecordingProvider { _, _ -> listOf(hello) }
        val document = open(textAndScanPdf())
        val result = PdfOcr.makeSearchable(document, provider).getOrThrow()
        document.close()

        assertEquals(2, result.pages.size)
        assertEquals(OcrPageStatus.SKIPPED_HAS_TEXT, result.pages[0].status)
        assertEquals(OcrPageStatus.ADDED, result.pages[1].status)
        assertEquals(1, provider.calls.size)

        open(result.bytes).use { saved ->
            assertEquals("Visible", saved.pages()[0].layout().text)
            assertEquals(1, saved.searchText("Hello").size)
        }
    }

    @Test
    fun selectedPagesAreProcessedEvenWhenTheyHaveText() {
        val provider = RecordingProvider { _, _ -> listOf(hello) }
        val document = open(textAndScanOnOnePagePdf())

        val skipped = PdfOcr.makeSearchable(document, provider).getOrThrow()
        assertEquals(OcrPageStatus.SKIPPED_HAS_TEXT, skipped.pages.single().status)
        assertEquals(0, provider.calls.size)

        val forced = PdfOcr.makeSearchable(document, provider, pages = listOf(0)).getOrThrow()
        document.close()
        assertEquals(OcrPageStatus.ADDED, forced.pages.single().status)
        assertEquals(1, provider.calls.size)

        open(forced.bytes).use { saved ->
            assertTrue(saved.pages().single().layout().text.contains("Visible"))
            assertEquals(1, saved.searchText("Hello").size)
        }
    }

    @Test
    fun reportsProviderFailureWithoutAborting() {
        val provider = RecordingProvider { _, _ -> throw IllegalStateException("boom") }
        val document = open(scanPdf())
        val result = PdfOcr.makeSearchable(document, provider).getOrThrow()
        document.close()

        val report = result.pages.single()
        assertEquals(OcrPageStatus.PROVIDER_FAILED, report.status)
        assertEquals(0, report.wordCount)
        assertTrue(report.problems.single().contains("boom"))

        open(result.bytes).use { saved ->
            assertTrue(saved.searchText("Hello").isEmpty())
            assertEquals(1, saved.pages().single().buildDisplayList().images.size)
        }
    }

    @Test
    fun reportsPagesWithoutImages() {
        val provider = RecordingProvider { _, _ -> listOf(hello) }
        val document = open(blankPdf())
        val result = PdfOcr.makeSearchable(document, provider).getOrThrow()
        document.close()

        assertEquals(OcrPageStatus.NO_IMAGE, result.pages.single().status)
        assertEquals(0, provider.calls.size)
        open(result.bytes).use { saved -> assertTrue(saved.pages().size == 1) }
    }

    @Test
    fun reportsNoWordsAndDoesNotAddAFont() {
        val provider = RecordingProvider { _, _ -> emptyList() }
        val document = open(scanPdf())
        val result = PdfOcr.makeSearchable(document, provider).getOrThrow()
        document.close()

        assertEquals(OcrPageStatus.NO_WORDS, result.pages.single().status)
        assertEquals(0, result.wordsAdded)
        open(result.bytes).use { saved ->
            val page = saved.pages().single()
            assertTrue(page.extractText().runs.isEmpty())
            val fonts = page.resources?.get("Font") as? PdfDict
            assertFalse(fonts?.entries?.keys?.any { it.name.startsWith("PpO") } == true)
        }
    }

    @Test
    fun dropsBlankAndInvertedWords() {
        val provider = RecordingProvider { _, _ ->
            listOf(
                OcrWord("   ", OcrBounds(10.0, 10.0, 20.0, 20.0), 0.9),
                OcrWord("Bad", OcrBounds(30.0, 20.0, 20.0, 10.0), 0.9)
            )
        }
        val document = open(scanPdf())
        val result = PdfOcr.makeSearchable(document, provider).getOrThrow()
        document.close()

        assertEquals(OcrPageStatus.NO_WORDS, result.pages.single().status)
    }

    @Test
    fun findsScansInsideFormXObjects() {
        val provider = RecordingProvider { _, _ -> listOf(hello) }
        val document = open(formScanPdf())
        val result = PdfOcr.makeSearchable(document, provider).getOrThrow()
        document.close()

        assertEquals(OcrPageStatus.ADDED, result.pages.single().status)
        open(result.bytes).use { saved ->
            val run = saved.pages().single().extractText().runs.single()
            assertEquals("Hello", run.text)
            // CTM = [2 0 0 1 50 100] × form [200 0 0 100 0 0] = [400 0 0 100 50 100].
            assertEquals(90.0, run.x0, 0.001) // 50 + 400 × 10/100
            assertEquals(140.0, run.y0, 0.001) // 100 + 100 × (1 − 30/50)
            assertEquals(290.0, run.x1, 0.001) // 50 + 400 × 60/100
            assertEquals(180.0, run.y1, 0.001) // 100 + 100 × (1 − 10/50)
            assertEquals(40.0, run.fontSize, 0.001)
        }
    }

    @Test
    fun sharesOneFontAcrossPages() {
        val provider = RecordingProvider { _, _ ->
            listOf(OcrWord("Word", OcrBounds(10.0, 10.0, 40.0, 30.0), 0.95))
        }
        val document = open(twoScanPdf())
        val result = PdfOcr.makeSearchable(document, provider).getOrThrow()
        document.close()

        assertEquals(2, result.pagesAdded)
        open(result.bytes).use { saved ->
            val refs = saved.pages().map { page ->
                val fonts = page.resources!!.get("Font") as PdfDict
                val name = fonts.entries.keys.single { it.name.startsWith("PpO") }
                fonts.entries.getValue(name) as PdfRef
            }
            assertEquals(1, refs.map { it.objNum }.distinct().size)
            assertEquals(2, saved.searchText("Word").size)
        }
    }
}
