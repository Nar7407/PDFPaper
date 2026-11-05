package com.pdfpaper.engine.mark

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.layout
import com.pdfpaper.engine.write.WriteFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Watermarks, headers, footers, page numbers and Bates numbers: the marks go
 * on the page, they are listed as optional content, and taking them away
 * leaves the page's own content exactly as it was.
 */
class PdfMarksTest {

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private fun textOf(bytes: ByteArray): List<String> {
        val document = open(bytes)
        document.use {
            return it.pages().map { page -> page.layout().text }
        }
    }

    @Test
    fun aWatermarkIsDrawnOnEveryPage() {
        val original = WriteFixtures.threePageDoc()
        val document = open(original)
        val marked = PdfMarks.applyWatermark(
            document,
            WatermarkOptions(content = WatermarkContent.Text("CONFIDENTIAL"))
        ).getOrThrow()

        val texts = textOf(marked)
        assertEquals(3, texts.size)
        texts.forEach { text ->
            assertTrue("the watermark should be on the page: $text", text.contains("CONFIDENTIAL"))
            assertTrue("the page's own text should stay: $text", text.contains("P"))
        }
    }

    @Test
    fun theWatermarkIsListedAsOptionalContent() {
        val original = WriteFixtures.threePageDoc()
        val document = open(original)
        val marked = PdfMarks.applyWatermark(
            document,
            WatermarkOptions(content = WatermarkContent.Text("CONFIDENTIAL"))
        ).getOrThrow()

        val result = open(marked)
        result.use {
            val catalog = it.resolveRoot() as? PdfDict
            assertTrue("the catalogue must list the group", catalog != null)
            val properties = catalog!!["OCProperties"] as? PdfDict
            assertTrue("the catalogue must have /OCProperties", properties != null)
            val groups = properties!!["OCGs"] as? PdfArray
            assertTrue("the group list must not be empty", groups != null && groups.items.isNotEmpty())
            val group = it.resolve(groups!!.items.first() as com.pdfpaper.engine.model.PdfRef) as? PdfDict
            val name = (group?.get("Name") as? PdfString)?.text()
            assertEquals("PDFPaper watermark", name)
        }
    }

    @Test
    fun removingTheMarksRestoresTheOriginalPageContent() {
        val original = WriteFixtures.threePageDoc()
        val before = open(original).use { document ->
            document.pages().map { page -> page.contents().getOrNull()?.bytes?.toString(Charsets.ISO_8859_1) }
        }

        val document = open(original)
        val marked = PdfMarks.applyWatermark(
            document,
            WatermarkOptions(content = WatermarkContent.Text("CONFIDENTIAL"))
        ).getOrThrow()
        assertFalse("the watermark must change the page", marked.contentEquals(original))

        val markedDocument = open(marked)
        val removed = PdfMarks.removeAdded(markedDocument).getOrThrow()
        val after = open(removed).use { doc ->
            doc.pages().map { page -> page.contents().getOrNull()?.bytes?.toString(Charsets.ISO_8859_1) }
        }
        assertEquals(
            "the original content streams must come back unchanged",
            before,
            after
        )
        val texts = textOf(removed)
        texts.forEach { text ->
            assertFalse("the watermark must be gone: $text", text.contains("CONFIDENTIAL"))
        }
    }

    @Test
    fun removingTheMarksAlsoWorksAfterAHeaderAndBatesNumber() {
        val original = WriteFixtures.threePageDoc()
        val before = open(original).use { document ->
            document.pages().map { page -> page.contents().getOrNull()?.bytes?.toString(Charsets.ISO_8859_1) }
        }
        val document = open(original)
        val withHeader = PdfMarks.applyHeaderFooter(
            document,
            HeaderFooterOptions(footer = "Page {page} of {pages}")
        ).getOrThrow()
        val withBates = PdfMarks.applyBates(
            open(withHeader),
            BatesOptions(prefix = "ABC-", digits = 4)
        ).getOrThrow()
        val removed = PdfMarks.removeAdded(open(withBates)).getOrThrow()
        val after = open(removed).use { doc ->
            doc.pages().map { page -> page.contents().getOrNull()?.bytes?.toString(Charsets.ISO_8859_1) }
        }
        assertEquals(before, after)
    }

    @Test
    fun theHeaderAndFooterUseTheTokens() {
        val original = WriteFixtures.threePageDoc()
        val document = open(original)
        val marked = PdfMarks.applyHeaderFooter(
            document,
            HeaderFooterOptions(
                header = "Report of {filename}",
                footer = "Page {page} of {pages}",
                format = NumberFormat.PAGE_OF_TOTAL
            ),
            fileName = "quarterly.pdf"
        ).getOrThrow()

        val texts = textOf(marked)
        assertTrue(texts[0].contains("Page 1 of 3"))
        assertTrue(texts[1].contains("Page 2 of 3"))
        assertTrue(texts[2].contains("Page 3 of 3"))
        assertTrue(texts[0].contains("Report of quarterly.pdf"))
    }

