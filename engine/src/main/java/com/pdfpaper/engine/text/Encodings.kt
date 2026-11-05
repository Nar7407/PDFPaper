package com.pdfpaper.engine.text

/**
 * Built-in encodings and glyph-name resolution (ISO 32000-1, §Appendix D
 * "Character sets and encodings"; /Differences per §9.6.6, Table 115).
 *
 * Tables were generated from the Python codecs (cp1252, mac_roman) so the
 * data is byte-verified. Undefined codes decode to "" (extraction drops them)
 * instead of throwing.
 */
internal object Encodings {

    /** CP1252 (= WinAnsi, Table D.1) rows 0x80-0x9F; rest is ASCII + Latin-1. */
    private val CP1252: Map<Int, Char> = mapOf(
        0x80 to '\u20AC', 0x81 to '\uFFFD', 0x82 to '\u201A', 0x83 to '\u0192',
        0x84 to '\u201E', 0x85 to '\u2026', 0x86 to '\u2020', 0x87 to '\u2021',
        0x88 to '\u02C6', 0x89 to '\u2030', 0x8A to '\u0160', 0x8B to '\u2039',
        0x8C to '\u0152', 0x8D to '\uFFFD', 0x8E to '\u017D', 0x8F to '\uFFFD',
        0x90 to '\uFFFD', 0x91 to '\u2018', 0x92 to '\u2019', 0x93 to '\u201C',
        0x94 to '\u201D', 0x95 to '\u2022', 0x96 to '\u2013', 0x97 to '\u2014',
        0x98 to '\u02DC', 0x99 to '\u2122', 0x9A to '\u0161', 0x9B to '\u203A',
        0x9C to '\u0153', 0x9D to '\uFFFD', 0x9E to '\u017E', 0x9F to '\u0178'
    )

    /**
     * StandardEncoding (Table D.2) approximated from CP1252 with the
     * quoteright-at-0x27 override; fine for text extraction of Western files.
     */
    private val STANDARD_OVERRIDES: Map<Int, String> = mapOf(0x27 to "\u2019")

    /**
     * MacRomanEncoding (Table D.4), verified against the Python mac_roman
     * codec (CP1000).
     */
    private val MACROMAN: Map<Int, Char> = mapOf(
        0x80 to '\u00C4', 0x81 to '\u00C5', 0x82 to '\u00C7', 0x83 to '\u00C9',
        0x84 to '\u00D1', 0x85 to '\u00D6', 0x86 to '\u00DC', 0x87 to '\u00E1',
        0x88 to '\u00E0', 0x89 to '\u00E2', 0x8A to '\u00E4', 0x8B to '\u00E3',
        0x8C to '\u00E5', 0x8D to '\u00E7', 0x8E to '\u00E9', 0x8F to '\u00E8',
        0x90 to '\u00EA', 0x91 to '\u00EB', 0x92 to '\u00ED', 0x93 to '\u00EC',
        0x94 to '\u00EE', 0x95 to '\u00EF', 0x96 to '\u00F1', 0x97 to '\u00F3',
        0x98 to '\u00F2', 0x99 to '\u00F4', 0x9A to '\u00F6', 0x9B to '\u00F5',
        0x9C to '\u00FA', 0x9D to '\u00F9', 0x9E to '\u00FB', 0x9F to '\u00FC',
        0xA0 to '\u2020', 0xA1 to '\u00B0', 0xA2 to '\u00A2', 0xA3 to '\u00A3',
        0xA4 to '\u00A7', 0xA5 to '\u2022', 0xA6 to '\u00B6', 0xA7 to '\u00DF',
        0xA8 to '\u00AE', 0xA9 to '\u00A9', 0xAA to '\u2122', 0xAB to '\u00B4',
        0xAC to '\u00A8', 0xAD to '\u2260', 0xAE to '\u00C6', 0xAF to '\u00D8',
        0xB0 to '\u221E', 0xB1 to '\u00B1', 0xB2 to '\u2264', 0xB3 to '\u2265',
        0xB4 to '\u00A5', 0xB5 to '\u00B5', 0xB6 to '\u2202', 0xB7 to '\u2211',
        0xB8 to '\u220F', 0xB9 to '\u03C0', 0xBA to '\u222B', 0xBB to '\u00AA',
        0xBC to '\u00BA', 0xBD to '\u03A9', 0xBE to '\u00E6', 0xBF to '\u00F8',
        0xC0 to '\u00BF', 0xC1 to '\u00A1', 0xC2 to '\u00AC', 0xC3 to '\u221A',
        0xC4 to '\u0192', 0xC5 to '\u2248', 0xC6 to '\u2206', 0xC7 to '\u00AB',
        0xC8 to '\u00BB', 0xC9 to '\u2026', 0xCA to '\u00A0', 0xCB to '\u00C0',
        0xCC to '\u00C3', 0xCD to '\u00D5', 0xCE to '\u0152', 0xCF to '\u0153',
        0xD0 to '\u2013', 0xD1 to '\u2014', 0xD2 to '\u201C', 0xD3 to '\u201D',
        0xD4 to '\u2018', 0xD5 to '\u2019', 0xD6 to '\u00F7', 0xD7 to '\u25CA',
        0xD8 to '\u00FF', 0xD9 to '\u0178', 0xDA to '\u2044', 0xDB to '\u20AC',
        0xDC to '\u2039', 0xDD to '\u203A', 0xDE to '\uFB01', 0xDF to '\uFB02',
        0xE0 to '\u2021', 0xE1 to '\u00B7', 0xE2 to '\u201A', 0xE3 to '\u201E',
        0xE4 to '\u2030', 0xE5 to '\u00C2', 0xE6 to '\u00CA', 0xE7 to '\u00C1',
        0xE8 to '\u00CB', 0xE9 to '\u00C8', 0xEA to '\u00CD', 0xEB to '\u00CE',
        0xEC to '\u00CF', 0xED to '\u00CC', 0xEE to '\u00D3', 0xEF to '\u00D4',
        0xF0 to '\uFFFD', 0xF1 to '\u00D2', 0xF2 to '\u00DA', 0xF3 to '\u00DB',
        0xF4 to '\u00D9', 0xF5 to '\u0131', 0xF6 to '\u02C6', 0xF7 to '\u02DC',
        0xF8 to '\u00AF', 0xF9 to '\u02D8', 0xFA to '\u02D9', 0xFB to '\u02DA',
        0xFC to '\u00B8', 0xFD to '\u02DD', 0xFE to '\u02DB', 0xFF to '\u02C7'
    )

