package com.pdfpaper.app.ui.viewer.fit

import com.pdfpaper.app.ui.crop.CropMargins
import com.pdfpaper.app.ui.crop.cropBoxFromMargins
import com.pdfpaper.engine.fit.FitSize
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.render.PageBox
import kotlin.math.roundToInt

/** How the viewer frames a page (T57). View only; nothing is written back. */
enum class FitContentMode(val label: String, val short: String) {
    /** The full page, as before. */
    OFF("Off", "Off"),

    /** One robust box for the whole document, so the zoom never jumps. */
    DOCUMENT("Whole document", "Document"),

    /** Every page gets its own content box. */
    PAGE("Each page", "Page"),

    /** The reader drags the four corners; the margins apply to every page. */
    MANUAL("Adjust manually", "Manual")
}

/**
 * What the viewer shows about the fit. [boxes] are the detected content boxes
 * in unrotated user space (document/per-page modes); [margins] is the manual
 * box, shared by every page like the crop screen's "all pages" mode.
 */
data class FitContentState(
    val mode: FitContentMode = FitContentMode.OFF,
    val loading: Boolean = false,
    val boxes: Map<Int, PdfRect> = emptyMap(),
    val margins: CropMargins = CropMargins.ZERO,
    val oddSize: FitSize? = null,
    val evenSize: FitSize? = null,
    val detectedPages: Int = 0,
    val fallbackPages: List<Int> = emptyList(),
    val scannedPages: List<Int> = emptyList(),
    val warnings: List<String> = emptyList(),
    val ignoreHeaderFooter: Boolean = true,
    val error: String? = null
) {
    val active: Boolean get() = mode != FitContentMode.OFF

    /** True when the detected pages all share one displayed size. */
    val uniform: Boolean get() = active && mode != FitContentMode.MANUAL &&
        fallbackPages.isEmpty() && oddSize != null
}

/**
 * The boxes the viewer should draw. Undetected pages fall back to their whole
 * page, so a blank page is never a blank screen. The page rotation is kept.
 */
fun fitViewBoxes(pages: List<PageBox>, state: FitContentState): List<PageBox> {
    if (!state.active || pages.isEmpty()) return pages
    return pages.mapIndexed { index, page ->
        when (state.mode) {
            FitContentMode.OFF -> page
            FitContentMode.MANUAL -> {
                val base = PdfRect(page.left, page.bottom, page.right, page.top)
                val box = cropBoxFromMargins(base, state.margins)
                if (box == null) page else pageBox(box, page.rotate)
            }
            FitContentMode.DOCUMENT, FitContentMode.PAGE -> {
                val box = state.boxes[index]
                if (box == null) page else pageBox(box, page.rotate)
            }
        }
    }
}

/** The boxes "Make this permanent" hands to the crop screen. */
fun fitCropBoxes(pages: List<PageBox>, state: FitContentState): Map<Int, PdfRect> {
    if (!state.active) return emptyMap()
    val result = LinkedHashMap<Int, PdfRect>()
    pages.forEachIndexed { index, page ->
        val base = PdfRect(page.left, page.bottom, page.right, page.top)
        val box = when (state.mode) {
            FitContentMode.OFF -> null
            FitContentMode.MANUAL -> cropBoxFromMargins(base, state.margins)
            else -> state.boxes[index]
        }
        if (box != null) result[index] = box
    }
    return result
}

/** One-line description for the sheet and the toolbar. */
fun fitDescription(state: FitContentState, pages: List<PageBox>): String {
    if (state.loading) return "Finding the content..."
    state.error?.let { return it }
    if (!state.active) return "Showing the whole page."
    return when (state.mode) {
        FitContentMode.OFF -> "Showing the whole page."
        FitContentMode.MANUAL -> "Margins ${state.margins.describe()}"
        FitContentMode.DOCUMENT -> {
            val size = state.oddSize
            val zoom = size?.let { magnification(pages.firstOrNull(), it) }
            val suffix = if (zoom != null) " - text at $zoom %" else ""
            "One box for the document$suffix"
        }
        FitContentMode.PAGE -> {
            val zoom = state.boxes.entries.firstOrNull()?.let { (index, box) ->
                pages.getOrNull(index)?.let { page ->
                    magnification(page, displaySize(box, page.rotate))
                }
            }
            val suffix = if (zoom != null) " - text at $zoom %" else ""
            "Each page fitted to its own content$suffix"
        }
    }
}

/** How much larger the content is drawn than the page: 100 = unchanged. */
fun magnification(page: PageBox?, size: FitSize): Int? {
    if (page == null || size.width <= 0.0 || page.rotatedWidth <= 0.0) return null
    return (page.rotatedWidth / size.width * 100.0).roundToInt()
}

/** The displayed size of a user-space box on a page with [rotate]. */
fun displaySize(box: PdfRect, rotate: Int): FitSize =
    if (rotate % 180 == 0) {
        FitSize(box.width, box.height)
    } else {
        FitSize(box.height, box.width)
    }

/** Warnings worth showing; pages that were kept whole are counted, not listed. */
fun fitNotes(state: FitContentState): List<String> {
    val notes = ArrayList<String>()
    if (state.scannedPages.isNotEmpty()) {
        notes += "${state.scannedPages.size} scanned page(s) were measured from a low-resolution render"
    }
    if (state.fallbackPages.isNotEmpty()) {
        notes += "${state.fallbackPages.size} page(s) keep their own box (a full-page picture, " +
            "a landscape page or too little content)"
    }
    notes += state.warnings.take(3)
    return notes
}

private fun pageBox(box: PdfRect, rotate: Int): PageBox =
    PageBox(box.left, box.bottom, box.right, box.top, rotate)
