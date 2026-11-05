package com.pdfpaper.engine.mark

import com.pdfpaper.engine.edit.StandardFont
import com.pdfpaper.engine.graphics.PdfColor

/**
 * Which pages the added marks go on (the page numbers are zero-based, the way
 * the engine counts pages).
 */
sealed interface PageSelection {

    object All : PageSelection

    object Odd : PageSelection

    object Even : PageSelection

    /** An explicit set of zero-based page numbers. */
    data class Custom(val pages: Set<Int>) : PageSelection

    /** True when the page at [index] (zero-based) is selected. */
    fun includes(index: Int): Boolean = when (this) {
        All -> true
        Odd -> index % 2 == 0
        Even -> index % 2 == 1
        is Custom -> index in pages
    }

    companion object {
        /** Parses "all", "odd", "even" or a list such as "1,3-5" (one-based). */
        fun parse(text: String): PageSelection {
            val value = text.trim().lowercase()
            return when {
                value.isEmpty() || value == "all" -> All
                value == "odd" -> Odd
                value == "even" -> Even
                else -> {
                    val pages = HashSet<Int>()
                    for (part in value.split(',')) {
                        val piece = part.trim()
                        if (piece.isEmpty()) continue
                        val dash = piece.indexOf('-')
                        if (dash <= 0) {
                            piece.toIntOrNull()?.let { pages += it - 1 }
                        } else {
                            val from = piece.substring(0, dash).trim().toIntOrNull() ?: continue
                            val to = piece.substring(dash + 1).trim().toIntOrNull() ?: continue
                            for (page in from..to) pages += page - 1
                        }
                    }
                    Custom(pages)
                }
            }
        }
    }
}

/** Where a watermark sits on the page. */
enum class WatermarkPosition(val label: String) {
    CENTER("Centre"),
    TILED("Tiled"),
    TOP_LEFT("Top left"),
    TOP_RIGHT("Top right"),
    BOTTOM_LEFT("Bottom left"),
    BOTTOM_RIGHT("Bottom right")
}

/** The font used for the added text. */
sealed interface MarkFont {

    /**
     * One of the standard 14 fonts (§9.6.2.2). Every reader has them, so
     * nothing has to be embedded, but they only cover Latin (and the two
     * symbol fonts).
     */
    data class Standard(val font: StandardFont) : MarkFont

    /**
     * A TrueType font the caller supplies. It is embedded into the file
     * (as a Type0 font with an Identity-H encoding), so the text travels with
     * the document and shows up in readers that do not have the font.
     */
    data class Embedded(val bytes: ByteArray, val baseName: String = "PDFPaperEmbedded") : MarkFont
}

/** What a watermark shows. */
sealed interface WatermarkContent {

    data class Text(val text: String) : WatermarkContent

    /** An image, given as the bytes of a PNG or JPEG file. */
    data class Image(val bytes: ByteArray) : WatermarkContent
}

/** How a page number is written. */
enum class NumberFormat(val label: String) {
    DECIMAL("1"),
    LOWER_ROMAN("i"),
    UPPER_ROMAN("I"),
    PAGE_OF_TOTAL("Page 1 of N");

    /** The number as it should appear on the page. */
    fun format(page: Int, total: Int): String = when (this) {
        DECIMAL -> page.toString()
        LOWER_ROMAN -> roman(page).lowercase()
        UPPER_ROMAN -> roman(page)
        PAGE_OF_TOTAL -> "Page $page of $total"
    }

    private fun roman(value: Int): String {
        if (value <= 0) return value.toString()
        val table = listOf(
            1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
            50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I"
        )
        var rest = value
        val out = StringBuilder()
        for ((amount, symbol) in table) {
            while (rest >= amount) {
                out.append(symbol)
                rest -= amount
            }
        }
        return out.toString()
    }
}

