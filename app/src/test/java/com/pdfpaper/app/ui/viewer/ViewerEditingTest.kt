package com.pdfpaper.app.ui.viewer

import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.text.TextRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure annotation-tool maths behind the viewer (T12). No
 * Android classes are involved, so these run on the JVM.
 */
class ViewerEditingTest {

    private val plan = PageGeometry.plan(PageBox(0.0, 0.0, 200.0, 100.0, 0), 1.0)

    @Test
    fun fractionMapsBackThroughTheRenderPlan() {
        val centre = pageFractionToUserSpace(plan, 0.5f, 0.5f)!!
        assertEquals(100.0, centre[0], 1e-6)
        assertEquals(50.0, centre[1], 1e-6)

        // The top-left of the drawn bitmap is the top-left of the page in PDF
        // user space, where y grows up.
        val topLeft = pageFractionToUserSpace(plan, 0f, 0f)!!
        assertEquals(0.0, topLeft[0], 1e-6)
        assertEquals(100.0, topLeft[1], 1e-6)
    }

    @Test
    fun selectionBecomesAHighlightWithContents() {
        val selection = ViewerSelection(
            pageIndex = 3,
            start = 0,
            end = 5,
            rects = listOf(TextRect(10.0, 20.0, 90.0, 32.0)),
            text = "  hello  "
        )
        val spec = selectionToHighlight(selection)!!
        assertEquals(3, spec.pageIndex)
        assertEquals(1, spec.rects.size)
        assertEquals(10.0, spec.rects[0].left, 1e-9)
        assertEquals(32.0, spec.rects[0].top, 1e-9)
        assertEquals("hello", spec.contents)
    }

    @Test
    fun emptySelectionProducesNoHighlight() {
        val selection = ViewerSelection(0, 0, 0, listOf(TextRect(5.0, 5.0, 5.0, 5.0)), "   ")
        assertNull(selectionToHighlight(selection))
    }

    @Test
    fun noteRectIsASquareAtThePoint() {
        val rect = noteRectAt(12.0, 30.0, 20.0)
        assertEquals(12.0, rect.left, 1e-9)
        assertEquals(32.0, rect.right, 1e-9)
        assertEquals(30.0, rect.bottom, 1e-9)
        assertEquals(50.0, rect.top, 1e-9)
    }
}
