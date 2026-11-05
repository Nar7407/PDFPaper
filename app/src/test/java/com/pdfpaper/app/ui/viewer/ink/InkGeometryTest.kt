package com.pdfpaper.app.ui.viewer.ink

import com.pdfpaper.engine.graphics.PdfColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** T54: simplification, curve fitting, hit tests, lasso and shape snap. */
class InkGeometryTest {

    private fun stroke(
        id: Long = 1,
        page: Int = 0,
        points: List<InkSample>,
        width: Double = 2.0
    ) = InkStroke(id, page, points, PdfColor.BLACK, width)

    @Test
    fun rdpKeepsEndpointsAndDropsCollinearPoints() {
        val samples = listOf(
            InkSample(0.0, 0.0),
            InkSample(10.0, 0.0),
            InkSample(20.0, 0.0),
            InkSample(30.0, 0.0)
        )
        val simplified = InkGeometry.simplify(samples, epsilon = 0.5)
        assertEquals(2, simplified.size)
        assertEquals(0.0, simplified.first().x, 0.0001)
        assertEquals(30.0, simplified.last().x, 0.0001)
    }

    @Test
    fun rdpKeepsADeviatingPoint() {
        val samples = listOf(
            InkSample(0.0, 0.0),
            InkSample(10.0, 8.0),
            InkSample(20.0, 0.0)
        )
        val simplified = InkGeometry.simplify(samples, epsilon = 1.0)
        assertEquals(3, simplified.size)
    }

    @Test
    fun catmullRomPassesThroughEndpointsAndInterpolatesPressure() {
        val samples = listOf(
            InkSample(0.0, 0.0, 0.2),
            InkSample(10.0, 10.0, 0.8),
            InkSample(20.0, 0.0, 0.4)
        )
        val curved = InkGeometry.catmullRom(samples, subdivisions = 4)
        assertEquals(1 + (samples.size - 1) * 4, curved.size)
        assertEquals(0.0, curved.first().x, 0.0001)
        assertEquals(20.0, curved.last().x, 0.0001)
        assertTrue(curved[2].pressure > 0.2 && curved[2].pressure < 0.8)
    }

    @Test
    fun forSavingCurveFitsAndSimplifiesNoisyInput() {
        val noisy = (0..60).map { i ->
            InkSample(i * 2.0, if (i % 2 == 0) 0.2 else -0.2, 0.5 + i / 200.0)
        }
        val saved = InkGeometry.forSaving(noisy)
        assertTrue("simplification must shrink the stroke", saved.size < noisy.size)
        assertEquals(noisy.first().x, saved.first().x, 0.0001)
        assertEquals(noisy.last().x, saved.last().x, 0.0001)
        assertTrue(saved.first().pressure in 0.0..1.0)
    }

    @Test
    fun hitTestingUsesTheSegmentNotJustVertices() {
        val line = stroke(points = listOf(InkSample(0.0, 0.0), InkSample(100.0, 0.0)))
        assertEquals(2.0, InkGeometry.distanceToStroke(line, 50.0, 2.0), 0.0001)
        assertTrue(InkGeometry.strokeHitsPath(line, listOf(InkSample(50.0, 2.5)), 2.0))
        assertFalse(InkGeometry.strokeHitsPath(line, listOf(InkSample(50.0, 20.0)), 2.0))
    }

    @Test
    fun lassoSelectsOnlyStrokesInsideThePolygon() {
        val inside = stroke(1, points = listOf(InkSample(10.0, 10.0), InkSample(20.0, 20.0)))
        val outside = stroke(2, points = listOf(InkSample(90.0, 90.0), InkSample(95.0, 95.0)))
        val polygon = listOf(
            InkSample(0.0, 0.0),
            InkSample(40.0, 0.0),
            InkSample(40.0, 40.0),
            InkSample(0.0, 40.0)
        )
        assertEquals(setOf(1L), InkGeometry.lassoSelect(listOf(inside, outside), polygon))
        assertTrue(InkGeometry.insidePolygon(polygon, 20.0, 20.0))
        assertFalse(InkGeometry.insidePolygon(polygon, 60.0, 20.0))
    }

    @Test
    fun moveTranslatesEverySample() {
        val moved = InkGeometry.move(
            stroke(points = listOf(InkSample(1.0, 2.0), InkSample(3.0, 4.0))),
            dx = 5.0,
            dy = -1.0
        )
        assertEquals(6.0, moved.samples[0].x, 0.0001)
        assertEquals(1.0, moved.samples[0].y, 0.0001)
        assertEquals(8.0, moved.samples[1].x, 0.0001)
        assertEquals(3.0, moved.samples[1].y, 0.0001)
    }

    @Test
    fun quickStrokeNeverSnapsButHeldStraightStrokeBecomesALine() {
        val straight = listOf(InkSample(0.0, 0.0), InkSample(20.0, 0.0), InkSample(50.0, 0.0))
        assertNull(InkGeometry.detectShape(straight, held = false))
        val line = InkGeometry.detectShape(straight, held = true)!!
        assertEquals(ShapeKind.LINE, line.kind)
        assertEquals(2, line.samples.size)
    }

    @Test
    fun heldClosedRoundStrokeBecomesACircle() {
        val circle = (0 until 72).map { step ->
            val angle = 2.0 * Math.PI * step / 72.0
            InkSample(50.0 + kotlin.math.cos(angle) * 20.0, 50.0 + kotlin.math.sin(angle) * 20.0)
        } + InkSample(70.0, 50.0)
        val snap = InkGeometry.detectShape(circle, held = true)!!
        assertEquals(ShapeKind.CIRCLE, snap.kind)
        assertTrue(snap.samples.size >= 72)
        val radius = hypot(snap.samples[0].x - 50.0, snap.samples[0].y - 50.0)
        assertEquals(20.0, radius, 1.5)
    }

    @Test
    fun heldClosedBoxStrokeBecomesARectangle() {
        val rectangle = listOf(
            InkSample(10.0, 10.0),
            InkSample(80.0, 10.0),
            InkSample(80.0, 50.0),
            InkSample(10.0, 50.0),
            InkSample(10.0, 10.0)
        )
        val snap = InkGeometry.detectShape(rectangle, held = true)!!
        assertEquals(ShapeKind.RECTANGLE, snap.kind)
        assertEquals(10.0, snap.samples.minOf { it.x }, 0.001)
        assertEquals(80.0, snap.samples.maxOf { it.x }, 0.001)
    }

    @Test
    fun boundsIncludeTheStrokeWidth() {
        val box = InkGeometry.bounds(
            listOf(InkSample(10.0, 20.0), InkSample(30.0, 40.0)),
            width = 4.0
        )!!
        assertEquals(8.0, box.left, 0.0001)
        assertEquals(18.0, box.bottom, 0.0001)
        assertEquals(32.0, box.right, 0.0001)
        assertEquals(42.0, box.top, 0.0001)
    }
}
