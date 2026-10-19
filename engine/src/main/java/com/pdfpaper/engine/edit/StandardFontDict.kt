package com.pdfpaper.engine.edit

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.write.PdfDocumentBuilder

/**
 * Adds a standard-14 font dictionary with its AFM /Widths to [builder]
 * (§9.6.2.2, Table 111) and returns its reference.
 *
 * The standard 14 fonts need no embedded program, so one dictionary with the
 * WinAnsi encoding and the real widths is all a viewer needs to advance the
 * glyphs exactly as the writer measured them. Shared by the PDF builder and
 * the stamp writer so generated text measures the same everywhere.
 */
internal fun addStandardFont(builder: PdfDocumentBuilder, font: StandardFont): PdfRef {
    val widths = PdfArray(
        (32..255).map { code -> PdfInteger(Standard14.width1000(font, code).toLong()) }
    )
    return builder.add(
        PdfDict(
            linkedMapOf(
                PdfName("Type") to PdfName("Font"),
                PdfName("Subtype") to PdfName("Type1"),
                PdfName("BaseFont") to PdfName(font.baseFont),
                PdfName("Encoding") to PdfName("WinAnsiEncoding"),
                PdfName("FirstChar") to PdfInteger(32),
                PdfName("LastChar") to PdfInteger(255),
                PdfName("Widths") to widths
            )
        )
    )
}
