package com.pdfpaper.engine.text

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
 * T11 acceptance: reading order (single- and multi-column), case-insensitive
 * search hits with bounding boxes, and selection rectangles. Runs are built
 * directly for the layout tests; two small real PDFs cover the end-to-end path
 * (page.layout() / document.searchText()).
 */
class TextLayoutTest {

    // ------------------------------------------------------------- helpers

    /** A horizontal run at (x, baseline); advance defaults to 6pt per char. */
    private fun run(
        text: String,
        x: Double,
        baseline: Double,
        size: Double = 12.0,
        advance: Double = text.length * 6.0
    ): PdfTextRun = PdfTextRun(text, size, "F1", x, baseline, x + advance, baseline + size)

    private fun layout(vararg runs: PdfTextRun): PageLayout =
        pageLayout(PageText(runs.toList(), emptyList()))

    private fun streamObj(num: Int, dict: String, content: String): TestObj {
        val data = content.toByteArray(StandardCharsets.ISO_8859_1)
        val body = ("<< /Length ${data.size} $dict>>\nstream\n").toByteArray(StandardCharsets.US_ASCII) +
            data + "\nendstream".toByteArray(StandardCharsets.US_ASCII)
        return TestObj(num, 0, body)
    }

    private val fontNumber = 100

    /** Helvetica stand-in: every code 32..126 is 0.5 em wide, so 12pt ⇒ 6pt. */
    private fun fontObj(): TestObj {
        val widths = (32..126).joinToString(" ") { "500" }
        return TestObj(
            fontNumber, 0,
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding " +
                "/FirstChar 32 /Widths [ $widths ] >>"
        )
    }

    private fun pdfWith(contents: List<String>): PdfDocument {
        val objects = ArrayList<TestObj>()
        val kids = ArrayList<String>()
        var number = 3
        for (unused in contents) {
            kids += "$number 0 R"
            number += 2
        }
        objects += TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>")
        objects += TestObj(2, 0, "<< /Type /Pages /Kids [ ${kids.joinToString(" ")} ] /Count ${contents.size} >>")
        number = 3
        for (content in contents) {
            objects += TestObj(
                number, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /Font << /F1 $fontNumber 0 R >> >> /Contents ${number + 1} 0 R >>"
            )
            objects += streamObj(number + 1, "", content)
            number += 2
        }
        objects += fontObj()
        return PdfDocument.open(ByteArraySource(buildTestPdf(objects))).getOrThrow()
    }

    // -------------------------------------------------------- reading order

    @Test
    fun aSpaceIsInsertedBetweenRunsSeparatedByAGap() {
        val layout = layout(run("Hello", 50.0, 700.0), run("World", 86.0, 700.0))
        assertEquals(1, layout.lines.size)
        assertEquals("Hello World", layout.text)
    }

    @Test
    fun adjacentRunsJoinWithoutASpace() {
        // TJ split: "Hel" + "lo" touch (kerning pulls them together).
        val layout = layout(run("Hel", 50.0, 700.0, advance = 18.0), run("lo", 68.0, 700.0, advance = 12.0))
        assertEquals("Hello", layout.text)
    }

    @Test
    fun linesAreReadTopToBottom() {
        val layout = layout(run("First", 50.0, 700.0), run("Second", 50.0, 686.0))
        assertEquals(listOf("First", "Second"), layout.lines.map { it.text })
        assertEquals("First\nSecond", layout.text)
    }

    @Test
    fun runsAreSortedLeftToRightRegardlessOfInputOrder() {
        val layout = layout(run("World", 86.0, 700.0), run("Hello", 50.0, 700.0))
        assertEquals("Hello World", layout.text)
    }

    @Test
    fun aWideGapOnOneLineIsNotTreatedAsAColumn() {
        // 30pt gap (> 1.5 em) but only one line tall ⇒ not a gutter.
        val layout = layout(
            run("Chapter 1", 50.0, 700.0, advance = 54.0),
            run("Page 5", 134.0, 700.0, advance = 36.0)
        )
        assertEquals(1, layout.lines.size)
        assertEquals("Chapter 1 Page 5", layout.text)
    }

    @Test
    fun multiColumnIsReadColumnMajor() {
        // Content order is interleaved; reading order must be column by column.
        val layout = layout(
            run("Left-1", 50.0, 700.0, advance = 36.0),
            run("Right-1", 330.0, 700.0, advance = 42.0),
            run("Left-2", 50.0, 680.0, advance = 36.0),
            run("Right-2", 330.0, 680.0, advance = 42.0)
        )
        assertEquals(listOf("Left-1", "Left-2", "Right-1", "Right-2"), layout.lines.map { it.text })
    }

    // --------------------------------------------------------------- search

    @Test
    fun searchIsCaseInsensitiveAndReportsTheBoundingBox() {
        val layout = layout(run("Hello World", 50.0, 700.0))
        val matches = layout.search("WORLD")
        assertEquals(1, matches.size)
        assertEquals(6, matches[0].start)
        assertEquals(11, matches[0].end)
        val rect = matches[0].rects.single()
        // "Hello " is 6 of 11 chars of a 66pt-wide run starting at x=50.
        assertEquals(86.0, rect.x0, 1e-9)
        assertEquals(116.0, rect.x1, 1e-9)
        assertEquals(700.0, rect.y0, 1e-9)
        assertEquals(712.0, rect.y1, 1e-9)
    }

