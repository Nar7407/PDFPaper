package com.pdfpaper.app.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetHighlighterTest {

    private val start = SnippetHighlighter.MARK_START
    private val end = SnippetHighlighter.MARK_END

    @Test
    fun matchedWordsAreHighlighted() {
        val parts = SnippetHighlighter.parse("the ${start}budget${end} is late")

        assertEquals(3, parts.size)
        assertFalse(parts[0].highlighted)
        assertTrue(parts[1].highlighted)
        assertEquals("budget", parts[1].text)
        assertEquals(" is late", parts[2].text)
    }

    @Test
    fun twoMatchesInOneSnippetAreBothMarked() {
        val parts = SnippetHighlighter.parse(
            "${start}tax${end} and ${start}budget${end}"
        )

        assertEquals(3, parts.size)
        assertTrue(parts[0].highlighted)
        assertTrue(parts[2].highlighted)
        assertEquals(" and ", parts[1].text)
    }

    @Test
    fun unmatchedMarkersAndControlCharactersNeverThrow() {
        val parts = SnippetHighlighter.parse("a${start}b\u0000c${end}")

        assertEquals("ab c", SnippetHighlighter.plain("a${start}b\u0000c${end}"))
        assertTrue(parts.any { it.highlighted })
    }

    @Test
    fun whitespaceIsCollapsedForDisplay() {
        val parts = SnippetHighlighter.parse("one\n\n two\tthree")

        assertEquals("one two three", parts.joinToString("") { it.text })
    }

    @Test
    fun plainTextDropsMarkers() {
        assertEquals(
            "before match after",
            SnippetHighlighter.plain("before ${start}match${end} after")
        )
    }
}
