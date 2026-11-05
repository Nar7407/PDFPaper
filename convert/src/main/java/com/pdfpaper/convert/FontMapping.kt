package com.pdfpaper.convert

/**
 * A font as it will appear in the converted document.
 *
 * The engine's text model gives us the PDF /BaseFont name (with any subset
 * prefix already removed) and nothing else — no embedded style flags, no
 * Unicode-to-PowerPoint mapping — so the family and the bold/italic flags are
 * inferred from the name. That is a lossy step and the report says so.
 */
data class MappedFont(
    /** A family name every Office/LibreOffice install has. */
    val family: String,
    val bold: Boolean,
    val italic: Boolean,
    /** True when the base name matched a family we recognise. */
    val recognized: Boolean,
    /** True for symbol/dingbat fonts mapped to a look-alike family. */
    val symbol: Boolean = false
) {
    /** Short label used in the conversion report. */
    val label: String get() = buildString {
        append(family)
        if (bold) append(" Bold")
        if (italic) append(" Italic")
    }
}

/**
 * Maps a PDF font name to a [MappedFont]. Pure name matching, deliberately
 * conservative: an unknown name becomes Arial and is reported as unrecognised
 * rather than guessed into the wrong family.
 */
object FontMapping {

    private val SUBSET_PREFIX = Regex("^[A-Za-z]{6}\\+")

    private val SERIF_HINTS = listOf(
        "times", "serif", "georgia", "garamond", "cambria", "palatino", "book",
        "minion", "tiro", "roman", "baskerville", "didot", "caslon", "antiqua",
        "slab", "charter", "nimbusrom", "freeserif", "dejavuserif", "liberationserif"
    )
    private val MONO_HINTS = listOf(
        "courier", "mono", "consol", "menlo", "monaco", "cour", "typewriter",
        "inconsolata", "sourcecode", "fira code"
    )
    private val SANS_HINTS = listOf(
        "arial", "helvetica", "calibri", "verdana", "tahoma", "segoe", "futura",
        "frutiger", "gill", "optima", "avenir", "lato", "roboto", "opensans",
        "notosans", "dejavusans", "liberationsans", "nimbussans", "freesans",
        "sans", "gothic", "grotesk", "geneva", "trebuchet", "candara", "corbel",
        "myriad", "helv", "univers", "swiss", "din", "inter", "muli", "nunito"
    )

    fun of(baseFont: String?): MappedFont {
        val raw = baseFont?.trim().orEmpty()
        if (raw.isEmpty()) return MappedFont("Arial", false, false, recognized = false)
        val name = SUBSET_PREFIX.replace(raw, "").lowercase()
        val bold = name.contains("bold") || name.contains("black") ||
            name.contains("heavy") || name.contains("ultra") || name.contains("semibold")
        val italic = name.contains("italic") || name.contains("oblique")

        val symbol = name.contains("symbol") || name.contains("dingbat") || name.contains("zapf")
        val dingbat = name.contains("dingbat") || name.contains("zapf") || name.contains("wingding")

        val family: String
        val recognized: Boolean
        when {
            dingbat -> { family = "Wingdings"; recognized = true }
            symbol -> { family = "Symbol"; recognized = true }
            MONO_HINTS.any { it in name } -> { family = "Courier New"; recognized = true }
            SERIF_HINTS.any { it in name } -> { family = "Times New Roman"; recognized = true }
            SANS_HINTS.any { it in name } -> { family = "Arial"; recognized = true }
            else -> { family = "Arial"; recognized = false }
        }
        return MappedFont(family, bold, italic, recognized, symbol)
    }
}
