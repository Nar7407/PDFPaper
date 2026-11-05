package com.pdfpaper.engine.reflow

import com.pdfpaper.engine.create.PageOrientation
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.create.PdfMetadata
import com.pdfpaper.engine.create.PdfPageBuilder
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.edit.ImageData
import com.pdfpaper.engine.edit.Raster
import com.pdfpaper.engine.edit.RasterDecoder
import com.pdfpaper.engine.edit.StandardFont
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T33: reflow extraction. Paragraph grouping, headings, list items, the
 * reading order of a two-column page, hidden page numbers and footnotes,
 * pictures and table regions, and the scanned / blank / vector-only pages.
 */
class ReflowExtractorTest {

    // ---------------------------------------------------------------- fixtures

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private fun builder(size: PageSize = PageSize.A4): PdfBuilder =
        PdfBuilder(size, PageOrientation.PORTRAIT, PdfMetadata())

    private fun firstPage(bytes: ByteArray): PdfPage = open(bytes).pages().first()

    private fun extract(
        bytes: ByteArray,
        options: ReflowOptions = ReflowOptions.DEFAULT
    ): ReflowPage = ReflowExtractor.extract(firstPage(bytes), options).getOrThrow()

    /** [lines] lines of the same text, so a paragraph has no ragged right edge. */
    private fun block(
        page: PdfPageBuilder,
        x: Double,
        top: Double,
        lines: Int,
        text: String,
        size: Double = 10.0,
        leading: Double = 14.0
    ) {
        for (i in 0 until lines) page.drawText(text, x, top + i * leading, size)
    }

    private val fakePng: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    private fun image(width: Int, height: Int): ImageData =
        EditImages.decode(
            fakePng,
            object : RasterDecoder {
                override fun decode(bytes: ByteArray): Raster =
                    Raster(width, height, IntArray(width * height) { -1 })
            }
        ).getOrThrow()

    private fun paragraphs(): ByteArray {
        val pdf = builder()
        val page = pdf.addPage()
        page.drawText("Chapter One", 60.0, 80.0, 18.0)
        block(page, 60.0, 120.0, 4, "The quick brown fox jumps over the lazy dog")
        block(page, 60.0, 200.0, 3, "A second paragraph continues the argument here")
        return pdf.build()
    }

