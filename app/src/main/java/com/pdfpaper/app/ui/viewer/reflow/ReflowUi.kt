package com.pdfpaper.app.ui.viewer.reflow

import android.graphics.Bitmap
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.reflow.ReflowPage
import com.pdfpaper.engine.render.PageBox

/**
 * Everything the reflow overlay shows (T33). Text pages are extracted lazily,
 * so [pages] only holds the pages near the viewport; a page that is missing is
 * a placeholder the overlay asks for again.
 */
data class ReflowUiState(
    val active: Boolean = false,
    val settings: ReflowSettings = ReflowSettings.DEFAULT,
    val pages: Map<Int, ReflowPage> = emptyMap(),
    /** Pages whose extraction is in flight, so the overlay shows a spinner once. */
    val loading: Set<Int> = emptySet(),
    /** Where the reader was when reflow opened, so the list can start there. */
    val entry: ReflowPosition? = null,
    /** "Text is small. Try Reflow" is on screen (page view only, T33b). */
    val hint: Boolean = false,
    val hintDismissed: Boolean = false,
    /** The page an OCR run is reading, and whether one is running. */
    val ocrPage: Int? = null,
    val ocrBusy: Boolean = false,
    val ocrMessage: String? = null
) {
    /** True while any page near the viewport is still being extracted. */
    val busy: Boolean get() = loading.isNotEmpty()
}

/** A rendered picture or table region, keyed by page, block and pixel width. */
data class ReflowRegion(
    val bitmap: Bitmap,
    /** Width the region was rendered at, so the overlay can scale it cleanly. */
    val pixelWidth: Int
)

/** Cache key for a region bitmap; a width change re-renders at the new size. */
fun reflowRegionKey(page: Int, block: Int, pixelWidth: Int): String =
    "$page:$block:$pixelWidth"

/**
 * The page-space box of a picture or table region, with the page's rotation so
 * the renderer turns it the same way it turns the page.
 */
fun reflowRegionBox(bounds: PdfRect, rotate: Int): PageBox =
    PageBox(bounds.left, bounds.bottom, bounds.right, bounds.top, rotate)

/**
 * Pixels per point that make a region [pixelWidth] wide. A region turned by 90°
 * or 270° is as wide as the box is tall.
 */
fun reflowRegionScale(bounds: PdfRect, rotate: Int, pixelWidth: Int): Double {
    val width = if (rotate % 180 == 0) bounds.width else bounds.height
    if (width <= 0.0 || pixelWidth <= 0) return 1.0
    return (pixelWidth.toDouble() / width).coerceIn(0.05, 4.0)
}

/**
 * Height divided by width of a rendered region, taken from the bitmap itself so
 * a picture keeps its shape at any column width. The overlay multiplies the
 * region's width in dp by this, which is why the height never mixes pixels and
 * dp (a missing conversion made every region about 2.7x too tall).
 */
fun reflowRegionAspect(bitmapWidth: Int, bitmapHeight: Int): Float =
    if (bitmapWidth <= 0 || bitmapHeight <= 0) 1f
    else bitmapHeight.toFloat() / bitmapWidth
