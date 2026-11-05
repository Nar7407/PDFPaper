package com.pdfpaper.engine.eink

import com.pdfpaper.engine.graphics.PdfColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** Task T63: the E-Ink profiles must reduce a page predictably and purely. */
class EinkTest {

    private val midGrey = 0xFF808080.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun theProfilesHavePlainLanguageNames() {
        assertEquals(EinkProfile.TEXT_BITONAL, EinkProfile.fromName("TEXT_BITONAL"))
        assertEquals(EinkProfile.OFF, EinkProfile.fromName("not-a-profile"))
        assertEquals(EinkProfile.OFF, EinkProfile.fromName(null))
        assertEquals("1-bit text", EinkProfile.TEXT_BITONAL.shortLabel)
        assertTrue(EinkProfile.GRAYSCALE.description.contains("text"))
    }

    @Test
    fun settingsAreClampedAndNaNIsIgnored() {
        val clamped = EinkSettings(
            profile = EinkProfile.FULL_BITONAL,
            threshold = 4.0,
            minLineWidthPx = 99.0
        ).normalized()
        assertEquals(EinkSettings.MAX_THRESHOLD, clamped.threshold, 0.0)
        assertEquals(EinkSettings.MAX_MIN_LINE_WIDTH_PX, clamped.minLineWidthPx, 0.0)
        assertEquals(
            EinkSettings.DEFAULT_THRESHOLD,
            EinkSettings(threshold = Double.NaN).normalized().threshold,
            0.0
        )
        assertEquals(
            EinkSettings.DEFAULT_MIN_LINE_WIDTH_PX,
            EinkSettings(minLineWidthPx = Double.NaN).normalized().minLineWidthPx,
            0.0
        )
    }

    @Test
    fun theProfileFlagsDescribeTheWorkEachOneDoes() {
        assertFalse(EinkSettings.OFF.active)
        assertFalse(EinkSettings.OFF.bitonal)
        assertFalse(EinkSettings.OFF.postProcessed)
        assertTrue(EinkSettings(profile = EinkProfile.GRAYSCALE).postProcessed)
        assertFalse(EinkSettings(profile = EinkProfile.GRAYSCALE).bitonal)
        assertTrue(EinkSettings(profile = EinkProfile.TEXT_BITONAL).bitonal)
        assertTrue(EinkSettings(profile = EinkProfile.TEXT_BITONAL).ditheredImages)
        assertFalse(EinkSettings(profile = EinkProfile.TEXT_BITONAL).postProcessed)
        assertTrue(EinkSettings(profile = EinkProfile.FULL_BITONAL).postProcessed)
        assertFalse(EinkSettings(profile = EinkProfile.FULL_BITONAL).ditheredImages)
    }

    @Test
    fun theGreyCurveKeepsBlackAndWhiteAndSteepensTheMiddle() {
        val settings = EinkSettings(profile = EinkProfile.GRAYSCALE, threshold = 0.5)
        assertEquals(0.0, settings.grayscaleValue(0.0), 1e-9)
        assertEquals(1.0, settings.grayscaleValue(1.0), 1e-9)
        // The curve is a contrast stretch: a dark tone gets darker and a light
        // tone lighter, while the middle stays near where it was.
        assertTrue(settings.grayscaleValue(0.3) < 0.3)
        assertTrue(settings.grayscaleValue(0.7) > 0.7)
        assertTrue(settings.grayscaleValue(0.5) in 0.4..0.6)
    }

    @Test
    fun aHigherThresholdMakesTheGreyCurveDarker() {
        val low = EinkSettings(profile = EinkProfile.GRAYSCALE, threshold = 0.3)
        val high = EinkSettings(profile = EinkProfile.GRAYSCALE, threshold = 0.7)
        assertTrue(high.grayscaleValue(0.5) < low.grayscaleValue(0.5))
    }

