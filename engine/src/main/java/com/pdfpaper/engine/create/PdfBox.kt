package com.pdfpaper.engine.create

/**
 * An axis-aligned rectangle in page coordinates with the origin at the top-left
 * corner and y growing downward, the same convention as [PdfPageBuilder].
 */
data class PdfBox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
) {
    init {
        require(x.isFinite() && y.isFinite()) { "box origin must be finite" }
        require(width.isFinite() && width >= 0.0) { "box width must be finite and non-negative" }
        require(height.isFinite() && height >= 0.0) { "box height must be finite and non-negative" }
    }

    val right: Double get() = x + width
    val bottom: Double get() = y + height
}
