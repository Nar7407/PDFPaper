package com.pdfpaper.engine.create

/**
 * Document information dictionary (§14.3.3). Null/blank fields are omitted;
 * [creator] and [producer] default to the app's name.
 */
data class PdfMetadata(
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
    val creator: String = "PDFPaper",
    val producer: String = "PDFPaper"
)
