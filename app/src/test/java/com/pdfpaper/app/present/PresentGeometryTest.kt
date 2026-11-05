package com.pdfpaper.app.present

import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T53: letterboxing and pen-to-user-space mapping. */
class PresentGeometryTest {

    @Test
    fun portraitPageIsPillarboxedOnAwideScreen() {
        // 3:4 page (0.75) in a 1000x500 container: height is the limit.
        val fit = PresentGeometry.fit(1000f, 500f, 0.75f)
        assertEquals(375f, fit.width, 0.001f)
        assertEquals(500f, fit.height, 0.001f)
        assertEquals(312.5f, fit.left, 0.001f)
        assertEquals(0f, fit.top, 0.001f)
    }

    @Test
    fun widePageIsLetterboxedOnATallScreen() {
        // 4:3 page (1.333) in a 500x1000 container: width is the limit.
        val fit = PresentGeometry.fit(500f, 1000f, 4f / 3f)
        assertEquals(500f, fit.width, 0.001f)
        assertEquals(375f, fit.height, 0.001f)
        assertEquals(0f, fit.left, 0.001f)
        assertEquals(312.5f, fit.top, 0.001f)
    }

    @Test
    fun fractionMapsInsideAndRejectsTheLetterbox() {
        assertEquals(
            0.5f to 0.5f,
            PresentGeometry.fraction(500f, 250f, 1000f, 500f, 0.75f)
        )
        val point = PresentGeometry.fraction(312.5f, 125f, 1000f, 500f, 0.75f)
        assertEquals(0f, point!!.first, 0.001f)
        assertEquals(0.25f, point.second, 0.001f)
        assertNull(PresentGeometry.fraction(10f, 250f, 1000f, 500f, 0.75f))
    }

    @Test
    fun badSizesNeverProduceNegativeGeometry() {
        val fit = PresentGeometry.fit(0f, 0f, 0.75f)
        assertEquals(0f, fit.width, 0f)
        assertNull(PresentGeometry.fraction(0f, 0f, 0f, 0f, 0.75f))
    }

    @Test
    fun pagesClampAtBothEnds() {
        assertEquals(0, PresentPages.clamp(-4, 5))
        assertEquals(4, PresentPages.clamp(9, 5))
        assertEquals(0, PresentPages.step(0, -1, 5))
        assertEquals(4, PresentPages.step(4, 1, 5))
        assertEquals(0, PresentPages.clamp(3, 0))
    }

    @Test
    fun normalizedInkMapsBackThroughTheRenderPlan() {
        val box = PageBox(0.0, 0.0, 100.0, 100.0, 0)
        val plan = PageGeometry.plan(box, 1.0)
        val specs = PresentInk.toInkSpecs(
            strokes = listOf(
                PenStroke(0, listOf(0f, 0f, 0.5f, 0.5f, 1f, 1f))
            ),
            plans = mapOf(0 to plan)
        )
        assertEquals(1, specs.size)
        val points = specs[0].strokes.single()
        assertEquals(0.0, points[0], 0.001)
        assertEquals(100.0, points[1], 0.001)
        assertEquals(50.0, points[2], 0.001)
        assertEquals(50.0, points[3], 0.001)
        assertEquals(100.0, points[4], 0.001)
        assertEquals(0.0, points[5], 0.001)
    }

    @Test
    fun rotatedAndOffsetPagesRoundTrip() {
        val box = PageBox(20.0, 30.0, 220.0, 330.0, 90)
        val plan = PageGeometry.plan(box, 2.0)
        val spec = PresentInk.toInkSpecs(
            strokes = listOf(PenStroke(2, listOf(0.2f, 0.7f, 0.9f, 0.1f))),
            plans = mapOf(2 to plan)
        ).single()
        val points = spec.strokes.single()
        val px = 0.2 * plan.pixelWidth
        val py = 0.7 * plan.pixelHeight
        val device = plan.matrix.apply(points[0], points[1])
        assertEquals(px, device[0], 0.001)
        assertEquals(py, device[1], 0.001)
        assertEquals(4, points.size)
    }

    @Test
    fun pagesWithoutAPlanOrPointsAreDropped() {
        val plan = PageGeometry.plan(PageBox(0.0, 0.0, 100.0, 100.0, 0), 1.0)
        assertTrue(
            PresentInk.toInkSpecs(listOf(PenStroke(9, listOf(0f, 0f, 1f, 1f))), mapOf(0 to plan))
                .isEmpty()
        )
        assertTrue(
            PresentInk.toInkSpecs(listOf(PenStroke(0, listOf(0.5f, 0.5f))), mapOf(0 to plan))
                .isEmpty()
        )
    }

    @Test
    fun inkGroupsStrokesPerPage() {
        val plan = PageGeometry.plan(PageBox(0.0, 0.0, 100.0, 100.0, 0), 1.0)
        val specs = PresentInk.toInkSpecs(
            listOf(
                PenStroke(0, listOf(0.1f, 0.1f, 0.2f, 0.2f)),
                PenStroke(0, listOf(0.3f, 0.3f, 0.4f, 0.4f)),
                PenStroke(1, listOf(0.5f, 0.5f, 0.6f, 0.6f))
            ),
            mapOf(0 to plan, 1 to plan)
        )
        assertEquals(2, specs.size)
        assertEquals(2, specs.first { it.pageIndex == 0 }.strokes.size)
        assertEquals(1, specs.first { it.pageIndex == 1 }.strokes.size)
    }

    @Test
    fun transitionNamesAreStable() {
        assertEquals(PresentTransition.FADE, PresentTransition.fromName(null))
        assertEquals(PresentTransition.FADE, PresentTransition.fromName("unknown"))
        assertEquals(PresentTransition.NONE, PresentTransition.fromName("NONE"))
        assertEquals(PresentTransition.SLIDE, PresentTransition.fromName("SLIDE"))
    }

    @Test
    fun matrixInverseIsAvailableForThePaintingPath() {
        val plan = PageGeometry.plan(PageBox(0.0, 0.0, 10.0, 20.0, 180), 1.0)
        val inverse = plan.matrix.inverse()
        assertTrue(inverse != null)
        val device = plan.matrix.apply(3.0, 7.0)
        val back = inverse!!.apply(device[0], device[1])
        assertEquals(3.0, back[0], 0.001)
        assertEquals(7.0, back[1], 0.001)
    }
}
