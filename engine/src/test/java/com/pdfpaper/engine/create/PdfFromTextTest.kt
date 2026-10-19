package com.pdfpaper.engine.create

import com.pdfpaper.engine.edit.Standard14
import com.pdfpaper.engine.edit.StandardFont
import com.pdfpaper.engine.page.page
import com.pdfpaper.engine.page.pageCount
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.extractText
import java.nio.file.Files
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PdfFromTextTest {

    private val options = TextPdfOptions(pageSize = PageSize.A4, margin = 72.0, fontSize = 12.0)

    @Test
    fun wrapsAtWordBoundariesWithoutLosingText() {
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(30)
        val maxWidth = 220.0
        val lines = PdfFromText.wrap(text, maxWidth, StandardFont.HELVETICA, 12.0)

        assertTrue(lines.size > 1)
        for (line in lines) {
            assertTrue(
                Standard14.width(StandardFont.HELVETICA, line, 12.0) <= maxWidth + 1e-6,
                "line is too wide: '$line'"
            )
            assertEquals(line.trim(), line, "line has edge whitespace: '$line'")
        }
        assertEquals(
            text.trim().split(Regex("\\s+")),
            lines.joinToString(" ").split(" ")
        )
    }

    @Test
    fun breaksWordsThatAreWiderThanTheLine() {
        val word = "A".repeat(200)
        val maxWidth = 80.0
        val lines = PdfFromText.wrap(word, maxWidth)

        assertTrue(lines.size > 1)
        assertTrue(lines.all { Standard14.width(StandardFont.HELVETICA, it, 12.0) <= maxWidth + 1e-6 })
        assertEquals(word, lines.joinToString(""))
    }

    @Test
    fun keepsBlankLinesAsVerticalSpace() {
        assertEquals(
            listOf("first line", "", "second line"),
            PdfFromText.wrap("first line\n\nsecond line", 500.0)
        )
        assertEquals(emptyList(), PdfFromText.wrap("", 500.0))
    }

    @Test
    fun createsMultiplePagesAndKeepsAllLines() {
        val count = 200
        val text = (1..count).joinToString("\n") { "L$it" }
        val bytes = PdfFromText.create(text, options)

        openPdf(bytes).use { document ->
            val contentHeight = PageSize.A4.height - 2 * options.margin
            val linesPerPage = (contentHeight / (options.fontSize * options.lineSpacing)).toInt()
            val expectedPages = ceil(count.toDouble() / linesPerPage).toInt()

            assertEquals(expectedPages, document.pageCount())
            assertTrue(document.pageCount() > 1)
            assertTrue(document.pages().all { textRuns(it).isNotEmpty() })

            val runs = document.pages().flatMap { textRuns(it) }
            assertEquals((1..count).map { "L$it" }, runs)
        }
    }

    @Test
    fun drawsBlankLinesWithoutTextRuns() {
        val bytes = PdfFromText.create("first\n\nsecond", options)
        openPdf(bytes).use { document ->
            assertEquals(1, document.pageCount())
            assertEquals(listOf("first", "second"), textRuns(pageOf(document, 0)))
        }
    }

    @Test
    fun emptyTextProducesOneBlankPage() {
        val bytes = PdfFromText.create("", options)
        openPdf(bytes).use { document ->
            assertEquals(1, document.pageCount())
            assertEquals(emptyList(), textRuns(pageOf(document, 0)))
        }
    }

    @Test
    fun landscapeSwapsThePageAndPositionsTheBaseline() {
        val landscape = options.copy(
            orientation = PageOrientation.LANDSCAPE,
            margin = 50.0
        )
        val bytes = PdfFromText.create("Landscape", landscape)
        openPdf(bytes).use { document ->
            assertEqualsList(listOf(0.0, 0.0, 841.89, 595.276), mediaBox(pageOf(document, 0)))

            val run = pageOf(document, 0).extractText().runs.single()
            assertEquals("Landscape", run.text)
            assertClose(50.0, run.x0)
            assertClose(
                PageSize.A4.width - (50.0 + Standard14.ascent(StandardFont.HELVETICA, 12.0)),
                run.originY
            )
        }
    }

    @Test
    fun usesTheChosenStandardFont() {
        val bytes = PdfFromText.create(
            "Times",
            options.copy(font = StandardFont.TIMES_ROMAN, fontSize = 16.0)
        )
        openPdf(bytes).use { document ->
            val run = pageOf(document, 0).extractText().runs.single()
            assertEquals("Times", run.text)
            assertEquals(16.0, run.fontSize)
        }
        val raw = bytes.toString(Charsets.ISO_8859_1)
        assertTrue(raw.contains("/BaseFont /Times-Roman"))
    }

    @Test
    fun createFileReadsUtf8AndReturnsFailures() {
        val file = Files.createTempFile("pdfpaper-text", ".txt").toFile()
        file.deleteOnExit()
        // Windows editors and Set-Content often leave a UTF-8 BOM; it must not
        // turn into a leading '?' glyph.
        file.writeBytes(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                "Grüße\naus PDFPaper\n".toByteArray(Charsets.UTF_8)
        )

        val result = PdfFromText.createFile(file, options)
        assertTrue(result.isSuccess)
        openPdf(result.getOrThrow()).use { document ->
            assertEquals(listOf("Grüße", "aus PDFPaper"), textRuns(pageOf(document, 0)))
        }

        val missing = PdfFromText.createFile(file.resolveSibling("missing.txt"), options)
        assertTrue(missing.isFailure)
        assertNotNull(missing.exceptionOrNull())
    }
}
