package com.pdfpaper.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings → Appearance: every Code style palette must keep its text readable.
 * WCAG 2.1 AA asks for a contrast ratio of at least 4.5:1 for body text, so a
 * palette whose text or accent falls below it fails this test.
 */
class CodePaletteContrastTest {

    private val palettes = CodePalettes.ALL

    @Test
    fun thereAreSixPalettesWithUniqueNamesAndFiveAccents() {
        assertEquals(6, palettes.size)
        assertEquals(palettes.size, palettes.map { it.name }.toSet().size)
        for (palette in palettes) {
            assertEquals("${palette.name} accents", 5, palette.accents.size)
            assertEquals(5, palette.accents.toSet().size)
        }
    }

    @Test
    fun midnightBlueEmberForestVioletNightAreDarkAndSandPaperLightAreLight() {
        val dark = palettes.filter { it.darkBackground }.map { it.name }
        val light = palettes.filterNot { it.darkBackground }.map { it.name }
        assertEquals(listOf("Midnight Blue", "Ember", "Forest", "Violet Night"), dark)
        assertEquals(listOf("Sand", "Paper Light"), light)
    }

    @Test
    fun everyChromePairMeetsWcagAaForAllPalettes() {
        for (palette in palettes) {
            for ((label, pair) in CodePalettes.checkedPairs(palette)) {
                val (foreground, background) = pair
                val ratio = CodePalettes.contrastRatio(foreground, background)
                assertTrue(
                    "${palette.name}: $label is %.2f:1, below %.1f:1".format(
                        ratio,
                        CodePalettes.MIN_CONTRAST
                    ),
                    ratio >= CodePalettes.MIN_CONTRAST
                )
            }
        }
    }

    @Test
    fun theContrastMathsMatchesWcagKnownValues() {
        assertEquals(21.0, CodePalettes.contrastRatio(0xFF000000, 0xFFFFFFFF), 1e-9)
        assertEquals(1.0, CodePalettes.contrastRatio(0xFF808080, 0xFF808080), 1e-9)
        val ratio = CodePalettes.contrastRatio(0xFF767676, 0xFFFFFFFF)
        assertTrue("grey 0x767676 on white is %.2f:1".format(ratio), ratio >= 4.5 && ratio < 4.6)
    }
}
