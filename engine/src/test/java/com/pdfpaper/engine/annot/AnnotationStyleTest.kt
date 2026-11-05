package com.pdfpaper.engine.annot

import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T56: patching an existing annotation in place through an incremental update.
 * Changing the text, author or colour of an annotation must keep its object
 * number (so replies, popups and review state keep pointing at it) and must be
 * appended after the original bytes, never rewritten over them.
 */
class AnnotationStyleTest {

    /** A form XObject body with a correct /Length. */
    private fun formObject(num: Int, content: String): TestObj =
        TestObj(
            num,
            0,
            ("<< /Type /XObject /Subtype /Form /FormType 1 /BBox [0 0 40 20] " +
                "/Length ${content.length} >>\nstream\n$content\nendstream")
        )

    private fun documentBytes(): ByteArray {
        val objects = mutableListOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> " +
                    "/Contents 4 0 R /Annots [5 0 R 6 0 R 8 0 R 10 0 R] >>"
            ),
            TestObj(4, 0, "<< /Length 18 >>\nstream\nq 0 0 10 10 re f Q\nendstream"),
            // A note with a popup that points back at it.
            TestObj(
                5, 0,
                "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (old) " +
                    "/T (Alice) /C [1 0 0] /M (D:20200101000000Z) /F 4 /Popup 10 0 R >>"
            ),
            // A highlight with its own yellow appearance.
            TestObj(
                6, 0,
                "<< /Type /Annot /Subtype /Highlight /Rect [50 100 150 120] " +
                    "/QuadPoints [50 120 150 120 50 100 150 100] /C [1 1 0] /F 4 " +
                    "/AP << /N 7 0 R >> >>"
            ),
            formObject(7, "q 1 1 0 rg 0 0 100 20 re f Q"),
            // An ink annotation with one stroke.
            TestObj(
                8, 0,
                "<< /Type /Annot /Subtype /Ink /Rect [200 200 260 240] " +
                    "/InkList [[200 200 230 240 260 200]] /C [0 0 1] /F 4 >>"
            ),
            TestObj(10, 0, "<< /Type /Annot /Subtype /Popup /Rect [40 40 90 80] /Parent 5 0 R >>")
        )
        return buildTestPdf(objects)
    }

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private fun annotation(bytes: ByteArray, objectNumber: Int): PdfAnnotation =
        open(bytes).use { document ->
            document.pages().flatMap { it.annotations() }
                .first { it.ref?.objNum == objectNumber }
        }

    @Test
    fun noteTextAuthorAndColourArePatchedWithoutRenumbering() {
        val original = documentBytes()
        val noteId = 5
        val bytes = open(original).use { document ->
            AnnotationStyle.applyIncremental(
                document,
                original,
                listOf(
                    AnnotationStyle.Update(
                        pageIndex = 0,
                        objectNumber = noteId,
                        contents = "revised note",
                        author = "Bob",
                        color = PdfColor(0.0, 0.0, 1.0)
                    )
                )
            ).getOrThrow()
        }

        assertTrue(bytes.size > original.size)
        assertTrue(
            bytes.copyOf(original.size).contentEquals(original),
            "the original revision must stay byte-identical"
        )
        open(bytes).use { reopened ->
            val note = reopened.pages()[0].annotations().first { it.ref?.objNum == noteId }
            assertEquals(AnnotationType.TEXT, note.type)
            assertEquals("revised note", note.contents)
            assertEquals("Bob", note.title)
            val noteColor = note.color
            assertNotNull(noteColor)
            assertEquals(0.0, noteColor.red, 1e-9)
            assertEquals(1.0, noteColor.blue, 1e-9)
            assertNotNull(note.modifiedAtMillis)

            // The popup still points at the same object, and its /Parent does too.
            val popup = note.popup
            assertNotNull(popup)
            assertEquals(10, popup.objNum)
            val popupDict = reopened.resolve(popup) as PdfDict
            assertEquals(noteId, (popupDict["Parent"] as PdfRef).objNum)
        }
    }

    @Test
    fun changingAHighlightColourRewritesOnlyItsAppearance() {
        val original = documentBytes()
        val highlightId = 6
        val appearanceBefore = annotation(original, highlightId).appearance?.data?.let {
            String(it, StandardCharsets.ISO_8859_1)
        }
        assertNotNull(appearanceBefore)
        assertTrue(appearanceBefore.contains("1 1 0 rg"))

        val bytes = open(original).use { document ->
            AnnotationStyle.applyIncremental(
                document,
                original,
                listOf(AnnotationStyle.Update(0, highlightId, color = PdfColor(0.0, 1.0, 0.0)))
            ).getOrThrow()
        }

        assertTrue(bytes.copyOf(original.size).contentEquals(original))
        val highlight = annotation(bytes, highlightId)
        assertEquals(AnnotationType.HIGHLIGHT, highlight.type)
        val highlightColor = highlight.color
        assertNotNull(highlightColor)
        assertEquals(0.0, highlightColor.red, 1e-9)
        assertEquals(1.0, highlightColor.green, 1e-9)
        // The quads are unchanged, so the words stay covered.
        assertEquals(1, highlight.quadBounds().size)
        val appearance = String(highlight.appearance!!.data, StandardCharsets.ISO_8859_1)
        assertTrue(appearance.contains("0 1 0 rg"), "the new appearance paints the new colour: $appearance")
        assertTrue(appearance.endsWith("Q"), "the appearance must close its graphics state")
        assertTrue(!appearance.contains("fQ"), "a real space must separate f and Q")
    }

    @Test
    fun changingAnInkColourKeepsTheStrokeList() {
        val original = documentBytes()
        val inkId = 8
        val bytes = open(original).use { document ->
            AnnotationStyle.applyIncremental(
                document,
                original,
                listOf(AnnotationStyle.Update(0, inkId, color = PdfColor(1.0, 0.0, 0.0)))
            ).getOrThrow()
        }

        val ink = annotation(bytes, inkId)
        assertEquals(AnnotationType.INK, ink.type)
        assertEquals(listOf(200.0, 200.0, 230.0, 240.0, 260.0, 200.0), ink.inkLists.single())
        val appearance = String(ink.appearance!!.data, StandardCharsets.ISO_8859_1)
        assertTrue(appearance.contains("1 0 0 RG"), "the stroke uses the new colour: $appearance")
        assertTrue(appearance.contains(" m") && appearance.contains(" l"), "the stroke is redrawn")
    }

    @Test
    fun anEmptyUpdateListFailsWithAMessage() {
        val original = documentBytes()
        open(original).use { document ->
            val result = AnnotationStyle.applyIncremental(document, original, emptyList())
            assertTrue(result.isFailure)
            assertNotNull(result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun anUnknownAnnotationFailsWithoutWriting() {
        val original = documentBytes()
        open(original).use { document ->
            val result = AnnotationStyle.applyIncremental(
                document,
                original,
                listOf(AnnotationStyle.Update(0, 99, contents = "x"))
            )
            assertTrue(result.isFailure)
        }
    }
}
