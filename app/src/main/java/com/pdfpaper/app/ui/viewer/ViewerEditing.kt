package com.pdfpaper.app.ui.viewer

import com.pdfpaper.engine.annot.HighlightSpec
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.render.PageRenderPlan

/**
 * Pure mapping helpers behind the viewer's annotation tools (T12). They are
 * kept apart from [ViewerViewModel] so they can be unit-tested without an
 * Android device: a fractional point on the drawn page becomes a point in PDF
 * user space, and a text selection becomes a highlight spec.
 */

/**
 * Maps a fractional point on a drawn page (0..1 across the bitmap, origin at
 * the top-left) back to PDF user space through the render plan's inverse
 * matrix. Returns null when the page has not been laid out yet or the matrix
 * is singular.
 */
internal fun pageFractionToUserSpace(
    plan: PageRenderPlan,
    fractionX: Float,
    fractionY: Float
): DoubleArray? {
    val inverse = plan.matrix.inverse() ?: return null
    return inverse.apply(
        fractionX.toDouble() * plan.pixelWidth,
        fractionY.toDouble() * plan.pixelHeight
    )
}

/** The square, in user space, where a new sticky note of [size] points is placed. */
internal fun noteRectAt(x: Double, y: Double, size: Double): PdfRect =
    PdfRect(x, y, x + size, y + size)

/**
 * Turns a selection into a highlight spec. Rectangles with no area are dropped
 * (a line of pure spaces can produce them); the trimmed text becomes /Contents.
 * Returns null when nothing usable remains.
 */
internal fun selectionToHighlight(selection: ViewerSelection): HighlightSpec? {
    val rects = selection.rects
        .map { PdfRect(it.x0, it.y0, it.x1, it.y1) }
        .filter { !it.isEmpty }
    if (rects.isEmpty()) return null
    val text = selection.text.trim().take(1024)
    return HighlightSpec(selection.pageIndex, rects, contents = text.ifEmpty { null })
}
