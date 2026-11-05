package com.pdfpaper.engine.eink

import com.pdfpaper.engine.graphics.PdfColor
import kotlin.math.max

/**
 * Task T63: the rendering profiles of an E-Ink / reflex display.
 *
 * A profile reduces the page to what an electrophoretic panel can show well.
 * The choice is deliberately explicit: pure 1-bit output is crisp and
 * ghost-free on a panel, but it can destroy the anti-aliasing of small text,
 * so the reader picks the profile instead of the app guessing.
 */
enum class EinkProfile(val label: String, val description: String) {

    /** The page as authored. */
    OFF("Off", "The page as it was made."),

    /**
     * Grayscale with a strong contrast curve. Anti-aliased glyph edges are
     * kept, so small text stays readable; no dithering is applied.
     */
    GRAYSCALE(
        "Grayscale high contrast",
        "A strong grey curve. Small text keeps its anti-aliased edges."
    ),

    /**
     * One bit for text and line art, ordered or Floyd-Steinberg dithering for
     * pictures. This is the profile a text-heavy book wants.
     */
    TEXT_BITONAL(
        "1-bit text, dithered images",
        "Black and white text, pictures dithered so they keep their tones."
    ),

    /**
     * One bit for everything, with the reader's own threshold. Drawings and
     * faint scans are thresholded; photographs become a pattern.
     */
    FULL_BITONAL(
        "1-bit everything",
        "The whole page becomes black and white at one threshold."
    );

    /** A chip-sized name. */
    val shortLabel: String
        get() = when (this) {
            OFF -> "Off"
            GRAYSCALE -> "Grayscale"
            TEXT_BITONAL -> "1-bit text"
            FULL_BITONAL -> "1-bit all"
        }

    companion object {
        fun fromName(name: String?): EinkProfile =
            values().firstOrNull { it.name == name } ?: OFF
    }
}

/** How a one-bit picture is reduced (T63). */
enum class EinkDither(val label: String) {

    /** A 4x4 Bayer matrix: a regular pattern, cheap and stable on a panel. */
    ORDERED("Ordered (4×4)"),

    /** Error diffusion: finer detail, slightly more texture. */
    FLOYD_STEINBERG("Floyd–Steinberg")
}

/**
 * The E-Ink display settings (T63). They are device-wide by default and can be
 * overridden per document (the app stores that override); the file itself is
 * never touched.
 *
 * [threshold] is the luminance at which a pixel turns black, 0.15..0.85. It is
 * the contrast slider of [EinkProfile.FULL_BITONAL]; for the other profiles it
 * moves the middle of the greyscale curve and the dither bias, so one slider
 * means the same thing everywhere.
 *
 * [minLineWidthPx] keeps hairlines visible after the reduction: a stroke
 * thinner than this many device pixels is drawn this wide (T63's "thin lines
 * are thickened to a minimum width"). Rendering profiles one and two are the
 * ones that need it.
 */
