package com.pdfpaper.convert.pdf

/**
 * The PDF standard 14 text fonts used for DOCX→PDF output (§9.6.2.2). A DOCX
 * usually names its own fonts (Calibri, Arial, Times New Roman, ...) and embeds
 * none, so the converter maps the family onto a font every reader has and
 * reports the substitution instead of silently inventing a font program.
 */
internal enum class StandardFont(
    val baseName: String,
    val bold: Boolean,
    val italic: Boolean,
    val family: Family
) {
    HELVETICA("Helvetica", false, false, Family.SANS),
    HELVETICA_BOLD("Helvetica-Bold", true, false, Family.SANS),
    HELVETICA_OBLIQUE("Helvetica-Oblique", false, true, Family.SANS),
    HELVETICA_BOLD_OBLIQUE("Helvetica-BoldOblique", true, true, Family.SANS),
    TIMES_ROMAN("Times-Roman", false, false, Family.SERIF),
    TIMES_BOLD("Times-Bold", true, false, Family.SERIF),
    TIMES_ITALIC("Times-Italic", false, true, Family.SERIF),
    TIMES_BOLD_ITALIC("Times-BoldItalic", true, true, Family.SERIF),
    COURIER("Courier", false, false, Family.MONO),
    COURIER_BOLD("Courier-Bold", true, false, Family.MONO),
    COURIER_OBLIQUE("Courier-Oblique", false, true, Family.MONO),
    COURIER_BOLD_OBLIQUE("Courier-BoldOblique", true, true, Family.MONO);

    enum class Family { SANS, SERIF, MONO }

    companion object {
        /**
         * True when [family] names one of the fourteen standard fonts (or is
         * absent): only then does the output use the document's own font.
         */
        fun isStandard(family: String?): Boolean {
            val name = family?.trim()?.lowercase() ?: return true
            return values().any { it.baseName.lowercase() == name }
        }
    }
}

internal object StandardFonts {

    private val SANS = setOf(
        "arial", "helvetica", "calibri", "segoe ui", "roboto", "verdana", "tahoma",
        "trebuchet ms", "comic sans ms", "candara", "corbel", "lato", "open sans",
        "source sans pro", "noto sans", "dejavu sans", "liberation sans", "franklin gothic",
        "gill sans", "futura", "century gothic", "sans-serif", "sans", "system-ui", "calibri light"
    )
    private val SERIF = setOf(
        "times new roman", "times", "garamond", "georgia", "cambria", "book antiqua",
        "palatino linotype", "bookman old style", "constantia", "serif", "liberation serif",
        "dejavu serif", "noto serif", "source serif pro", "rockwell", "baskerville", "cambria math"
    )
    private val MONO = setOf(
        "courier new", "courier", "consolas", "monaco", "menlo", "lucida console",
        "monospace", "dejavu sans mono", "liberation mono", "roboto mono", "source code pro",
        "noto sans mono", "cascadia mono", "cascadia code", "sf mono"
    )

    /** True when [family] is one the converter knows; used for the report. */
    fun recognised(family: String?): Boolean {
        val name = family?.trim()?.lowercase() ?: return true
        return name in SANS || name in SERIF || name in MONO
    }

    /**
     * Maps a font family name plus the bold/italic flags onto a standard font.
     * Unknown families come out as Helvetica; the caller reports them once.
     */
    fun resolve(family: String?, bold: Boolean, italic: Boolean): StandardFont {
        val name = family?.trim()?.lowercase()
        val kind = when {
            name == null -> StandardFont.Family.SANS
            name in MONO -> StandardFont.Family.MONO
            name in SERIF -> StandardFont.Family.SERIF
            name in SANS -> StandardFont.Family.SANS
            // A word in the name is a better guess than nothing.
            name.contains("mono") || name.contains("courier") -> StandardFont.Family.MONO
            name.contains("times") || name.contains("serif") -> StandardFont.Family.SERIF
            else -> StandardFont.Family.SANS
        }
        return when (kind) {
            StandardFont.Family.SANS -> when {
                bold && italic -> StandardFont.HELVETICA_BOLD_OBLIQUE
                bold -> StandardFont.HELVETICA_BOLD
                italic -> StandardFont.HELVETICA_OBLIQUE
                else -> StandardFont.HELVETICA
            }
            StandardFont.Family.SERIF -> when {
                bold && italic -> StandardFont.TIMES_BOLD_ITALIC
                bold -> StandardFont.TIMES_BOLD
                italic -> StandardFont.TIMES_ITALIC
                else -> StandardFont.TIMES_ROMAN
            }
            StandardFont.Family.MONO -> when {
                bold && italic -> StandardFont.COURIER_BOLD_OBLIQUE
                bold -> StandardFont.COURIER_BOLD
                italic -> StandardFont.COURIER_OBLIQUE
                else -> StandardFont.COURIER
            }
        }
    }

    /** Advance width of one WinAnsi code in 1/1000 em (§9.7.4). */
    fun width1000(font: StandardFont, code: Int): Int = StandardWidths.ALL[font.ordinal][code and 0xFF]

    /** AFM ascender in 1/1000 em (positive). */
    fun ascent1000(font: StandardFont): Int = StandardWidths.ASCENT[font.ordinal]

    /** AFM descender in 1/1000 em (negative). */
    fun descent1000(font: StandardFont): Int = StandardWidths.DESCENT[font.ordinal]

    /**
     * One line at single spacing: the font's ascent and descent plus 0.2 em of
     * leading. Word derives its "single" from the *source* font metrics, which
     * a substituted standard font does not have, so the leading is the one
     * approximation in the layout (reported).
     */
    fun singleLineHeight(font: StandardFont, size: Double): Double =
        size * (ascent1000(font) - descent1000(font) + 200) / 1000.0

    /**
     * Width of [text] in 1/1000 em. Characters WinAnsi cannot encode are
     * measured as '?', which is what [WinAnsi.encode] writes for them.
     */
    fun advance1000(font: StandardFont, text: String): Int {
        var total = 0
        for (ch in text) total += width1000(font, WinAnsi.codeOf(ch))
        return total
    }

    /** Width of [text] in points at [size]. */
    fun width(font: StandardFont, text: String, size: Double): Double =
        advance1000(font, text) * size / 1000.0
}
