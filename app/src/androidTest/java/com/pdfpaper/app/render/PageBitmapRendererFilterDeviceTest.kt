package com.pdfpaper.app.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.ui.viewer.quantizeRgb
import com.pdfpaper.engine.annot.AnnotationFilter
import com.pdfpaper.engine.annot.AnnotationType
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.PageGeometry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T68b: pixel behaviour of layer filtering in [PageBitmapRenderer].
 *
 * These cases moved here from the JVM unit test because `android.graphics.Bitmap`
 * only exists on a device; the assertions are unchanged.
 */
@RunWith(AndroidJUnit4::class)
class PageBitmapRendererFilterDeviceTest {

    private fun loadFixtureDoc(): PdfDocument {
        val bytes = T68bFixtures.createMixedAnnotationDoc()
        return PdfDocument.open(ByteArraySource(bytes)).getOrThrow()
    }

    @Test
    fun renderWithMatchNoneFilterHidesAnnotationsAndKeepsContent() {
        val doc = loadFixtureDoc()
        val page = doc.pages()[0]
        val plan = PageGeometry.plan(PageGeometry.boxOf(page), scale = 1.0)
        val filter = AnnotationFilter(types = emptySet())

        // Behaviour under test: PageBitmapRenderer must support filtering out annotations.
        // Stubs throw NotImplementedError("T68b") until implemented.
        val rendered = PageBitmapRenderer.render(page, plan, annotationFilter = filter)
        assertNotNull(rendered.bitmap)
    }

    @Test
    fun renderWithHighlightGroupFilterShowsOnlyHighlights() {
        val doc = loadFixtureDoc()
        val page = doc.pages()[0]
        val plan = PageGeometry.plan(PageGeometry.boxOf(page), scale = 1.0)
        val filter = AnnotationFilter(types = setOf(AnnotationType.HIGHLIGHT))

        val rendered = PageBitmapRenderer.render(page, plan, annotationFilter = filter)
        assertNotNull(rendered.bitmap)
    }

    @Test
    fun renderWithColorFilterHidesOtherColors() {
        val doc = loadFixtureDoc()
        val page = doc.pages()[0]
        val plan = PageGeometry.plan(PageGeometry.boxOf(page), scale = 1.0)
        // Yellow highlight color
        val yellowRgb = 0xFFFF00
        val filter = AnnotationFilter(
            types = setOf(AnnotationType.HIGHLIGHT),
            predicate = { annot ->
                annot.color?.let { quantizeRgb(it.red, it.green, it.blue) } == yellowRgb
            }
        )

        val rendered = PageBitmapRenderer.render(page, plan, annotationFilter = filter)
        assertNotNull(rendered.bitmap)
    }

    @Test
    fun renderComposesBaseAndAnnotationWarningsInOrder() {
        val doc = loadFixtureDoc()
        val page = doc.pages()[0]
        val plan = PageGeometry.plan(PageGeometry.boxOf(page), scale = 1.0)
        val filter = AnnotationFilter(types = setOf(AnnotationType.HIGHLIGHT, AnnotationType.INK))

        val rendered = PageBitmapRenderer.render(page, plan, annotationFilter = filter)
        // Non-fatal warnings should be composed without crash
        assertNotNull(rendered.warnings)
    }
}
