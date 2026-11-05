package com.pdfpaper.app.data

import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.render.ColorTransform
import com.pdfpaper.engine.render.PageFilter
import com.pdfpaper.engine.render.PageTheme
import com.pdfpaper.engine.render.PageThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T62: how a document's look is stored and chosen.
 *
 * The storage format must round-trip exactly, a record from another version
 * must decode to null rather than to a wrong look, and the resolution order is
 * fixed: the document's own look, then the old T32b per-document theme, then
 * the global default, then the T32 global theme - with "follow system" only
 * ever removing a dark look from a document that has none of its own.
 */
class DocumentLookTest {

    private val trueBlack = PageTheme(mode = PageThemeMode.TRUE_BLACK)
    private val warmInk = PdfColor(0.95, 0.9, 0.8)

    // --------------------------------------------------------------- codec

    @Test
    fun aLookRoundTripsThroughTheCodec() {
        val look = ColorTransform(
            filter = PageFilter.INVERTED_MONO,
            warmth = 0.31,
            tint = PdfColor(1.0, 0.85, 0.6),
            brightness = -0.12,
            contrast = 1.45,
            gamma = 0.8
        )
        val decoded = DocumentLookCodec.decode(DocumentLookCodec.encode(look))
        assertNotNull(decoded)
        assertEquals(look.filter, decoded!!.filter)
        assertEquals(look.warmth, decoded.warmth, 1.0e-6)
        assertEquals(look.brightness, decoded.brightness, 1.0e-6)
        assertEquals(look.contrast, decoded.contrast, 1.0e-6)
        assertEquals(look.gamma, decoded.gamma, 1.0e-6)
        assertEquals(rgbOf(look.tint), rgbOf(decoded.tint))
    }

    @Test
    fun aNeutralLookRoundTripsAsNeutral() {
        val decoded = DocumentLookCodec.decode(DocumentLookCodec.encode(ColorTransform()))
        // The tint is stored on the 8-bit grid, so compare with the same grid.
        assertEquals(ColorTransform.NONE.normalized(), decoded)
        assertFalse(decoded!!.active)
        assertEquals(PageFilter.NORMAL, decoded.filter)
    }

    @Test
    fun anotherVersionOrGarbageDecodesToNull() {
        val good = DocumentLookCodec.encode(ColorTransform(filter = PageFilter.SEPIA))
        assertNotNull(DocumentLookCodec.decode(good))
        assertNull(DocumentLookCodec.decode(null))
        assertNull(DocumentLookCodec.decode(""))
        assertNull(DocumentLookCodec.decode("2|SEPIA|0.5|16777215|0.0|1.0|1.0|0"))
        assertNull(DocumentLookCodec.decode("1|SEPIA"))
        assertNull(DocumentLookCodec.decode(good.replaceFirst("|SEPIA|", "|NOPE|").replace("0.5", "x")))
        // An unknown filter name falls back to Normal rather than failing.
        val unknown = DocumentLookCodec.decode(good.replaceFirst("SEPIA", "TEAL"))
        assertEquals(PageFilter.NORMAL, unknown?.filter)
    }

    @Test
    fun theCodecClampsOutOfRangeValues() {
        val wild = DocumentLookCodec.decode("1|SEPIA|9.0|16777215|5.0|9.0|0.01|0")
        assertNotNull(wild)
        assertEquals(1.0, wild!!.warmth, 1.0e-9)
        assertEquals(ColorTransform.MAX_BRIGHTNESS, wild.brightness, 1.0e-9)
        assertEquals(ColorTransform.MAX_CONTRAST, wild.contrast, 1.0e-9)
        assertEquals(ColorTransform.MIN_GAMMA, wild.gamma, 1.0e-9)
    }

    // ---------------------------------------------------------- resolution

    @Test
    fun aDocumentsOwnLookWins() {
        val look = ColorTransform(filter = PageFilter.SEPIA)
        val resolved = LookResolution.resolve(
            baseTheme = trueBlack,
            documentLook = look,
            legacyMode = PageThemeMode.TRUE_BLACK,
            defaultLook = ColorTransform(filter = PageFilter.HIGH_CONTRAST),
            followSystem = false,
            systemDark = true
        )
        assertEquals(look, resolved.transform)
        assertTrue(resolved.fromDocument)
        assertEquals(PageThemeMode.OFF, resolved.theme.mode)
        assertEquals(PageThemeMode.OFF, resolved.documentMode)
        // The global ink and dim still come from the T32 settings.
        assertEquals(trueBlack.ink, resolved.theme.ink)
    }

