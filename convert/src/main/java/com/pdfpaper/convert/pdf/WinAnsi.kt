package com.pdfpaper.convert.pdf

/**
 * The WinAnsi encoding (§9.6.6, Annex D): the byte codes a standard font's
 * `/Encoding /WinAnsiEncoding` maps to glyphs. It is CP1252, so codes 0x20–0x7E
 * and 0xA0–0xFF are identity and only 0x80–0x9F carry typographic characters.
 *
 * Characters with no code are written as '?' and collected by the caller for
 * the report — a silent substitution would be a silent content loss.
 */
internal object WinAnsi {

    private val SPECIAL_TO_CODE: Map<Char, Int> = mapOf(
        '\u20AC' to 0x80, '\u201A' to 0x82, '\u0192' to 0x83, '\u201E' to 0x84,
        '\u2026' to 0x85, '\u2020' to 0x86, '\u2021' to 0x87, '\u02C6' to 0x88,
        '\u2030' to 0x89, '\u0160' to 0x8A, '\u2039' to 0x8B, '\u0152' to 0x8C,
        '\u017D' to 0x8E, '\u2018' to 0x91, '\u2019' to 0x92, '\u201C' to 0x93,
        '\u201D' to 0x94, '\u2022' to 0x95, '\u2013' to 0x96, '\u2014' to 0x97,
        '\u02DC' to 0x98, '\u2122' to 0x99, '\u0161' to 0x9A, '\u203A' to 0x9B,
        '\u0153' to 0x9C, '\u017E' to 0x9E, '\u0178' to 0x9F
    )

    private val CODE_TO_CHAR: Map<Int, Char> = SPECIAL_TO_CODE.entries.associate { (ch, code) -> code to ch }

    /** The WinAnsi code for [ch], or '?' when the encoding has none. */
    fun codeOf(ch: Char): Int = when {
        ch == '\u00AD' -> '?'.code // a soft hyphen is a layout hint, never a glyph
        ch.code < 0x20 -> '?'.code
        ch.code < 0x7F -> ch.code
        ch.code in 0xA0..0xFF -> ch.code
        else -> SPECIAL_TO_CODE[ch] ?: '?'.code
    }

    /** The character a WinAnsi [code] renders, or null for the undefined slots. */
    fun charOf(code: Int): Char? = when {
        code in 0x20..0x7E -> code.toChar()
        code in 0xA0..0xFF -> code.toChar()
        else -> CODE_TO_CHAR[code]
    }

    /** The characters of [text] that WinAnsi cannot represent, in order. */
    fun unmappable(text: String): List<Char> =
        text.filter { it.code > 0x7F && codeOf(it) == '?'.code && it != '\u00AD' }.toList()

    /** [text] with soft hyphens removed — the layout carries no hyphenation. */
    fun stripSoftHyphens(text: String): String =
        if (text.indexOf('\u00AD') < 0) text else text.replace("\u00AD", "")

    fun encode(text: String): ByteArray {
        val out = ByteArray(text.length)
        for ((i, ch) in text.withIndex()) out[i] = codeOf(ch).toByte()
        return out
    }
}