    @Test
    fun searchFindsEveryOccurrenceOnALine() {
        val layout = layout(run("aXbXc", 10.0, 100.0))
        val matches = layout.search("x")
        assertEquals(listOf(1, 3), matches.map { it.start })
        assertEquals(16.0, matches[0].rects.single().x0, 1e-9)
        assertEquals(28.0, matches[1].rects.single().x0, 1e-9)
        assertEquals(22.0, matches[0].rects.single().x1, 1e-9)
    }

    @Test
    fun searchReturnsNothingForBlankOrMissingText() {
        val layout = layout(run("Hello", 50.0, 700.0))
        assertTrue(layout.search("").isEmpty())
        assertTrue(layout.search("zzz").isEmpty())
    }

    @Test
    fun matchAcrossRunsMapsToEachRunFragment() {
        val layout = layout(run("Hello", 50.0, 700.0), run("World", 86.0, 700.0))
        val match = layout.search("lo Wo").single()
        assertEquals(2, match.rects.size)
        assertEquals(68.0, match.rects[0].x0, 1e-9) // "lo"
        assertEquals(86.0, match.rects[1].x0, 1e-9) // "Wo"
    }

    // ------------------------------------------------------------ selection

    @Test
    fun selectionRectsCoverPartialRuns() {
        val layout = layout(run("Hello", 50.0, 700.0), run("World", 86.0, 700.0))
        val rects = layout.rectsFor(3, 8)
        assertEquals(2, rects.size)
        assertEquals(68.0, rects[0].x0, 1e-9) // "lo"
        assertEquals(80.0, rects[0].x1, 1e-9)
        assertEquals(86.0, rects[1].x0, 1e-9) // "Wo"
        assertEquals(98.0, rects[1].x1, 1e-9)
    }

    @Test
    fun selectionRectsSpanLines() {
        val layout = layout(run("First", 50.0, 700.0), run("Second", 50.0, 686.0))
        val rects = layout.rectsFor(2, 8) // "rst" of First + "Se" of Second
        assertEquals(2, rects.size)
        assertEquals(62.0, rects[0].x0, 1e-9)
        assertEquals(80.0, rects[0].x1, 1e-9)
        assertEquals(50.0, rects[1].x0, 1e-9)
        assertEquals(62.0, rects[1].x1, 1e-9)
    }

    @Test
    fun selectionOfTheWholePageCoversEveryRun() {
        val layout = layout(run("Hello", 50.0, 700.0), run("World", 86.0, 700.0))
        assertEquals(2, layout.rectsFor(0, layout.text.length).size)
        assertTrue(layout.rectsFor(5, 5).isEmpty())
        assertTrue(layout.rectsFor(9, 3).isEmpty())
    }

    @Test
    fun wordAtFindsTheWordUnderAPoint() {
        val layout = layout(run("Hello", 50.0, 700.0), run("World", 86.0, 700.0))
        assertEquals(6..10, layout.wordAt(90.0, 705.0))
        assertEquals(0..4, layout.wordAt(52.0, 705.0))
    }

    @Test
    fun wordRangeAtASeparatorReturnsTheNearestWord() {
        val layout = layout(run("Hello", 50.0, 700.0), run("World", 86.0, 700.0))
        assertEquals(6..10, layout.wordRangeAt(5)) // the inserted space
    }

    @Test
    fun emptyLayoutIsSafe() {
        val layout = pageLayout(PageText(emptyList(), emptyList()))
        assertTrue(layout.isEmpty)
        assertEquals("", layout.text)
        assertTrue(layout.search("a").isEmpty())
        assertTrue(layout.rectsFor(0, 1).isEmpty())
        assertEquals(IntRange.EMPTY, layout.wordAt(10.0, 10.0))
        assertEquals(IntRange.EMPTY, layout.wordRangeAt(3))
    }

    // ----------------------------------------------------------- end to end

    @Test
    fun pdfMultiColumnPageIsLaidOutInReadingOrder() {
        // Content order interleaves the columns; positions put them side by side.
        val content = "BT /F1 12 Tf 1 0 0 1 50 700 Tm (Left-1) Tj ET\n" +
            "BT /F1 12 Tf 1 0 0 1 330 700 Tm (Right-1) Tj ET\n" +
            "BT /F1 12 Tf 1 0 0 1 50 680 Tm (Left-2) Tj ET\n" +
            "BT /F1 12 Tf 1 0 0 1 330 680 Tm (Right-2) Tj ET"
        pdfWith(listOf(content)).use { doc ->
            val layout = doc.pages().first().layout()
            assertEquals(
                listOf("Left-1", "Left-2", "Right-1", "Right-2"),
                layout.lines.map { it.text }
            )
            val match = layout.search("RIGHT-1").single()
            val rect = match.rects.single()
            assertEquals(330.0, rect.x0, 1e-9)
            assertEquals(372.0, rect.x1, 1e-9) // 7 chars × 6pt
        }
    }

    @Test
    fun documentSearchReturnsPageOrderedMatches() {
        val pageOne = "BT /F1 12 Tf 1 0 0 1 50 700 Tm (Beta Alpha) Tj ET"
        val pageTwo = "BT /F1 12 Tf 1 0 0 1 50 700 Tm (Alpha only) Tj ET"
        pdfWith(listOf(pageOne, pageTwo)).use { doc ->
            val matches = doc.searchText("alpha")
            assertEquals(listOf(0, 1), matches.map { it.pageIndex })
            assertEquals(listOf(5, 0), matches.map { it.start })
        }
    }
}
