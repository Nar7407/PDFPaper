package com.pdfpaper.app.ui.mark

import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.mark.BatesPlacement
import com.pdfpaper.engine.mark.MarkAlign
import com.pdfpaper.engine.mark.NumberFormat
import com.pdfpaper.engine.mark.PageSelection
import com.pdfpaper.engine.mark.WatermarkPosition

/**
 * The pure parts of the mark screens: the page box the marks go on, the page
 * range the user types, and the geometry the live preview draws.
 *
 * Nothing here touches Android, so every rule can be checked by plain JVM
 * tests. The numbers mirror the engine (com.pdfpaper.engine.mark) so the
 * preview shows what the engine will really do.
 */

/**
 * The page box the marks are placed on, in PDF points (ISO 32000-1 §14.11.2).
 *
 * [left]/[bottom]/[right]/[top] are the crop box (the media box when the page
 * has no crop box); [rotate] is the page's /Rotate in degrees, clockwise.
 */
data class MarkBox(
    val left: Double = 0.0,
    val bottom: Double = 0.0,
    val right: Double = 612.0,
    val top: Double = 792.0,
    val rotate: Int = 0
) {

    /** The width of the page in points, without the rotation. */
    val width: Double get() = right - left

    /** The height of the page in points, without the rotation. */
    val height: Double get() = top - bottom

    /** The width of the page as it is shown (the rotation applied). */
    val displayWidth: Double get() = if (isTurned) height else width

    /** The height of the page as it is shown (the rotation applied). */
    val displayHeight: Double get() = if (isTurned) width else height

    /** True when the page is shown turned by 90 or 270 degrees. */
    val isTurned: Boolean get() = normalizedRotate() % 180 != 0

    /** The rotation as 0, 90, 180 or 270. */
    fun normalizedRotate(): Int = ((rotate % 360) + 360) % 360

    /**
     * A point in PDF user space as a point on the shown page: the origin is
     * the top-left corner and y grows down, the way a bitmap looks.
     */
    fun toDevice(x: Double, y: Double): Pair<Double, Double> = when (normalizedRotate()) {
        90 -> (y - bottom) to (x - left)
        180 -> (right - x) to (y - bottom)
        270 -> (top - y) to (right - x)
        else -> (x - left) to (top - y)
    }
}

/** Where one mark sits on the page, in PDF user space. */
data class MarkAnchor(
    /** The x of the anchor, in points from the left of the page box. */
    val x: Double,
    /** The baseline of the text, in points from the bottom of the page box. */
    val y: Double,
    /** How the text is aligned to the anchor. */
    val align: MarkAlign
)

/** The corner margin the engine keeps for a corner watermark, in points. */
const val MARK_CORNER_MARGIN = 36.0

/** The distance between two tiled watermarks, in points. */
const val MARK_TILE_WIDTH = 220.0

/** The distance between two rows of tiled watermarks, in points. */
const val MARK_TILE_HEIGHT = 140.0

/** How the colours of the marks are chosen. */
enum class MarkColorChoice(val label: String, val red: Double, val green: Double, val blue: Double) {
    GREY("Grey", 0.75, 0.75, 0.75),
    BLACK("Black", 0.0, 0.0, 0.0),
    RED("Red", 0.85, 0.15, 0.15),
    BLUE("Blue", 0.15, 0.30, 0.75),
    GREEN("Green", 0.10, 0.50, 0.25);

    /** The colour the engine writes. */
    fun toPdfColor(): PdfColor = PdfColor(red, green, blue)
}

/** Which pages the marks go on. */
enum class PageRangeMode(val label: String) {
    ALL("All"),
    ODD("Odd"),
    EVEN("Even"),
    CUSTOM("Custom")
}

/**
 * Parses a page range such as "1,3-5" into the zero-based page numbers the
 * engine counts pages with. Parts that make no sense are skipped, never
 * fatal, so a half-typed range still works.
 */
fun parsePageRange(text: String): Set<Int> {
    val pages = LinkedHashSet<Int>()
    for (part in text.split(',')) {
        val piece = part.trim()
        if (piece.isEmpty()) continue
        val dash = piece.indexOf('-')
        if (dash <= 0) {
            val single = piece.toIntOrNull() ?: continue
            if (single >= 1) pages += single - 1
        } else {
            val from = piece.substring(0, dash).trim().toIntOrNull() ?: continue
            val to = piece.substring(dash + 1).trim().toIntOrNull() ?: continue
            if (from < 1 || to < from) continue
            for (page in from..to) pages += page - 1
        }
    }
    return pages
}

/** The engine's page selection for the chosen mode and range text. */
fun pageSelectionFor(mode: PageRangeMode, customText: String): PageSelection = when (mode) {
    PageRangeMode.ALL -> PageSelection.All
    PageRangeMode.ODD -> PageSelection.Odd
    PageRangeMode.EVEN -> PageSelection.Even
    PageRangeMode.CUSTOM -> PageSelection.Custom(parsePageRange(customText))
}

/** The one-line note under the page-range picker. */
fun describePageRange(mode: PageRangeMode, customText: String): String = when (mode) {
    PageRangeMode.ALL -> "Every page of the document."
    PageRangeMode.ODD -> "The odd pages: 1, 3, 5 …"
    PageRangeMode.EVEN -> "The even pages: 2, 4, 6 …"
    PageRangeMode.CUSTOM -> {
        val pages = parsePageRange(customText)
        if (pages.isEmpty()) {
            "Type the pages, for example 1,3-5."
        } else {
            "Pages " + pages.sorted().joinToString(", ") { (it + 1).toString() } + "."
        }
    }
}

