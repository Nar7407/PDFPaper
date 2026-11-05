package com.pdfpaper.app.ui.viewer.flow

import android.graphics.Bitmap
import com.pdfpaper.engine.flow.FlowPage
import com.pdfpaper.engine.flow.FlowSegment

/**
 * Everything the column-flow overlay shows (T59). Pages are detected lazily, so
 * [pages] only holds the pages near the reader; a page that is missing is a
 * placeholder the overlay asks for again. A rendered segment is keyed by page,
 * reading order and pixel width, so a wider screen or a page rotation simply
 * asks for a new bitmap.
 */
data class ColumnFlowUiState(
    val active: Boolean = false,
    val settings: ColumnFlowSettings = ColumnFlowSettings.DEFAULT,
    val pages: Map<Int, FlowPage> = emptyMap(),
    /** Pages whose detection is in flight, so the overlay shows a spinner once. */
    val loading: Set<Int> = emptySet(),
    val position: FlowPosition = FlowPosition.START,
    /** The whole page instead of one segment (the "full page" toggle). */
    val fullPage: Boolean = false,
    /** A one-line note for the reader (a fallback page, a correction saved). */
    val message: String? = null
) {
    val page: FlowPage? get() = pages[position.page]
    val segment: FlowSegment? get() = page?.segmentAt(position.order)
    val segmentCount: Int get() = page?.flowSegments?.size ?: 0
    val busy: Boolean get() = loading.isNotEmpty()

    /** True when the page was deliberately left as one block. */
    val fallback: Boolean get() = page?.fallback == true
}

/** One rendered segment, at the pixel width it was drawn. */
data class FlowSegmentBitmap(
    val bitmap: Bitmap,
    val pixelWidth: Int
)

/**
 * How many detected pages are kept in memory. A [FlowPage] holds only boxes, so
 * this is generous: a 500-page book must not re-detect a page every time the
 * reader steps back.
 */
const val FLOW_PAGE_CACHE = 80

/** How many rendered segments are kept; each is one screen-sized bitmap. */
const val FLOW_BITMAP_CACHE = 16
