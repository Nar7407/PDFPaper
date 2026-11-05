package com.pdfpaper.app.ui.crop

import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.mark.PageSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for the crop screen's margins, handles and preview mapping (T46). */
class CropUiLogicTest {

    @Test
    fun pageModesMapToTheEngineSelection() {
        assertTrue(parseCropPages(CropPageMode.ALL, "ignored") is PageSelection.All)
        assertTrue(parseCropPages(CropPageMode.ODD, "") is PageSelection.Odd)
        assertTrue(parseCropPages(CropPageMode.EVEN, "") is PageSelection.Even)

        val custom = parseCropPages(CropPageMode.CUSTOM, "1,3-5") as PageSelection.Custom
        assertEquals(setOf(0, 2, 3, 4), custom.pages)

        val blank = parseCropPages(CropPageMode.CUSTOM, "  ") as PageSelection.Custom
        assertTrue(blank.pages.isEmpty())
    }

    @Test
    fun marginsConvertBothWays() {
        val base = PdfRect(0.0, 0.0, 600.0, 800.0)
        val margins = CropMargins(10.0, 20.0, 30.0, 40.0)
        val box = cropBoxFromMargins(base, margins)
        assertNotNull(box)
        assertEquals(PdfRect(10.0, 20.0, 570.0, 760.0), box)
        val back = marginsFor(base, box!!)
        assertNotNull(back)
        assertEquals(10.0, back!!.left, 0.0001)
        assertEquals(20.0, back.bottom, 0.0001)
        assertEquals(30.0, back.right, 0.0001)
        assertEquals(40.0, back.top, 0.0001)
    }

    @Test
    fun impossibleMarginsReturnNull() {
        val base = PdfRect(0.0, 0.0, 100.0, 100.0)
        assertNull(cropBoxFromMargins(base, CropMargins(60.0, 0.0, 60.0, 0.0)))
        assertNull(cropBoxFromMargins(base, CropMargins(-1.0, 0.0, 0.0, 0.0)))
        assertNull(cropBoxFromMargins(base, CropMargins(Double.NaN, 0.0, 0.0, 0.0)))
        assertNull(marginsFor(base, PdfRect(-5.0, 0.0, 50.0, 50.0)))
    }

    @Test
    fun draggingACornerKeepsTheBoxInsideThePage() {
        val bounds = PdfRect(0.0, 0.0, 500.0, 700.0)
        val box = PdfRect(100.0, 100.0, 400.0, 600.0)

        assertEquals(
            PdfRect(0.0, 0.0, 400.0, 600.0),
            dragCrop(box, CropHandle.LEFT_BOTTOM, -50.0, -50.0, bounds)
        )
        assertEquals(
            PdfRect(100.0, 100.0, 500.0, 700.0),
            dragCrop(box, CropHandle.RIGHT_TOP, 9999.0, 9999.0, bounds)
        )
        assertEquals(
            PdfRect(100.0, 300.0, 350.0, 600.0),
            dragCrop(box, CropHandle.RIGHT_BOTTOM, 350.0, 300.0, bounds)
        )
        assertEquals(
            PdfRect(150.0, 100.0, 400.0, 250.0),
            dragCrop(box, CropHandle.LEFT_TOP, 150.0, 250.0, bounds)
        )
    }

    @Test
    fun draggingNeverCrossesTheMinimumSize() {
        val bounds = PdfRect(0.0, 0.0, 500.0, 700.0)
        val box = PdfRect(100.0, 100.0, 400.0, 600.0)
        val dragged = dragCrop(box, CropHandle.LEFT_BOTTOM, 499.0, 699.0, bounds, minSize = 8.0)
        assertEquals(400.0 - 8.0, dragged.left, 0.0001)
        assertEquals(600.0 - 8.0, dragged.bottom, 0.0001)
        assertEquals(400.0, dragged.right, 0.0001)
        assertEquals(600.0, dragged.top, 0.0001)
    }

    @Test
    fun fitCentresTheBitmapAndInverseRoundTrips() {
        val centred = fitBitmap(200, 100, 400.0, 400.0)
        assertEquals(2.0, centred.scale, 0.0001)
        assertEquals(0.0, centred.left, 0.0001)
        assertEquals(100.0, centred.top, 0.0001)

        // A normal page render map: 2 px/pt and y flipped, 400 px bitmap.
        val matrix = PdfMatrix(2.0, 0.0, 0.0, -2.0, 0.0, 400.0)
        val fit = fitBitmap(400, 400, 400.0, 400.0)
        val box = PdfRect(0.0, 0.0, 200.0, 200.0)
        val canvas = fit.toCanvas(deviceRect(box, matrix))
        assertEquals(0.0, canvas.left, 0.0001)
        assertEquals(0.0, canvas.top, 0.0001)
        assertEquals(400.0, canvas.right, 0.0001)
        assertEquals(400.0, canvas.bottom, 0.0001)

        val user = canvasToUser(120.0, 160.0, fit, matrix)
        assertNotNull(user)
        assertEquals(60.0, user!!.first, 0.0001)
        assertEquals(120.0, user.second, 0.0001)

        val corner = canvasCorner(box, CropHandle.LEFT_BOTTOM, matrix, fit)
        assertEquals(0.0, corner.first, 0.0001)
        assertEquals(400.0, corner.second, 0.0001)
    }

    @Test
    fun sourcePixelsCutTheAfterThumbnailOutOfTheRender() {
        val matrix = PdfMatrix(2.0, 0.0, 0.0, -2.0, 0.0, 400.0)
        val pixels = sourcePixels(PdfRect(10.0, 20.0, 90.0, 180.0), matrix, 400, 400)
        assertEquals(20, pixels.left)
        assertEquals(40, pixels.top)
        assertEquals(180, pixels.right)
        assertEquals(360, pixels.bottom)
        assertEquals(160, pixels.width)
        assertEquals(320, pixels.height)
    }

    @Test
    fun cropStatePicksMarginsOrThePerPageBox() {
        val base = PdfRect(0.0, 0.0, 600.0, 800.0)
        val info = CropPageInfo(0, base, base, 0)
        val all = CropUiState(
            pages = listOf(info),
            margins = CropMargins.uniform(10.0),
            editMode = CropEditMode.ALL
        )
        assertEquals(PdfRect(10.0, 10.0, 590.0, 790.0), all.boxFor())
        assertFalse(all.selectionIsEmpty())

        val perPage = CropUiState(
            pages = listOf(info),
            editMode = CropEditMode.PER_PAGE,
            boxes = mapOf(0 to PdfRect(20.0, 20.0, 500.0, 700.0))
        )
        assertEquals(PdfRect(20.0, 20.0, 500.0, 700.0), perPage.boxFor())

        val empty = CropUiState(pageMode = CropPageMode.CUSTOM, customPages = "")
        assertTrue(empty.selectionIsEmpty())
        assertEquals("report-cropped.pdf", suggestedCropName("report.pdf"))
        assertEquals("cropped.pdf", suggestedCropName("  "))
        assertTrue(CropMargins.uniform(1.0).isZero.not())
    }
}