    fun winAnsi(code: Int): String = singleByte(code, CP1252, emptyMap())

    fun standard(code: Int): String = singleByte(code, CP1252, STANDARD_OVERRIDES)

    fun macRoman(code: Int): String = singleByte(code, MACROMAN, emptyMap())

    private fun singleByte(code: Int, special: Map<Int, Char>, overrides: Map<Int, String>): String =
        when {
            code in overrides -> overrides.getValue(code)
            code in 0x20..0x7E -> code.toChar().toString()
            code in special -> special.getValue(code).toString().let {
                // Private-use / replacement rows are dropped from text output.
                if (it == "\uFFFD") "" else it
            }
            code in 0xA0..0xFF -> code.toChar().toString()
            else -> "" // control codes: undefined, silently dropped (§7.2)
        }

    enum class Base { STANDARD, WIN_ANSI, MAC_ROMAN }

    fun byName(name: String?): Base = when (name) {
        "StandardEncoding" -> Base.STANDARD
        "WinAnsiEncoding", "PDFDocEncoding" -> Base.WIN_ANSI // PDFDoc approximated (T14 cand.)
        "MacRomanEncoding" -> Base.MAC_ROMAN
        else -> Base.STANDARD
    }

    fun decode(base: Base, code: Int): String = when (base) {
        Base.STANDARD -> standard(code)
        Base.WIN_ANSI -> winAnsi(code)
        Base.MAC_ROMAN -> macRoman(code)
    }

    /**
     * PostScript glyph name → Unicode (a compact subset of the AGL covering
     * ASCII, Latin-1 and common punctuation), plus the uniXXXX/uXXXX
     * patterns and the "aXX" component pattern from the spec.
     */
    fun glyphToUnicode(name: String): String? {
        GLYPH_NAMES[name]?.let { return it.toString() }
        // uniXXXX (§d.1.x): one or more 4-digit groups.
        if (name.startsWith("uni") && name.length >= 7 && name.length % 4 == 3) {
            val groups = (name.length - 3) / 4
            val sb = StringBuilder()
            repeat(groups) { g ->
                val hex = name.substring(3 + g * 4, 7 + g * 4)
                val cp = hex.toIntOrNull(16) ?: return null
                sb.appendCodePoint(cp)
            }
            return sb.toString()
        }
        // uXXXX[XX]: arbitrary code points.
        if (name.startsWith("u") && name.length in 5..8) {
            name.substring(1).toIntOrNull(16)?.let { return String(Character.toChars(it)) }
        }
        // aXX (AFB components, e.g. a105 = hat glyph): keep lookup of the
        // standard pieces; unknown names become "" (dropped) upstream.
        return null
    }

