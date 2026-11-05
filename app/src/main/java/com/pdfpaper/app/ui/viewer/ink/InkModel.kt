package com.pdfpaper.app.ui.viewer.ink

import com.pdfpaper.engine.annot.InkPoint
import com.pdfpaper.engine.annot.InkTool
import com.pdfpaper.engine.annot.RichInkSpec
import com.pdfpaper.engine.annot.StoredInkStroke
import com.pdfpaper.engine.graphics.PdfColor

/**
 * One sampled stylus/finger point in PDF user space (T54). Pressure is 0..1,
 * tilt is degrees from the screen normal. Keeping tilt here means the input
 * path really consumes `AXIS_TILT`; the saved PDF stores it next to the
 * pressure for later editing, while the painted appearance is pressure-driven.
 */
data class InkSample(
    val x: Double,
    val y: Double,
    val pressure: Double = 1.0,
    val tilt: Double = 0.0
) {
    fun toEnginePoint(): InkPoint =
        InkPoint(x, y, pressure.coerceIn(0.0, 1.0), tilt.coerceIn(0.0, 90.0))
}

/** A selected drawing tool. Eraser and lasso are actions, not saved tools. */
enum class InkToolMode {
    PEN,
    HIGHLIGHTER,
    ERASER_STROKE,
    ERASER_POINT,
    LASSO
}

/**
 * One editable stroke. The id is local to the editing session and only used
 * for selection/undo; the PDF stores the geometry, not the id.
 */
data class InkStroke(
    val id: Long,
    val pageIndex: Int,
    val samples: List<InkSample>,
    val color: PdfColor,
    val width: Double,
    val opacity: Double = 1.0,
    val tool: InkTool = InkTool.PEN
) {
    val highlighter: Boolean get() = tool == InkTool.HIGHLIGHTER

    fun toSpec(contents: String? = null): RichInkSpec = RichInkSpec(
        pageIndex = pageIndex,
        strokes = listOf(samples.map { it.toEnginePoint() }),
        color = color,
        width = width,
        opacity = opacity,
        tool = tool,
        contents = contents
    )
}

/** Preview data for a shape that replaces a freehand stroke when held. */
data class ShapeSnap(
    val kind: ShapeKind,
    val samples: List<InkSample>
)

enum class ShapeKind { LINE, CIRCLE, RECTANGLE }

/**
 * The editable ink layer: strokes for every page plus the transient gesture
 * state. [savedAnnotationIds] are the source annotation object numbers whose
 * private ink was loaded; saving replaces them instead of duplicating them.
 */
data class InkUiState(
    val mode: InkToolMode = InkToolMode.PEN,
    val color: PdfColor = INK_BLACK,
    val width: Double = 2.0,
    val stylusOnly: Boolean = false,
    val strokes: List<InkStroke> = emptyList(),
    val active: InkStroke? = null,
    val lasso: List<InkSample> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val savedAnnotationIds: Set<Int> = emptySet(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dirty: Boolean = false,
    val loading: Boolean = false,
    val focusPage: Int? = null
) {
    val erasing: Boolean
        get() = mode == InkToolMode.ERASER_STROKE || mode == InkToolMode.ERASER_POINT

    fun strokesOn(pageIndex: Int): List<InkStroke> =
        strokes.filter { it.pageIndex == pageIndex }

    companion object {
        val INK_BLACK = PdfColor(0.10, 0.10, 0.10)
    }
}

/** Colour and width presets shared by the toolbar and the focus canvas. */
object InkPalette {
    val colors = listOf(
        InkUiState.INK_BLACK,
        PdfColor(0.86, 0.12, 0.12),
        PdfColor(0.10, 0.36, 0.88),
        PdfColor(0.08, 0.58, 0.28),
        PdfColor(0.96, 0.68, 0.08),
        PdfColor(0.55, 0.20, 0.75)
    )

    val widths = listOf(1.0, 2.0, 4.0, 7.0, 12.0)
    const val HIGHLIGHTER_WIDTH = 18.0
    const val HIGHLIGHTER_OPACITY = 0.34

    fun converted(stored: StoredInkStroke, pageIndex: Int, id: Long): InkStroke =
        InkStroke(
            id = id,
            pageIndex = pageIndex,
            samples = stored.points.map {
                InkSample(it.x, it.y, it.pressure, it.tilt)
            },
            color = stored.color,
            width = stored.width,
            opacity = stored.opacity,
            tool = stored.tool
        )
}
