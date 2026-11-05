package com.pdfpaper.convert

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontMappingTest {

    @Test
    fun `standard families map to office equivalents`() {
        assertEquals("Arial", FontMapping.of("Helvetica").family)
        assertEquals("Times New Roman", FontMapping.of("Times-Roman").family)
        assertEquals("Courier New", FontMapping.of("Courier").family)
        assertEquals("Arial", FontMapping.of("Calibri").family)
        assertEquals("Times New Roman", FontMapping.of("Georgia").family)
    }

    @Test
    fun `subset prefix is stripped before matching`() {
        val mapped = FontMapping.of("ABCDEF+Arial-BoldMT")
        assertEquals("Arial", mapped.family)
        assertTrue(mapped.bold)
        assertFalse(mapped.italic)
        assertTrue(mapped.recognized)
    }

    @Test
    fun `style flags come from the name`() {
        val italic = FontMapping.of("TimesNewRomanPS-ItalicMT")
        assertTrue(italic.italic)
        assertFalse(italic.bold)

        val heavy = FontMapping.of("Helvetica-BlackOblique")
        assertTrue(heavy.bold)
        assertTrue(heavy.italic)
    }

    @Test
    fun `symbol fonts get a look-alike`() {
        assertEquals("Symbol", FontMapping.of("Symbol").family)
        assertEquals("Wingdings", FontMapping.of("ZapfDingbats").family)
    }

    @Test
    fun `unknown and missing names fall back to Arial and are flagged`() {
        val unknown = FontMapping.of("TotallyMadeUpFont")
        assertEquals("Arial", unknown.family)
        assertFalse(unknown.recognized)

        val missing = FontMapping.of(null)
        assertFalse(missing.recognized)
        assertEquals("Arial", missing.family)
    }
}
