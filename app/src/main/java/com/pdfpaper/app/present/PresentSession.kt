package com.pdfpaper.app.present

import com.pdfpaper.engine.annot.InkSpec
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.render.PageRenderPlan
import kotlin.math.abs
import kotlin.math.max

/** Page change animation used while presenting (T53). */
enum class PresentTransition(val label: String) {
    NONE("None"),
    SLIDE("Slide"),
    FADE("Fade");

    companion object {
        fun fromName(name: String?): PresentTransition =
            entries.firstOrNull { it.name == name } ?: FADE
    }
}

/**
 * One temporary pen stroke (T53). [points] are normalized pairs (x0, y0, x1,
 * y1, ...) inside the rendered page bitmap, so the same stroke draws correctly
 * on the phone and on an external display of any size.
 */
data class PenStroke(
    val pageIndex: Int,
    val points: List<Float>
) {
    val size: Int get() = points.size / 2
}

/** Page stepping shared by the UI and the tests. */
object PresentPages {
    fun clamp(index: Int, pageCount: Int): Int =
        if (pageCount <= 0) 0 else index.coerceIn(0, pageCount - 1)

    fun step(current: Int, delta: Int, pageCount: Int): Int =
        clamp(current + delta, pageCount)
}

/**
 * Letterbox geometry. The page is scaled to fit inside the container with its
 * aspect ratio preserved and centred; the leftover area stays black (T53).
 */
object PresentGeometry {

    data class Fit(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float
    ) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
    }

    fun fit(containerWidth: Float, containerHeight: Float, aspect: Float): Fit {
        if (containerWidth <= 0f || containerHeight <= 0f) {
            return Fit(0f, 0f, 0f, 0f)
        }
        if (!aspect.isFinite() || aspect <= 0f) {
            return Fit(0f, 0f, containerWidth, containerHeight)
        }
        val containerAspect = containerWidth / containerHeight
        val width: Float
        val height: Float
        if (aspect > containerAspect) {
            width = containerWidth
            height = containerWidth / aspect
        } else {
            height = containerHeight
            width = containerHeight * aspect
        }
        return Fit(
            left = (containerWidth - width) / 2f,
            top = (containerHeight - height) / 2f,
            width = width,
            height = height
        )
    }

    /**
     * Point → normalized page fraction, or null when the point is in the
     * letterbox area (outside the painted page).
     */
    fun fraction(
        x: Float,
        y: Float,
        containerWidth: Float,
        containerHeight: Float,
        aspect: Float
    ): Pair<Float, Float>? {
        val rect = fit(containerWidth, containerHeight, aspect)
        if (rect.width <= 0f || rect.height <= 0f) return null
        if (x < rect.left - 0.5f || x > rect.right + 0.5f) return null
        if (y < rect.top - 0.5f || y > rect.bottom + 0.5f) return null
        val fx = ((x - rect.left) / rect.width).coerceIn(0f, 1f)
        val fy = ((y - rect.top) / rect.height).coerceIn(0f, 1f)
        return fx to fy
    }
}

/**
 * Turns temporary presenter ink into real `/Ink` annotations (T53). The
 * normalized points are mapped back through the render plan's inverse matrix,
 * so page rotation, a crop-box origin and a downscaled render are all handled
 * by the same geometry the viewer used to draw the page.
 */
object PresentInk {

    val defaultColor = PdfColor(0.86, 0.12, 0.10)
    const val defaultWidth: Double = 2.5

    fun toInkSpecs(
        strokes: List<PenStroke>,
        plans: Map<Int, PageRenderPlan>,
        color: PdfColor = defaultColor,
        width: Double = defaultWidth
    ): List<InkSpec> {
        if (strokes.isEmpty()) return emptyList()
        return strokes.groupBy { it.pageIndex }
            .mapNotNull pageLoop@{ (pageIndex, pageStrokes) ->
                val plan = plans[pageIndex] ?: return@pageLoop null
                val inverse = plan.matrix.inverse() ?: return@pageLoop null
                val polylines = pageStrokes.mapNotNull strokeLoop@{ stroke ->
                    if (stroke.points.size < 4) return@strokeLoop null
                    val out = ArrayList<Double>(stroke.points.size)
                    var i = 0
                    while (i + 1 < stroke.points.size) {
                        val px = stroke.points[i] * plan.pixelWidth
                        val py = stroke.points[i + 1] * plan.pixelHeight
                        val user = inverse.apply(px.toDouble(), py.toDouble())
                        out += user[0]
                        out += user[1]
                        i += 2
                    }
                    out.takeIf { it.size >= 4 }
                }
                if (polylines.isEmpty()) return@pageLoop null
                InkSpec(
                    pageIndex = pageIndex,
                    strokes = polylines,
                    color = color,
                    width = width,
                    contents = "Pen marks from presentation"
                )
            }
    }
}

/** Small helper for pen sampling: ignore sub-pixel jitter between moves. */
internal fun PenStroke.shouldAppend(x: Float, y: Float): Boolean {
    if (points.isEmpty()) return true
    val lastX = points[points.size - 2]
    val lastY = points[points.size - 1]
    return abs(x - lastX) + abs(y - lastY) >= 0.0015f
}

/** Aspect ratio used for a page frame; falls back to portrait A4. */
internal fun pageAspect(pixelWidth: Int, pixelHeight: Int): Float =
    if (pixelWidth > 0 && pixelHeight > 0) {
        pixelWidth.toFloat() / pixelHeight.toFloat()
    } else {
        max(1, 612) / 792f
    }
