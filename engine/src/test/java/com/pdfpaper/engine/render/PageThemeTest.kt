package com.pdfpaper.engine.render

import com.pdfpaper.engine.graphics.PdfColor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T32/T32b: the colour rules of the page themes.
 *
 * The theme is applied to the primitives a renderer receives, so these tests
 * pin down what a renderer is handed: paper becomes the background, near-black
 * line art becomes the ink, every other fill is compressed into a dark range
 * that keeps its hue, text and strokes are ink, images are untouched, and the
 * ink keeps a readable contrast against everything it can be drawn on.
 */
class PageThemeTest {

    private val black = PdfColor(0.0, 0.0, 0.0)
    private val white = PdfColor(1.0, 1.0, 1.0)

    // ------------------------------------------------------------ mode basics

    @Test
    fun offChangesNothing() {
        val theme = PageTheme.OFF
        assertFalse(theme.active)
        assertEquals(white, theme.background)
        assertEquals(PagePaint(PdfColor(0.2, 0.4, 0.9), 1.0), theme.fill(PdfColor(0.2, 0.4, 0.9), 1.0))
        assertEquals(PagePaint(PdfColor(0.2, 0.4, 0.9), 0.5), theme.stroke(PdfColor(0.2, 0.4, 0.9), 0.5))
        assertEquals(PdfColor.BLACK, theme.text().color)
        assertEquals(1.0, theme.imageDim())
        assertEquals(PageThemeMode.OFF, PageThemeMode.fromName("nonsense"))
        assertEquals(PageThemeMode.TRUE_BLACK, PageThemeMode.fromName("TRUE_BLACK"))
    }

    @Test
    fun trueBlackPaperIsExactlyBlack() {
        val theme = PageTheme(mode = PageThemeMode.TRUE_BLACK)
        assertTrue(theme.active)
        assertEquals(black, theme.background)
        assertEquals(0.0, theme.background.red)
        assertEquals(0.0, theme.background.green)
        assertEquals(0.0, theme.background.blue)
    }

    @Test
    fun darkPaperIsTheDarkGrey() {
        val theme = PageTheme(mode = PageThemeMode.DARK)
        assertEquals(PageTheme.DARK_PAPER, theme.background)
        // #121212, not pure black: an OLED reader may still want a visible page.
        assertEquals(18.0 / 255.0, theme.background.red)
    }

    @Test
    fun inkDefaultsToSoftOffWhite() {
        val theme = PageTheme(mode = PageThemeMode.TRUE_BLACK)
        assertEquals(PageTheme.DEFAULT_INK, theme.text().color)
        assertEquals(230.0 / 255.0, theme.text().color.red)
    }

    // -------------------------------------------------------- fill and stroke

    @Test
    fun paperFillsBecomeTheBackground() {
        // A document that paints its own white "paper" rectangle must not leave
        // a bright slab on a true-black page.
        for (mode in listOf(PageThemeMode.DARK, PageThemeMode.TRUE_BLACK)) {
            val theme = PageTheme(mode = mode)
            assertEquals(theme.background, theme.fill(white, 1.0).color)
            assertEquals(theme.background, theme.fill(PdfColor(0.95, 0.95, 0.95), 1.0).color)
        }
    }

    @Test
    fun nearBlackFillsStayAFaintDarkShape() {
        val theme = PageTheme(mode = PageThemeMode.TRUE_BLACK)
        // A black box must not become the ink colour: text on it is ink too,
        // and off-white text on off-white would disappear.
        assertEquals(PageTheme.INK_FILL, theme.fill(black, 1.0).color)
        assertEquals(PageTheme.INK_FILL, theme.fill(PdfColor(0.1, 0.1, 0.12), 1.0).color)
        assertTrue(contrastRatio(theme.ink, PageTheme.INK_FILL) >= 4.5)
        assertTrue(PageTheme.INK_FILL.red < theme.ink.red)
    }

