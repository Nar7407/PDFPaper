package com.pdfpaper.engine.render

import com.pdfpaper.engine.graphics.PdfColor
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T62: the colour rules of a page look. The look is applied to the colours a
 * renderer is handed, so these tests pin down exactly what a renderer paints:
 * a neutral look is identity, sepia is a warm duotone whose warmth is the
 * slider, inverted monochrome is a tinted inversion (paper dark, ink light),
 * high contrast moves the mid tones and leaves the ends, and the three sliders
 * compose on top of any filter. The dark and true-black filters must keep
 * T32/T32b's exact papers.
 */
class ColorTransformTest {

    private val black = PdfColor(0.0, 0.0, 0.0)
    private val white = PdfColor(1.0, 1.0, 1.0)
    private val midGrey = PdfColor(0.5, 0.5, 0.5)

    private fun near(expected: Double, actual: Double, tolerance: Double = 1.0e-9) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "expected $expected but was $actual"
        )
    }

    private fun near(expected: PdfColor, actual: PdfColor, tolerance: Double = 1.0e-9) {
        near(expected.red, actual.red, tolerance)
        near(expected.green, actual.green, tolerance)
        near(expected.blue, actual.blue, tolerance)
    }

    // ------------------------------------------------------------ identity

    @Test
    fun aNeutralLookChangesNothing() {
        val look = ColorTransform()
        assertFalse(look.active)
        assertFalse(look.graded)
        assertEquals(PdfColor(0.2, 0.4, 0.9, 0.6), look.grade(PdfColor(0.2, 0.4, 0.9, 0.6)))
        assertEquals(0xFF123456.toInt(), look.gradeArgb(0xFF123456.toInt()))
        assertEquals(0x00123456, look.gradeRgb(0x12, 0x34, 0x56))
        val pixels = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        look.apply(pixels)
        assertEquals(0xFFFFFFFF.toInt(), pixels[0])
        assertEquals(0xFF000000.toInt(), pixels[1])
    }

    @Test
    fun aNormalFilterKeepsTheT32PageTheme() {
        val base = PageTheme(mode = PageThemeMode.DARK)
        assertEquals(PageThemeMode.DARK, ColorTransform().pageTheme(base).mode)
    }

    // --------------------------------------------------------------- sepia

    @Test
    fun sepiaMapsWhiteToAWarmPaperAndBlackToABrownInk() {
        val look = ColorTransform(filter = PageFilter.SEPIA)
        val paper = look.grade(white)
        val ink = look.grade(black)
        assertTrue(paper.red >= paper.green && paper.green >= paper.blue, "paper red ${paper.red}")
        assertTrue(paper.red - paper.blue > 0.1, "the paper is not warm: $paper")
        assertTrue(ink.red > ink.green && ink.green > ink.blue, "the ink is not brown: $ink")
        assertTrue(ink.red < 0.25, "the ink is too light: $ink")
        // A grey stays on the line between the two.
        val grey = look.grade(midGrey)
        assertTrue(grey.red > ink.red && grey.red < paper.red)
    }

    @Test
    fun warmthControlsHowAmberSepiaIs() {
        val cool = ColorTransform(filter = PageFilter.SEPIA, warmth = 0.0)
        val warm = ColorTransform(filter = PageFilter.SEPIA, warmth = 1.0)
        // No warmth is a plain grey duotone: paper stays white, ink black.
        near(white, cool.grade(white))
        near(black, cool.grade(black))
        assertTrue(warm.grade(white).blue < cool.grade(white).blue)
        assertTrue(
            warm.grade(white).red - warm.grade(white).blue >
                cool.grade(white).red - cool.grade(white).blue,
            "red-blue gap should grow with warmth"
        )
        // The default sits between the two ends.
        val look = ColorTransform(filter = PageFilter.SEPIA)
        near(1.0 - 0.24 * ColorTransform.DEFAULT_WARMTH, look.grade(white).blue)
    }

    @Test
    fun sepiaSurvivesThePackedBytePath() {
        val look = ColorTransform(filter = PageFilter.SEPIA, warmth = 1.0)
        val paper = look.gradeRgb(255, 255, 255)
        assertEquals(255, (paper shr 16) and 0xFF)
        assertEquals(235, (paper shr 8) and 0xFF) // 1 - 0.08 = 0.92 → 234.6
        assertEquals(194, paper and 0xFF) // 1 - 0.24 = 0.76 → 193.8
    }

    // ---------------------------------------------------------------- dark

    @Test
    fun darkFilterUsesTheDarkPaperAndKeepsTheInk() {
        val base = PageTheme(mode = PageThemeMode.OFF, ink = PdfColor(0.9, 0.85, 0.8), dim = 0.8)
        val look = ColorTransform(filter = PageFilter.DARK)
        val theme = look.pageTheme(base)
        assertEquals(PageThemeMode.DARK, theme.mode)
        assertEquals(PageTheme.DARK_PAPER, look.background(base))
        assertEquals(PdfColor(0.9, 0.85, 0.8), theme.ink)
        assertEquals(0.8, theme.dim)
    }

    @Test
    fun trueBlackFilterClearsToExactBlack() {
        val look = ColorTransform(filter = PageFilter.TRUE_BLACK)
        assertEquals(PageThemeMode.TRUE_BLACK, look.pageTheme(PageTheme.OFF).mode)
        assertEquals(black, look.background(PageTheme.OFF))
    }

    @Test
    fun theDarkFiltersDoNotGradeThePrimitives() {
        val look = ColorTransform(filter = PageFilter.DARK)
        val ink = PdfColor(0.9, 0.9, 0.9)
        near(ink, look.grade(ink))
    }

    // ------------------------------------------------------ inverted mono

    @Test
    fun invertedMonoMapsPaperToTheDarkTintAndInkToTheLightTint() {
        val look = ColorTransform(filter = PageFilter.INVERTED_MONO)
        val paper = look.grade(white)
        val ink = look.grade(black)
        near(ColorTransform.blueprintFloor(ColorTransform.DEFAULT_TINT), paper)
        near(ColorTransform.DEFAULT_TINT, ink)
        // Paper dark, ink light: this is what makes a drawing readable.
        assertTrue(paper.blue < 0.15)
        assertTrue(ink.blue > 0.9)
        // The tint's hue survives: blue leads, red trails.
        assertTrue(ink.blue > ink.green && ink.green > ink.red)
    }

    @Test
    fun invertedMonoUsesTheChosenTint() {
        val amber = PdfColor(1.0, 0.75, 0.35)
        val look = ColorTransform(filter = PageFilter.INVERTED_MONO, tint = amber)
        val ink = look.grade(black)
        near(amber, ink)
        val paper = look.grade(white)
        near(amber.red * ColorTransform.PAPER_FLOOR, paper.red)
        assertTrue(paper.red > paper.blue)
    }

    // ------------------------------------------------------ high contrast

    @Test
    fun highContrastPushesMidGreyAwayAndKeepsTheEnds() {
        val look = ColorTransform(filter = PageFilter.HIGH_CONTRAST)
        near(black, look.grade(black))
        near(white, look.grade(white))
        near(midGrey, look.grade(midGrey))
        val dark = look.grade(PdfColor(0.4, 0.4, 0.4))
        val light = look.grade(PdfColor(0.6, 0.6, 0.6))
        near(0.5 - 0.1 * ColorTransform.HIGH_CONTRAST_GAIN, dark.red)
        near(0.5 + 0.1 * ColorTransform.HIGH_CONTRAST_GAIN, light.red)
        // A coloured pixel keeps its hue order.
        val blue = look.grade(PdfColor(0.35, 0.55, 0.85))
        assertTrue(blue.blue > blue.green && blue.green > blue.red, "the hue order survived: $blue")
    }

    // ------------------------------------------------------------ sliders

    @Test
    fun brightnessLiftsEveryChannelAndClipsAtWhite() {
        val look = ColorTransform(brightness = 0.1)
        assertTrue(look.active)
        near(PdfColor(0.6, 0.6, 0.6), look.grade(midGrey))
        near(white, look.grade(PdfColor(0.95, 0.95, 0.95)))
        val darker = ColorTransform(brightness = -0.2)
        near(PdfColor(0.3, 0.3, 0.3), darker.grade(midGrey))
        near(black, darker.grade(PdfColor(0.1, 0.1, 0.1)))
    }

    @Test
    fun contrastSpreadsAroundMidGrey() {
        val look = ColorTransform(contrast = 1.5)
        near(midGrey, look.grade(midGrey))
        near(PdfColor(0.35, 0.35, 0.35), look.grade(PdfColor(0.4, 0.4, 0.4)))
        near(PdfColor(0.65, 0.65, 0.65), look.grade(PdfColor(0.6, 0.6, 0.6)))
        val flat = ColorTransform(contrast = 0.5)
        near(PdfColor(0.45, 0.45, 0.45), flat.grade(PdfColor(0.4, 0.4, 0.4)))
    }

    @Test
    fun gammaChangesTheMidTonesAndKeepsBlackAndWhite() {
        val look = ColorTransform(gamma = 2.0)
        near(black, look.grade(black))
        near(white, look.grade(white))
        near(0.25, look.grade(midGrey).red)
        val lighter = ColorTransform(gamma = 0.5)
        near(0.5, lighter.grade(PdfColor(0.25, 0.25, 0.25)).red, 1.0e-9)
    }

    @Test
    fun theSlidersApplyOnTopOfTheFilter() {
        val sepia = ColorTransform(filter = PageFilter.SEPIA, warmth = 1.0)
        val lifted = sepia.copy(brightness = 0.1)
        val base = sepia.grade(black)
        near(PdfColor(base.red + 0.1, base.green + 0.1, base.blue + 0.1), lifted.grade(black))
        // Brightness works on the dark themes too (they grade nothing themselves).
        val dark = ColorTransform(filter = PageFilter.TRUE_BLACK, brightness = 0.2)
        near(PdfColor(0.2, 0.2, 0.2), dark.background(PageTheme.OFF))
    }

    @Test
    fun valuesAreClampedAndNaNIsIgnored() {
        val wild = ColorTransform(
            warmth = 4.0,
            brightness = 9.0,
            contrast = -2.0,
            gamma = Double.NaN
        ).normalized()
        assertEquals(1.0, wild.warmth)
        assertEquals(ColorTransform.MAX_BRIGHTNESS, wild.brightness)
        assertEquals(ColorTransform.MIN_CONTRAST, wild.contrast)
        assertEquals(1.0, wild.gamma)
        val nanWarmth = ColorTransform(warmth = Double.NaN).normalized()
        assertEquals(ColorTransform.DEFAULT_WARMTH, nanWarmth.warmth)
    }

    // ------------------------------------------------------------- pixels

    @Test
    fun packedPixelsKeepTheirAlpha() {
        val look = ColorTransform(filter = PageFilter.INVERTED_MONO)
        val result = look.gradeArgb(0x80FFFFFF.toInt())
        assertEquals(0x80, (result ushr 24) and 0xFF)
        // A zero alpha stays zero rather than becoming opaque.
        assertEquals(0, look.gradeArgb(0x00FFFFFF) ushr 24)
    }

    @Test
    fun applyMapsABufferInPlace() {
        val look = ColorTransform(filter = PageFilter.SEPIA)
        val pixels = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        look.apply(pixels)
        assertTrue((pixels[0] shr 16 and 0xFF) > (pixels[0] and 0xFF))
        assertEquals(0xFF, (pixels[1] ushr 24))
        assertTrue(pixels[1] != 0xFF000000.toInt())
    }

    @Test
    fun thePixelPathAgreesWithTheColourPath() {
        val colours = listOf(
            PdfColor(0.0, 0.0, 0.0),
            PdfColor(1.0, 1.0, 1.0),
            PdfColor(0.5, 0.5, 0.5),
            PdfColor(0.2, 0.4, 0.9),
            PdfColor(0.93, 0.9, 0.42),
            PdfColor(0.05, 0.7, 0.1)
        )
        for (filter in PageFilter.entries) {
            val look = ColorTransform(
                filter = filter,
                warmth = 0.7,
                brightness = 0.05,
                contrast = 1.2,
                gamma = 1.1
            )
            for (colour in colours) {
                val r = (colour.red * 255.0).roundToInt()
                val g = (colour.green * 255.0).roundToInt()
                val b = (colour.blue * 255.0).roundToInt()
                // Compare against the same (byte-quantised) input the pixel path sees.
                val graded = look.grade(PdfColor(r / 255.0, g / 255.0, b / 255.0))
                val packed = look.gradeArgb(0xFF000000.toInt() or (r shl 16) or (g shl 8) or b)
                // The byte path rounds; a channel may differ by at most one step.
                near(graded.red, ((packed shr 16) and 0xFF) / 255.0, 1.0 / 255.0 + 1.0e-9)
                near(graded.green, ((packed shr 8) and 0xFF) / 255.0, 1.0 / 255.0 + 1.0e-9)
                near(graded.blue, (packed and 0xFF) / 255.0, 1.0 / 255.0 + 1.0e-9)
            }
        }
    }

    // --------------------------------------------------------------- names

    @Test
    fun theFilterNamesRoundTrip() {
        PageFilter.entries.forEach { filter ->
            assertEquals(filter, PageFilter.fromName(filter.name))
        }
        assertEquals(PageFilter.NORMAL, PageFilter.fromName("nonsense"))
        assertEquals(PageFilter.NORMAL, PageFilter.fromName(null))
        assertEquals(PageFilter.DARK, PageFilter.of(PageThemeMode.DARK))
        assertEquals(PageFilter.TRUE_BLACK, PageFilter.of(PageThemeMode.TRUE_BLACK))
        assertEquals(PageFilter.NORMAL, PageFilter.of(PageThemeMode.OFF))
        assertEquals(PageFilter.NORMAL, PageFilter.of(null))
        assertEquals(PageThemeMode.TRUE_BLACK, PageFilter.TRUE_BLACK.themeMode)
        assertEquals(PageThemeMode.OFF, PageFilter.SEPIA.themeMode)
        assertNull(PageFilter.entries.firstOrNull { it.label.isBlank() })
    }
}
