package com.pdfpaper.app.ui.viewer.flow

import com.pdfpaper.engine.flow.ColumnFlow
import com.pdfpaper.engine.flow.FlowPage
import com.pdfpaper.engine.flow.FlowSegment
import com.pdfpaper.engine.flow.FlowSegmentKind
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * T59: the reader's column-flow preferences. Everything here is remembered in
 * [com.pdfpaper.app.data.AppSettings] and applies to every document; the
 * detection itself always uses the engine's defaults, because a reader who
 * cannot see the page cannot tune a gutter.
 */
data class ColumnFlowSettings(
    /** Show two columns together when the screen is wide enough. */
    val sideBySide: Boolean = true,
    /** Animate the step from one segment to the next. */
    val animate: Boolean = true,
    /** Show the page mini-map with the current segment outlined. */
    val showMiniMap: Boolean = true
) {
    fun normalised(): ColumnFlowSettings = this

    companion object {
        val DEFAULT = ColumnFlowSettings()

        /** Below this width two columns would each be narrower than a phone. */
        const val SIDE_BY_SIDE_DP = 700f
    }
}

/**
 * Where the reader is: a zero-based page and the reading-order index of the
 * segment inside it. A page index alone is not enough — a two-column page has
 * several places a reader can be on — and a pixel offset is meaningless once
 * the mode changes (T33/T38b made the same choice).
 */
data class FlowPosition(val page: Int, val order: Int) {
    companion object {
        val START = FlowPosition(0, 0)
    }
}

/** A rectangle as fractions of the displayed page, 0..1, origin top-left. */
data class FlowMiniRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

/**
 * How many segments sit side by side. One on a phone; two only when the reader
 * asked for it and the screen really has room for two readable columns — a
 * 700 dp tablet in landscape or a phone in landscape.
 */
fun flowColumns(widthDp: Float, sideBySide: Boolean): Int {
    if (!sideBySide) return 1
    return if (widthDp >= ColumnFlowSettings.SIDE_BY_SIDE_DP) 2 else 1
}

/**
 * The segments to show together: the current one and, when two columns fit,
 * the next column of the same band. A spanning figure or table is always shown
 * alone, because pairing it with a column would shrink the figure for nothing.
 */
fun flowWindow(page: FlowPage, order: Int, columns: Int): List<FlowSegment> {
    val flow = page.flowSegments
    if (flow.isEmpty()) return emptyList()
    val start = flow.indexOfFirst { it.order == order }.let { if (it < 0) 0 else it }
    val first = flow[start]
    if (columns <= 1) return listOf(first)
    if (first.kind != FlowSegmentKind.TEXT) return listOf(first)
    val shown = ArrayList<FlowSegment>(columns)
    shown += first
    for (index in start + 1 until flow.size) {
        if (shown.size >= columns) break
        val next = flow[index]
        // Only a neighbouring column of the same band pairs up; a different
        // band starts below the first one and would read out of order.
        if (next.kind != FlowSegmentKind.TEXT) break
        if (next.column == first.column) break
        if (next.bounds.top > first.bounds.top + 0.5) break
        if (next.bounds.bottom < first.bounds.bottom - 0.5) break
        shown += next
    }
    return shown
}

/** Cache key for a rendered segment; a width or a mode change re-renders. */
fun flowSegmentKey(
    page: Int,
    order: Int,
    pixelWidth: Int,
    fullPage: Boolean = false
): String = if (fullPage) "$page:whole:$pixelWidth" else "$page:$order:$pixelWidth"

/**
 * The page-space box a segment is rendered from, with the page's rotation so
 * the renderer turns it exactly the way it turns the page.
 */
fun flowSegmentBox(bounds: PdfRect, rotate: Int): PageBox =
    PageBox(bounds.left, bounds.bottom, bounds.right, bounds.top, rotate)

/**
 * Pixels per point that make a segment [pixelWidth] wide. A segment on a page
 * turned by 90° or 270° is as wide as its box is tall. The upper clamp keeps a
 * very small box from being blown up past what the screen can show.
 */
fun flowSegmentScale(bounds: PdfRect, rotate: Int, pixelWidth: Int): Double {
    val width = if (rotate % 180 == 0) bounds.width else bounds.height
    if (width <= 0.0 || pixelWidth <= 0) return 1.0
    return (pixelWidth.toDouble() / width).coerceIn(0.05, 6.0)
}

/**
 * Where [bounds] sits on the displayed page, as fractions, for the mini-map.
 * The mapping goes through the engine's own page matrix, so a `/Rotate`d or
 * offset crop box needs no special case here.
 */
fun minimapRect(bounds: PdfRect, page: PdfRect, rotate: Int): FlowMiniRect {
    val box = PageBox(page.left, page.bottom, page.right, page.top, rotate)
    val width = box.rotatedWidth
    val height = box.rotatedHeight
    if (width <= 0.0 || height <= 0.0) return FlowMiniRect(0f, 0f, 1f, 1f)
    val matrix = PageGeometry.matrixFor(box, 1.0, 1.0)
    val xs = DoubleArray(4)
    val ys = DoubleArray(4)
    val corners = doubleArrayOf(
        bounds.left, bounds.bottom,
        bounds.left, bounds.top,
        bounds.right, bounds.bottom,
        bounds.right, bounds.top
    )
    for (i in 0 until 4) {
        val mapped = matrix.apply(corners[i * 2], corners[i * 2 + 1])
        xs[i] = mapped[0] / width
        ys[i] = mapped[1] / height
    }
    return FlowMiniRect(
        left = xs.min().toFloat().coerceIn(0f, 1f),
        top = ys.min().toFloat().coerceIn(0f, 1f),
        right = xs.max().toFloat().coerceIn(0f, 1f),
        bottom = ys.max().toFloat().coerceIn(0f, 1f)
    )
}