    @Test
    fun otherFillsAreCompressedIntoTheDarkRange() {
        val theme = PageTheme(mode = PageThemeMode.TRUE_BLACK)
        val colours = listOf(
            PdfColor(0.9, 0.2, 0.2),
            PdfColor(0.2, 0.8, 0.3),
            PdfColor(0.1, 0.3, 0.9),
            PdfColor(0.6, 0.6, 0.2),
            PdfColor(0.5, 0.5, 0.5),
            PdfColor(0.89, 0.2, 0.2)
        )
        for (colour in colours) {
            val mapped = theme.fill(colour, 1.0).color
            val peak = maxOf(mapped.red, mapped.green, mapped.blue)
            assertTrue(peak <= PageTheme.FILL_MAX + 1e-9, "peak $peak of $colour")
            assertTrue(peak >= PageTheme.FILL_MIN - 1e-9, "peak $peak of $colour")
            // The hue survives: the relative order of the channels is kept.
            val sourceRank = listOf(colour.red, colour.green, colour.blue)
                .withIndex().sortedBy { it.value }.map { it.index }
            val mappedRank = listOf(mapped.red, mapped.green, mapped.blue)
                .withIndex().sortedBy { it.value }.map { it.index }
            assertEquals(sourceRank, mappedRank)
        }
    }

    @Test
    fun aDarkFillIsBrightenedRatherThanLeftInvisible() {
        val theme = PageTheme(mode = PageThemeMode.TRUE_BLACK)
        // A deep navy is almost invisible on black; the theme lifts it.
        val navy = PdfColor(0.0, 0.0, 0.25)
        val mapped = theme.fill(navy, 1.0).color
        assertTrue(mapped.blue > navy.blue)
        assertTrue(mapped.blue <= PageTheme.FILL_MAX + 1e-9)
    }

    @Test
    fun strokesAreInkAndWhiteStrokesStayPaper() {
        val theme = PageTheme(mode = PageThemeMode.TRUE_BLACK)
        assertEquals(theme.ink, theme.stroke(PdfColor(0.2, 0.2, 0.9), 1.0).color)
        // A white rule is a knockout: it keeps the separation it drew on paper.
        assertEquals(theme.background, theme.stroke(white, 1.0).color)
    }

    @Test
    fun stencilMasksUseTheFillMapping() {
        val theme = PageTheme(mode = PageThemeMode.TRUE_BLACK)
        assertEquals(theme.fill(PdfColor(0.3, 0.5, 0.7), 1.0), theme.stencil(PdfColor(0.3, 0.5, 0.7)))
    }

    // ------------------------------------------------------------ alpha, dim

    @Test
    fun semiTransparentFillsKeepTheirAlphaAndLiftTheFaintEnd() {
        val theme = PageTheme(mode = PageThemeMode.TRUE_BLACK)
        val colour = PdfColor(0.9, 0.2, 0.2)
        assertEquals(0.0, theme.fill(colour, 0.0).alpha)
        assertEquals(1.0, theme.fill(colour, 1.0).alpha)
        // A 1 % tint would vanish into black; sqrt lifts it without touching 0/1.
        val faint = theme.fill(colour, 0.01).alpha
        assertTrue(faint > 0.01)
        assertTrue(faint < 1.0)
        assertEquals(0.5, theme.fill(colour, 0.25).alpha, 1e-9)
    }

    @Test
    fun imagesKeepTheirOwnColoursByDefault() {
        val theme = PageTheme(mode = PageThemeMode.TRUE_BLACK)
        assertEquals(1.0, theme.imageDim())
        // The optional dim is the only thing that touches a photograph.
        assertEquals(0.6, theme.copy(dim = 0.6).imageDim(), 1e-9)
        assertEquals(1.0, theme.copy(dim = 0.6).copy(mode = PageThemeMode.OFF).imageDim())
        assertEquals(1.0, theme.copy(dim = 3.0).imageDim())
    }

    // ------------------------------------------------------------- resolution

