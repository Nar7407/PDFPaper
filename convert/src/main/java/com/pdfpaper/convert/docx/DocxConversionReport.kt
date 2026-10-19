package com.pdfpaper.convert.docx

/** Per-page conversion statistics and the reasons things were dropped. */
class DocxPageStats(
    val pageNumber: Int,
    val widthPt: Double,
    val heightPt: Double,
    val paragraphs: Int,
    val headings: Int,
    val listItems: Int,
    val tables: Int,
    val images: Int,
    /** Human-readable reasons for images that could not be embedded. */
    val imagesSkipped: List<String>,
    /** Text runs that render nothing (an OCR layer over a scan). */
    val invisibleRuns: Int,
    /** Runs whose baseline is not horizontal; they are written unrotated. */
    val rotatedRuns: Int,
    val runningHeadsRemoved: Int,
    val vectorFills: Int,
    val vectorStrokes: Int,
    /** True when vector art was folded into a page picture instead of dropped. */
    val vectorsRasterized: Boolean,
    val unknownFonts: List<String>,
    val warnings: List<String>
)

/**
 * What a PDF→DOCX conversion did and — the part that matters — what it could
 * not do.
 */
class DocxConversionReport(
    val sourceName: String,
    /** "flow (editable text, reflowed)" or "exact (absolute page layout)". */
    val mode: String,
    val pages: List<DocxPageStats>,
    /** Caveats that apply to the whole document. */
    val notes: List<String> = emptyList()
) {

    val pageCount: Int get() = pages.size
    val paragraphs: Int get() = pages.sumOf { it.paragraphs }
    val headings: Int get() = pages.sumOf { it.headings }
    val listItems: Int get() = pages.sumOf { it.listItems }
    val tables: Int get() = pages.sumOf { it.tables }
    val images: Int get() = pages.sumOf { it.images }
    val imagesSkipped: Int get() = pages.sumOf { it.imagesSkipped.size }
    val invisibleRuns: Int get() = pages.sumOf { it.invisibleRuns }
    val rotatedRuns: Int get() = pages.sumOf { it.rotatedRuns }
    val runningHeadsRemoved: Int get() = pages.sumOf { it.runningHeadsRemoved }
    val vectorFills: Int get() = pages.sumOf { it.vectorFills }
    val vectorStrokes: Int get() = pages.sumOf { it.vectorStrokes }
    val unknownFonts: List<String> get() = pages.flatMap { it.unknownFonts }.distinct().sorted()

    /** True when at least one feature had to be dropped or approximated. */
    val hasLosses: Boolean
        get() = imagesSkipped > 0 || rotatedRuns > 0 || unknownFonts.isNotEmpty() ||
            notes.isNotEmpty() || pages.any { it.warnings.isNotEmpty() } ||
            pages.any { it.vectorFills + it.vectorStrokes > 0 && !it.vectorsRasterized }

    fun toText(): String {
        val sb = StringBuilder()
        sb.append("PDFPaper conversion report\n")
        sb.append("  source : ").append(sourceName).append('\n')
        sb.append("  mode   : ").append(mode).append('\n')
        sb.append("  pages  : ").append(pageCount).append('\n')
        sb.append('\n')

        sb.append("Totals\n")
        sb.append("  paragraphs      : ").append(paragraphs).append('\n')
        sb.append("  headings        : ").append(headings).append('\n')
        sb.append("  list items      : ").append(listItems).append('\n')
        sb.append("  tables          : ").append(tables).append('\n')
        sb.append("  pictures        : ").append(images).append('\n')
        sb.append("  images skipped  : ").append(imagesSkipped).append('\n')
        sb.append("  vector fills    : ").append(vectorFills).append('\n')
        sb.append("  vector strokes  : ").append(vectorStrokes).append('\n')
        if (runningHeadsRemoved > 0) {
            sb.append("  running heads   : ").append(runningHeadsRemoved).append(" removed\n")
        }
        sb.append('\n')

        sb.append("What was lost or approximated\n")
        if (!hasLosses) {
            sb.append("  nothing reported\n")
        } else {
            if (imagesSkipped > 0) {
                sb.append("  - ").append(imagesSkipped)
                    .append(" image(s) could not be embedded (unsupported codec or missing data)\n")
            }
            if (pages.any { it.vectorFills + it.vectorStrokes > 0 && !it.vectorsRasterized }) {
                sb.append("  - vector graphics are not part of a DOCX: ")
                    .append(vectorFills).append(" fill(s), ").append(vectorStrokes)
                    .append(" stroke(s) dropped (rules, boxes and shapes have to be redrawn by hand)\n")
            }
            if (rotatedRuns > 0) {
                sb.append("  - ").append(rotatedRuns)
                    .append(" rotated text run(s) written horizontally\n")
            }
            if (invisibleRuns > 0) {
                sb.append("  - ").append(invisibleRuns)
                    .append(" invisible text run(s) skipped (they duplicate the page picture)\n")
            }
            for (note in notes) sb.append("  - ").append(note).append('\n')
        }
        sb.append('\n')

        sb.append("Per page\n")
        for (page in pages) {
            sb.append("  ").append(page.pageNumber).append(": ")
            sb.append(fmt(page.widthPt)).append('x').append(fmt(page.heightPt)).append(" pt, ")
            sb.append("paragraphs ").append(page.paragraphs).append(", ")
            sb.append("headings ").append(page.headings).append(", ")
            sb.append("lists ").append(page.listItems).append(", ")
            sb.append("tables ").append(page.tables).append(", ")
            sb.append("pictures ").append(page.images)
            if (page.runningHeadsRemoved > 0) {
                sb.append(", running heads removed ").append(page.runningHeadsRemoved)
            }
            sb.append('\n')
            if (page.imagesSkipped.isNotEmpty()) {
                sb.append("      images skipped: ").append(page.imagesSkipped.joinToString("; ")).append('\n')
            }
            for (warning in page.warnings.take(5)) {
                sb.append("      warning: ").append(warning).append('\n')
            }
        }
        return sb.toString()
    }

    private fun fmt(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)
}
