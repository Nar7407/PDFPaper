package com.pdfpaper.app.data

import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.render.PageTheme
import com.pdfpaper.engine.render.PageThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * T32b: the packed-integer bridge between a colour picker and the engine's
 * 0..1 device components (§8.6), plus the stored defaults a reader gets before
 * touching anything.
 */
class ThemeColorTest {

    @Test
    fun defaultInkIsTheSoftOffWhite() {
        assertEquals(0xE6E6E6, DEFAULT_THEME_INK)
        val colour = pdfColorOf(DEFAULT_THEME_INK)
        assertEquals(230.0 / 255.0, colour.red, 1e-9)
        assertEquals(colour.red, colour.green, 1e-9)
        assertEquals(colour.green, colour.blue, 1e-9)
        assertEquals(1.0, colour.alpha, 1e-9)
        assertEquals(PageTheme.DEFAULT_INK, colour)
    }

    @Test
    fun packedColoursRoundTripExactly() {
        val samples = listOf(0x000000, 0xFFFFFF, 0xE6E6E6, 0x121212, 0xF2E8D5, 0x123456, 0x00FF80)
        for (rgb in samples) {
            assertEquals(rgb, rgbOf(pdfColorOf(rgb)))
        }
    }

    @Test
    fun rgbOfClampsOutOfRangeComponents() {
        assertEquals(0xFFFFFF, rgbOf(PdfColor(2.0, 2.0, 2.0)))
        assertEquals(0x000000, rgbOf(PdfColor(-1.0, -1.0, -1.0)))
    }

    @Test
    fun blackAndWhiteStayBlackAndWhite() {
        assertEquals(PdfColor.BLACK, pdfColorOf(0x000000))
        assertEquals(PdfColor.WHITE, pdfColorOf(0xFFFFFF))
    }

    @Test
    fun theStoredThemeIsOffUntilChosen() {
        // The default PageTheme must not change a document as authored.
        assertEquals(PageThemeMode.OFF, PageTheme().mode)
        assertEquals(1.0, PageTheme().dim, 1e-9)
        assertFalse(PageTheme().active)
    }
}
