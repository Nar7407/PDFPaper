package com.pdfpaper.engine.annot

import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.PdfPage

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
 * author, date, and flags.
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
    fun matches(annotation: PdfAnnotation): Boolean = TODO("T68")

    companion object {
        val SHOW_ALL = AnnotationFilter()
    }
}

/**
 * Produces a document-wide annotation filter report with counts per type and color (T68).
 */
fun PdfDocument.annotationFilterReport(
    filter: AnnotationFilter = AnnotationFilter.SHOW_ALL
): AnnotationFilterReport = TODO("T68")
