package com.pdfpaper.app.print

import com.pdfpaper.app.ui.viewer.AnnotationOutputMode
import com.pdfpaper.engine.annot.AnnotationFilter
import com.pdfpaper.engine.model.PdfDocument

/**
 * T68b: Print adapter with annotation output mode policy.
 */
class PdfPrintAdapter(
    val document: PdfDocument,
    val outputMode: AnnotationOutputMode = AnnotationOutputMode.ALL,
    val activeFilter: AnnotationFilter = AnnotationFilter.SHOW_ALL
) {
    fun resolveFilter(): AnnotationFilter = TODO("T68b")
}
