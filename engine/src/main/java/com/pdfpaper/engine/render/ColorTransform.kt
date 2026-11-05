package com.pdfpaper.engine.render

import com.pdfpaper.engine.graphics.PdfColor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A reader's colour look (T62). Every filter changes what is *painted*, never
 * the document: the bytes on disk are untouched, and the same page renders as
 * authored again the moment the look is set back to [NORMAL].
 */
enum class PageFilter(val label: String) {

    /** The page as authored. */
    NORMAL("Normal"),

    /** A warm duotone, for scans on yellowed paper. */
    SEPIA("Sepia"),

    /** The T32 content-aware dark page: dark paper, off-white ink, photos kept. */
    DARK("Dark"),

    /** The T32b true-black page, for AMOLED panels. */
    TRUE_BLACK("True black"),

    /** Blueprint/CAD: everything inverted, then tinted (drawings read well). */
    INVERTED_MONO("Inverted monochrome"),

    /** A stronger contrast curve, so faint scans stay legible. */
    HIGH_CONTRAST("High contrast");

    /** The T32 page theme this filter paints with; the others paint light. */
    val themeMode: PageThemeMode
        get() = when (this) {
            DARK -> PageThemeMode.DARK
            TRUE_BLACK -> PageThemeMode.TRUE_BLACK
            else -> PageThemeMode.OFF
        }

    companion object {
        fun fromName(name: String?): PageFilter =
            values().firstOrNull { it.name == name } ?: NORMAL

        /** The filter that reproduces a T32 page theme (for a document that has none of its own). */
        fun of(mode: PageThemeMode?): PageFilter = when (mode) {
            PageThemeMode.DARK -> DARK
            PageThemeMode.TRUE_BLACK -> TRUE_BLACK
            else -> NORMAL
        }
    }
}

/**
 * How one document should be painted (T62).
 *
 * The look has two halves that compose:
 *  - a [filter], which is a colour mapping of the page (duotone sepia, an
 *    inversion with a tint, a contrast curve, or the T32/T32b dark themes);
 *  - three global sliders, applied on top of whatever the filter produced.
 *
 * Dark and true black stay on the T32 primitive path (the paper, ink and
 * near-black rules of [PageTheme]), because mapping the primitives keeps
 * anti-aliased glyph edges free of a halo. The other filters are per-channel
 * colour mappings, so they are applied to the colours a renderer is handed -
 * fills, strokes, text, stencil tints and image pixels - and never to a
 * finished bitmap. A "Normal" look with neutral sliders is bit-exact identity,
 * so a document with no look renders exactly as it always did.
 */