/** Where a Bates number is stamped. */
enum class BatesPlacement(val label: String) {
    TOP_LEFT("Top left"),
    TOP_CENTER("Top centre"),
    TOP_RIGHT("Top right"),
    BOTTOM_LEFT("Bottom left"),
    BOTTOM_CENTER("Bottom centre"),
    BOTTOM_RIGHT("Bottom right")
}

/** Where a line sits between the margins. */
enum class MarkAlign(val label: String) {
    LEFT("Left"),
    CENTER("Centre"),
    RIGHT("Right")
}

/** The text of a watermark. */
data class WatermarkOptions(
    val content: WatermarkContent,
    val font: MarkFont = MarkFont.Standard(StandardFont.HELVETICA),
    val fontSize: Double = 48.0,
    val color: PdfColor = PdfColor(0.75, 0.75, 0.75),
    val opacity: Double = 0.3,
    val rotationDegrees: Double = 45.0,
    val position: WatermarkPosition = WatermarkPosition.CENTER,
    /** True draws the watermark under the page content, false draws it on top. */
    val behindContent: Boolean = true,
    /** The width of an image watermark as a fraction of the page width. */
    val imageWidthFraction: Double = 0.4,
    val pages: PageSelection = PageSelection.All
)

/** A header or footer line, with the page number tokens. */
data class HeaderFooterOptions(
    /** The header line; null leaves the top of the page alone. */
    val header: String? = null,
    /** The footer line; null leaves the bottom of the page alone. */
    val footer: String? = null,
    val format: NumberFormat = NumberFormat.DECIMAL,
    val font: MarkFont = MarkFont.Standard(StandardFont.HELVETICA),
    val fontSize: Double = 10.0,
    val color: PdfColor = PdfColor.BLACK,
    val align: MarkAlign = MarkAlign.CENTER,
    val marginTop: Double = 36.0,
    val marginBottom: Double = 36.0,
    val marginLeft: Double = 54.0,
    val marginRight: Double = 54.0,
    /** The number of the first page (the page count continues from there). */
    val startNumber: Int = 1,
    /** True leaves the first page without a header or footer. */
    val skipFirstPage: Boolean = false,
    val pages: PageSelection = PageSelection.All
)

/** A Bates number: a fixed prefix, a number and a fixed suffix. */
data class BatesOptions(
    val prefix: String = "",
    val digits: Int = 6,
    val startNumber: Int = 1,
    val suffix: String = "",
    val placement: BatesPlacement = BatesPlacement.BOTTOM_RIGHT,
    val font: MarkFont = MarkFont.Standard(StandardFont.HELVETICA),
    val fontSize: Double = 9.0,
    val color: PdfColor = PdfColor.BLACK,
    val marginTop: Double = 28.0,
    val marginBottom: Double = 28.0,
    val marginLeft: Double = 36.0,
    val marginRight: Double = 36.0,
    val pages: PageSelection = PageSelection.All
)

/**
 * The optional content groups the added marks live in (§8.11). Every group
 * has a name a reader shows in its layers panel, and the names are how the
 * removal function finds the content again.
 */
object MarkGroups {
    const val WATERMARK = "PDFPaper watermark"
    const val HEADER_FOOTER = "PDFPaper header-footer"
    const val BATES = "PDFPaper bates"

    /** Every group PDFPaper adds. */
    val all: List<String> = listOf(WATERMARK, HEADER_FOOTER, BATES)

    /** The name of the marked-content property for a group. */
    fun propertyName(group: String): String = when (group) {
        WATERMARK -> "PDFPaperWatermark"
        HEADER_FOOTER -> "PDFPaperHeaderFooter"
        BATES -> "PDFPaperBates"
        else -> "PDFPaperAdded"
    }
}

/** The tokens a header or footer line can use. */
object MarkTokens {
    const val PAGE = "{page}"
    const val PAGES = "{pages}"
    const val DATE = "{date}"
    const val FILENAME = "{filename}"
    const val TITLE = "{title}"

    val all: List<String> = listOf(PAGE, PAGES, DATE, FILENAME, TITLE)
}
