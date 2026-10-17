package com.pdfpaper.engine.graphics

import com.pdfpaper.engine.geom.PdfMatrix

/**
 * Stroke parameters (§8.4.3.2, Table 57): line width, cap/join, miter limit and
 * dash pattern. Immutable; use [copy] to change one field.
 */
data class LineStyle(
    val width: Double = 1.0,
    val cap: Int = 0,
    val join: Int = 0,
    val miterLimit: Double = 10.0,
    /** Empty = solid line (§8.4.3.6). */
    val dashArray: DoubleArray = DoubleArray(0),
    val dashPhase: Double = 0.0
) {
    override fun equals(other: Any?): Boolean = other is LineStyle &&
        width == other.width && cap == other.cap && join == other.join &&
        miterLimit == other.miterLimit && dashPhase == other.dashPhase &&
        dashArray.contentEquals(other.dashArray)

    override fun hashCode(): Int {
        var h = width.hashCode()
        h = 31 * h + cap
        h = 31 * h + join
        h = 31 * h + miterLimit.hashCode()
        h = 31 * h + dashPhase.hashCode()
        h = 31 * h + dashArray.contentHashCode()
        return h
    }

    override fun toString(): String =
        "LineStyle(width=$width, cap=$cap, join=$join, miter=$miterLimit, dash=${dashArray.toList()}@$dashPhase)"
}

/**
 * Device-independent colour (§8.6): non-linear components plus constant alpha
 * (from the ExtGState /ca and /CA keys). The engine never converts to
 * platform pixel formats; renderers do that.
 */
class PdfColor(
    val red: Double,
    val green: Double,
    val blue: Double,
    val alpha: Double = 1.0
) {
    override fun equals(other: Any?): Boolean = other is PdfColor &&
        red == other.red && green == other.green && blue == other.blue && alpha == other.alpha

    override fun hashCode(): Int {
        var h = red.hashCode()
        h = 31 * h + green.hashCode()
        h = 31 * h + blue.hashCode()
        h = 31 * h + alpha.hashCode()
        return h
    }

    override fun toString(): String = "PdfColor($red,$green,$blue,$alpha)"

    companion object {
        val BLACK = PdfColor(0.0, 0.0, 0.0)
        val WHITE = PdfColor(1.0, 1.0, 1.0)
    }
}

/**
 * Painter state (§8.4.1, Table 58) limited to what the display list needs:
 * transform, line style, colour spaces/components and constant alpha.
 * q/Q push/pop copies of this object.
 */
class GraphicsState {
    var ctm: PdfMatrix = PdfMatrix.IDENTITY
    var lineStyle: LineStyle = LineStyle()
    var strokeColorSpace: PdfColorSpace? = null
    var fillColorSpace: PdfColorSpace? = null
    var strokeComponents: DoubleArray = doubleArrayOf(0.0)
    var fillComponents: DoubleArray = doubleArrayOf(0.0)
    var fillAlpha: Double = 1.0
    var strokeAlpha: Double = 1.0
    var blendMode: String? = null

    fun copy(): GraphicsState {
        val clone = GraphicsState()
        clone.ctm = ctm
        clone.lineStyle = lineStyle
        clone.strokeColorSpace = strokeColorSpace
        clone.fillColorSpace = fillColorSpace
        clone.strokeComponents = strokeComponents.copyOf()
        clone.fillComponents = fillComponents.copyOf()
        clone.fillAlpha = fillAlpha
        clone.strokeAlpha = strokeAlpha
        clone.blendMode = blendMode
        return clone
    }

    /** Stroking colour; DeviceGray black when no /CS /G was seen (§8.6.8). */
    fun strokeColor(): PdfColor = (strokeColorSpace ?: DeviceGrayCS).toRgb(strokeComponents)

    /** Non-stroking colour; DeviceGray black by default. */
    fun fillColor(): PdfColor = (fillColorSpace ?: DeviceGrayCS).toRgb(fillComponents)
}