    private val GLYPH_NAMES: Map<String, Char> = buildMap {
        // ASCII punctuation names.
        val punctuation = listOf(
            "space" to ' ', "exclam" to '!', "quotedbl" to '"', "numbersign" to '#',
            "dollar" to '$', "percent" to '%', "ampersand" to '&', "quoteright" to '\u2019',
            "parenleft" to '(', "parenright" to ')', "asterisk" to '*', "plus" to '+',
            "comma" to ',', "hyphen" to '-', "period" to '.', "slash" to '/',
            "colon" to ':', "semicolon" to ';', "less" to '<', "equal" to '=',
            "greater" to '>', "question" to '?', "at" to '@', "quotesingle" to '\'',
            "bracketleft" to '[', "backslash" to '\\', "bracketright" to ']',
            "asciicircum" to '^', "underscore" to '_', "grave" to '`',
            "braceleft" to '{', "bar" to '|', "braceright" to '}', "asciitilde" to '~'
        )
        for ((n, c) in punctuation) put(n, c)
        // Digits and letters: names equal the character itself.
        for (c in '0'..'9') put(c.toString(), c)
        for (c in 'A'..'Z') put(c.toString(), c)
        for (c in 'a'..'z') put(c.toString(), c)
        // Latin-1 supplement names, aligned 1:1 with `slot` below (both are
        // 64 entries covering AGL order U+00C0..U+00FF).
        val named = listOf(
            "Agrave", "Aacute", "Acircumflex", "Atilde", "Adieresis", "Aring", "AE",
            "Ccedilla", "Egrave", "Eacute", "Ecircumflex", "Edieresis", "Igrave",
            "Iacute", "Icircumflex", "Idieresis", "Eth", "Ntilde", "Ograve", "Oacute",
            "Ocircumflex", "Otilde", "Odieresis", "multiply", "Oslash", "Ugrave",
            "Uacute", "Ucircumflex", "Udieresis", "Yacute", "Thorn", "germandbls",
            "agrave", "aacute", "acircumflex", "atilde", "adieresis", "aring", "ae",
            "ccedilla", "egrave", "eacute", "ecircumflex", "edieresis", "igrave",
            "iacute", "icircumflex", "idieresis", "eth", "ntilde", "ograve", "oacute",
            "ocircumflex", "otilde", "odieresis", "divide", "oslash", "ugrave",
            "uacute", "ucircumflex", "udieresis", "yacute", "thorn", "ydieresis"
        )
        // Slot order follows the AGL: U+00C0..U+00FF with the reserved gaps
        // at ×/÷ explicitly present (named["multiply"]/["divide"] use them).
        val slot = listOf(
            '\u00C0', '\u00C1', '\u00C2', '\u00C3', '\u00C4', '\u00C5', '\u00C6', '\u00C7',
            '\u00C8', '\u00C9', '\u00CA', '\u00CB', '\u00CC', '\u00CD', '\u00CE', '\u00CF',
            '\u00D0', '\u00D1', '\u00D2', '\u00D3', '\u00D4', '\u00D5', '\u00D6', '\u00D7',
            '\u00D8', '\u00D9', '\u00DA', '\u00DB', '\u00DC', '\u00DD', '\u00DE', '\u00DF',
            '\u00E0', '\u00E1', '\u00E2', '\u00E3', '\u00E4', '\u00E5', '\u00E6', '\u00E7',
            '\u00E8', '\u00E9', '\u00EA', '\u00EB', '\u00EC', '\u00ED', '\u00EE', '\u00EF',
            '\u00F0', '\u00F1', '\u00F2', '\u00F3', '\u00F4', '\u00F5', '\u00F6', '\u00F7',
            '\u00F8', '\u00F9', '\u00FA', '\u00FB', '\u00FC', '\u00FD', '\u00FE', '\u00FF'
        )
        require(named.size == slot.size)
        for ((i, n) in named.withIndex()) put(n, slot[i])
        // Common extra names used in /Differences.
        put("endash", '\u2013')
        put("emdash", '\u2014')
        put("quotedblleft", '\u201C')
        put("quotedblright", '\u201D')
        put("quotedblbase", '\u201E')
        put("quoteleft", '\u2018')
        put("bullet", '\u2022')
        put("ellipsis", '\u2026')
        put("perthousand", '\u2030')
        put("euro", '\u20AC')
        put("Euro", '\u20AC')
        put("copyright", '\u00A9')
        put("registered", '\u00AE')
        put("trademark", '\u2122')
        put("degrees", '\u00B0')
        put("guillemotleft", '«')
        put("guillemotright", '»')
        put("guilsinglleft", '\u2039')
        put("guilsinglright", '\u203A')
        put("fi", '\uFB01')
        put("fl", '\uFB02')
        put("minus", '\u2212')
        put("periodcentered", '\u00B7')
        put("logicalnot", '\u00AC')
        put("degree", '\u00B0')
        put("plusminus", '\u00B1')
        put("mu", '\u00B5')
        put("paragraph", '\u00B6')
        put("exclamdown", '\u00A1')
        put("cent", '\u00A2')
        put("sterling", '\u00A3')
        put("fraction", '\u2044')
        put("yen", '\u00A5')
        put("brokenbar", '\u00A6')
        put("section", '\u00A7')
        put("dieresis", '\u00A8')
        put("ordfeminine", '\u00AA')
        put("ordmasculine", '\u00BA')
        put("onesuperior", '\u00B9')
        put("twosuperior", '\u00B2')
        put("threesuperior", '\u00B3')
        put("onequarter", '\u00BC')
        put("onehalf", '\u00BD')
        put("threequarters", '\u00BE')
        put("seven", '7') // sanity-anchor: keep letter/digit names stable
    }
}
