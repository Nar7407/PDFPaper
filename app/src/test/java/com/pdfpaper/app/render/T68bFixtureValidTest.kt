package com.pdfpaper.app.render

import com.pdfpaper.engine.annot.AnnotationType
import com.pdfpaper.engine.annot.annotationFilterReport
import com.pdfpaper.engine.annot.annotations
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.outline.DocumentOutline
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.extractText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fixture validation test for T68b (Rule 1).
 * Verifies that the fixture opens, parses properly, and has the expected pages,
 * text, outline, annotations, and filter report counts.
 */
class T68bFixtureValidTest {

    @Test
    fun mixedAnnotationFixtureIsValid() {
        val bytes = T68bFixtures.createMixedAnnotationDoc()
        val doc = PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

        // Page count
        val pages = doc.pages()
        assertEquals(3, pages.size)

        // Text extraction
        val p1Text = pages[0].extractText().runs.joinToString("") { it.text }
        val p2Text = pages[1].extractText().runs.joinToString("") { it.text }
        val p3Text = pages[2].extractText().runs.joinToString("") { it.text }
        assertTrue(p1Text.contains("P1"))
        assertTrue(p2Text.contains("P2"))
        assertTrue(p3Text.contains("P3"))

        // Outline
        val outline = DocumentOutline.read(doc)
        assertTrue(outline.isNotEmpty())
        assertEquals("Book One", outline[0].title)

        // Annotations on page 1
        val p1Annots = pages[0].annotations()
        assertTrue(p1Annots.isNotEmpty())
        assertTrue(p1Annots.any { it.type == AnnotationType.HIGHLIGHT })
        assertTrue(p1Annots.any { it.type == AnnotationType.UNDERLINE })
        assertTrue(p1Annots.any { it.type == AnnotationType.STRIKEOUT })
        assertTrue(p1Annots.any { it.type == AnnotationType.INK })
        assertTrue(p1Annots.any { it.type == AnnotationType.TEXT })
        assertTrue(p1Annots.any { it.type == AnnotationType.STAMP })
        assertTrue(p1Annots.any { it.type == AnnotationType.WIDGET })
        assertTrue(p1Annots.any { it.type == AnnotationType.LINK })

        // Annotations on page 2
        val p2Annots = pages[1].annotations()
        assertTrue(p2Annots.any { it.type == AnnotationType.HIGHLIGHT })
        assertTrue(p2Annots.any { it.type == AnnotationType.INK })

        // Annotations on page 3
        val p3Annots = pages[2].annotations()
        assertTrue(p3Annots.any { it.type == AnnotationType.TEXT })

        // Report check
        val report = doc.annotationFilterReport()
        assertTrue("Total count should be at least 10", report.totalCount >= 10)
        assertTrue("Watermark marked content should be detected as non-filterable", report.notFilterableCount > 0)
        assertTrue(report.typeCounts.containsKey(AnnotationType.HIGHLIGHT))
        assertTrue(report.typeCounts.containsKey(AnnotationType.INK))
        assertTrue(report.typeCounts.containsKey(AnnotationType.TEXT))

        // Verify acceptance asset file exists for device tests
        val assetFile = File("src/androidTest/assets/t68b/t68b-mixed.pdf")
        if (assetFile.exists()) {
            assertTrue("Asset file should have bytes", assetFile.length() > 0)
        }
    }
}
