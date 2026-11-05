package com.pdfpaper.engine.edit

/**
 * Metrics for the standard 14 text fonts (ISO 32000-1 §9.6.2.2).
 *
 * The widths and the ascent/descent come from the Adobe AFM tables in
 * [StandardWidths]. They let PDF creation wrap and centre text exactly without
 * embedding a font program, and they are written into the generated font
 * dictionaries so other viewers advance the glyphs identically.
 */
object Standard14 {

    /** Advance width of one WinAnsi code in 1/1000 em (§9.7.4). */
    fun width1000(font: StandardFont, code: Int): Int {
        require(font.winAnsi) { "${font.baseFont} does not use WinAnsi encoding" }
        return StandardWidths.ALL[font.ordinal][code and 0xFF]
    }

    /** AFM ascender in 1/1000 em (positive). */
    fun ascent1000(font: StandardFont): Int {
        require(font.winAnsi) { "${font.baseFont} does not use WinAnsi encoding" }
        return StandardWidths.ASCENT[font.ordinal]
    }

    /** AFM descender in 1/1000 em (negative). */
    fun descent1000(font: StandardFont): Int {
        require(font.winAnsi) { "${font.baseFont} does not use WinAnsi encoding" }
        return StandardWidths.DESCENT[font.ordinal]
    }

    /** Width of [text] in points at [size]. Unencodable characters count as '?'. */
    fun width(font: StandardFont, text: String, size: Double): Double {
        require(size.isFinite() && size >= 0.0) { "font size must be finite and non-negative" }
        var total = 0
        for (character in text) {
            total += width1000(font, WinAnsi.codeOf(character))
        }
        return total * size / 1000.0
    }

    /** Ascender of [font] at [size], in points. */
    fun ascent(font: StandardFont, size: Double): Double = ascent1000(font) * size / 1000.0

    /** Descender of [font] at [size], in points (negative). */
    fun descent(font: StandardFont, size: Double): Double = descent1000(font) * size / 1000.0
}
