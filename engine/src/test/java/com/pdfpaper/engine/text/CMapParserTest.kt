package com.pdfpaper.engine.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Direct coverage of the ToUnicode CMap parser (tolerance paths included). */
class CMapParserTest {

    @Test
    fun parsesBfcharPairsWithLowByteAlias() {
        val map = CMapParser.parse(
            "begincmap\n2 beginbfchar\n<41> <00E7>\n<0042> <00C5>\nendbfchar\nendcmap".toByteArray(Charsets.ISO_8859_1)
        )
        assertEquals("ç", map[0x41])
        assertEquals("Å", map[0x42])
        // Two-byte source also aliases to the single-byte code.
        assertEquals("Å", map[0x42])
    }

    @Test
    fun parsesBfrangeBaseForm() {
        val map = CMapParser.parse(
            "begincmap\n1 beginbfrange\n<31> <33> <0250>\nendbfrange\nendcmap".toByteArray(Charsets.ISO_8859_1)
        )
        assertEquals("\u0250", map[0x31])
        assertEquals("\u0251", map[0x32])
        assertEquals("\u0252", map[0x33])
    }

    @Test
    fun parsesBfrangeArrayForm() {
        val map = CMapParser.parse(
            "begincmap\n1 beginbfrange\n<40> <42> [<2222> <2023> <2424>]\nendbfrange\nendcmap".toByteArray(Charsets.ISO_8859_1)
        )
        assertEquals("\u2222", map[0x40])
        assertEquals("\u2023", map[0x41])
        assertEquals("\u2424", map[0x42])
    }

    @Test
    fun decodesUtf16BeSurrogateDestination() {
        val map = CMapParser.parse(
            "begincmap\n1 beginbfchar\n<00> <D83DDE00>\nendbfchar\nendcmap".toByteArray(Charsets.ISO_8859_1)
        )
        assertEquals("\uD83D\uDE00", map[0])
    }

    @Test
    fun keepsMultiCodepointDestination() {
        val map = CMapParser.parse(
            "begincmap\n1 beginbfchar\n<58> <00410042>\nendbfchar\nendcmap".toByteArray(Charsets.ISO_8859_1)
        )
        assertEquals("AB", map[0x58])
    }

    @Test
    fun cidKeysKeepFullValueAndAliasLowByte() {
        val map = CMapParser.parse(
            "begincmap\n1 beginbfchar\n<0102> <0041>\nendbfchar\nendcmap".toByteArray(Charsets.ISO_8859_1)
        )
        assertEquals("A", map[0x0102])
        assertEquals("A", map[0x02])
    }

    @Test
    fun malformedInputYieldsEmptyMapWithoutCrash() {
        assertTrue(CMapParser.parse("beginbfchar <zz bad açı".toByteArray(Charsets.ISO_8859_1)).isEmpty())
        assertTrue(CMapParser.parse("beginbfrange <40> [ <A>]\n".toByteArray(Charsets.ISO_8859_1)).isEmpty())
        assertTrue(CMapParser.parse(ByteArray(0)).isEmpty())
    }

    @Test
    fun commentsAndWhitespaceAreSkipped() {
        val src = listOf(
            "% comment line",
            "begincmap",
            "1 beginbfrange",
            "% inline comment",
            "<20> <21> <0061>",
            "endbfrange",
            "endcmap"
        ).joinToString("\n")
        val map = CMapParser.parse(src.toByteArray(Charsets.ISO_8859_1))
        assertEquals("a", map[0x20])
        assertEquals("b", map[0x21])
    }
}
