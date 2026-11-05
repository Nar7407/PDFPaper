package com.pdfpaper.app.ui.viewer.ink

import com.pdfpaper.engine.render.PageRenderPlan

/**
 * Page-fraction ↔ PDF-user-space and display-width helpers for ink (T54).
 *
 * The viewer draws a page through [PageRenderPlan.matrix], which already folds
 * the CropBox origin, the y flip and `/Rotate` (§14.11.2). Reusing that matrix
 * and its inverse is what keeps strokes on the right spot on rotated or
 * cropped pages instead of introducing a second transform model.
 */
object InkTransform {

    fun sampleAt(
        plan: PageRenderPlan,
        fractionX: Float,
        fractionY: Float,
        pressure: Float,
        tilt: Float
    ): InkSample? {
        val inverse = plan.matrix.inverse() ?: return null
        val user = inverse.apply(
            fractionX.toDouble() * plan.pixelWidth,
            fractionY.toDouble() * plan.pixelHeight
        )
        if (!user[0].isFinite() || !user[1].isFinite()) return null
        return InkSample(user[0], user[1], pressure.toDouble(), tilt.toDouble())
    }

    /** User-space point to a fraction of the rendered page (0..1, top-left). */
    fun fractionOf(plan: PageRenderPlan, x: Double, y: Double): Pair<Float, Float>? {
        if (plan.pixelWidth <= 0 || plan.pixelHeight <= 0) return null
        val pixel = plan.matrix.apply(x, y)
        return (pixel[0] / plan.pixelWidth).toFloat() to
            (pixel[1] / plan.pixelHeight).toFloat()
    }

    /**
     * A stroke width in points as display pixels. [viewWidth] is the Compose
     * page width, which can differ from the bitmap's pixel width after a fit or
     * zoom, so both scales are applied.
     */
    fun widthPx(stroke: InkStroke, plan: PageRenderPlan, viewWidth: Float): Float {
        if (plan.pixelWidth <= 0 || viewWidth <= 0f) return 0f
        return (stroke.width * plan.scale * (viewWidth / plan.pixelWidth)).toFloat()
    }

    /** Pressure-driven half-width in points, matching the PDF appearance. */
    fun halfWidth(stroke: InkStroke, pressure: Double): Double =
        if (stroke.highlighter) {
            stroke.width / 2.0
        } else {
            stroke.width * (0.35 + 0.65 * pressure.coerceIn(0.0, 1.0)) / 2.0
        }
}