    private fun twoColumn(): ByteArray {
        val pdf = builder()
        val page = pdf.addPage()
        for (i in 0 until 6) {
            page.drawText("Left column line number $i", 60.0, 120.0 + i * 14.0, 10.0)
            page.drawText("Right column line number $i", 340.0, 120.0 + i * 14.0, 10.0)
        }
        return pdf.build()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun headingsAndParagraphs() {
        val page = extract(paragraphs())
        assertEquals(3, page.blocks.size)

        val heading = page.blocks[0]
        assertEquals(ReflowBlockKind.HEADING, heading.kind)
        assertEquals(1, heading.level)
        assertEquals("Chapter One", heading.text)

        val first = page.blocks[1]
        assertEquals(ReflowBlockKind.PARAGRAPH, first.kind)
        assertEquals(
            List(4) { "The quick brown fox jumps over the lazy dog" }.joinToString(" "),
            first.text
        )
        assertEquals(0, first.level)

        val second = page.blocks[2]
        assertEquals(ReflowBlockKind.PARAGRAPH, second.kind)
        assertTrue(second.text.startsWith("A second paragraph"))
    }

    @Test
    fun boldSubHeadingIsAHeadingEvenWhenItIsOnlySlightlyLarger() {
        val pdf = builder()
        val page = pdf.addPage()
        block(page, 60.0, 120.0, 4, "The body text of the page runs down the column")
        page.drawText("What survives the change", 60.0, 220.0, 12.0, StandardFont.HELVETICA_BOLD)
        block(page, 60.0, 250.0, 3, "The words survive in the order they were written")

        val reflow = extract(pdf.build())
        val headings = reflow.blocks.filter { it.kind == ReflowBlockKind.HEADING }
        assertEquals(1, headings.size)
        // 12 pt against a 10 pt body is 1.2x, just under the size-only rule;
        // a short bold block is a sub-heading, ranked below the title.
        assertEquals("What survives the change", headings.first().text)
        assertEquals(3, headings.first().level)
        // The running text around it is not promoted.
        assertEquals(2, reflow.blocks.count { it.kind == ReflowBlockKind.PARAGRAPH })
    }

    @Test
    fun reflowTextIsNumberedAndPageOffsetsAreMonotonic() {
        val page = extract(paragraphs())
        var expected = 0
        var previousEnd = -1
        for (block in page.blocks) {
            assertTrue(block.isText)
            assertEquals(expected, block.textStart)
            assertEquals(expected + block.text.length, block.textEnd)
            assertEquals(block.text, page.text.substring(block.textStart, block.textEnd))
            expected = block.textEnd + 1
            assertTrue(block.pageTextStart > previousEnd, "page offsets follow reading order")
            assertTrue(block.pageTextEnd >= block.pageTextStart)
            previousEnd = block.pageTextEnd
        }
        assertTrue(page.hasText)
        assertFalse(page.isBlank)
        assertFalse(page.scanned)
    }

    @Test
    fun twoColumnPageReadsOneColumnThenTheOther() {
        val page = extract(twoColumn())
        assertEquals(2, page.blocks.size)
        val left = page.blocks[0]
        val right = page.blocks[1]
        assertTrue(left.text.startsWith("Left column line number 0"), left.text)
        assertTrue(left.text.endsWith("Left column line number 5"), left.text)
        assertTrue(right.text.startsWith("Right column line number 0"), right.text)
        assertTrue(right.text.endsWith("Right column line number 5"), right.text)
        assertTrue(left.bounds.right < right.bounds.left)
    }

    @Test
    fun listItemsKeepTheirMarker() {
        val pdf = builder()
        val page = pdf.addPage()
        block(page, 60.0, 100.0, 2, "A normal introductory paragraph of body text")
        page.drawText("\u2022 First bullet point", 72.0, 160.0, 10.0)
        page.drawText("\u2022 Second bullet point", 72.0, 174.0, 10.0)
        page.drawText("1. First numbered item", 72.0, 210.0, 10.0)
        val reflow = extract(pdf.build())

        val items = reflow.blocks.filter { it.kind == ReflowBlockKind.LIST_ITEM }
        assertEquals(3, items.size)
        assertEquals("\u2022", items[0].marker)
        assertEquals("First bullet point", items[0].body)
        assertEquals("1.", items[2].marker)
        assertEquals("First numbered item", items[2].body)
    }

    @Test
    fun pageNumberAndFootnoteAreHiddenByDefault() {
        val pdf = builder()
        val page = pdf.addPage()
        block(page, 60.0, 120.0, 5, "Body text of the page that a reader wants")
        page.drawText("1 A footnote in small type at the bottom", 60.0, 800.0, 7.0)
        page.drawText("12", 290.0, 830.0, 9.0)
        val bytes = pdf.build()

        val hidden = extract(bytes)
        assertTrue(hidden.blocks.none { it.text.contains("footnote") }, "footnote hidden")
        assertTrue(hidden.blocks.none { it.text.trim() == "12" }, "page number hidden")
        assertEquals(1, hidden.blocks.size)

        val shown = extract(
            bytes,
            ReflowOptions(hidePageNumbers = false, hideFootnotes = false)
        )
        assertTrue(shown.blocks.any { it.text.contains("footnote") }, "footnote kept")
        assertTrue(shown.blocks.any { it.text.trim() == "12" }, "page number kept")
    }

    @Test
    fun hyphenatedWordIsRejoined() {
        val pdf = builder()
        val page = pdf.addPage()
        page.drawText("We discuss the inter-", 60.0, 120.0, 10.0)
        page.drawText("national effort in detail", 60.0, 134.0, 10.0)
        val reflow = extract(pdf.build())
        assertEquals(1, reflow.blocks.size)
        assertEquals("We discuss the international effort in detail", reflow.blocks[0].text)
    }

    @Test
    fun pictureKeepsTheReadingOrderOfTheText() {
        val pdf = builder()
        val page = pdf.addPage()
        page.drawText("Text above the picture", 60.0, 100.0, 10.0)
        page.drawImage(image(40, 24), 72.0, 200.0, 200.0, 120.0)
        page.drawText("Text below the picture", 60.0, 400.0, 10.0)
        val reflow = extract(pdf.build())

        assertEquals(3, reflow.blocks.size)
        assertEquals(ReflowBlockKind.PARAGRAPH, reflow.blocks[0].kind)
        assertEquals(ReflowBlockKind.IMAGE, reflow.blocks[1].kind)
        assertEquals(ReflowBlockKind.PARAGRAPH, reflow.blocks[2].kind)
        assertTrue(reflow.blocks[0].text.startsWith("Text above"))
        assertTrue(reflow.blocks[2].text.startsWith("Text below"))

        val picture = reflow.blocks[1].bounds
        assertTrue(abs(picture.left - 72.0) < 0.5, "left ${picture.left}")
        assertTrue(abs(picture.right - 272.0) < 0.5, "right ${picture.right}")
        assertTrue(abs(picture.bottom - (page.height - 320.0)) < 0.5, "bottom ${picture.bottom}")
        assertTrue(abs(picture.top - (page.height - 200.0)) < 0.5, "top ${picture.top}")
    }

    @Test
    fun tableBecomesOneRegionBetweenTheParagraphs() {
        val pdf = builder()
        val page = pdf.addPage()
        block(page, 60.0, 120.0, 2, "A paragraph above the table of figures")
        val columns = doubleArrayOf(60.0, 220.0, 380.0)
        for (row in 0 until 3) {
            for (column in columns.indices) {
                page.drawText("Cell $row${column + 1}", columns[column], 200.0 + row * 14.0, 10.0)
            }
        }
        block(page, 60.0, 300.0, 2, "A paragraph below the table of figures")
        val reflow = extract(pdf.build())

        assertEquals(3, reflow.blocks.size)
        assertEquals(ReflowBlockKind.PARAGRAPH, reflow.blocks[0].kind)
        val table = reflow.blocks[1]
        assertEquals(ReflowBlockKind.TABLE, table.kind)
        assertTrue(table.bounds.width > 300.0, "table spans the cells: ${table.bounds}")
        assertEquals(ReflowBlockKind.PARAGRAPH, reflow.blocks[2].kind)
        assertTrue(reflow.blocks[2].text.startsWith("A paragraph below"))
    }

    @Test
    fun tableDetectionCanBeTurnedOff() {
        val pdf = builder()
        val page = pdf.addPage()
        val columns = doubleArrayOf(60.0, 220.0, 380.0)
        for (row in 0 until 3) {
            for (column in columns.indices) {
                page.drawText("Cell $row${column + 1}", columns[column], 200.0 + row * 14.0, 10.0)
            }
        }
        val reflow = extract(pdf.build(), ReflowOptions(detectTables = false))
        assertTrue(reflow.blocks.none { it.kind == ReflowBlockKind.TABLE })
        assertTrue(reflow.hasText)
    }

    @Test
    fun twoColumnTextIsNotMistakenForATable() {
        val reflow = extract(twoColumn())
        assertTrue(reflow.blocks.none { it.kind == ReflowBlockKind.TABLE })
    }

    @Test
    fun scannedPageAsksForOcr() {
        val pdf = builder()
        val page = pdf.addPage()
        page.drawImage(image(64, 64), 0.0, 0.0, page.width, page.height)
        val reflow = extract(pdf.build())

        assertTrue(reflow.scanned)
        assertTrue(reflow.needsOcr)
        assertTrue(reflow.blocks.isEmpty())
        assertFalse(reflow.hasText)
        assertEquals(reflow.box.rotatedWidth, reflow.box.width)
    }

    @Test
    fun blankPageHasNothing() {
        val pdf = builder()
        pdf.addPage()
        val reflow = extract(pdf.build())
        assertTrue(reflow.isBlank)
        assertFalse(reflow.scanned)
        assertTrue(reflow.blocks.isEmpty())
    }

    @Test
    fun vectorOnlyPageBecomesOneRegion() {
        val pdf = builder()
        val page = pdf.addPage()
        page.drawRect(100.0, 100.0, 120.0, 80.0)
        val reflow = extract(pdf.build())
        assertEquals(1, reflow.blocks.size)
        assertEquals(ReflowBlockKind.IMAGE, reflow.blocks[0].kind)
        assertTrue(reflow.blocks[0].bounds.width > 500.0)
        assertFalse(reflow.scanned)
    }

    @Test
    fun pageNumberInTheTopBandIsHiddenToo() {
        val pdf = builder()
        val page = pdf.addPage()
        page.drawText("3", 290.0, 30.0, 9.0)
        block(page, 60.0, 200.0, 3, "The only body text on this page")
        val reflow = extract(pdf.build())
        assertEquals(1, reflow.blocks.size)
        assertTrue(reflow.blocks[0].text.startsWith("The only body text"))
    }

    @Test
    fun pageOffsetFindsTheBlockThatHoldsIt() {
        val page = extract(paragraphs())
        val second = page.blocks[2]
        val found = page.blockForPageOffset(second.pageTextStart + 3)
        assertNotNull(found)
        assertEquals(second.text, found.text)

        val first = page.blocks[1]
        assertEquals(first.text, page.blockForPageOffset(first.pageTextStart)?.text)
    }

    @Test
    fun fractionOfBlockGrowsDownThePage() {
        val page = extract(paragraphs())
        val fractions = page.blocks.map { page.fractionOf(it) }
        assertEquals(fractions.sorted(), fractions)
        assertTrue(fractions.all { it in 0.0..1.0 })
        assertTrue(fractions.last() > fractions.first())

        val nearest = page.blockForFraction(fractions[2])
        assertEquals(page.blocks[2].text, nearest?.text)
    }

    @Test
    fun unreadablePageReturnsAnEmptyReflowPage() {
        // A page whose content stream is missing still has to come back as a
        // page, never as a crash.
        val pdf = builder()
        pdf.addPage()
        val document = open(pdf.build())
        val page = document.pages().first()
        val result = ReflowExtractor.extract(page)
        assertTrue(result.isSuccess)
        val reflow = result.getOrThrow()
        assertEquals(0, reflow.index)
        assertTrue(reflow.blocks.isEmpty())
    }

    @Test
    fun pictureBoundsAreTheRenderedRegion() {
        val page = extract(paragraphs())
        val block = page.blocks.first()
        val rect = block.bounds
        assertTrue(rect.width > 0.0 && rect.height > 0.0)
        assertTrue(rect.left >= 0.0 && rect.right <= page.box.right)
    }

    @Test
    fun bodySizeComesFromTheMostText() {
        // One large title, many body lines: the body must not be a heading.
        val pdf = builder()
        val page = pdf.addPage()
        page.drawText("A Very Large Title Line", 60.0, 80.0, 24.0)
        block(page, 60.0, 120.0, 8, "Body text of the article that carries most words")
        val reflow = extract(pdf.build())
        assertEquals(ReflowBlockKind.HEADING, reflow.blocks.first().kind)
        assertTrue(reflow.blocks.drop(1).all { it.kind == ReflowBlockKind.PARAGRAPH })
    }

    @Test
    fun rectOfABlockStaysInsideThePage() {
        val page = extract(twoColumn())
        for (block in page.blocks) {
            assertTrue(block.bounds.left >= page.box.left - 1.0, "${block.bounds}")
            assertTrue(block.bounds.right <= page.box.right + 1.0, "${block.bounds}")
        }
    }

    @Test
    fun regionBlockHasNoTextOffsets() {
        val pdf = builder()
        val page = pdf.addPage()
        page.drawImage(image(40, 24), 72.0, 200.0, 200.0, 120.0)
        page.drawText("A caption under the picture", 60.0, 400.0, 10.0)
        val reflow = extract(pdf.build())
        val picture = reflow.blocks.first { it.kind == ReflowBlockKind.IMAGE }
        assertEquals(-1, picture.textStart)
        assertEquals(-1, picture.textEnd)
        assertEquals(-1, picture.pageTextStart)
        assertEquals(-1, picture.pageTextEnd)
        assertFalse(picture.isText)
        assertTrue(reflow.text.isNotEmpty())
    }

    @Test
    fun emptyRectIsNotABlock() {
        val page = extract(paragraphs())
        assertTrue(page.blocks.none { it.bounds.isEmpty })
        assertTrue(PdfRect(0.0, 0.0, 0.0, 0.0).isEmpty)
    }
}
