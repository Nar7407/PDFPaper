package com.pdfpaper.engine.look

import com.pdfpaper.engine.render.PageFilter

/**
 * T62: a suggestion, never a change. A page whose paper is yellowed (a scan of
 * an old book) reads better in sepia, and a blueprint-style line drawing reads
 * better inverted; both are offered to the reader as a dismissible hint and are
 * never applied by the engine or the app on their own.
 *
 * The analysis runs on a rendered page (packed ARGB), so it works for scans
 * that carry no text layer as well as for vector drawings. It is deliberately
 * small and bounded: a sampled grid for the colour statistics and a scan of
 * sampled rows/columns for long straight lines, which is what separates a CAD
 * drawing from a page of text (glyph strokes are short).
 */
object LookSuggestion {

    /** Below this many sampled ink pixels the page counts as blank. */
    const val MIN_INK_FRACTION = 0.001

    /** Above this many sampled ink pixels the page is a photo or solid art. */
    const val MAX_INK_FRACTION = 0.06

    /** Above this fraction of saturated pixels the page is not line art. */
    const val MAX_COLOUR_FRACTION = 0.03

    /** A dark run must span this fraction of the page to count as a rule/line. */
    const val LONG_RUN_FRACTION = 0.25

    /** How many warm pixels (of the bright ones) make a scan "yellowed". */
    const val WARM_FRACTION = 0.45

    /** At least this much of the page must be paper before sepia is offered. */
    const val MIN_PAPER_FRACTION = 0.30

    /** How many long horizontal and vertical runs a line drawing needs. */
    const val MIN_LONG_RUNS = 2

    /**
     * A filter worth offering for this page, or null when the page looks like
     * ordinary text on white paper (or cannot be measured). Never throws: a
     * short buffer, a zero size or a nonsense stride returns null.
     */
    fun suggest(pixels: IntArray?, width: Int, height: Int): PageFilter? {
        if (pixels == null || width < 8 || height < 8) return null
        if (pixels.size < width * height) return null
        val stepX = (width / 240).coerceAtLeast(1)
        val stepY = (height / 320).coerceAtLeast(1)

        var sampled = 0
        var paper = 0
        var warm = 0
        var ink = 0
        var coloured = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val p = pixels[y * width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val l = luminance(r, g, b)
                sampled++
                when {
                    l >= PAPER_LUMINANCE -> {
                        paper++
                        // A scan on aged paper is bright but warm: red leads and
                        // blue trails, with green in between.
                        if (r - b >= WARM_DELTA && r >= g && g >= b) warm++
                    }
                    l <= INK_LUMINANCE -> ink++
                }
                if (maxOf(r, g, b) - minOf(r, g, b) > SATURATION_DELTA) coloured++
                x += stepX
            }
            y += stepY
        }
        if (sampled == 0) return null

        val paperFraction = paper.toDouble() / sampled
        if (paperFraction >= MIN_PAPER_FRACTION && warm.toDouble() >= paper * WARM_FRACTION) {
            return PageFilter.SEPIA
        }

        val inkFraction = ink.toDouble() / sampled
        if (inkFraction < MIN_INK_FRACTION || inkFraction > MAX_INK_FRACTION) return null
        if (coloured.toDouble() > sampled * MAX_COLOUR_FRACTION) return null
        val horizontal = longRuns(pixels, width, height, stepX, stepY, horizontal = true)
        val vertical = longRuns(pixels, width, height, stepX, stepY, horizontal = false)
        return if (horizontal >= MIN_LONG_RUNS && vertical >= MIN_LONG_RUNS) {
            PageFilter.INVERTED_MONO
        } else {
            null
        }
    }

    /**
     * How many sampled rows (or columns) contain one dark run at least
     * [LONG_RUN_FRACTION] of the page long. Runs are measured along the full
     * row/column so a dashed rule with small gaps still counts once per dash,
     * but a line of text never reaches the length.
     */
    private fun longRuns(
        pixels: IntArray,
        width: Int,
        height: Int,
        stepX: Int,
        stepY: Int,
        horizontal: Boolean
    ): Int {
        val length = if (horizontal) width else height
        val threshold = (length * LONG_RUN_FRACTION).toInt().coerceAtLeast(8)
        val across = if (horizontal) height else width
        val acrossStep = if (horizontal) stepY else stepX
        var lines = 0
        var a = 0
        while (a < across) {
            var run = 0
            var longest = 0
            var along = 0
            while (along < length) {
                val x = if (horizontal) along else a
                val y = if (horizontal) a else along
                val p = pixels[y * width + x]
                val dark = luminance((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) <= INK_LUMINANCE
                if (dark) {
                    run++
                    if (run > longest) longest = run
                } else {
                    run = 0
                }
                along++
            }
            if (longest >= threshold) lines++
            a += acrossStep
        }
        return lines
    }

    private fun luminance(r: Int, g: Int, b: Int): Double =
        (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0

    /** Luminance at or above this is paper. */
    private const val PAPER_LUMINANCE = 0.55

    /** Luminance at or below this is ink. */
    private const val INK_LUMINANCE = 0.35

    /** A warm paper pixel's red-blue gap (0..1 of a byte). */
    private const val WARM_DELTA = 0.05 * 255.0

    /** Channel spread above which a pixel counts as coloured. */
    private const val SATURATION_DELTA = 0.25 * 255.0
}