data class ColorTransform(
    val filter: PageFilter = PageFilter.NORMAL,
    /** Sepia warmth, 0 (neutral grey duotone) .. 1 (strong amber). */
    val warmth: Double = DEFAULT_WARMTH,
    /** The ink colour of [PageFilter.INVERTED_MONO]; a light tint reads best. */
    val tint: PdfColor = DEFAULT_TINT,
    /** Added to every channel, -0.5 .. 0.5. */
    val brightness: Double = 0.0,
    /** Scales every channel around mid grey, 0.5 .. 2.0. */
    val contrast: Double = 1.0,
    /** Channel power curve, 0.4 .. 2.5; above 1 darkens the mid tones. */
    val gamma: Double = 1.0
) {

    /** True when this look changes anything at all. */
    val active: Boolean
        get() = filter != PageFilter.NORMAL || brightness != 0.0 || contrast != 1.0 || gamma != 1.0

    /** True when the reader's sliders alone would change something. */
    val graded: Boolean
        get() = brightness != 0.0 || contrast != 1.0 || gamma != 1.0

    /**
     * Every field inside its documented range; a NaN falls back to the default.
     * The tint is snapped to the 8-bit grid a colour picker and the stored
     * record use, so a look compares equal to the one that is read back.
     */
    fun normalized(): ColorTransform = ColorTransform(
        filter = filter,
        warmth = warmth.or(DEFAULT_WARMTH).coerceIn(0.0, 1.0),
        tint = PdfColor(
            quantize(tint.red),
            quantize(tint.green),
            quantize(tint.blue),
            tint.alpha
        ),
        brightness = brightness.or(0.0).coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS),
        contrast = contrast.or(1.0).coerceIn(MIN_CONTRAST, MAX_CONTRAST),
        gamma = gamma.or(1.0).coerceIn(MIN_GAMMA, MAX_GAMMA)
    )

    /**
     * The page theme to paint with. The dark filters choose the T32/T32b paper
     * and keep the reader's global ink and photo dim; every other filter paints
     * the page light, so a light look on a document whose global default is
     * dark does not come out dark.
     */
    fun pageTheme(base: PageTheme = PageTheme.OFF): PageTheme = when (filter) {
        PageFilter.DARK -> base.copy(mode = PageThemeMode.DARK)
        PageFilter.TRUE_BLACK -> base.copy(mode = PageThemeMode.TRUE_BLACK)
        PageFilter.NORMAL -> base
        PageFilter.SEPIA, PageFilter.INVERTED_MONO, PageFilter.HIGH_CONTRAST ->
            base.copy(mode = PageThemeMode.OFF)
    }

    /** The colour the page is cleared to: the theme's paper through the look. */
    fun background(base: PageTheme = PageTheme.OFF): PdfColor = grade(pageTheme(base).background)

    /** Maps one device colour; the constant alpha is carried through. */
    fun grade(color: PdfColor): PdfColor {
        if (!active) return color
        val out = scratch.get()
        mapInto(color.red, color.green, color.blue, out)
        return PdfColor(out[0], out[1], out[2], color.alpha)
    }

    /** Maps one `0xRRGGBB` colour, for hosts that work in bytes. */
    fun gradeRgb(red: Int, green: Int, blue: Int): Int {
        val r = red.coerceIn(0, 255)
        val g = green.coerceIn(0, 255)
        val b = blue.coerceIn(0, 255)
        if (!active) return (r shl 16) or (g shl 8) or b
        val out = scratch.get()
        mapInto(r / 255.0, g / 255.0, b / 255.0, out)
        return (channelByte(out[0]) shl 16) or (channelByte(out[1]) shl 8) or channelByte(out[2])
    }

    /** Maps a packed ARGB pixel; the alpha byte is kept. */
    fun gradeArgb(argb: Int): Int {
        if (!active) return argb
        val out = scratch.get()
        mapInto(
            ((argb shr 16) and 0xFF) / 255.0,
            ((argb shr 8) and 0xFF) / 255.0,
            (argb and 0xFF) / 255.0,
            out
        )
        return (argb and 0xFF000000.toInt()) or
            (channelByte(out[0]) shl 16) or
            (channelByte(out[1]) shl 8) or
            channelByte(out[2])
    }

    /**
     * Maps a whole pixel buffer in place. One scratch buffer per thread is used
     * so a full-page scan costs no allocation at all: a 2500 x 3500 scan is
     * 8.75 M pixels.
     */
    fun apply(pixels: IntArray) {
        if (!active) return
        val out = scratch.get()
        for (i in pixels.indices) {
            val pixel = pixels[i]
            mapInto(
                ((pixel shr 16) and 0xFF) / 255.0,
                ((pixel shr 8) and 0xFF) / 255.0,
                (pixel and 0xFF) / 255.0,
                out
            )
            pixels[i] = (pixel and 0xFF000000.toInt()) or
                (channelByte(out[0]) shl 16) or
                (channelByte(out[1]) shl 8) or
                channelByte(out[2])
        }
    }

    // ------------------------------------------------------------- internals

    /**
     * The one mapping both the colour path and the pixel path use. The result
     * is written into [out] (r, g, b, 0..1) instead of being returned, so a
     * pixel loop does not allocate.
     */
    private fun mapInto(r: Double, g: Double, b: Double, out: DoubleArray) {
        var fr = r
        var fg = g
        var fb = b
        when (filter) {
            PageFilter.NORMAL, PageFilter.DARK, PageFilter.TRUE_BLACK -> Unit
            PageFilter.SEPIA -> {
                val w = warmth.or(DEFAULT_WARMTH).coerceIn(0.0, 1.0)
                val l = luminance(fr, fg, fb)
                fr = 0.36 * w + (1.0 - 0.36 * w) * l
                fg = 0.25 * w + (1.0 - 0.08 * w - 0.25 * w) * l
                fb = 0.15 * w + (1.0 - 0.24 * w - 0.15 * w) * l
            }
            PageFilter.INVERTED_MONO -> {
                val scale = PAPER_FLOOR + (1.0 - PAPER_FLOOR) * (1.0 - luminance(fr, fg, fb))
                fr = tint.red.coerceIn(0.0, 1.0) * scale
                fg = tint.green.coerceIn(0.0, 1.0) * scale
                fb = tint.blue.coerceIn(0.0, 1.0) * scale
            }
            PageFilter.HIGH_CONTRAST -> {
                fr = (fr - 0.5) * HIGH_CONTRAST_GAIN + 0.5
                fg = (fg - 0.5) * HIGH_CONTRAST_GAIN + 0.5
                fb = (fb - 0.5) * HIGH_CONTRAST_GAIN + 0.5
            }
        }
        val gamma = this.gamma.or(1.0)
        val contrast = this.contrast.or(1.0)
        val brightness = this.brightness.or(0.0)
        out[0] = adjust(fr, gamma, contrast, brightness)
        out[1] = adjust(fg, gamma, contrast, brightness)
        out[2] = adjust(fb, gamma, contrast, brightness)
    }

    /** Gamma, then contrast around mid grey, then brightness; each clipped. */
    private fun adjust(value: Double, gamma: Double, contrast: Double, brightness: Double): Double {
        var c = value.coerceIn(0.0, 1.0)
        if (gamma != 1.0) c = c.pow(gamma)
        if (contrast != 1.0) c = (c - 0.5) * contrast + 0.5
        if (brightness != 0.0) c += brightness
        return c.coerceIn(0.0, 1.0)
    }

    private fun luminance(r: Double, g: Double, b: Double): Double =
        (0.2126 * r + 0.7152 * g + 0.0722 * b).coerceIn(0.0, 1.0)

    private fun channelByte(value: Double): Int =
        (value.coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceIn(0, 255)

    /** One mapping buffer per thread; a look is shared, renderers are not. */
    private val scratch: ThreadLocal<DoubleArray> = ThreadLocal.withInitial { DoubleArray(3) }

    companion object {

        /** Sepia warmth that still looks warm without going orange. */
        const val DEFAULT_WARMTH = 0.55

        /** Blueprint blue, a light tint that reads on a dark surface. */
        val DEFAULT_TINT = PdfColor(0.66, 0.78, 0.94)

        /** The darkest a blueprint paper may get, as a fraction of the tint. */
        const val PAPER_FLOOR = 0.10

        /** How hard [PageFilter.HIGH_CONTRAST] pushes around mid grey. */
        const val HIGH_CONTRAST_GAIN = 1.9

        const val MIN_BRIGHTNESS = -0.5
        const val MAX_BRIGHTNESS = 0.5
        const val MIN_CONTRAST = 0.5
        const val MAX_CONTRAST = 2.0
        const val MIN_GAMMA = 0.4
        const val MAX_GAMMA = 2.5

        /** The look that changes nothing: the page as authored. */
        val NONE = ColorTransform()

        /** The darkest tone of [PageFilter.INVERTED_MONO]'s paper (for tests). */
        fun blueprintFloor(tint: PdfColor = DEFAULT_TINT): PdfColor = PdfColor(
            tint.red * PAPER_FLOOR,
            tint.green * PAPER_FLOOR,
            tint.blue * PAPER_FLOOR,
            tint.alpha
        )

        private fun Double.or(fallback: Double): Double = if (isFinite()) this else fallback

        /** A channel on the 0..255 grid, so a stored colour reads back equal. */
        private fun quantize(value: Double): Double =
            (value.coerceIn(0.0, 1.0) * 255.0).roundToInt() / 255.0
    }
}

