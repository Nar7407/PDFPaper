package com.pdfpaper.engine.annot

import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import kotlin.math.roundToInt

/**
 * Which annotation timestamp field to filter against (T68).
 */
enum class AnnotationDateField {
    CREATION,
    MODIFIED
}

/**
 * Report of document-wide annotation counts and detected flattened marks (T68).
 */
data class AnnotationFilterReport(
    val typeCounts: Map<AnnotationType, Int> = emptyMap(),
    val colorCounts: Map<Int?, Int> = emptyMap(),
    val totalCount: Int = 0,
    val matchingCount: Int = 0,
    val hiddenCount: Int = 0,
    val notFilterableCount: Int = 0,
    val notFilterableMarks: List<NotFilterableMark> = emptyList()
)

/**
 * A mark in page content that acts like an annotation but cannot be filtered (T68).
 */
data class NotFilterableMark(
    val pageIndex: Int,
    val description: String
)

/**
 * Non-destructive visibility filter for annotations during rendering and export (T68).
 *
 * Evaluates whether an annotation should be drawn or included based on type, color,
 * author, date, and flags. PDF visibility flags always win: an annotation hidden by
 * /F (INVISIBLE, HIDDEN, NO_VIEW — §12.5.3 Table 165) is never matched.
 */
data class AnnotationFilter(
    val types: Set<AnnotationType>? = null,
    val colors: Set<Int?>? = null,
    val authors: Set<String>? = null,
    val dateField: AnnotationDateField = AnnotationDateField.MODIFIED,
    val dateRange: LongRange? = null,
    val flagsMask: Int? = null,
    val flagsValue: Int? = null,
    val predicate: ((PdfAnnotation) -> Boolean)? = null
) {
    fun matches(annotation: PdfAnnotation): Boolean {
        if (annotation.hidden) return false
        types?.let { if (annotation.type !in it) return false }
        colors?.let { if (annotation.color?.rgbInt() !in it) return false }
        authors?.let { if (annotation.title !in it) return false }
        dateRange?.let { range ->
            val millis = when (dateField) {
                AnnotationDateField.CREATION -> PdfDate.millis(annotation.creationDate)
                AnnotationDateField.MODIFIED -> PdfDate.millis(annotation.modifiedDate)
            }
            if (millis == null || millis !in range) return false
        }
        val mask = flagsMask
        val value = flagsValue
        if (mask != null && value != null && (annotation.flags and mask) != value) return false
        val extra = predicate
        if (extra != null && !extra(annotation)) return false
        return true
    }

    companion object {
        val SHOW_ALL = AnnotationFilter()
    }
}

/** Canonical 0xRRGGBB key for a resolved colour; absent /C stays null. */
internal fun PdfColor.rgbInt(): Int {
    fun channel(value: Double): Int = (value.coerceIn(0.0, 1.0) * 255.0).roundToInt()
    return (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
}

/** Marked-content groups PDFPaper itself writes (see mark.MarkGroups). */
private val NOT_FILTERABLE_MARKERS = listOf(
    "/OC /PDFPaperWatermark BDC" to "PDFPaper watermark cannot be filtered",
    "/OC /PDFPaperHeaderFooter BDC" to "PDFPaper header/footer cannot be filtered",
    "/OC /PDFPaperBates BDC" to "PDFPaper Bates number cannot be filtered"
)

/**
 * Produces a document-wide annotation filter report with counts per type and color (T68).
 * Reading only: parser objects are never modified.
 */
fun PdfDocument.annotationFilterReport(
    filter: AnnotationFilter = AnnotationFilter.SHOW_ALL
): AnnotationFilterReport {
    val typeCounts = LinkedHashMap<AnnotationType, Int>()
    val colorCounts = LinkedHashMap<Int?, Int>()
    val marks = ArrayList<NotFilterableMark>()
    var total = 0
    var matching = 0
    for (page in pages()) {
        for (annotation in page.annotations()) {
            total++
            typeCounts[annotation.type] = (typeCounts[annotation.type] ?: 0) + 1
            val color = annotation.color?.rgbInt()
            colorCounts[color] = (colorCounts[color] ?: 0) + 1
            if (filter.matches(annotation)) matching++
        }
        marks += page.notFilterableMarks()
    }
    return AnnotationFilterReport(
        typeCounts = typeCounts,
        colorCounts = colorCounts,
        totalCount = total,
        matchingCount = matching,
        hiddenCount = total - matching,
        notFilterableCount = marks.size,
        notFilterableMarks = marks
    )
}

private fun com.pdfpaper.engine.page.PdfPage.notFilterableMarks(): List<NotFilterableMark> {
    val bytes = contents().getOrNull()?.bytes ?: return emptyList()
    val text = String(bytes, Charsets.ISO_8859_1)
    return NOT_FILTERABLE_MARKERS.mapNotNull { (marker, description) ->
        if (text.contains(marker)) NotFilterableMark(index, description) else null
    }
}
