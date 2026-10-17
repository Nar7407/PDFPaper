package com.pdfpaper.engine.annot

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.ClipPath
import com.pdfpaper.engine.graphics.FillPath
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.graphics.RestoreGraphicsState
import com.pdfpaper.engine.graphics.SaveGraphicsState
import com.pdfpaper.engine.graphics.SetBlendMode
import com.pdfpaper.engine.graphics.buildDisplayList
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.write.PdfPageOps
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T12 acceptance: annotations are read into a typed model (§12.5) and rendered,
 * both through their /AP /N appearance stream (§12.5.5) and through a synthetic
 * appearance when a file has none.
 */
class AnnotationTest {

    private fun stream(num: Int, content: String): TestObj {
        val bytes = content.toByteArray(StandardCharsets.ISO_8859_1)
        return TestObj(num, 0, "<< /Length ${bytes.size} >>\nstream\n$content\nendstream")
    }

    private fun open(vararg objects: TestObj): PdfDocument =
        PdfDocument.open(ByteArraySource(buildTestPdf(objects.toList()))).getOrThrow()

    /** A page whose /Annots list is [annots]; annotation objects are 5..n. */
    private fun document(vararg annots: String): PdfDocument {
        val objs = ArrayList<TestObj>()
        objs += TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>")
        objs += TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        val refs = (5..(4 + annots.size)).joinToString(" ") { "$it 0 R" }
        objs += TestObj(
            3, 0,
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> " +
                "/Annots [$refs] >>"
        )
        annots.forEachIndexed { index, body -> objs += TestObj(5 + index, 0, body) }
        return open(*objs.toTypedArray())
    }

    @Test
    fun readsHighlightUnderlineInkAndNote() {
        val doc = document(
            "<< /Type /Annot /Subtype /Highlight /Rect [100 100 200 120] " +
                "/QuadPoints [100 120 200 120 100 100 200 100] /C [1 1 0] /Contents (marked) /F 4 >>",
            "<< /Type /Annot /Subtype /Ink /Rect [10 10 50 50] " +
                "/InkList [[10 10 20 20 30 30]] /C [1 0 0] /BS << /W 3 >> >>",
            "<< /Type /Annot /Subtype /Text /Rect [5 5 25 25] /Contents (a note) /C [1 0.8 0] /Name /Note >>",
            "<< /Type /Annot /Subtype /Link /Rect [0 0 10 10] /Border [0 0 0] >>"
        )
        val annots = doc.pages().first().annotations()
        assertEquals(4, annots.size)

        val highlight = annots[0]
        assertEquals(AnnotationType.HIGHLIGHT, highlight.type)
        assertEquals(PdfRect(100.0, 100.0, 200.0, 120.0), highlight.rect)
        assertEquals(1, highlight.quadPoints.size)
        assertEquals(PdfRect(100.0, 100.0, 200.0, 120.0), highlight.quadPoints[0].bounds())
        assertEquals(PdfColor(1.0, 1.0, 0.0), highlight.color)
        assertEquals("marked", highlight.contents)

        val ink = annots[1]
        assertEquals(AnnotationType.INK, ink.type)
        assertEquals(3.0, ink.borderWidth)
        assertEquals(listOf(listOf(10.0, 10.0, 20.0, 20.0, 30.0, 30.0)), ink.inkLists)

        val note = annots[2]
        assertEquals(AnnotationType.TEXT, note.type)
        assertEquals("a note", note.contents)

        val link = annots[3]
        assertEquals(AnnotationType.LINK, link.type)
        assertEquals(0.0, link.borderWidth)
        assertTrue(!link.hasVisibleBorder)
        doc.close()
    }

    @Test
    fun hiddenAnnotationsAreSkippedWhenRendering() {
        val doc = document(
            "<< /Type /Annot /Subtype /Highlight /Rect [100 100 200 120] /C [1 1 0] /F 2 >>"
        )
        val page = doc.pages().first()
        assertTrue(page.annotations().first().hidden)
        assertTrue(page.buildAnnotationDisplayList().commands.isEmpty())
        doc.close()
    }

    @Test
    fun highlightWithoutAppearanceBecomesStrokeOfCommands() {
        val doc = document(
            "<< /Type /Annot /Subtype /Highlight /Rect [100 100 200 120] " +
                "/QuadPoints [100 120 200 120 100 100 200 100] /C [1 1 0] >>"
        )
        val list = doc.pages().first().buildAnnotationDisplayList()
        assertEquals(4, list.commands.size)
        assertTrue(list.commands[0] is SaveGraphicsState)
        assertEquals("Multiply", (list.commands[1] as SetBlendMode).mode)
        val fill = list.commands[2] as FillPath
        assertEquals(PdfColor(1.0, 1.0, 0.0), fill.color)
        assertEquals(0.5, fill.alpha)
        assertTrue(list.commands[3] is RestoreGraphicsState)
        doc.close()
    }

    @Test
    fun appearanceStreamIsReplayedOntoTheRect() {
        val formContent = "0 0 10 10 re f"
        val doc = open(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> " +
                    "/Annots [5 0 R] >>"
            ),
            TestObj(
                5, 0,
                "<< /Type /Annot /Subtype /Stamp /Rect [50 50 70 70] /AP << /N 6 0 R >> >>"
            ),
            TestObj(
                6, 0,
                "<< /Type /XObject /Subtype /Form /BBox [0 0 10 10] /Resources << >> " +
                    "/Length ${formContent.toByteArray(StandardCharsets.ISO_8859_1).size} >>\n" +
                    "stream\n$formContent\nendstream"
            )
        )
        val page = doc.pages().first()
        val annot = page.annotations().first()
        assertNotNull(annot.appearance)
        val list = page.buildAnnotationDisplayList()
        // Save, clip to /BBox, fill, restore.
        assertTrue(list.commands.first() is SaveGraphicsState)
        val fill = list.commands.filterIsInstance<FillPath>().first()
        // BBox [0 0 10 10] mapped to Rect [50 50 70 70]: scale 2, translate 50,50.
        assertEquals(2.0, fill.matrix.a)
        assertEquals(2.0, fill.matrix.d)
        assertEquals(50.0, fill.matrix.e)
        assertEquals(50.0, fill.matrix.f)
        assertTrue(list.commands.any { it is ClipPath })
        doc.close()
    }

    @Test
    fun createsAndSavesHighlightInkAndNote() {
        val doc = document("<< /Type /Annot /Subtype /Text /Rect [1 1 20 20] /Contents (existing) >>")
        val specs = listOf<AnnotationSpec>(
            HighlightSpec(
                0,
                listOf(PdfRect(100.0, 100.0, 200.0, 120.0), PdfRect(100.0, 80.0, 180.0, 100.0)),
                contents = "key line"
            ),
            InkSpec(0, listOf(listOf(10.0, 10.0, 30.0, 40.0, 60.0, 20.0)), width = 3.0),
            NoteSpec(0, PdfRect(300.0, 600.0, 320.0, 620.0), "remember this", title = "Tester")
        )
        val bytes = PdfPageOps.applyAnnotations(doc, specs).getOrThrow()
        doc.close()

        val reopened = PdfDocument.open(ByteArraySource(bytes)).getOrThrow()
        val annots = reopened.pages().first().annotations()
        // The pre-existing note plus the three new annotations.
        assertEquals(4, annots.size)
        val highlight = annots.single { it.type == AnnotationType.HIGHLIGHT }
        assertEquals(2, highlight.quadPoints.size)
        assertEquals(PdfRect(100.0, 80.0, 200.0, 120.0), highlight.rect)
        assertEquals("key line", highlight.contents)
        assertNotNull(highlight.appearance)

        val ink = annots.single { it.type == AnnotationType.INK }
        assertEquals(3.0, ink.borderWidth)
        assertEquals(listOf(listOf(10.0, 10.0, 30.0, 40.0, 60.0, 20.0)), ink.inkLists)

        val note = annots.single { it.contents == "remember this" }
        assertEquals(AnnotationType.TEXT, note.type)
        assertEquals("Tester", note.title)
        assertNotNull(note.appearance)

        // The saved file renders its own annotations.
        assertTrue(reopened.pages().first().buildAnnotationDisplayList().commands.isNotEmpty())
        reopened.close()
    }

    @Test
    fun emptyHighlightIsNotAdded() {
        val doc = document("<< /Type /Annot /Subtype /Text /Rect [1 1 20 20] /Contents (keep me) >>")
        val bytes = PdfPageOps.applyAnnotations(doc, listOf(HighlightSpec(0, emptyList()))).getOrThrow()
        doc.close()
        val reopened = PdfDocument.open(ByteArraySource(bytes)).getOrThrow()
        assertEquals(1, reopened.pages().first().annotations().size)
        reopened.close()
    }

    @Test
    fun existingContentAndAnnotationsSurviveAnEdit() {
        val doc = open(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> " +
                    "/Contents 4 0 R /Annots [5 0 R] >>"
            ),
            stream(4, "1 0 0 rg 10 10 100 100 re f"),
            TestObj(5, 0, "<< /Type /Annot /Subtype /Text /Rect [1 1 20 20] /Contents (old) >>")
        )
        val bytes = PdfPageOps.applyAnnotations(
            doc,
            listOf(NoteSpec(0, PdfRect(50.0, 50.0, 70.0, 70.0), "new"))
        ).getOrThrow()
        doc.close()

        val reopened = PdfDocument.open(ByteArraySource(bytes)).getOrThrow()
        val page = reopened.pages().first()
        assertEquals(2, page.annotations().size)
        // The page still has its content stream and paints it.
        assertTrue(page.buildDisplayList().commands.isNotEmpty())
        assertNotNull(page.annotations().firstOrNull { it.contents == "old" })
        assertNotNull(page.annotations().firstOrNull { it.contents == "new" })
        reopened.close()
    }

    @Test
    fun annotationWithoutRectIsTolerated() {
        val doc = document("<< /Type /Annot /Subtype /Highlight /C [1 1 0] >>")
        val annot = doc.pages().first().annotations().single()
        assertNull(annot.rect)
        assertTrue(doc.pages().first().buildAnnotationDisplayList().commands.isEmpty())
        doc.close()
    }
}
