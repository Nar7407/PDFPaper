package com.pdfpaper.app.ui.viewer.reflow

import com.pdfpaper.engine.reflow.ReflowOptions
import com.pdfpaper.engine.text.PageLayout
import com.pdfpaper.engine.text.bodyFontSize
import kotlin.math.roundToInt

/** The typeface a reflow page is drawn in (T33). */
enum class ReflowFont(val label: String) {
    SERIF("Serif"),
    SANS("Sans"),
    MONO("Mono"),

    /** The one readable font the app bundles (Noto Sans Devanagari). */
    READABLE("Readable")
}

/** Text alignment in reflow. */
enum class ReflowAlign(val label: String) {
    LEFT("Left"),
    JUSTIFIED("Justified")
}

/**
 * The reader's reflow preferences (T33). Everything here is remembered in
 * [com.pdfpaper.app.data.AppSettings] and applies to every document.
 */
data class ReflowSettings(
    val fontSizeSp: Float = 17f,
    val lineSpacing: Float = 1.5f,
    val paragraphSpacingDp: Float = 14f,
    val marginDp: Float = 20f,
    val align: ReflowAlign = ReflowAlign.LEFT,
    val font: ReflowFont = ReflowFont.SERIF,
    val hidePageNumbers: Boolean = true,
    val hideFootnotes: Boolean = true
) {

    /** The engine options that follow the reader's choices. */
    fun options(): ReflowOptions = ReflowOptions(
        hidePageNumbers = hidePageNumbers,
        hideFootnotes = hideFootnotes
    )

    /** Values from outside (a slider, an old settings file) are clamped here. */
    fun normalised(): ReflowSettings = copy(
        fontSizeSp = fontSizeSp.coerceIn(MIN_FONT_SP, MAX_FONT_SP),
        lineSpacing = lineSpacing.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING),
        paragraphSpacingDp = paragraphSpacingDp.coerceIn(0f, MAX_PARAGRAPH_DP),
        marginDp = marginDp.coerceIn(0f, MAX_MARGIN_DP)
    )

    /** Line height in sp, i.e. the font size times the spacing multiple. */
    val lineHeightSp: Float get() = fontSizeSp * lineSpacing

    companion object {
        const val MIN_FONT_SP = 12f
        const val MAX_FONT_SP = 32f
        const val MIN_LINE_SPACING = 1.0f
        const val MAX_LINE_SPACING = 2.2f
        const val MAX_PARAGRAPH_DP = 32f
        const val MAX_MARGIN_DP = 48f

        /** Comfortable defaults for a phone in portrait. */
        val DEFAULT = ReflowSettings()
    }
}

/**
 * Where the reader is, in the form the page view understands: a zero-based
 * page and how far into that page (0..1). Switching modes hands one of these
 * across in both directions, so the reading spot survives the switch.
 */
data class ReflowPosition(val page: Int, val fraction: Float) {
    companion object {
        val START = ReflowPosition(0, 0f)
    }
}

/**
 * How far into the page item the viewport has scrolled, 0..1. [itemHeightPx]
 * is the measured height of the page item, so a page that grows when its
 * blocks arrive still maps to the same fraction of itself.
 */
fun pageFraction(offsetPx: Int, itemHeightPx: Int): Float {
    if (itemHeightPx <= 0) return 0f
    return (offsetPx.toFloat() / itemHeightPx).coerceIn(0f, 1f)
}

/** The scroll offset in pixels that puts [fraction] of a page at the top. */
fun offsetForFraction(fraction: Float, itemHeightPx: Int): Int {
    if (itemHeightPx <= 0) return 0
    return (fraction.coerceIn(0f, 1f) * itemHeightPx).roundToInt()
}

/**
 * True when the page's body text is drawn smaller than [thresholdSp] at the
 * current zoom (T33b's "Text is small" suggestion). [scale] is the bitmap's
 * pixels per PDF point and [density] the screen's density, so the result is in
 * the same sp the reader sees.
 */
fun isTextSmall(
    layout: PageLayout,
    scale: Double,
    density: Float,
    thresholdSp: Float = 12f
): Boolean {
    val size = layout.bodyFontSize()
    if (size <= 0.0) return false
    val densityValue = if (density > 0f) density.toDouble() else 1.0
    val renderedSp = size * scale / densityValue
    return renderedSp.isFinite() && renderedSp < thresholdSp
}

/** One-line description of the reading settings, for the sheet header. */
fun describeReflow(settings: ReflowSettings): String {
    val size = settings.fontSizeSp.roundToInt()
    val lines = ((settings.lineSpacing - 1f) * 100f).roundToInt()
    return "${settings.font.label} $size sp, ${lines}% extra leading, ${settings.align.label.lowercase()}"
}

/** Estimated height of a page that has not been extracted yet, in dp. */
const val REFLOW_PLACEHOLDER_DP: Float = 640f

/**
 * How many extracted pages stay in memory. Text only, so a page is a few KB;
 * the cap keeps a 500-page book from holding every page at once.
 */
const val REFLOW_PAGE_CACHE = 120
