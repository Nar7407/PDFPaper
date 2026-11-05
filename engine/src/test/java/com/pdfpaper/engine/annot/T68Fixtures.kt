package com.pdfpaper.engine.annot

import com.pdfpaper.engine.edit.StandardFont
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.mark.MarkFont
import com.pdfpaper.engine.mark.PageSelection
import com.pdfpaper.engine.mark.PdfMarks
import com.pdfpaper.engine.mark.WatermarkContent
import com.pdfpaper.engine.mark.WatermarkOptions
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.stamp.StampLibrary
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.write.PdfPageOps
import java.nio.charset.StandardCharsets

/**
 * Fixture builder for T68 annotation layers and filtering.
 *
 * Uses the project's test helpers (`buildTestPdf`) and the engine's own document
 * writer, annotation applier, and watermark applier (ISO 32000-1, §12.5 and §8.11).
 */
object T68Fixtures {

    val YELLOW = PdfColor(1.0, 1.0, 0.0)
    val GREEN = PdfColor(0.0, 1.0, 0.0)
    val RED = PdfColor(1.0, 0.0, 0.0)
    val BLUE = PdfColor(0.0, 0.0, 1.0)
    val ORANGE_NOTE = NoteSpec.DEFAULT_NOTE_COLOR

    private fun streamObj(num: Int, text: String): TestObj {
        val body = "BT /F1 12 Tf 10 10 Td ($text) Tj ET"
        return TestObj(num, 0, "<< /Length ${body.length} >>\nstream\n$body\nendstream")
    }

    private fun page(parent: Int, contents: Int, extra: String = ""): String =
        "<< /Type /Page /Parent $parent 0 R /MediaBox [0 0 200 200] " +
            "/Resources << /Font << /F1 40 0 R >> >> /Contents $contents 0 R$extra >>"

    /** Base 3-page document with outlines, text ("P1", "P2", "P3"), a link, and a form widget. */
    fun baseThreePageDoc(): ByteArray = buildTestPdf(
        listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R /Outlines 10 0 R /AcroForm 20 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>"),
            TestObj(3, 0, page(2, 6, " /Annots [22 0 R 30 0 R]")),
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
            TestObj(22, 0, "<< /Type /Annot /Subtype /Widget /Parent 21 0 R /P 3 0 R /Rect [10 10 100 30] /T (field) >>"),
            TestObj(30, 0, "<< /Type /Annot /Subtype /Link /Rect [0 0 40 40] /Dest [5 0 R /Fit] >>"),
            TestObj(40, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        ),
        rootRef = "1 0 R"
    )

    /**
     * Builds a valid 3-page PDF with mixed annotations, outline, text,
     * form field widgets, links, and PDFPaper optional content watermarks.
     */
    fun createMixedAnnotationDoc(): ByteArray {
        val baseDocBytes = baseThreePageDoc()
        val baseDoc = PdfDocument.open(ByteArraySource(baseDocBytes)).getOrThrow()

        val specs = listOf(
            // Page 0 (Page 1): yellow highlight (Alice), green highlight (Bob), red highlight (Charlie)
            HighlightSpec(
                pageIndex = 0,
                rects = listOf(PdfRect(20.0, 100.0, 80.0, 115.0)),
                color = YELLOW,
                contents = "Yellow highlight",
                title = "Alice"
            ),
            HighlightSpec(
                pageIndex = 0,
                rects = listOf(PdfRect(20.0, 80.0, 80.0, 95.0)),
                color = GREEN,
                contents = "Green highlight",
                title = "Bob"
            ),
            HighlightSpec(
                pageIndex = 0,
                rects = listOf(PdfRect(20.0, 60.0, 80.0, 75.0)),
                color = RED,
                contents = "Red highlight",
                title = "Charlie"
            ),
            // Page 0: red underline (Alice)
            UnderlineSpec(
                pageIndex = 0,
                rects = listOf(PdfRect(20.0, 40.0, 80.0, 50.0)),
                color = RED,
                contents = "Red underline",
                title = "Alice"
            ),
            // Page 0: blue ink stroke (Bob)
            InkSpec(
                pageIndex = 0,
                strokes = listOf(listOf(10.0, 10.0, 20.0, 20.0, 30.0, 10.0)),
                color = BLUE,
                contents = "Blue ink drawing",
                title = "Bob"
            ),
            // Page 0: sticky note (Alice)
            NoteSpec(
                pageIndex = 0,
                rect = PdfRect(5.0, 150.0, 25.0, 170.0),
                contents = "Yellow note on page 1",
                color = ORANGE_NOTE,
                title = "Alice"
            ),
            // Page 0: stamp (Charlie)
            StampSpec(
                pageIndex = 0,
                design = StampLibrary.builtIn.first(),
                x = 100.0,
                y = 100.0,
                size = 80.0,
                author = "Charlie"
            ),

            // Page 1 (Page 2): yellow highlight (Alice)
            HighlightSpec(
                pageIndex = 1,
                rects = listOf(PdfRect(30.0, 100.0, 90.0, 115.0)),
                color = YELLOW,
                contents = "Yellow highlight on page 2",
                title = "Alice"
            ),
            // Page 1: red ink stroke (Bob)
            InkSpec(
                pageIndex = 1,
                strokes = listOf(listOf(40.0, 40.0, 50.0, 50.0)),
                color = RED,
                contents = "Red ink on page 2",
                title = "Bob"
            ),

            // Page 2 (Page 3): sticky note (Charlie)
            NoteSpec(
                pageIndex = 2,
                rect = PdfRect(10.0, 10.0, 30.0, 30.0),
                contents = "Note on page 3",
                color = ORANGE_NOTE,
                title = "Charlie"
            )
        )

        val annotatedBytes = PdfPageOps.applyAnnotations(baseDoc, specs).getOrThrow()
        val annotatedDoc = PdfDocument.open(ByteArraySource(annotatedBytes)).getOrThrow()

        // Apply a PDFPaper watermark (marked content on page content streams)
        val markedBytes = PdfMarks.applyWatermark(
            annotatedDoc,
            WatermarkOptions(
                content = WatermarkContent.Text("CONFIDENTIAL"),
                font = MarkFont.Standard(StandardFont.HELVETICA),
                fontSize = 24.0,
                pages = PageSelection.All
            )
        ).getOrThrow()

        return markedBytes
    }
}