/** The x of a line that is aligned to the margins of the page box. */
private fun alignedX(box: MarkBox, marginLeft: Double, marginRight: Double, align: MarkAlign): Double =
    when (align) {
        MarkAlign.LEFT -> box.left + marginLeft
        MarkAlign.CENTER -> (box.left + marginLeft + box.right - marginRight) / 2.0
        MarkAlign.RIGHT -> box.right - marginRight
    }

/**
 * Where the watermark text goes, in the same way the engine places it: in the
 * middle, in a corner or tiled over the page.
 */
fun watermarkAnchors(
    position: WatermarkPosition,
    box: MarkBox,
    fontSize: Double
): List<MarkAnchor> = when (position) {
    WatermarkPosition.CENTER -> listOf(
        MarkAnchor(box.left + box.width / 2.0, box.bottom + box.height / 2.0 - fontSize * 0.35, MarkAlign.CENTER)
    )
    WatermarkPosition.TILED -> {
        val columns = maxOf(1, Math.ceil(box.width / MARK_TILE_WIDTH).toInt())
        val rows = maxOf(1, Math.ceil(box.height / MARK_TILE_HEIGHT).toInt())
        val anchors = ArrayList<MarkAnchor>(columns * rows)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val x = box.left + (column + 0.5) * (box.width / columns)
                val y = box.bottom + (row + 0.5) * (box.height / rows) - 130.0 - fontSize * 0.35
                anchors += MarkAnchor(x, y, MarkAlign.CENTER)
            }
        }
        anchors
    }
    WatermarkPosition.TOP_LEFT -> listOf(
        MarkAnchor(
            box.left + MARK_CORNER_MARGIN,
            box.top - MARK_CORNER_MARGIN - fontSize,
            MarkAlign.LEFT
        )
    )
    WatermarkPosition.TOP_RIGHT -> listOf(
        MarkAnchor(
            box.right - MARK_CORNER_MARGIN,
            box.top - MARK_CORNER_MARGIN - fontSize,
            MarkAlign.RIGHT
        )
    )
    WatermarkPosition.BOTTOM_LEFT -> listOf(
        MarkAnchor(box.left + MARK_CORNER_MARGIN, box.bottom + MARK_CORNER_MARGIN, MarkAlign.LEFT)
    )
    WatermarkPosition.BOTTOM_RIGHT -> listOf(
        MarkAnchor(box.right - MARK_CORNER_MARGIN, box.bottom + MARK_CORNER_MARGIN, MarkAlign.RIGHT)
    )
}

/** Where the header line goes, in PDF user space. */
fun headerAnchor(
    box: MarkBox,
    marginTop: Double,
    marginLeft: Double,
    marginRight: Double,
    align: MarkAlign
): MarkAnchor = MarkAnchor(
    alignedX(box, marginLeft, marginRight, align),
    box.top - marginTop,
    align
)

/** Where the footer line goes, in PDF user space. */
fun footerAnchor(
    box: MarkBox,
    marginBottom: Double,
    marginLeft: Double,
    marginRight: Double,
    align: MarkAlign
): MarkAnchor = MarkAnchor(
    alignedX(box, marginLeft, marginRight, align),
    box.bottom + marginBottom,
    align
)

/** Where the Bates number goes, in PDF user space. */
fun batesAnchor(
    placement: BatesPlacement,
    box: MarkBox,
    marginTop: Double,
    marginBottom: Double,
    marginLeft: Double,
    marginRight: Double
): MarkAnchor {
    val align = when (placement) {
        BatesPlacement.TOP_LEFT, BatesPlacement.BOTTOM_LEFT -> MarkAlign.LEFT
        BatesPlacement.TOP_CENTER, BatesPlacement.BOTTOM_CENTER -> MarkAlign.CENTER
        BatesPlacement.TOP_RIGHT, BatesPlacement.BOTTOM_RIGHT -> MarkAlign.RIGHT
    }
    val y = when (placement) {
        BatesPlacement.TOP_LEFT, BatesPlacement.TOP_CENTER, BatesPlacement.TOP_RIGHT ->
            box.top - marginTop
        else -> box.bottom + marginBottom
    }
    return MarkAnchor(alignedX(box, marginLeft, marginRight, align), y, align)
}

/** The text of a Bates number, e.g. "ABC-000042". */
fun batesText(prefix: String, digits: Int, number: Int, suffix: String): String {
    val width = digits.coerceIn(1, 12)
    return prefix + number.toString().padStart(width, '0') + suffix
}

/**
 * Replaces the {page}, {pages}, {date}, {filename} and {title} tokens of a
 * header or footer line, the way the engine does.
 */
fun expandMarkTokens(
    template: String,
    page: Int,
    total: Int,
    date: String,
    fileName: String,
    title: String,
    format: NumberFormat
): String = template
    .replace("{page}", format.format(page, total))
    .replace("{pages}", total.toString())
    .replace("{date}", date)
    .replace("{filename}", fileName)
    .replace("{title}", title)

/**
 * True when every character of [text] is in the Latin-1 range, so the
 * standard-14 fonts can show it. Anything else needs an embedded font.
 */
fun isLatinText(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (codePoint >= 256) return false
        index += Character.charCount(codePoint)
    }
    return true
}

/**
 * The enum value with that name, or [fallback] when the name is unknown (an
 * older or newer build wrote it).
 */
inline fun <reified T : Enum<T>> enumOrDefault(name: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback
