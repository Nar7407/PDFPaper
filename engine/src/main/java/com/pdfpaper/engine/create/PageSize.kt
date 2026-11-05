package com.pdfpaper.engine.create

/** Page orientation. Landscape swaps the page's width and height. */
enum class PageOrientation { PORTRAIT, LANDSCAPE }

/**
 * A page size in PDF points (1 pt = 1/72 in).
 *
 * [width] and [height] are the portrait dimensions; [oriented] swaps them for
 * landscape. [A4] and [LETTER] are the presets; [custom] accepts any positive
 * size, e.g. a scanned page or a label.
 */
class PageSize private constructor(
    val width: Double,
    val height: Double,
    private val presetName: String?
) {

    init {
        require(width.isFinite() && width > 0.0) { "page width must be finite and positive" }
        require(height.isFinite() && height > 0.0) { "page height must be finite and positive" }
    }

    /** The same size in [orientation]; landscape swaps width and height. */
    fun oriented(orientation: PageOrientation): PageSize = when (orientation) {
        PageOrientation.PORTRAIT -> this
        PageOrientation.LANDSCAPE -> PageSize(height, width, presetName)
    }

    override fun toString(): String = presetName ?: "${format(width)} x ${format(height)} pt"

    private fun format(value: Double): String =
        java.math.BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()

    companion object {

        /** ISO A4: 210 x 297 mm. */
        val A4 = PageSize(210.0 / 25.4 * 72.0, 297.0 / 25.4 * 72.0, "A4")

        /** US Letter: 8.5 x 11 in. */
        val LETTER = PageSize(612.0, 792.0, "Letter")

        /** A size in points, not tied to a preset. */
        fun custom(width: Double, height: Double): PageSize = PageSize(width, height, null)

        /**
         * Parses "A4", "Letter" or a custom "WIDTHxHEIGHT" in points, for the
         * command line. Returns null for anything else.
         */
        fun parse(value: String): PageSize? {
            val text = value.trim()
            if (text.equals("a4", ignoreCase = true)) return A4
            if (text.equals("letter", ignoreCase = true)) return LETTER
            val parts = text.lowercase().split('x')
            if (parts.size != 2) return null
            val width = parts[0].trim().toDoubleOrNull() ?: return null
            val height = parts[1].trim().toDoubleOrNull() ?: return null
            return runCatching { custom(width, height) }.getOrNull()
        }
    }
}
