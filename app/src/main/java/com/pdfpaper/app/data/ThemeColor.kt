package com.pdfpaper.app.data

import com.pdfpaper.engine.graphics.PdfColor
import kotlin.math.roundToInt

/**
 * The page-theme ink colour is stored as a packed `0xRRGGBB` integer (T32b),
 * which is what a colour picker hands back. The engine works in 0..1 device
 * components (§8.6), so the two conversions live here and nowhere else.
 */

/** #E6E6E6, the default soft off-white ink of the true-black mode. */
const val DEFAULT_THEME_INK: Int = 0xE6E6E6

fun pdfColorOf(rgb: Int): PdfColor = PdfColor(
    ((rgb shr 16) and 0xFF) / 255.0,
    ((rgb shr 8) and 0xFF) / 255.0,
    (rgb and 0xFF) / 255.0
)

fun rgbOf(color: PdfColor): Int {
    val r = (color.red * 255.0).roundToInt().coerceIn(0, 255)
    val g = (color.green * 255.0).roundToInt().coerceIn(0, 255)
    val b = (color.blue * 255.0).roundToInt().coerceIn(0, 255)
    return (r shl 16) or (g shl 8) or b
}
