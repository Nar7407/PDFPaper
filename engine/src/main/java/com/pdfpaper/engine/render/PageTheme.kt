package com.pdfpaper.engine.render

import com.pdfpaper.engine.graphics.PdfColor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How a surface paints a page (T32 content-aware dark mode, T32b true black).
 *
 * The theme is applied to the *primitives* a renderer receives, before
 * rasterisation (§8.5.3 painting, §8.6 colour, §9.4.3 text), never to the
 * finished bitmap. That is what keeps text and line art free of the light
 * halo a per-pixel inversion leaves on anti-aliased edges: an edge pixel is
 * mixed from the mapped ink and the mapped background, not from inverted
 * originals.
 */
enum class PageThemeMode(val label: String) {

    /** The page as authored: white paper, original colours. */
    OFF("Off"),

    /** Content-aware dark: dark grey paper, soft off-white ink, photos kept. */
    DARK("Dark"),

    /** T32b: paper is exactly #000000, for AMOLED panels. */
    TRUE_BLACK("True black (AMOLED)");

    companion object {
        fun fromName(name: String?): PageThemeMode =
            values().firstOrNull { it.name == name } ?: OFF
    }
}

/** A paint resolved for one theme: a device colour plus its constant alpha. */
class PagePaint(val color: PdfColor, val alpha: Double) {

    override fun equals(other: Any?): Boolean =
        other is PagePaint && color == other.color && alpha == other.alpha

    override fun hashCode(): Int = 31 * color.hashCode() + alpha.hashCode()

    override fun toString(): String = "PagePaint($color, alpha=$alpha)"
}

/**
 * The colour rules of the page themes.
 *
 * Classification is deliberately coarse and content-aware:
 *  - a **paper** fill or stroke (every channel near white) becomes the page
 *    background, so the white "paper" rectangles a document draws itself do
 *    not stay a bright slab;
 *  - a **near-black** fill stays a faint dark shape: text drawn on a black box
 *    is the ink colour too, so the box must not become the ink;
 *  - every other fill is compressed into a dark range, keeping its hue, so a
 *    coloured box stays a *dark* box and off-white text on it stays readable;
 *  - text and strokes always become the ink colour;
 *  - raster images are never re-coloured; only the optional [dim] touches
 *    them, and it is 1.0 (unchanged) by default.
 */
