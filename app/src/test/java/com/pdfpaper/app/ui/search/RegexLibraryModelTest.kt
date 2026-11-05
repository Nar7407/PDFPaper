package com.pdfpaper.app.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T65 Task 5: the library scope's pure rules. The DAO, the repository and the
 * view model need Room and a real document, so the device test covers them;
 * everything here is a plain function over text.
 */
class RegexLibraryModelTest {

    @Test
    fun optionsForLibraryUseTheLibraryBudget() {
        val options = libraryOptions(DEFAULT_REGEX_FLAGS)

        assertEquals(LIBRARY_MAX_MATCHES, options.maxMatches)
        assertEquals(LIBRARY_RUN_TIME_MS, options.runTimeLimitMs)
        // The per-page deadline is the shared one: a library run may take
        // longer overall, but no single page may hang it.
        assertEquals(1_500L, options.timeLimitMs)
        assertEquals(DEFAULT_REGEX_FLAGS, options.flags)
    }

    @Test
    fun rowsFromStoredTextHaveNoRectsAndKeepOrdinals() {
        val page = libraryPageResult(
            fileId = 7L,
            documentUri = "content://docs/seven",
            displayName = "seven.pdf",
            pageIndex = 2,
            storedText = "one AB-1234 two AB-5678",
            pattern = "AB-\\d{4}",
            flags = DEFAULT_REGEX_FLAGS
        )

        assertNull(page.error)
        assertTrue(!page.truncated && !page.timedOut && !page.cancelled)
        val rows = page.rows
        assertEquals(2, rows.size)
        assertEquals(listOf(0, 1), rows.map { it.ordinal })
        assertEquals(listOf("AB-1234", "AB-5678"), rows.map { it.text })
        // The index stores no geometry, so a library row can never carry rects;
        // the tap recomputes them from the page it opens.
        assertTrue(rows.all { it.rects.isEmpty() })
        assertTrue(rows.all { it.fileId == 7L })
        assertTrue(rows.all { it.pageIndex == 2 })
        assertTrue(rows.all { it.documentUri == "content://docs/seven" })
        assertTrue(rows.all { it.displayName == "seven.pdf" })
    }

    @Test
    fun ordinalSelectionPicksTheNthMatch() {
        val layout = "one AB-1234 two AB-5678"

        val range = ordinalRangeInLayout(layout, "AB-\\d{4}", DEFAULT_REGEX_FLAGS, 1)

        assertNotNull(range)
        assertEquals("AB-5678", layout.substring(range!!.first, range.last + 1))
    }

    @Test
    fun ordinalBeyondTheListReturnsNull() {
        val layout = "one AB-1234"

        assertNull(ordinalRangeInLayout(layout, "AB-\\d{4}", DEFAULT_REGEX_FLAGS, 4))
        assertNull(ordinalRangeInLayout("", "AB-\\d{4}", DEFAULT_REGEX_FLAGS, 0))
    }

    @Test
    fun normalisedRangeMapsAcrossAWrappedLine() {
        // The stored text collapsed this break to one space, so the match is
        // found on the normalised string and must widen back over the newline.
        val layout = "prefix AB-\n1234 suffix"

        val range = ordinalRangeInLayout(layout, "AB-\\s*1234", DEFAULT_REGEX_FLAGS, 0)

        assertNotNull(range)
        assertEquals("AB-\n1234", layout.substring(range!!.first, range.last + 1))
    }

    @Test
    fun layoutRangeNeverEscapesTheText() {
        val layout = "ends with AB-9"

        val range = ordinalRangeInLayout(layout, "AB-\\d", DEFAULT_REGEX_FLAGS, 0)

        assertNotNull(range)
        assertTrue(range!!.first >= 0)
        assertTrue(range.last < layout.length)
        assertEquals("AB-9", layout.substring(range.first, range.last + 1))
    }
}
