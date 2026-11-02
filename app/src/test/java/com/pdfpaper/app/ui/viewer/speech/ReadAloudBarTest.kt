package com.pdfpaper.app.ui.viewer.speech

import com.pdfpaper.engine.text.TextRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T64: which page the spoken word is drawn on, and when nothing is drawn. */
class ReadAloudBarTest {

    private val word = listOf(TextRect(10.0, 20.0, 30.0, 40.0))

    @Test
    fun theHighlightIsDrawnOnlyOnTheSpokenPage() {
        val state = ReadAloudPlaybackState(active = true, wordPageIndex = 3, wordRects = word)
        assertEquals(word, speechHighlight(state, 3))
        assertTrue(
            "a box from another page must never be drawn over this one",
            speechHighlight(state, 4).isEmpty()
        )
    }

    @Test
    fun nothingIsHighlightedWhenNoReadingIsActive() {
        val state = ReadAloudPlaybackState(active = false, wordPageIndex = 0, wordRects = word)
        assertTrue(speechHighlight(state, 0).isEmpty())
    }
}
