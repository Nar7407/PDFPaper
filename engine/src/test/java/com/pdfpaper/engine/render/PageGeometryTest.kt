package com.pdfpaper.engine.render

import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T9 acceptance: page boxes (§7.7.3.3) and the crop-box → bitmap transform
 * (§14.11.2 rotation, y flip) are correct, and the pixel budget bounds a
 * single page's bitmap for the viewer's memory target.
 */
class PageGeometryTest {

    private fun pdfPage(extraKeys: String): PdfPage {
        val objects = listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(3, 0, "<< /Type /Page /Parent 2 0 R $extraKeys >>"),
            TestObj(4, 0, "<< /Length 0 >>\nstream\n\nendstream")
        )
        val doc = PdfDocument.open(ByteArraySource(buildTestPdf(objects))).getOrThrow()
        return doc.pages().first()
    }

    private fun assertPoint(matrix: PdfMatrix, x: Double, y: Double, ex: Double, ey: Double) {
        val p = matrix.apply(x, y)
        assertTrue(abs(p[0] - ex) < 1e-6 && abs(p[1] - ey) < 1e-6, "($x,$y) → ${p[0]},${p[1]} expected $ex,$ey")
    }

    // -------------------------------------------------------------- boxes

    @Test
    fun missingBoxFallsBackToLetter() {
        val box = PageGeometry.boxOf(pdfPage("/Contents 4 0 R"))
        assertEquals(0.0, box.left)
        assertEquals(0.0, box.bottom)
        assertEquals(612.0, box.right)
        assertEquals(792.0, box.top)
        assertEquals(0, box.rotate)
    }

    @Test
    fun cropBoxWinsOverMediaBox() {
        val box = PageGeometry.boxOf(
            pdfPage("/MediaBox [0 0 612 792] /CropBox [10 20 300 400] /Contents 4 0 R")
        )
        assertEquals(10.0, box.left)
        assertEquals(20.0, box.bottom)
        assertEquals(300.0, box.right)
        assertEquals(400.0, box.top)
        assertEquals(290.0, box.width)
        assertEquals(380.0, box.height)
    }

    @Test
    fun invertedCornersAreNormalized() {
        val box = PageGeometry.boxOf(
            pdfPage("/MediaBox [300 400 10 20] /Contents 4 0 R")
        )
        assertEquals(10.0, box.left)
        assertEquals(20.0, box.bottom)
        assertEquals(300.0, box.right)
        assertEquals(400.0, box.top)
    }

    @Test
    fun zeroAreaBoxFallsBackToLetter() {
        val box = PageGeometry.boxOf(pdfPage("/MediaBox [0 0 0 0] /Contents 4 0 R"))
        assertEquals(612.0, box.width)
        assertEquals(792.0, box.height)
    }

    @Test
    fun rotateSwapsDisplayedSize() {
        val box = PageGeometry.boxOf(
            pdfPage("/MediaBox [0 0 612 792] /Rotate 90 /Contents 4 0 R")
        )
        assertEquals(90, box.rotate)
        assertEquals(792.0, box.rotatedWidth)
        assertEquals(612.0, box.rotatedHeight)
    }

    // ------------------------------------------------------- page matrix

    @Test
    fun identityRotationFlipsYAndOffsetsToBoxOrigin() {
        val box = PageBox(0.0, 0.0, 612.0, 792.0, 0)
        val m = PageGeometry.matrixFor(box, 1.0, 1.0)
        assertPoint(m, 0.0, 792.0, 0.0, 0.0)   // PDF top-left → bitmap top-left
        assertPoint(m, 0.0, 0.0, 0.0, 792.0)   // PDF bottom-left → bitmap bottom-left
        assertPoint(m, 612.0, 792.0, 612.0, 0.0)
    }

    @Test
    fun offsetCropBoxMapsItsOwnOriginToZero() {
        val box = PageBox(100.0, 50.0, 300.0, 250.0, 0)
        val m = PageGeometry.matrixFor(box, 2.0, 2.0)
        assertPoint(m, 100.0, 250.0, 0.0, 0.0)
        assertPoint(m, 300.0, 50.0, 400.0, 400.0)
    }

    @Test
    fun rotation90MapsCornersClockwise() {
        val box = PageBox(0.0, 0.0, 612.0, 792.0, 90)
        val m = PageGeometry.matrixFor(box, 1.0, 1.0)
        assertPoint(m, 0.0, 792.0, 792.0, 0.0)
        assertPoint(m, 0.0, 0.0, 0.0, 0.0)
        assertPoint(m, 612.0, 0.0, 0.0, 612.0)
        assertPoint(m, 612.0, 792.0, 792.0, 612.0)
    }

    @Test
    fun rotation180MapsCornersOpposite() {
        val box = PageBox(0.0, 0.0, 612.0, 792.0, 180)
        val m = PageGeometry.matrixFor(box, 1.0, 1.0)
        assertPoint(m, 0.0, 792.0, 612.0, 792.0)
        assertPoint(m, 0.0, 0.0, 612.0, 0.0)
        assertPoint(m, 612.0, 0.0, 0.0, 0.0)
    }

    @Test
    fun rotation270MapsCornersCounterClockwise() {
        val box = PageBox(0.0, 0.0, 612.0, 792.0, 270)
        val m = PageGeometry.matrixFor(box, 1.0, 1.0)
        assertPoint(m, 0.0, 792.0, 0.0, 612.0)
        assertPoint(m, 0.0, 0.0, 792.0, 612.0)
        assertPoint(m, 612.0, 792.0, 0.0, 0.0)
        assertPoint(m, 612.0, 0.0, 792.0, 0.0)
    }

    // ------------------------------------------------------------- plans

    @Test
    fun planSizesBitmapFromScale() {
        val plan = PageGeometry.plan(PageBox(0.0, 0.0, 612.0, 792.0, 0), 2.0)
        assertEquals(1224, plan.pixelWidth)
        assertEquals(1584, plan.pixelHeight)
        assertEquals(2.0, plan.scale)
        assertTrue(!plan.downscaled)
    }

    @Test
    fun planSwapsPixelSizeForRotatedPages() {
        val plan = PageGeometry.plan(PageBox(0.0, 0.0, 612.0, 792.0, 90), 1.0)
        assertEquals(792, plan.pixelWidth)
        assertEquals(612, plan.pixelHeight)
    }

    @Test
    fun planClampsToPixelBudget() {
        val plan = PageGeometry.plan(PageBox(0.0, 0.0, 612.0, 792.0, 0), 20.0, maxPixels = 1_000_000)
        assertTrue(plan.downscaled)
        assertTrue(plan.scale < 20.0)
        val pixels = plan.pixelWidth.toLong() * plan.pixelHeight.toLong()
        assertTrue(pixels <= 1_000_000 + 4096, "got $pixels pixels at scale ${plan.scale}")
    }

    @Test
    fun scaleForPixelWidthUsesRotatedWidth() {
        val box = PageBox(0.0, 0.0, 612.0, 792.0, 90)
        assertEquals(2.0, PageGeometry.scaleForPixelWidth(box, 1584))
    }

    @Test
    fun degenerateScaleFallsBackToOne() {
        val plan = PageGeometry.plan(PageBox(0.0, 0.0, 612.0, 792.0, 0), 0.0)
        assertEquals(1.0, plan.scale)
        assertEquals(612, plan.pixelWidth)
    }
}
