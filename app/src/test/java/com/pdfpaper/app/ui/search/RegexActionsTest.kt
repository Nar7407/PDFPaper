package com.pdfpaper.app.ui.search

import com.pdfpaper.engine.annot.HighlightSpec
import com.pdfpaper.engine.text.TextRect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T65 Task 8: the pure mapping from regex rows to highlight annotations. Rows
 * without boxes (library scope, or a page whose layout changed) are skipped
 * and counted, never written as zero-size highlights.
 */
class RegexActionsTest {

    @Test
    fun oneSpecPerRowWithBoxes() {
        val rows = listOf(
            row(page = 0, rects = listOf(TextRect(10.0, 20.0, 30.0, 40.0), TextRect(1.0, 2.0, 3.0, 4.0))),
            row(page = 1, rects = listOf(TextRect(5.0, 6.0, 7.0, 8.0)))
        )
        val specs = RegexActions.highlightsFor(rows, author = "reader")
        assertEquals(2, specs.size)
        assertEquals(2, specs[0].rects.size)
        assertEquals(1, specs[1].rects.size)
        assertEquals(0, specs[0].pageIndex)
        assertEquals(1, specs[1].pageIndex)
    }

    @Test
    fun rowsWithoutBoxesAreCountedAndSkipped() {
        val rows = listOf(row(page = 0, rects = emptyList()), row(page = 1, rects = listOf(TextRect(1.0, 2.0, 3.0, 4.0))))
        val specs = RegexActions.highlightsFor(rows, author = "reader")
        assertEquals(1, specs.size)
        assertEquals(1, RegexActions.skippedWithoutBoxes(rows))
    }

    @Test
    fun capDropsTheTail() {
        val rows = (0 until 5).map { row(page = it, rects = listOf(TextRect(1.0, 2.0, 3.0, 4.0))) }
        val specs = RegexActions.highlightsFor(rows, author = "reader", maxHighlights = 3)
        assertEquals(3, specs.size)
        assertEquals(0, RegexActions.skippedWithoutBoxes(rows))
        // The cap keeps the head of the list, in reading order.
        assertEquals(0, specs.first().pageIndex)
        assertEquals(2, specs.last().pageIndex)
    }

    @Test
    fun contentsAndTitleAreCarried() {
        val rows = listOf(row(page = 0, text = "AB-1234", rects = listOf(TextRect(1.0, 2.0, 3.0, 4.0))))
        val specs = RegexActions.highlightsFor(rows, author = "")
        assertEquals(1, specs.size)
        assertEquals("AB-1234", specs[0].contents)
        assertEquals("PDFPaper regex", specs[0].title)
        val titled = RegexActions.highlightsFor(rows, author = "Nina")
        assertEquals("Nina", titled[0].title)
    }

    private fun row(
        page: Int,
        text: String = "AB-1234",
        rects: List<TextRect>
    ) = RegexRow(
        fileId = 0L,
        documentUri = "",
        displayName = "doc.pdf",
        pageIndex = page,
        ordinal = 0,
        text = text,
        before = "",
        after = "",
        rects = rects
    )
}
