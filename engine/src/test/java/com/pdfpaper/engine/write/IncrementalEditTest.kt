package com.pdfpaper.engine.write

import com.pdfpaper.engine.annot.AnnotationType
import com.pdfpaper.engine.annot.HighlightSpec
import com.pdfpaper.engine.annot.NoteSpec
import com.pdfpaper.engine.annot.PdfComments
import com.pdfpaper.engine.annot.ReplySpec
import com.pdfpaper.engine.annot.annotations
import com.pdfpaper.engine.annot.comments
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.form.formFields
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** T55: incremental annotation, comment and form saves (§7.5.6). */
class IncrementalEditTest {

    private fun documentBytes(): ByteArray {
        val objects = mutableListOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R /AcroForm 8 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> /Contents 4 0 R /Annots [5 0 R] >>"),
            TestObj(4, 0, "<< /Length 18 >>\nstream\nq 0 0 10 10 re f Q\nendstream"),
            TestObj(5, 0, "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (old) /T (A) /F 4 >>"),
            TestObj(8, 0, "<< /Fields [9 0 R] /DA (/Helv 0 Tf 0 g) >>"),
            TestObj(
                9, 0,
                "<< /FT /Tx /T (name) /Rect [50 700 250 720] /Type /Annot /Subtype /Widget /F 4 /P 3 0 R >>"
            )
        )
        return buildTestPdf(objects)
    }

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    @Test
    fun appendsAnnotationsWithoutChangingTheOriginalPrefix() {
        val original = documentBytes()
        val bytes = open(original).use { document ->
            PdfPageOps.editIncremental(
                document,
                original,
                listOf(
                    HighlightSpec(0, listOf(PdfRect(10.0, 100.0, 120.0, 120.0)), contents = "new"),
                    NoteSpec(0, PdfRect(20.0, 200.0, 40.0, 220.0), "a note")
                )
            ).getOrThrow()
        }

        assertTrue(bytes.size > original.size)
        assertTrue(bytes.copyOf(original.size).contentEquals(original), "the original revision must stay byte-identical")
        open(bytes).use { reopened ->
            assertEquals(1, reopened.pages().size)
            val annots = reopened.pages()[0].annotations()
            assertEquals(3, annots.size)
            assertEquals(AnnotationType.HIGHLIGHT, annots[1].type)
            assertEquals("new", annots[1].contents)
            assertEquals(AnnotationType.TEXT, annots[2].type)
            assertEquals("a note", annots[2].contents)
            assertEquals("q 0 0 10 10 re f Q", String(reopened.pages()[0].contents().getOrThrow()!!.bytes).trim())
        }
    }

    @Test
    fun replyAndStatusAreAppendedThroughTheCommentApi() {
        val first = open(documentBytes()).use { document ->
            PdfPageOps.editIncremental(
                document,
                documentBytes(),
                listOf(NoteSpec(0, PdfRect(100.0, 100.0, 120.0, 120.0), "root", title = "Alice"))
            ).getOrThrow()
        }
        val rootId = open(first).use { doc -> doc.comments().first { it.contents == "root" }.id }

        val second = open(first).use { document ->
            PdfComments.editIncremental(
                document,
                first,
                listOf(ReplySpec(0, rootId, "agreed", title = "Bob"))
            ).getOrThrow()
        }

        assertTrue(second.copyOf(first.size).contentEquals(first))
        open(second).use { reopened ->
            val root = reopened.comments().first { it.contents == "root" }
            assertEquals(1, root.replies.size)
            assertEquals("agreed", root.replies[0].contents)
        }
    }

    @Test
    fun formValuesAppendFieldAndWidgetObjects() {
        val original = documentBytes()
        val bytes = open(original).use { document ->
            PdfPageOps.editIncremental(document, original, formValues = mapOf("name" to "Ada")).getOrThrow()
        }

        assertTrue(bytes.copyOf(original.size).contentEquals(original))
        open(bytes).use { reopened ->
            assertEquals("Ada", reopened.formFields().first { it.name == "name" }.value)
        }
    }

    @Test
    fun droppedAnnotationLeavesThePageList() {
        val original = documentBytes()
        val annotId = open(original).use { it.pages()[0].annotations()[0].ref!!.objNum }
        val bytes = open(original).use { document ->
            PdfPageOps.editIncremental(document, original, droppedAnnots = setOf(annotId)).getOrThrow()
        }

        open(bytes).use { reopened ->
            assertEquals(0, reopened.pages()[0].annotations().size)
        }
    }

    @Test
    fun rebuiltFileWithoutStartXrefFailsCleanly() {
        val original = documentBytes()
        // Remove the startxref pointer; opening still succeeds through the scan.
        val damaged = String(original, Charsets.ISO_8859_1)
            .replace(Regex("startxref\\s+\\d+"), "startxref\n-1")
            .toByteArray(Charsets.ISO_8859_1)
        open(damaged).use { document ->
            val result = PdfPageOps.editIncremental(
                document,
                damaged,
                listOf(NoteSpec(0, PdfRect(20.0, 20.0, 40.0, 40.0), "x"))
            )
            assertTrue(result.isFailure)
            assertNotNull(result.exceptionOrNull()?.message)
        }
    }
}
