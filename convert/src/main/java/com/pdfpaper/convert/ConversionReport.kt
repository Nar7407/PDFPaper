package com.pdfpaper.convert

/** Per-page conversion statistics and the reasons things were dropped. */
class PageLosses(
    val pageNumber: Int,
    val widthPt: Double,
    val heightPt: Double,
    val textBoxes: Int,
    val pictures: Int,
    /** Human-readable reasons for images that could not be placed. */
    val imagesSkipped: List<String>,
    val vectorFills: Int,
    val vectorStrokes: Int,
    /** True when vector art was folded into a background picture instead. */
    val vectorsRasterized: Boolean,
    val shadings: Int,
    val patterns: Int,
    /** Base font names that did not match a known family. */
    val unknownFonts: List<String>,
    val rotatedText: Int,
    val warnings: List<String>
)

/**
 * What a conversion did and — the part that matters — what it could not do.
 * The engine and the OOXML target both drop things silently, so every loss is
 * collected here and written next to the output.
 */
class ConversionReport(
    val sourceName: String,
    /** "vector" (editable shapes) or "fidelity" (rendered page + text). */
    val mode: String,
    val pages: List<PageLosses>,
    /** Caveats that apply to the whole document. */
    val notes: List<String> = emptyList()
) {

    val pageCount: Int get() = pages.size
    val textBoxes: Int get() = pages.sumOf { it.textBoxes }
    val pictures: Int get() = pages.sumOf { it.pictures }
    val imagesSkipped: Int get() = pages.sumOf { it.imagesSkipped.size }
    val vectorFills: Int get() = pages.sumOf { it.vectorFills }
    val vectorStrokes: Int get() = pages.sumOf { it.vectorStrokes }
    val shadings: Int get() = pages.sumOf { it.shadings }
    val patterns: Int get() = pages.sumOf { it.patterns }
    val rotatedText: Int get() = pages.sumOf { it.rotatedText }
    val unknownFonts: List<String> get() = pages.flatMap { it.unknownFonts }.distinct().sorted()

    /** True when at least one feature had to be dropped or approximated. */
    val hasLosses: Boolean
        get() = imagesSkipped > 0 || (!mode.startsWith("fidelity") && (vectorFills > 0 || vectorStrokes > 0)) ||
            shadings > 0 || patterns > 0 || rotatedText > 0 || unknownFonts.isNotEmpty() || notes.isNotEmpty()

    fun toText(): String {
        val sb = StringBuilder()
        sb.append("PDFPaper conversion report\n")
        sb.append("  source : ").append(sourceName).append('\n')
        sb.append("  mode   : ").append(mode).append('\n')
        sb.append("  slides : ").append(pageCount).append('\n')
        sb.append('\n')

        sb.append("Totals\n")
        sb.append("  text boxes      : ").append(textBoxes).append('\n')
        sb.append("  pictures        : ").append(pictures).append('\n')
        sb.append("  images skipped  : ").append(imagesSkipped).append('\n')
        sb.append("  vector fills    : ").append(vectorFills)
            .append(if (vectorFills > 0 && !mode.startsWith("fidelity")) " (skipped)" else "").append('\n')
        sb.append("  vector strokes  : ").append(vectorStrokes)
            .append(if (vectorStrokes > 0 && !mode.startsWith("fidelity")) " (skipped)" else "").append('\n')
        sb.append("  shadings        : ").append(shadings).append('\n')
        sb.append("  patterns        : ").append(patterns).append('\n')
        sb.append('\n')

        sb.append("What was lost or approximated\n")
        if (!hasLosses) {
            sb.append("  nothing reported\n")
        } else {
            if (imagesSkipped > 0) {
                sb.append("  - ").append(imagesSkipped)
                    .append(" image(s) could not be embedded (unsupported codec or missing data)\n")
            }
            if (!mode.startsWith("fidelity") && (vectorFills > 0 || vectorStrokes > 0)) {
                sb.append("  - vector graphics are not converted: ")
                    .append(vectorFills).append(" fill(s), ").append(vectorStrokes).append(" stroke(s) dropped\n")
            }
            if (mode.startsWith("fidelity")) {
                sb.append("  - the background picture cannot include text: the engine has no glyph rasterizer,\n")
                sb.append("    so glyphs are missing from the picture and only the text boxes carry the words\n")
            }
            if (shadings > 0) {
                sb.append("  - gradients/shadings are not supported: ").append(shadings).append(" dropped\n")
            }
            if (patterns > 0) {
                sb.append("  - pattern fills are not supported: ").append(patterns).append(" dropped\n")
            }
            if (rotatedText > 0) {
                sb.append("  - ").append(rotatedText)
                    .append(" rotated text box(es) placed by their bounding box\n")
            }
            if (unknownFonts.isNotEmpty()) {
                sb.append("  - unrecognised fonts mapped to Arial: ")
                    .append(unknownFonts.joinToString(", ") { "'$it'" }).append('\n')
            }
            for (note in notes) sb.append("  - ").append(note).append('\n')
        }
        sb.append('\n')

        sb.append("Per slide\n")
        for (page in pages) {
            sb.append("  ").append(page.pageNumber).append(": ")
            sb.append(fmt(page.widthPt)).append('x').append(fmt(page.heightPt)).append(" pt, ")
            sb.append("text ").append(page.textBoxes).append(", ")
            sb.append("pictures ").append(page.pictures).append(", ")
            sb.append("vectors ").append(page.vectorFills).append(" fill / ").append(page.vectorStrokes).append(" stroke")
            if (page.imagesSkipped.isNotEmpty()) {
                sb.append(", images skipped: ").append(page.imagesSkipped.joinToString("; "))
            }
            sb.append('\n')
            for (warning in page.warnings.take(5)) {
                sb.append("      warning: ").append(warning).append('\n')
            }
        }
        return sb.toString()
    }

    private fun fmt(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)
}
