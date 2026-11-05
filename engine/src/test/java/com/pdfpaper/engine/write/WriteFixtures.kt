package com.pdfpaper.engine.write

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.text.extractText

/**
 * Fixtures for T10 writer tests. Hand-written classic-xref documents with real
 * page trees, content streams, a link, bookmarks and a form field.
 */
object WriteFixtures {

    private fun page(parent: Int, contents: Int, extra: String = ""): String =
        "<< /Type /Page /Parent $parent 0 R /MediaBox [0 0 200 200] " +
            "/Resources << /Font << /F1 40 0 R >> >> /Contents $contents 0 R$extra >>"

    private fun streamObj(num: Int, text: String): TestObj {
        val body = "BT /F1 12 Tf 10 10 Td ($text) Tj ET"
        return TestObj(num, 0, "<< /Length ${body.length} >>\nstream\n$body\nendstream")
    }

    /**
     * Three pages that each draw a distinct word ("P1"/"P2"/"P3"), page 1 has a
     * link to page 3, two bookmarks (to pages 1 and 3), and one text field whose
     * widget sits on page 1.
     */
    fun threePageDoc(): ByteArray = buildTestPdf(
        listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R /Outlines 10 0 R /AcroForm 20 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>"),
            TestObj(3, 0, page(2, 6, " /Annots [30 0 R]")),
            TestObj(4, 0, page(2, 7)),
            TestObj(5, 0, page(2, 8)),
            streamObj(6, "P1"),
            streamObj(7, "P2"),
            streamObj(8, "P3"),
            TestObj(9, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
            TestObj(10, 0, "<< /Type /Outlines /First 11 0 R /Last 12 0 R /Count 2 >>"),
            TestObj(11, 0, "<< /Title (Book One) /Parent 10 0 R /Next 12 0 R /Dest [3 0 R /Fit] >>"),
            TestObj(12, 0, "<< /Title (Book Three) /Parent 10 0 R /Prev 11 0 R /Dest [5 0 R /Fit] >>"),
            TestObj(20, 0, "<< /Fields [21 0 R] /DA (0 g) >>"),
            TestObj(21, 0, "<< /T (field) /FT /Tx /Kids [22 0 R] >>"),
            TestObj(22, 0, "<< /Subtype /Widget /Parent 21 0 R /P 3 0 R /Rect [10 10 100 30] /T (field) >>"),
            TestObj(30, 0, "<< /Subtype /Link /Rect [0 0 40 40] /Dest [5 0 R /Fit] >>"),
            TestObj(40, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        ),
        rootRef = "1 0 R"
    )

    /** Two pages ("A1"/"A2"), no annotations or forms. */
    fun twoPageDoc(): ByteArray = buildTestPdf(
        listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>"),
            TestObj(3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /Font << /F1 30 0 R >> >> /Contents 5 0 R >>"),
            TestObj(4, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /Font << /F1 30 0 R >> >> /Contents 6 0 R >>"),
            streamObj(5, "A1"),
            streamObj(6, "A2"),
            TestObj(30, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        )
    )

    fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    fun pageWords(document: PdfDocument): List<String> =
        document.pages().map { page -> page.extractText().runs.joinToString("") { it.text } }
}
