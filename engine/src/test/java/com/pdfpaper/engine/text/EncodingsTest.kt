package com.pdfpaper.engine.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Spot checks of the byte-verified encoding tables and glyph names. */
class EncodingsTest {

    @Test
    fun winAnsiSpecialRows() {
        assertEquals("\u20AC", Encodings.winAnsi(0x80)) // €
        assertEquals("\u2019", Encodings.winAnsi(0x92)) // quoteright
        assertEquals("\u0153", Encodings.winAnsi(0x9C)) // œ
        assertEquals("", Encodings.winAnsi(0x81)) // undefined: dropped
        assertEquals("A", Encodings.winAnsi(0x41))
        assertEquals("\u00E9", Encodings.winAnsi(0xE9)) // Latin-1 identity
        assertEquals("", Encodings.winAnsi(0x0A)) // control codes dropped
    }

    @Test
    fun standardEncodingQuoterightAt0x27() {
        assertEquals("\u2019", Encodings.standard(0x27))
        assertEquals("A", Encodings.standard(0x41))
    }

    @Test
    fun macRomanRows() {
        assertEquals("\u00E3", Encodings.macRoman(0x8B)) // ã
        assertEquals("\u2013", Encodings.macRoman(0xD0)) // –
        assertEquals("\uFB01", Encodings.macRoman(0xDE)) // fi ligature
    }

    @Test
    fun glyphNamesResolveToUnicode() {
        assertEquals("Å", Encodings.glyphToUnicode("Aring"))
        assertEquals("ã", Encodings.glyphToUnicode("atilde"))
        assertEquals("–", Encodings.glyphToUnicode("endash"))
        assertEquals("€", Encodings.glyphToUnicode("Euro"))
        assertEquals("\u2019", Encodings.glyphToUnicode("quoteright"))
        assertEquals("A", Encodings.glyphToUnicode("A"))
        assertNull(Encodings.glyphToUnicode("noSuchGlyph"))
    }

    @Test
    fun uniAndUPatterns() {
        assertEquals("\u0041", Encodings.glyphToUnicode("uni0041"))
        assertEquals("AB", Encodings.glyphToUnicode("uni00410042"))
        assertEquals("\u2022", Encodings.glyphToUnicode("u2022"))
    }
}