    @Test
    fun theNumberFormatIsUsedForThePageToken() {
        val original = WriteFixtures.threePageDoc()
        val document = open(original)
        val marked = PdfMarks.applyHeaderFooter(
            document,
            HeaderFooterOptions(footer = "{page}", format = NumberFormat.LOWER_ROMAN)
        ).getOrThrow()
        val texts = textOf(marked)
        assertTrue("page one should be i: ${texts[0]}", texts[0].contains("i"))
        assertTrue("page two should be ii: ${texts[1]}", texts[1].contains("ii"))
    }

    @Test
    fun theFirstPageCanBeSkipped() {
        val original = WriteFixtures.threePageDoc()
        val document = open(original)
        val marked = PdfMarks.applyHeaderFooter(
            document,
            HeaderFooterOptions(footer = "Page {page}", skipFirstPage = true)
        ).getOrThrow()
        val texts = textOf(marked)
        assertFalse("the first page should have no footer: ${texts[0]}", texts[0].contains("Page 1"))
        assertTrue("the second page should have a footer: ${texts[1]}", texts[1].contains("Page 2"))
    }

    @Test
    fun batesNumbersAreStampedInOrder() {
        val original = WriteFixtures.threePageDoc()
        val document = open(original)
        val marked = PdfMarks.applyBates(
            document,
            BatesOptions(prefix = "ABC-", digits = 4, startNumber = 7, suffix = "-X")
        ).getOrThrow()
        val texts = textOf(marked)
        assertTrue(texts[0].contains("ABC-0007-X"))
        assertTrue(texts[1].contains("ABC-0008-X"))
        assertTrue(texts[2].contains("ABC-0009-X"))
    }

    @Test
    fun onlyTheSelectedPagesGetTheMark() {
        val original = WriteFixtures.threePageDoc()
        val document = open(original)
        val marked = PdfMarks.applyWatermark(
            document,
            WatermarkOptions(
                content = WatermarkContent.Text("ODD"),
                pages = PageSelection.Odd
            )
        ).getOrThrow()
        val texts = textOf(marked)
        assertTrue("page one is odd", texts[0].contains("ODD"))
        assertFalse("page two is even", texts[1].contains("ODD"))
        assertTrue("page three is odd", texts[2].contains("ODD"))
    }

    @Test
    fun theWatermarkCanBeDrawnOverTheContent() {
        val original = WriteFixtures.threePageDoc()
        val document = open(original)
        val marked = PdfMarks.applyWatermark(
            document,
            WatermarkOptions(
                content = WatermarkContent.Text("TOP"),
                behindContent = false
            )
        ).getOrThrow()
        val result = open(marked)
        result.use {
            val page = it.pages().first()
            val contents = page.dict["Contents"] as? PdfArray
            assertTrue("the page must have two content streams", contents != null && contents.items.size == 2)
            val last = it.resolve(contents!!.items.last() as com.pdfpaper.engine.model.PdfRef) as? com.pdfpaper.engine.model.PdfStream
            val text = String(last?.data ?: ByteArray(0), Charsets.ISO_8859_1)
            assertTrue("the watermark must come last: $text", text.contains("TOP"))
        }
    }

    @Test
    fun theWatermarkCanBeDrawnUnderTheContent() {
        val original = WriteFixtures.threePageDoc()
        val document = open(original)
        val marked = PdfMarks.applyWatermark(
            document,
            WatermarkOptions(
                content = WatermarkContent.Text("UNDER"),
                behindContent = true
            )
        ).getOrThrow()
        val result = open(marked)
        result.use {
            val page = it.pages().first()
            val contents = page.dict["Contents"] as? PdfArray
            assertTrue(contents != null && contents.items.size == 2)
            val first = it.resolve(contents!!.items.first() as com.pdfpaper.engine.model.PdfRef) as? com.pdfpaper.engine.model.PdfStream
            val text = String(first?.data ?: ByteArray(0), Charsets.ISO_8859_1)
            assertTrue("the watermark must come first: $text", text.contains("UNDER"))
        }
    }

    @Test
    fun theMarkIsMarkedAsOptionalContent() {
        val original = WriteFixtures.threePageDoc()
        val document = open(original)
        val marked = PdfMarks.applyWatermark(
            document,
            WatermarkOptions(content = WatermarkContent.Text("CONFIDENTIAL"))
        ).getOrThrow()
        val result = open(marked)
        result.use {
            val page = it.pages().first()
            val resources = page.resources
            val properties = resources?.get("Properties") as? PdfDict
            assertTrue("the page must carry the property", properties != null)
            val ref = properties!!["PDFPaperWatermark"]
            assertTrue("the property must be there", ref != null)
            val contents = page.dict["Contents"] as? PdfArray
            val first = it.resolve(contents!!.items.first() as com.pdfpaper.engine.model.PdfRef) as? com.pdfpaper.engine.model.PdfStream
            val text = String(first?.data ?: ByteArray(0), Charsets.ISO_8859_1)
            assertTrue(
                "the content must be marked: $text",
                text.contains("/OC /PDFPaperWatermark BDC")
            )
        }
    }
}
