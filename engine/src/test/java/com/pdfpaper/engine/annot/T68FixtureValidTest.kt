package com.pdfpaper.engine.annot

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.outline.DocumentOutline
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.extractText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Fixture validation test for T68 (Rule 1).
 * Verifies that the fixture opens, parses properly, and has the expected pages,
 * text, outline, and annotations.
 */
class T68FixtureValidTest {

    @Test
    fun mixedAnnotationFixtureIsValid() {
        val bytes = T68Fixtures.createMixedAnnotationDoc()
        val doc = PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

        // Page count
        val pages = doc.pages()
        assertEquals(3, pages.size, "Document should have 3 pages")

        // Text extraction
        val p1Text = pages[0].extractText().runs.joinToString("") { it.text }
        val p2Text = pages[1].extractText().runs.joinToString("") { it.text }
        val p3Text = pages[2].extractText().runs.joinToString("") { it.text }
        assertTrue(p1Text.contains("P1"), "Page 1 should contain text 'P1'")
        assertTrue(p2Text.contains("P2"), "Page 2 should contain text 'P2'")
        assertTrue(p3Text.contains("P3"), "Page 3 should contain text 'P3'")

        // Outline
        val outline = DocumentOutline.read(doc)
        assertTrue(outline.isNotEmpty(), "Document should have outline entries")
        assertEquals("Book One", outline[0].title)

        // Annotations on page 1
        val p1Annots = pages[0].annotations()
        assertTrue(p1Annots.isNotEmpty(), "Page 1 should have annotations")
        assertTrue(p1Annots.any { it.type == AnnotationType.HIGHLIGHT }, "Should have highlight")
        assertTrue(p1Annots.any { it.type == AnnotationType.UNDERLINE }, "Should have underline")
        assertTrue(p1Annots.any { it.type == AnnotationType.INK }, "Should have ink")
        assertTrue(p1Annots.any { it.type == AnnotationType.TEXT }, "Should have note")
        assertTrue(p1Annots.any { it.type == AnnotationType.STAMP }, "Should have stamp")
        assertTrue(p1Annots.any { it.type == AnnotationType.WIDGET }, "Should have widget")
        assertTrue(p1Annots.any { it.type == AnnotationType.LINK }, "Should have link")

        // Annotations on page 2
        val p2Annots = pages[1].annotations()
        assertTrue(p2Annots.any { it.type == AnnotationType.HIGHLIGHT }, "Page 2 should have highlight")
        assertTrue(p2Annots.any { it.type == AnnotationType.INK }, "Page 2 should have ink")

        // Annotations on page 3
        val p3Annots = pages[2].annotations()
        assertTrue(p3Annots.any { it.type == AnnotationType.TEXT }, "Page 3 should have note")

        // Also write out the acceptance asset if asset directory exists
        val assetDir = java.io.File("../app/src/androidTest/assets/t68")
        if (assetDir.exists() || assetDir.mkdirs()) {
            java.io.File(assetDir, "t68-mixed.pdf").writeBytes(bytes)
        }
    }
}
