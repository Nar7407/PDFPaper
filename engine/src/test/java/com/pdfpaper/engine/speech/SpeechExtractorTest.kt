package com.pdfpaper.engine.speech

import com.pdfpaper.engine.create.PageOrientation
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.create.PdfMetadata
import com.pdfpaper.engine.create.PdfPageBuilder
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.edit.ImageData
import com.pdfpaper.engine.edit.Raster
import com.pdfpaper.engine.edit.RasterDecoder
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T34: a page becomes spoken paragraphs whose words carry the page rectangles
 * the reader sees highlighted. Reading order, hidden page furniture, headings
 * and a hyphenated line break are the reflow rules (T33); the words and their
 * boxes are new.
 */
class SpeechExtractorTest {

    // ---------------------------------------------------------------- fixtures

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private fun builder(size: PageSize = PageSize.A4): PdfBuilder =
        PdfBuilder(size, PageOrientation.PORTRAIT, PdfMetadata())

    private fun firstPage(bytes: ByteArray): PdfPage = open(bytes).pages().first()

    private fun extract(bytes: ByteArray): SpeechPage =
        SpeechExtractor.extract(firstPage(bytes)).getOrThrow()

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

    private fun punctuation(): ByteArray {
        val pdf = builder()
        val page = pdf.addPage()
        page.drawText("Hello, world! This is a test.", 60.0, 120.0, 10.0)
        page.drawText("Second line: with punctuation?", 60.0, 134.0, 10.0)
        return pdf.build()
    }

