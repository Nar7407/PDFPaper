package com.pdfpaper.engine.edit

import com.pdfpaper.engine.text.Encodings

/**
 * A font choice for text added by the editor (ISO 32000-1, §9.6).
 *
 * [Standard] picks one of the standard 14 fonts, which every viewer already
 * has and which therefore never needs embedding (§9.6.2.2, Table 111).
 * [Fallback] is the TrueType font shipped with the engine for text that must
 * travel with the file; it covers the WinAnsi (Latin) range.
 */
sealed interface EditFont {
    data class Standard(val font: StandardFont) : EditFont
    data object Fallback : EditFont
}

/**
 * The standard 14 fonts. [winAnsi] is false for the two symbol fonts, whose
 * built-in encodings are not Latin (§9.6.2.2).
 */
enum class StandardFont(val baseFont: String, val winAnsi: Boolean = true) {
    HELVETICA("Helvetica"),
    HELVETICA_BOLD("Helvetica-Bold"),
    HELVETICA_OBLIQUE("Helvetica-Oblique"),
    HELVETICA_BOLD_OBLIQUE("Helvetica-BoldOblique"),
    TIMES_ROMAN("Times-Roman"),
    TIMES_BOLD("Times-Bold"),
    TIMES_ITALIC("Times-Italic"),
    TIMES_BOLD_ITALIC("Times-BoldItalic"),
    COURIER("Courier"),
    COURIER_BOLD("Courier-Bold"),
    COURIER_OBLIQUE("Courier-Oblique"),
    COURIER_BOLD_OBLIQUE("Courier-BoldOblique"),
    SYMBOL("Symbol", winAnsi = false),
    ZAPF_DINGBATS("ZapfDingbats", winAnsi = false);

    companion object {
        /** Family shorthands users type on the CLI ("times" → Times-Roman, ...). */
        private val ALIASES = mapOf(
            "times" to TIMES_ROMAN,
            "arial" to HELVETICA,
            "courier-new" to COURIER,
            "dingbats" to ZAPF_DINGBATS,
            "zapf" to ZAPF_DINGBATS
        )

        /** Resolves a font name from the CLI or app ("Helvetica-Bold", "courier", "times", ...). */
        fun byName(name: String): StandardFont? {
            val normalised = name.replace('-', '_').replace(' ', '_')
            ALIASES[name.trim().lowercase()]?.let { return it }
            return entries.firstOrNull {
                it.name.equals(normalised, ignoreCase = true) || it.baseFont.equals(name, ignoreCase = true)
            }
        }
    }
}

/**
 * WinAnsi (= CP1252) text encoding for content streams (§D.2). The fallback
 * font is embedded with /Encoding /WinAnsiEncoding, so both the standard and
 * the fallback font share this byte mapping.
 */
internal object WinAnsi {

    private val ENCODE: Map<Char, Int> = buildMap {
        for (code in 0..255) {
            val text = Encodings.winAnsi(code)
            if (text.length == 1) putIfAbsent(text[0], code)
        }
    }

    /** Encodes [text], mapping an unrepresentable character to '?' (§9.6.6). */
    fun encode(text: String): ByteArray {
        val out = ByteArray(text.length)
        for (i in text.indices) {
            out[i] = (ENCODE[text[i]] ?: QuestionMark).toByte()
        }
        return out
    }

    /** The WinAnsi byte for [character], or `'?'` when it has no code. */
    fun codeOf(character: Char): Int = ENCODE[character] ?: QuestionMark

    fun canEncode(character: Char): Boolean = ENCODE.containsKey(character)

    /** Unicode code point for a WinAnsi byte, or 0 when the byte is undefined. */
    fun unicodeOf(code: Int): Int = Encodings.winAnsi(code).firstOrNull()?.code ?: 0

    private const val QuestionMark = 0x3F
}