    @Test
    fun theGreyProfileWritesOneChannelAndKeepsAlpha() {
        val pixels = intArrayOf(
            0xFF000000.toInt(),
            0xFFFFFFFF.toInt(),
            midGrey,
            0x80402010.toInt()
        )
        EinkPipeline.apply(pixels, EinkSettings(profile = EinkProfile.GRAYSCALE))
        assertNotEquals(midGrey, pixels[2])
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            assertEquals("grey must have equal channels: $r/$g/$b", r, g)
            assertEquals(r, b)
        }
        assertEquals(0x80, (pixels[3] ushr 24) and 0xFF)
    }

    @Test
    fun theOneBitProfileProducesOnlyPureBlackAndWhite() {
        for (threshold in listOf(0.2, 0.5, 0.8)) {
            val pixels = intArrayOf(black, white, midGrey, 0xFF404040.toInt(), 0xFFC0C0C0.toInt())
            EinkPipeline.apply(
                pixels,
                EinkSettings(profile = EinkProfile.FULL_BITONAL, threshold = threshold)
            )
            for (pixel in pixels) {
                assertTrue(
                    "expected pure black or white at $threshold, got ${Integer.toHexString(pixel)}",
                    pixel == black || pixel == white
                )
            }
            assertEquals(black, pixels[0])
            assertEquals(white, pixels[1])
        }
    }

    @Test
    fun aHigherThresholdTurnsMorePixelsBlack() {
        val tones = IntArray(101) { i ->
            val v = (i * 255 / 100)
            0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        val low = tones.copyOf()
        val high = tones.copyOf()
        EinkPipeline.apply(low, EinkSettings(profile = EinkProfile.FULL_BITONAL, threshold = 0.3))
        EinkPipeline.apply(high, EinkSettings(profile = EinkProfile.FULL_BITONAL, threshold = 0.7))
        assertTrue(high.count { it == black } > low.count { it == black })
    }

    @Test
    fun oneBitKeepsTheAlphaOfASoftMaskedPicture() {
        val pixels = intArrayOf(0x00808080, 0x40808080, 0xFF808080.toInt())
        EinkPipeline.apply(pixels, EinkSettings(profile = EinkProfile.FULL_BITONAL))
        assertEquals(0x00, (pixels[0] ushr 24) and 0xFF)
        assertEquals(0x40, (pixels[1] ushr 24) and 0xFF)
        assertEquals(0xFF, (pixels[2] ushr 24) and 0xFF)
    }

    @Test
    fun orderedDitheringTurnsUniformGreyIntoAPattern() {
        val pixels = IntArray(8 * 8) { midGrey }
        EinkPipeline.ditherImage(
            pixels,
            8,
            8,
            EinkSettings(
                profile = EinkProfile.TEXT_BITONAL,
                dither = EinkDither.ORDERED,
                threshold = 0.5
            )
        )
        val blacks = pixels.count { it == black }
        assertTrue("a mid grey needs both tones, blacks=$blacks", blacks in 1..62)
        assertTrue(pixels.count { it == white } > 0)
        // The 4x4 matrix repeats: row 0 and row 4 are the same pattern.
        for (x in 0 until 8) assertEquals(pixels[x], pixels[4 * 8 + x])
    }

    @Test
    fun orderedDitheringLeavesPureTonesAlone() {
        val pixels = intArrayOf(black, white, black, white)
        EinkPipeline.ditherImage(
            pixels,
            4,
            1,
            EinkSettings(profile = EinkProfile.TEXT_BITONAL, dither = EinkDither.ORDERED)
        )
        assertArrayEquals(intArrayOf(black, white, black, white), pixels)
    }

    @Test
    fun floydSteinbergKeepsTheAverageTone() {
        val pixels = IntArray(16 * 16) { midGrey }
        EinkPipeline.ditherImage(
            pixels,
            16,
            16,
            EinkSettings(
                profile = EinkProfile.TEXT_BITONAL,
                dither = EinkDither.FLOYD_STEINBERG,
                threshold = 0.5
            )
        )
        val blacks = pixels.count { it == black }
        assertTrue("error diffusion must produce both tones, blacks=$blacks", blacks in 1..254)
        assertTrue(pixels.count { it == white } > 0)
        // A half-grey block should come out close to half black.
        val ratio = blacks / 256.0
        assertTrue("dither ratio $ratio", abs(ratio - 0.5) < 0.15)
    }

    @Test
    fun theDeviceSchemeMapsFillsAndStrokesForOneBit() {
        val settings = EinkSettings(profile = EinkProfile.TEXT_BITONAL, threshold = 0.5)
        assertEquals(EinkSettings.BLACK, settings.binaryFill(PdfColor(0.1, 0.1, 0.1)))
        assertEquals(EinkSettings.WHITE, settings.binaryFill(PdfColor(0.9, 0.9, 0.9)))
        // A thin grey rule is exactly what a panel loses first, so a stroke
        // stays black even when it is a light grey.
        assertEquals(EinkSettings.BLACK, settings.binaryStroke(PdfColor(0.6, 0.6, 0.6)))
        assertEquals(EinkSettings.WHITE, settings.binaryStroke(PdfColor(1.0, 1.0, 1.0)))
    }

    @Test
    fun aHairlineIsThickenedToTheMinimumDeviceWidth() {
        val settings = EinkSettings(profile = EinkProfile.TEXT_BITONAL, minLineWidthPx = 1.0)
        // 1 device px at half a device pixel per user unit needs 2 user units.
        assertEquals(2.0, settings.strokeWidth(0.2, 0.5), 1e-9)
        assertEquals(0.5, settings.strokeWidth(0.2, 2.0), 1e-9)
        assertEquals(2.0, settings.strokeWidth(2.0, 0.5), 1e-9)
        assertEquals(0.2, settings.strokeWidth(0.2, 0.0), 1e-9)
        // A colour page keeps the authored width.
        assertEquals(
            0.2,
            EinkSettings(profile = EinkProfile.GRAYSCALE, minLineWidthPx = 1.0)
                .strokeWidth(0.2, 0.5),
            1e-9
        )
    }

    @Test
    fun profileTwoLeavesTheFinishedPageToTheRenderer() {
        val pixels = intArrayOf(midGrey, white, black)
        val copy = pixels.copyOf()
        EinkPipeline.apply(pixels, EinkSettings(profile = EinkProfile.TEXT_BITONAL))
        assertArrayEquals(copy, pixels)
    }

    @Test
    fun malformedBuffersDoNotThrow() {
        val settings = EinkSettings(profile = EinkProfile.TEXT_BITONAL)
        EinkPipeline.ditherImage(IntArray(0), 0, 0, settings)
        EinkPipeline.ditherImage(IntArray(3), -1, 2, settings)
        EinkPipeline.apply(IntArray(0), EinkSettings(profile = EinkProfile.FULL_BITONAL))
    }

    private fun assertArrayEquals(expected: IntArray, actual: IntArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("pixel $i", Integer.toHexString(expected[i]), Integer.toHexString(actual[i]))
        }
    }
}