    private fun hyphenated(): ByteArray {
        val pdf = builder()
        val page = pdf.addPage()
        page.drawText("The interna-", 60.0, 120.0, 10.0)
        page.drawText("tional standard applies here", 60.0, 134.0, 10.0)
        return pdf.build()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun oneParagraphPerTextBlockInReadingOrder() {
        val page = extract(paragraphs())
        assertEquals(3, page.paragraphs.size)
        assertEquals("Chapter One", page.paragraphs[0].text)
        assertEquals(
            List(4) { "The quick brown fox jumps over the lazy dog" }.joinToString(" "),
            page.paragraphs[1].text
        )
        assertTrue(page.paragraphs[2].text.startsWith("A second paragraph"))
        assertEquals(listOf(0, 1, 2), page.paragraphs.map { it.index })
    }

    @Test
    fun spokenTextCollapsesWhitespaceAndKeepsPunctuation() {
        val page = extract(punctuation())
        assertEquals(1, page.paragraphs.size)
        assertEquals(
            "Hello, world! This is a test. Second line: with punctuation?",
            page.paragraphs[0].text
        )
        assertFalse(page.paragraphs[0].text.contains("  "), "no doubled spaces")
    }

    @Test
    fun eachWordHasRectsInsideItsParagraphBounds() {
        val page = extract(paragraphs())
        val paragraph = page.paragraphs[1]
        assertTrue(paragraph.words.size > 5)
        for (word in paragraph.words) {
            assertTrue(word.rects.isNotEmpty(), "${word.text} has a box")
            for (rect in word.rects) {
                assertTrue(rect.x0 >= paragraph.bounds.left - 0.5, "${word.text} left")
                assertTrue(rect.x1 <= paragraph.bounds.right + 0.5, "${word.text} right")
                assertTrue(rect.y0 >= paragraph.bounds.bottom - 0.5, "${word.text} bottom")
                assertTrue(rect.y1 <= paragraph.bounds.top + 0.5, "${word.text} top")
            }
        }
    }

    @Test
    fun wordOffsetsCoverTheSpokenTextWithoutGaps() {
        val page = extract(paragraphs())
        for (paragraph in page.paragraphs) {
            assertEquals(0, paragraph.words.first().start)
            for (i in 0 until paragraph.words.size - 1) {
                assertEquals(
                    paragraph.words[i].end + 1,
                    paragraph.words[i + 1].start,
                    "one space between ${paragraph.words[i].text} and ${paragraph.words[i + 1].text}"
                )
            }
            assertEquals(paragraph.text.length, paragraph.words.last().end)
            for (word in paragraph.words) {
                assertEquals(word.text, paragraph.text.substring(word.start, word.end))
            }
        }
    }

    @Test
    fun wordAtMapsAnOffsetToItsWord() {
        val page = extract(paragraphs())
        val paragraph = page.paragraphs[1]
        val word = paragraph.words[2]
        assertEquals(word, paragraph.wordAt(word.start))
        assertEquals(word, paragraph.wordAt(word.end - 1))
        assertEquals(null, paragraph.wordAt(-1))
        assertEquals(null, paragraph.wordAt(paragraph.text.length))
    }

    @Test
    fun aHyphenatedLineBreakBecomesOneWordOnTwoLines() {
        val page = extract(hyphenated())
        assertEquals(1, page.paragraphs.size)
        val paragraph = page.paragraphs[0]
        assertEquals("The international standard applies here", paragraph.text)
        val word = paragraph.words.first { it.text == "international" }
        assertEquals(2, word.rects.size)
        assertTrue(
            word.rects[0].y1 < word.rects[1].y0 || word.rects[0].y0 > word.rects[1].y1,
            "the two halves sit on different lines"
        )
    }

    @Test
    fun aPageNumberInTheFooterBandIsNotSpoken() {
        val pdf = builder()
        val page = pdf.addPage()
        block(page, 60.0, 120.0, 5, "Body text of the page that a reader wants")
        page.drawText("12", 290.0, 830.0, 9.0)
        val speech = extract(pdf.build())
        assertTrue(speech.paragraphs.none { it.text.trim() == "12" })
        assertFalse(speech.paragraphs.any { it.text.contains("12") })
    }

    @Test
    fun aHeadingBlockKeepsItsHeadingLevel() {
        val page = extract(paragraphs())
        assertEquals(1, page.paragraphs[0].headingLevel)
        assertEquals(0, page.paragraphs[1].headingLevel)
    }

    @Test
    fun aTwoColumnPageSpeaksColumnOneThenColumnTwo() {
        val page = extract(twoColumn())
        assertEquals(2, page.paragraphs.size)
        assertTrue(page.paragraphs[0].text.startsWith("Left column line number 0"))
        assertTrue(page.paragraphs[0].text.endsWith("Left column line number 5"))
        assertTrue(page.paragraphs[1].text.startsWith("Right column line number 0"))
        assertTrue(page.paragraphs[1].text.endsWith("Right column line number 5"))
    }

    @Test
    fun paragraphFractionsIncreaseDownThePage() {
        val page = extract(paragraphs())
        val fractions = page.paragraphs.map { it.fraction }
        for (i in 0 until fractions.size - 1) {
            assertTrue(fractions[i] < fractions[i + 1], "$fractions")
        }
        assertTrue(fractions.all { it in 0.0..1.0 })
    }

    @Test
    fun aScannedPageReportsScannedAndHasNoParagraphs() {
        val pdf = builder()
        val page = pdf.addPage()
        page.drawImage(image(64, 64), 0.0, 0.0, page.width, page.height)
        val speech = extract(pdf.build())
        assertTrue(speech.scanned)
        assertTrue(speech.paragraphs.isEmpty())
    }

    @Test
    fun aPageOfPicturesOnlyHasNoParagraphs() {
        val pdf = builder()
        val page = pdf.addPage()
        page.drawImage(image(40, 24), 72.0, 200.0, 200.0, 120.0)
        val speech = extract(pdf.build())
        assertFalse(speech.scanned)
        assertTrue(speech.paragraphs.isEmpty())
    }

    @Test
    fun aBlankPageIsEmpty() {
        val pdf = builder()
        pdf.addPage()
        val speech = extract(pdf.build())
        assertFalse(speech.scanned)
        assertTrue(speech.paragraphs.isEmpty())
        assertTrue(speech.headings.isEmpty())
    }

    @Test
    fun headingsAreListedForLevelOneAndTwo() {
        val pdf = builder()
        val page = pdf.addPage()
        block(page, 60.0, 100.0, 4, "The body text of the page runs down the column")
        page.drawText("A level one heading", 60.0, 190.0, 18.0)
        block(page, 60.0, 220.0, 3, "Body text after the first heading of the page")
        page.drawText("A level two heading", 60.0, 290.0, 14.0)
        block(page, 60.0, 320.0, 3, "Body text after the second heading of the page")
        page.drawText("A level three heading", 60.0, 390.0, 12.5)
        block(page, 60.0, 420.0, 3, "Body text after the third heading of the page")

        val speech = extract(pdf.build())
        val titles = speech.headings.map { it.title }
        assertEquals(listOf("A level one heading", "A level two heading"), titles)
        assertEquals(listOf(1, 2), speech.headings.map { it.level })
        for (heading in speech.headings) {
            assertEquals(heading.title, speech.paragraphs[heading.paragraphIndex].text)
        }
    }

    @Test
    fun anUnreadablePageIsReportedAsEmptyAndNeverThrows() {
        // A source that fails after close: the page was never read, so text
        // extraction cannot succeed, and the extractor must report a Result
        // failure instead of throwing.
        val source = object : com.pdfpaper.engine.io.RandomAccessSource {
            private val delegate = ByteArraySource(paragraphs())
            private var closed = false
            override val size: Long get() = delegate.size
            override fun read(offset: Long, length: Int): ByteArray {
                if (closed) throw java.io.IOException("source closed")
                return delegate.read(offset, length)
            }
            override fun close() {
                closed = true
            }
        }
        val document = PdfDocument.open(source).getOrThrow()
        val page = document.pages().first()
        document.close()
        val result = SpeechExtractor.extract(page)
        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        assertTrue(result.getOrThrow().paragraphs.isEmpty())
    }
}
