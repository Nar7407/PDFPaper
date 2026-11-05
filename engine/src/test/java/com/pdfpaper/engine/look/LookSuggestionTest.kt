package com.pdfpaper.engine.look

import com.pdfpaper.engine.render.PageFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * T62: the look suggestion. A page is only ever *offered* a filter, so these
 * tests pin the two cases worth offering (a yellowed scan, a line drawing) and
 * the cases that must stay quiet (ordinary text, a blank page, a photograph).
 */
class LookSuggestionTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val yellowPaper = 0xFFF5EECD.toInt()

    private fun page(
        width: Int = 300,
        height: Int = 400,
        fill: Int = white,
        paint: (IntArray, Int, Int) -> Unit = { _, _, _ -> }
    ): IntArray {
        val pixels = IntArray(width * height) { fill }
        paint(pixels, width, height)
        return pixels
    }

    private fun IntArray.set(x: Int, y: Int, width: Int, colour: Int) {
        this[y * width + x] = colour
    }

    /** Rows of short dark strokes: how text looks at page scale. */
    private fun textRows(pixels: IntArray, width: Int, height: Int, rows: Int = 22) {
        var line = 0
        var y = 20
        while (y < height - 20 && line < rows) {
            var x = 16
            while (x < width - 16) {
                val run = 9 + (x % 4)
                for (dx in 0 until run) pixels.set(x + dx, y, width, black)
                x += run + 7
            }
            y += 18
            line++
        }
    }

    @Test
    fun aYellowedScanSuggestsSepia() {
        val pixels = page(fill = yellowPaper) { px, w, h -> textRows(px, w, h) }
        assertEquals(PageFilter.SEPIA, LookSuggestion.suggest(pixels, 300, 400))
    }

    @Test
    fun sepiaIsOfferedEvenWhenTheScanIsOnlyPaper() {
        val pixels = page(fill = yellowPaper)
        assertEquals(PageFilter.SEPIA, LookSuggestion.suggest(pixels, 300, 400))
    }

    @Test
    fun ordinaryTextOnWhiteStaysQuiet() {
        val pixels = page { px, w, h -> textRows(px, w, h) }
        assertNull(LookSuggestion.suggest(pixels, 300, 400))
    }

    @Test
    fun aLineDrawingSuggestsInvertedMonochrome() {
        val pixels = page { px, w, h ->
            // Three full-width rules and three full-height rules: the geometry
            // of a CAD drawing, and only ~1.7 % of the page.
            for (y in listOf(60, 200, 340)) {
                for (x in 0 until w) px.set(x, y, w, black)
            }
            for (x in listOf(80, 150, 220)) {
                for (y in 0 until h) px.set(x, y, w, black)
            }
        }
        assertEquals(PageFilter.INVERTED_MONO, LookSuggestion.suggest(pixels, 300, 400))
    }

    @Test
    fun aSingleRuleIsNotADrawing() {
        val pixels = page { px, w, _ ->
            for (x in 0 until w) {
                px.set(x, 200, w, black)
                px.set(x, 201, w, black)
            }
        }
        assertNull(LookSuggestion.suggest(pixels, 300, 400))
    }

    @Test
    fun aBlankPageStaysQuiet() {
        assertNull(LookSuggestion.suggest(page(), 300, 400))
    }

    @Test
    fun aColourfulPageStaysQuiet() {
        val colours = intArrayOf(0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt())
        val pixels = page { px, w, h ->
            for (y in 0 until h) {
                for (x in 0 until w) {
                    px.set(x, y, w, colours[(x / 40 + y / 40) % colours.size])
                }
            }
        }
        assertNull(LookSuggestion.suggest(pixels, 300, 400))
    }

    @Test
    fun aDarkPhotographStaysQuiet() {
        // Mostly ink: too dense to be line art, and not paper at all.
        val pixels = page(fill = 0xFF202020.toInt())
        assertNull(LookSuggestion.suggest(pixels, 300, 400))
    }

    @Test
    fun malformedInputIsANullResultNotACrash() {
        assertNull(LookSuggestion.suggest(null, 300, 400))
        assertNull(LookSuggestion.suggest(IntArray(10), 300, 400))
        assertNull(LookSuggestion.suggest(IntArray(100), 0, 400))
        assertNull(LookSuggestion.suggest(IntArray(100), 300, 0))
        assertNull(LookSuggestion.suggest(IntArray(100), 4, 4))
        assertEquals(
            null,
            LookSuggestion.suggest(page(width = 12, height = 12), 12, 12)
        )
    }
}
