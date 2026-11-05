package com.pdfpaper.app.ui.viewer

import com.pdfpaper.app.ui.search.DEFAULT_REGEX_FLAGS
import com.pdfpaper.app.ui.search.DOCUMENT_CONTEXT_RADIUS
import com.pdfpaper.app.ui.search.DOCUMENT_MAX_MATCHES
import com.pdfpaper.app.ui.search.DOCUMENT_PAGE_TIME_MS
import com.pdfpaper.app.ui.search.DOCUMENT_RUN_TIME_MS
import com.pdfpaper.app.ui.search.RegexRow
import com.pdfpaper.app.ui.search.RegexScope
import com.pdfpaper.app.ui.search.RegexUiState
import com.pdfpaper.app.ui.search.describeRegexTester
import com.pdfpaper.app.ui.search.documentOptions
import com.pdfpaper.app.ui.search.stepRegexIndex
import com.pdfpaper.engine.text.RegexFlag
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure helpers behind the viewer's document-scope regex run (T65 Task 6).
 * The view model body stays thin; everything here is testable without
 * coroutines or a device.
 */
class ViewerRegexModelTest {

    @Test
    fun stepIndexWrapsForwardAndBackward() {
        assertEquals("a fresh run starts at the first row", 0, stepRegexIndex(-1, 3, 1))
        assertEquals("forward wraps from the last row", 0, stepRegexIndex(2, 3, 1))
        assertEquals("backward wraps from the first row", 2, stepRegexIndex(0, 3, -1))
        assertEquals("mid-list stepping is plain", 1, stepRegexIndex(0, 3, 1))
    }

    @Test
    fun stepIndexOnAnEmptyListStaysMinusOne() {
        assertEquals(-1, stepRegexIndex(-1, 0, 1))
        assertEquals(-1, stepRegexIndex(0, 0, -1))
        assertEquals(-1, stepRegexIndex(4, 0, 1))
    }

    @Test
    fun visibleRowsRespectUniqueOnly() {
        val dup = row(page = 1, text = "AB-1234")
        val other = row(page = 2, text = "XY-9")
        val state = RegexUiState(
            scope = RegexScope.DOCUMENT,
            rows = listOf(dup, dup, other),
            uniqueOnly = true
        )
        assertEquals(listOf(dup, other), state.visibleRows)
        assertEquals(state.rows, state.copy(uniqueOnly = false).visibleRows)
    }

    @Test
    fun testerTextCountsMatchesAndTime() {
        assertEquals("3 matches on page 4 · 1.2 s", describeRegexTester(3, 3, 1234))
        assertEquals("1 match on page 1 · 0.3 s", describeRegexTester(1, 0, 300))
        assertEquals("0 matches on page 2 · 0.0 s", describeRegexTester(0, 1, 40))
    }

    @Test
    fun documentOptionsUseTheDocumentBudget() {
        val options = documentOptions(setOf(RegexFlag.MULTILINE))
        assertEquals(setOf(RegexFlag.MULTILINE), options.flags)
        assertEquals(DOCUMENT_MAX_MATCHES, options.maxMatches)
        assertEquals(DOCUMENT_PAGE_TIME_MS, options.timeLimitMs)
        assertEquals(DOCUMENT_RUN_TIME_MS, options.runTimeLimitMs)
        assertEquals(DOCUMENT_CONTEXT_RADIUS, options.contextRadius)
        assertEquals(DEFAULT_REGEX_FLAGS, documentOptions(DEFAULT_REGEX_FLAGS).flags)
    }

    private fun row(page: Int, text: String) = RegexRow(
        fileId = 0L,
        documentUri = "",
        displayName = "doc.pdf",
        pageIndex = page,
        ordinal = 0,
        text = text,
        before = "",
        after = ""
    )
}