data class PageTheme(
    val mode: PageThemeMode = PageThemeMode.OFF,
    /** Text and line-art colour; about #E6E6E6 by default. */
    val ink: PdfColor = DEFAULT_INK,
    /** Raster dimming for photographs: 1.0 keeps their own colours (T32b). */
    val dim: Double = 1.0
) {

    val active: Boolean get() = mode != PageThemeMode.OFF

    /** The colour the page is cleared to before the content is drawn. */
    val background: PdfColor
        get() = when (mode) {
            PageThemeMode.OFF -> PdfColor.WHITE
            PageThemeMode.DARK -> DARK_PAPER
            PageThemeMode.TRUE_BLACK -> PdfColor.BLACK
        }

    /** `f`/`F`/`f*` fill (§8.5.3.1). */
    fun fill(color: PdfColor, alpha: Double): PagePaint {
        if (!active) return PagePaint(color, alpha)
        val a = lift(alpha * color.alpha)
        if (isPaper(color)) return PagePaint(background, a)
        // Near-black is line art or a dark box. It must stay dark: text drawn
        // on top of it is the ink colour too, so mapping it to the ink would
        // make the text vanish. It keeps a faint shape instead, which is what
        // a black box or a vectorised glyph looks like on a dark page.
        if (isInk(color)) return PagePaint(INK_FILL, a)
        val peak = max(color.red, max(color.green, color.blue)).coerceAtLeast(1.0e-6)
        // Dark source colours move up the range and light ones down, so a
        // filled shape is never darker than the paper it sits on.
        val t = (1.0 - luminance(color)).coerceIn(0.0, 1.0)
        val target = FILL_MIN + (FILL_MAX - FILL_MIN) * t
        val scale = target / peak
        return PagePaint(
            PdfColor(
                (color.red * scale).coerceIn(0.0, 1.0),
                (color.green * scale).coerceIn(0.0, 1.0),
                (color.blue * scale).coerceIn(0.0, 1.0)
            ),
            a
        )
    }

    /** `S`/`s` stroke (§8.5.3.1): line art, so always the ink colour. */
    fun stroke(color: PdfColor, alpha: Double): PagePaint {
        if (!active) return PagePaint(color, alpha)
        val a = lift(alpha * color.alpha)
        // A white stroke is a knockout rule; keeping it paper preserves the
        // separation it drew in light mode.
        return PagePaint(if (isPaper(color)) background else ink, a)
    }

    /** Text (§9.4.3). The engine's text model carries no colour. */
    fun text(): PagePaint = if (active) PagePaint(ink, 1.0) else PagePaint(PdfColor.BLACK, 1.0)

    /** `/ImageMask` stencils are painted in the fill colour (§8.9.6.2). */
    fun stencil(color: PdfColor): PagePaint = fill(color, 1.0)

    /** Scale applied to raster images; 1.0 leaves a photograph untouched. */
    fun imageDim(): Double = if (active) dim.coerceIn(0.0, 1.0) else 1.0

    /** True when [color] is the background the theme clears the page to. */
    fun isBackground(color: PdfColor): Boolean = active && color == background

    private fun isPaper(color: PdfColor): Boolean =
        min(color.red, min(color.green, color.blue)) >= PAPER_MIN

    private fun isInk(color: PdfColor): Boolean =
        max(color.red, max(color.green, color.blue)) <= INK_MAX

    private fun luminance(color: PdfColor): Double =
        0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue

    /**
     * Semi-transparent shapes keep their alpha, but a 10 % tint that reads on
     * white paper disappears on black. The square root keeps 0 and 1 fixed and
     * lifts the faint end enough to stay visible.
     */
    private fun lift(alpha: Double): Double {
        val a = alpha.coerceIn(0.0, 1.0)
        return if (a <= 0.0 || a >= 1.0) a else sqrt(a)
    }

    companion object {

        /** Soft off-white ink (#E6E6E6), the T32b default. */
        val DEFAULT_INK = PdfColor(230.0 / 255.0, 230.0 / 255.0, 230.0 / 255.0)

        /** Dark-mode paper (#121212). */
        val DARK_PAPER = PdfColor(18.0 / 255.0, 18.0 / 255.0, 18.0 / 255.0)

        /** Every channel at or above this is paper. */
        const val PAPER_MIN = 0.90

        /** Every channel at or below this is line art. */
        const val INK_MAX = 0.15

        /** The darkest and the brightest a mapped fill may be (peak channel). */
        const val FILL_MIN = 0.14
        const val FILL_MAX = 0.38

        /** What a near-black source fill becomes: a faint shape on the paper. */
        val INK_FILL = PdfColor(FILL_MIN, FILL_MIN, FILL_MIN)

        /** The theme that changes nothing. */
        val OFF = PageTheme()

        /**
         * Mode resolution order (T32b): a per-document override wins, then a
         * "follow system" global that is off while the system is light, then
         * the global default. Ink and dim always come from [global].
         */
        fun resolve(
            global: PageTheme,
            perDocument: PageThemeMode? = null,
            followSystem: Boolean = false,
            systemDark: Boolean = true
        ): PageTheme {
            val mode = when {
                perDocument != null -> perDocument
                followSystem && !systemDark -> PageThemeMode.OFF
                else -> global.mode
            }
            return global.copy(mode = mode)
        }
    }
}

/** WCAG 2.x relative luminance of a device colour (0..1). */
fun PdfColor.relativeLuminance(): Double {
    fun channel(value: Double): Double {
        val c = value.coerceIn(0.0, 1.0)
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
}

/** WCAG 2.x contrast ratio between two opaque colours (1.0 .. 21.0). */
fun contrastRatio(a: PdfColor, b: PdfColor): Double {
    val la = a.relativeLuminance()
    val lb = b.relativeLuminance()
    val hi = max(la, lb)
    val lo = min(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}