    @Test
    fun theOldPerDocumentThemeStillCountsAsADocumentLook() {
        val resolved = LookResolution.resolve(
            baseTheme = PageTheme(mode = PageThemeMode.DARK),
            legacyMode = PageThemeMode.DARK,
            followSystem = true,
            systemDark = false
        )
        assertTrue(resolved.fromDocument)
        assertEquals(PageFilter.DARK, resolved.transform.filter)
        assertEquals(PageThemeMode.DARK, resolved.theme.mode)
    }

    @Test
    fun theGlobalDefaultIsUsedWhenTheDocumentHasNone() {
        val resolved = LookResolution.resolve(
            baseTheme = PageTheme(mode = PageThemeMode.DARK),
            defaultLook = ColorTransform(filter = PageFilter.INVERTED_MONO),
            followSystem = false,
            systemDark = true
        )
        assertFalse(resolved.fromDocument)
        assertEquals(PageFilter.INVERTED_MONO, resolved.transform.filter)
        assertEquals(PageThemeMode.OFF, resolved.theme.mode)
        assertNull(resolved.documentMode)
    }

    @Test
    fun withNothingSavedTheT32ThemeIsTheLook() {
        val resolved = LookResolution.resolve(
            baseTheme = trueBlack,
            followSystem = false,
            systemDark = true
        )
        assertEquals(PageFilter.TRUE_BLACK, resolved.transform.filter)
        assertEquals(PageThemeMode.TRUE_BLACK, resolved.theme.mode)
        assertFalse(resolved.fromDocument)
    }

    @Test
    fun followSystemOnlyOverridesADocumentsWithoutTheirOwnLook() {
        val lightSystem = LookResolution.resolve(
            baseTheme = trueBlack,
            followSystem = true,
            systemDark = false
        )
        assertEquals(PageFilter.NORMAL, lightSystem.transform.filter)
        assertEquals(PageThemeMode.OFF, lightSystem.theme.mode)

        // A reader who chose Sepia for a book keeps it when the system is light.
        val sepia = ColorTransform(filter = PageFilter.SEPIA)
        val kept = LookResolution.resolve(
            baseTheme = trueBlack,
            documentLook = sepia,
            followSystem = true,
            systemDark = false
        )
        assertEquals(sepia, kept.transform)
        assertEquals(PageThemeMode.OFF, kept.theme.mode)
    }

    @Test
    fun followSystemAlsoOverridesTheGlobalDefault() {
        // The default is a default; "follow the system" is the reader's rule.
        val resolved = LookResolution.resolve(
            baseTheme = PageTheme(mode = PageThemeMode.DARK),
            defaultLook = ColorTransform(filter = PageFilter.DARK),
            followSystem = true,
            systemDark = false
        )
        assertEquals(PageFilter.NORMAL, resolved.transform.filter)
        assertEquals(PageThemeMode.OFF, resolved.theme.mode)
    }

    @Test
    fun aNormalLookOnAGloballyDarkDocumentStaysLight() {
        val resolved = LookResolution.resolve(
            baseTheme = PageTheme(mode = PageThemeMode.TRUE_BLACK),
            documentLook = ColorTransform.NONE,
            followSystem = false,
            systemDark = true
        )
        assertTrue(resolved.fromDocument)
        assertEquals(PageThemeMode.OFF, resolved.theme.mode)
        assertEquals(PdfColor.WHITE, resolved.theme.background)
    }

    @Test
    fun theSliderOnlyLookIsKeptAndStillPaintsLight() {
        val dimmed = ColorTransform(brightness = -0.1)
        val resolved = LookResolution.resolve(baseTheme = trueBlack, documentLook = dimmed)
        assertEquals(dimmed, resolved.transform)
        assertEquals(PageThemeMode.OFF, resolved.theme.mode)
        assertTrue(resolved.transform.active)
    }

    @Test
    fun theThemeKeepsTheWarmInkOfTheReader() {
        val base = PageTheme(mode = PageThemeMode.TRUE_BLACK, ink = warmInk, dim = 0.6)
        val resolved = LookResolution.resolve(
            baseTheme = base,
            documentLook = ColorTransform(filter = PageFilter.TRUE_BLACK)
        )
        assertEquals(warmInk, resolved.theme.ink)
        assertEquals(0.6, resolved.theme.dim, 1.0e-9)
        assertEquals(PageThemeMode.TRUE_BLACK, resolved.theme.mode)
    }
}