data class EinkSettings(
    val profile: EinkProfile = EinkProfile.OFF,
    val threshold: Double = DEFAULT_THRESHOLD,
    val dither: EinkDither = EinkDither.ORDERED,
    val minLineWidthPx: Double = DEFAULT_MIN_LINE_WIDTH_PX
) {

    /** True when a profile is doing anything. */
    val active: Boolean get() = profile != EinkProfile.OFF

    /** True when text and line art are painted in pure black or white. */
    val bitonal: Boolean
        get() = profile == EinkProfile.TEXT_BITONAL || profile == EinkProfile.FULL_BITONAL

    /** True when pictures are reduced with a dither pattern (profile 2). */
    val ditheredImages: Boolean get() = profile == EinkProfile.TEXT_BITONAL

    /** True when the finished bitmap is post-processed (profiles 1 and 3). */
    val postProcessed: Boolean
        get() = profile == EinkProfile.GRAYSCALE || profile == EinkProfile.FULL_BITONAL

    /** Every value inside its documented range. */
    fun normalized(): EinkSettings = copy(
        threshold = if (threshold.isFinite()) {
            threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        } else {
            DEFAULT_THRESHOLD
        },
        minLineWidthPx = if (minLineWidthPx.isFinite()) {
            minLineWidthPx.coerceIn(0.0, MAX_MIN_LINE_WIDTH_PX)
        } else {
            DEFAULT_MIN_LINE_WIDTH_PX
        }
    )

    /** True when [luminance] (0..1) is painted black at this threshold. */
    fun isBlack(luminance: Double): Boolean =
        luminance.coerceIn(0.0, 1.0) < threshold.orDefault()

    /**
     * A fill for profile 2: a threshold, so a light grey table header becomes
     * white and black text stays black. Alpha is decided by the renderer.
     */
    fun binaryFill(color: PdfColor): PdfColor =
        if (isBlack(luminance(color))) BLACK else WHITE

    /**
     * A stroke for profile 2. Line art is kept black even when it is a light
     * grey: a faint hairline is exactly what an E-Ink panel loses first, and
     * the reader asked for high contrast. A near-paper stroke is a knockout
     * rule, so it stays paper and keeps the separation it drew.
     */
    fun binaryStroke(color: PdfColor): PdfColor =
        if (luminance(color) >= KNOCKOUT_LUMINANCE) WHITE else BLACK

    /**
     * The greyscale curve of [EinkProfile.GRAYSCALE]: a linear stretch around
     * the reader's threshold. With the default 0.5 everything below 0.22 is
     * black and everything above 0.72 is white, so a grey scan gains contrast
     * while the anti-aliased edge of a glyph remains a gradient.
     */
    fun grayscaleValue(luminance: Double): Double {
        val t = threshold.orDefault()
        val black = (t - GRAY_BLACK_SPAN).coerceAtLeast(0.0)
        val white = (t + GRAY_WHITE_SPAN).coerceAtMost(1.0)
        if (white <= black) return if (luminance < t) 0.0 else 1.0
        return ((luminance - black) / (white - black)).coerceIn(0.0, 1.0)
    }

    /**
     * A stroke width in user space, grown so it covers at least
     * [minLineWidthPx] device pixels. [deviceScale] is device pixels per user
     * unit (the page plan times the CTM); a zero or negative scale leaves the
     * authored width alone.
     */
    fun strokeWidth(userWidth: Double, deviceScale: Double): Double {
        if (!bitonal || deviceScale <= 0.0 || minLineWidthPx <= 0.0) return userWidth
        return max(userWidth, minLineWidthPx / deviceScale)
    }

    /** Luminance of a device colour, using the Rec. 709 weights. */
    fun luminance(color: PdfColor): Double =
        (0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue).coerceIn(0.0, 1.0)

    private fun Double.orDefault(): Double =
        if (isFinite()) coerceIn(MIN_THRESHOLD, MAX_THRESHOLD) else DEFAULT_THRESHOLD

    companion object {
        const val DEFAULT_THRESHOLD = 0.5
        const val MIN_THRESHOLD = 0.15
        const val MAX_THRESHOLD = 0.85
        const val DEFAULT_MIN_LINE_WIDTH_PX = 1.0
        const val MAX_MIN_LINE_WIDTH_PX = 3.0

        val BLACK = PdfColor(0.0, 0.0, 0.0)
        val WHITE = PdfColor(1.0, 1.0, 1.0)

        /** How far below the threshold the grey curve reaches black. */
        const val GRAY_BLACK_SPAN = 0.28

        /** How far above the threshold it reaches white. */
        const val GRAY_WHITE_SPAN = 0.22

        /** A stroke at or above this luminance is a knockout, not line art. */
        const val KNOCKOUT_LUMINANCE = 0.88

        /** The profile changes nothing. */
        val OFF = EinkSettings()
    }
}
