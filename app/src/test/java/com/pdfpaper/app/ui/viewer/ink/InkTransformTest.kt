package com.pdfpaper.app.ui.viewer.ink

import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

/** T54: ink coordinates survive a crop-box offset and page rotation. */
class InkTransformTest {

    private val plan = PageGeometry.plan(
        PageBox(left = 100.0, bottom = 50.0, right = 300.0, top = 250.0, rotate = 90),
        scale = 2.0
    )

    private fun stroke(width: Double = 4.0) = InkStroke(
        id = 1,
        pageIndex = 0,
        samples = emptyList(),
        color = PdfColor.BLACK,
        width = width
    )

    @Test
    fun fractionRoundTripsThroughAPrintedRotatedCroppedPage() {
        assertEquals(400, plan.pixelWidth)
        assertEquals(400, plan.pixelHeight)
        val sample = InkTransform.sampleAt(plan, 0.5f, 0.5f, pressure = 0.8f, tilt = 35f)!!
        // The centre of the crop is the same point after /Rotate; the offset means
        // it is 200,150 in user space, not 50,50.
        assertEquals(200.0, sample.x, 0.0001)
        assertEquals(150.0, sample.y, 0.0001)
        assertEquals(0.8, sample.pressure, 0.0001)
        assertEquals(35.0, sample.tilt, 0.0001)
        val fraction = InkTransform.fractionOf(plan, sample.x, sample.y)!!
        assertEquals(0.5f, fraction.first, 0.0001f)
        assertEquals(0.5f, fraction.second, 0.0001f)
    }

    @Test
    fun everyCornerLandsInsideThePage() {
        val fractions = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)
        for ((fx, fy) in fractions) {
            val sample = InkTransform.sampleAt(plan, fx, fy, 1f, 0f)!!
            val back = InkTransform.fractionOf(plan, sample.x, sample.y)!!
            assertEquals(fx, back.first, 0.001f)
            assertEquals(fy, back.second, 0.001f)
        }
    }

    @Test
    fun widthConvertsFromPointsToDisplayPixels() {
        // 4 pt at scale 2 is 8 bitmap pixels; the Compose view is 800 px wide
        // against a 400 px bitmap, so the on-screen width doubles once more.
        assertEquals(16f, InkTransform.widthPx(stroke(4.0), plan, viewWidth = 800f), 0.001f)
    }

    @Test
    fun penHalfWidthFollowsPressureAndHighlighterDoesNot() {
        val pen = stroke(10.0)
        assertEquals(1.75, InkTransform.halfWidth(pen, 0.0), 0.0001)
        assertEquals(5.0, InkTransform.halfWidth(pen, 1.0), 0.0001)
        val highlighter = pen.copy(tool = com.pdfpaper.engine.annot.InkTool.HIGHLIGHTER)
        assertEquals(5.0, InkTransform.halfWidth(highlighter, 0.2), 0.0001)
    }
}