/** The word the header uses for a segment kind. */
fun flowKindLabel(kind: FlowSegmentKind): String = when (kind) {
    FlowSegmentKind.TEXT -> "Text"
    FlowSegmentKind.FIGURE -> "Figure"
    FlowSegmentKind.TABLE -> "Table"
    FlowSegmentKind.HEADER -> "Header"
    FlowSegmentKind.FOOTER -> "Footer"
}

/**
 * "Page 3 of 20 · text 2 of 4". The counter is the segment's place among the
 * page's own flow segments, so the reader always knows how much of the page is
 * left; a document-wide count would be a number nobody can act on.
 */
fun describeFlow(
    pageIndex: Int,
    pageCount: Int,
    order: Int,
    segmentCount: Int,
    kind: FlowSegmentKind
): String {
    val page = (pageIndex + 1).coerceAtLeast(1)
    val total = if (pageCount > 0) pageCount.coerceAtLeast(page) else page
    val place = if (segmentCount > 0) (order + 1).coerceIn(1, segmentCount) else 1
    val kindText = flowKindLabel(kind).lowercase()
    return "Page $page of $total · $kindText $place of ${segmentCount.coerceAtLeast(1)}"
}

/**
 * One line for the settings card: what the mode will do on this screen.
 */
fun describeFlowSettings(settings: ColumnFlowSettings, widthDp: Float): String {
    val columns = flowColumns(widthDp, settings.sideBySide)
    val layout = if (columns > 1) "two columns together" else "one column at a time"
    return if (settings.animate) "$layout, animated" else layout
}

/** Manual corrections survive a restart: page → absolute boxes, in page space. */
object FlowCorrectionCodec {

    private const val PAGE_SEPARATOR = '|'
    private const val BOX_SEPARATOR = ';'
    private const val FIELD_SEPARATOR = ','
    private const val PAGE_FIELD = ':'

    fun encode(corrections: Map<Int, List<PdfRect>>): String =
        corrections.entries
            .filter { it.key >= 0 && it.value.isNotEmpty() }
            .sortedBy { it.key }
            .joinToString(PAGE_SEPARATOR.toString()) { (page, boxes) ->
                page.toString() + PAGE_FIELD + boxes.joinToString(BOX_SEPARATOR.toString()) { box ->
                    listOf(box.left, box.bottom, box.right, box.top).joinToString(
                        FIELD_SEPARATOR.toString()
                    )
                }
            }

    fun decode(raw: String?): Map<Int, List<PdfRect>> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<Int, List<PdfRect>>()
        for (entry in raw.split(PAGE_SEPARATOR)) {
            if (entry.isBlank()) continue
            val split = entry.indexOf(PAGE_FIELD)
            if (split <= 0) continue
            val page = entry.substring(0, split).toIntOrNull() ?: continue
            if (page < 0) continue
            val boxes = ArrayList<PdfRect>()
            for (part in entry.substring(split + 1).split(BOX_SEPARATOR)) {
                if (part.isBlank()) continue
                val numbers = part.split(FIELD_SEPARATOR).map { it.trim().toDoubleOrNull() }
                if (numbers.size != 4 || numbers.any { it == null }) continue
                val values = numbers.filterNotNull()
                if (values.any { !it.isFinite() }) continue
                val box = PdfRect(values[0], values[1], values[2], values[3])
                if (box.width <= 0.0 || box.height <= 0.0) continue
                boxes += box
            }
            if (boxes.isNotEmpty()) result[page] = boxes
        }
        return result
    }
}

/**
 * Applies a saved manual correction to a freshly detected page. An empty or
 * missing correction leaves the detection alone; a correction is only ever
 * about this page's reading order, so the page furniture is kept.
 */
fun applyFlowCorrection(page: FlowPage, boxes: List<PdfRect>?): FlowPage =
    if (boxes.isNullOrEmpty()) page else ColumnFlow.withSegments(page, boxes)

/**
 * The segment to show after [steps] within one page, or null when the page has
 * no such segment (the caller then moves to the next or previous page).
 */
fun flowStep(page: FlowPage, order: Int, steps: Int): FlowSegment? {
    val flow = page.flowSegments
    if (flow.isEmpty()) return null
    val index = flow.indexOfFirst { it.order == order }.let { if (it < 0) 0 else it }
    val target = index + steps
    return if (target in flow.indices) flow[target] else null
}


/** Width in pixels the overlay renders a segment at, inside a pixel budget. */
fun flowSegmentWidthPx(availableWidthDp: Float, density: Float, maxPixels: Int): Int {
    if (availableWidthDp <= 0f || density <= 0f) return 1
    val raw = (availableWidthDp * density).roundToInt().coerceAtLeast(1)
    if (maxPixels <= 0) return raw
    return min(raw, maxPixels)
}