    @Test
    fun perDocumentOverrideWins() {
        val global = PageTheme(PageThemeMode.DARK, PdfColor(1.0, 1.0, 1.0), 0.8)
        val resolved = PageTheme.resolve(
            global = global,
            perDocument = PageThemeMode.TRUE_BLACK,
            followSystem = true,
            systemDark = false
        )
        assertEquals(PageThemeMode.TRUE_BLACK, resolved.mode)
        // Ink and dim always come from the global setting.
        assertEquals(global.ink, resolved.ink)
        assertEquals(0.8, resolved.dim)
    }

    @Test
    fun followSystemTurnsTheThemeOffInLightMode() {
        val global = PageTheme(mode = PageThemeMode.TRUE_BLACK)
        assertEquals(
            PageThemeMode.OFF,
            PageTheme.resolve(global, null, followSystem = true, systemDark = false).mode
        )
        assertEquals(
            PageThemeMode.TRUE_BLACK,
            PageTheme.resolve(global, null, followSystem = true, systemDark = true).mode
        )
    }

    @Test
    fun withoutFollowSystemTheGlobalModeApplies() {
        val global = PageTheme(mode = PageThemeMode.DARK)
        assertEquals(
            PageThemeMode.DARK,
            PageTheme.resolve(global, null, followSystem = false, systemDark = false).mode
        )
    }

    @Test
    fun aDocumentCanForceOffWhileFollowingTheSystem() {
        val global = PageTheme(mode = PageThemeMode.TRUE_BLACK)
        assertEquals(
            PageThemeMode.OFF,
            PageTheme.resolve(global, PageThemeMode.OFF, followSystem = true, systemDark = true).mode
        )
    }

    // -------------------------------------------------------------- contrast

    @Test
    fun inkStaysReadableOnBothBackgrounds() {
        for (mode in listOf(PageThemeMode.DARK, PageThemeMode.TRUE_BLACK)) {
            val theme = PageTheme(mode = mode)
            val ratio = contrastRatio(theme.ink, theme.background)
            assertTrue(ratio >= 4.5, "$mode contrast $ratio")
        }
        // The pure-black page is the highest contrast of all.
        assertTrue(
            contrastRatio(PageTheme.DEFAULT_INK, PdfColor.BLACK) >
                contrastRatio(PageTheme.DEFAULT_INK, PageTheme.DARK_PAPER)
        )
    }

    @Test
    fun inkStaysReadableOnEveryMappedFill() {
        val theme = PageTheme(mode = PageThemeMode.TRUE_BLACK)
        var worst = Double.MAX_VALUE
        var worstAt = ""
        // Text drawn on a coloured box is the case that breaks a naive
        // inversion: the box must end up dark enough for off-white text.
        var r = 0
        while (r <= 20) {
            var g = 0
            while (g <= 20) {
                var b = 0
                while (b <= 20) {
                    val colour = PdfColor(r / 20.0, g / 20.0, b / 20.0)
                    val mapped = theme.fill(colour, 1.0).color
                    val ratio = contrastRatio(theme.ink, mapped)
                    if (ratio < worst) {
                        worst = ratio
                        worstAt = "($r,$g,$b)/20"
                    }
                    b++
                }
                g++
            }
            r++
        }
        assertTrue(worst >= 4.5, "worst contrast $worst for fill $worstAt")
    }

    @Test
    fun luminanceAndContrastMatchTheKnownAnchors() {
        assertEquals(0.0, black.relativeLuminance(), 1e-9)
        assertEquals(1.0, white.relativeLuminance(), 1e-9)
        assertEquals(21.0, contrastRatio(black, white), 1e-6)
        assertEquals(1.0, contrastRatio(white, white), 1e-9)
        // Symmetric.
        val a = PdfColor(0.2, 0.4, 0.6)
        val b = PdfColor(0.8, 0.7, 0.1)
        assertEquals(contrastRatio(a, b), contrastRatio(b, a), 1e-12)
    }

    @Test
    fun isBackgroundRecognisesOnlyTheThemesPaper() {
        val theme = PageTheme(mode = PageThemeMode.TRUE_BLACK)
        assertTrue(theme.isBackground(black))
        assertFalse(theme.isBackground(white))
        assertFalse(PageTheme.OFF.isBackground(white))
        assertTrue(abs(theme.background.red - 0.0) < 1e-12)
    }
}
