package com.pdfpaper.app.ui.viewer.fit

import com.pdfpaper.app.ui.crop.CropMargins
import com.pdfpaper.engine.fit.FitSize
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.render.PageBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** T57: how the viewer turns detected content boxes into drawn page boxes. */
class FitContentModelTest {

    private fun page(
        left: Double = 0.0,
        bottom: Double = 0.0,
        right: Double = 600.0,
        top: Double = 800.0,
        rotate: Int = 0
    ) = PageBox(left, bottom, right, top, rotate)

    private fun state(
        mode: FitContentMode,
        boxes: Map<Int, PdfRect> = emptyMap(),
        margins: CropMargins = CropMargins.ZERO
    ) = FitContentState(mode = mode, boxes = boxes, margins = margins)

    private fun assertBox(expected: PdfRect, actual: PageBox, rotate: Int = 0) {
        assertEquals(expected.left, actual.left, 0.001)
        assertEquals(expected.bottom, actual.bottom, 0.001)
        assertEquals(expected.right, actual.right, 0.001)
        assertEquals(expected.top, actual.top, 0.001)
        assertEquals(rotate, actual.rotate)
    }

    @Test
    fun offKeepsTheWholePage() {
        val pages = listOf(page(), page(rotate = 90))
        val result = fitViewBoxes(pages, state(FitContentMode.OFF))
        assertEquals(2, result.size)
        assertBox(PdfRect(0.0, 0.0, 600.0, 800.0), result[0])
        assertBox(PdfRect(0.0, 0.0, 600.0, 800.0), result[1], rotate = 90)
    }

    @Test
    fun detectedBoxesAreDrawnWithThePageRotation() {
        val pages = listOf(page(), page(rotate = 90))
        val boxes = mapOf(
            0 to PdfRect(60.0, 100.0, 540.0, 700.0),
            1 to PdfRect(70.0, 120.0, 530.0, 680.0)
        )
        val result = fitViewBoxes(pages, state(FitContentMode.DOCUMENT, boxes))
        assertBox(PdfRect(60.0, 100.0, 540.0, 700.0), result[0])
        assertBox(PdfRect(70.0, 120.0, 530.0, 680.0), result[1], rotate = 90)
        // The rotated box is taller than wide on screen: the aspect swap comes
        // from the box plus /Rotate.
        assertEquals(560.0, result[1].rotatedWidth, 0.01)
        assertEquals(460.0, result[1].rotatedHeight, 0.01)
    }

    @Test
    fun aPageWithoutADetectedBoxFallsBackToItself() {
        val pages = listOf(page(), page())
        val boxes = mapOf(0 to PdfRect(50.0, 50.0, 550.0, 750.0))
        val result = fitViewBoxes(pages, state(FitContentMode.PAGE, boxes))
        assertBox(PdfRect(50.0, 50.0, 550.0, 750.0), result[0])
        assertBox(PdfRect(0.0, 0.0, 600.0, 800.0), result[1])
    }

    @Test
    fun manualMarginsTrimEveryPage() {
        val pages = listOf(page(), page(rotate = 180))
        val margins = CropMargins(20.0, 30.0, 40.0, 50.0)
        val result = fitViewBoxes(pages, state(FitContentMode.MANUAL, margins = margins))
        assertBox(PdfRect(20.0, 30.0, 560.0, 750.0), result[0])
        assertBox(PdfRect(20.0, 30.0, 560.0, 750.0), result[1], rotate = 180)
    }

    @Test
    fun impossibleManualMarginsKeepThePage() {
        val pages = listOf(page())
        val margins = CropMargins(400.0, 0.0, 400.0, 0.0)
        val result = fitViewBoxes(pages, state(FitContentMode.MANUAL, margins = margins))
        assertBox(PdfRect(0.0, 0.0, 600.0, 800.0), result[0])
    }

    @Test
    fun permanentBoxesMatchTheDrawnBoxes() {
        val pages = listOf(page(), page(rotate = 90))
        val boxes = mapOf(
            0 to PdfRect(60.0, 100.0, 540.0, 700.0),
            1 to PdfRect(70.0, 120.0, 530.0, 680.0)
        )
        val drawn = fitViewBoxes(pages, state(FitContentMode.DOCUMENT, boxes))
        val permanent = fitCropBoxes(pages, state(FitContentMode.DOCUMENT, boxes))
        assertEquals(2, permanent.size)
        for (index in pages.indices) {
            val crop = permanent[index]
            assertNotNull(crop)
            assertBox(crop!!, drawn[index], rotate = pages[index].rotate)
        }
    }

    @Test
    fun manualPermanentBoxesComeFromTheMargins() {
        val pages = listOf(page())
        val margins = CropMargins(10.0, 20.0, 30.0, 40.0)
        val drawn = fitViewBoxes(pages, state(FitContentMode.MANUAL, margins = margins))
        assertBox(PdfRect(10.0, 20.0, 570.0, 760.0), drawn[0])
        val permanent = fitCropBoxes(pages, state(FitContentMode.MANUAL, margins = margins))
        assertEquals(1, permanent.size)
        assertBox(PdfRect(10.0, 20.0, 570.0, 760.0), drawn[0])
    }

    @Test
    fun offHasNoPermanentBoxes() {
        assertTrue(fitCropBoxes(listOf(page()), state(FitContentMode.OFF)).isEmpty())
    }

    @Test
    fun descriptionReportsTheMagnification() {
        val pages = listOf(page())
        val fit = FitContentState(
            mode = FitContentMode.DOCUMENT,
            boxes = mapOf(0 to PdfRect(150.0, 100.0, 450.0, 700.0)),
            oddSize = FitSize(300.0, 600.0),
            detectedPages = 1
        )
        val text = fitDescription(fit, pages)
        assertTrue(text, text.contains("200 %"))
    }

    @Test
    fun descriptionIsHonestWhenOffAndWhileLoading() {
        assertEquals("Showing the whole page.", fitDescription(FitContentState(), listOf(page())))
        assertEquals(
            "Finding the content...",
            fitDescription(FitContentState(mode = FitContentMode.DOCUMENT, loading = true), emptyList())
        )
        assertEquals(
            "The page could not be measured",
            fitDescription(
                FitContentState(mode = FitContentMode.DOCUMENT, error = "The page could not be measured"),
                emptyList()
            )
        )
    }

    @Test
    fun manualDescriptionListsTheMargins() {
        val text = fitDescription(
            FitContentState(mode = FitContentMode.MANUAL, margins = CropMargins(12.0, 12.0, 12.0, 12.0)),
            listOf(page())
        )
        assertTrue(text, text.startsWith("Margins "))
        assertTrue(text, text.contains("12"))
    }

    @Test
    fun rotatedPageDisplaySizeSwapsTheAxes() {
        val box = PdfRect(10.0, 20.0, 310.0, 620.0)
        val unrotated = displaySize(box, 0)
        assertEquals(300.0, unrotated.width, 0.001)
        assertEquals(600.0, unrotated.height, 0.001)
        val rotated = displaySize(box, 90)
        assertEquals(600.0, rotated.width, 0.001)
        assertEquals(300.0, rotated.height, 0.001)
        assertEquals(rotated, displaySize(box, 270))
    }

    @Test
    fun magnificationIsNullWithoutAPage() {
        assertNull(magnification(null, FitSize(300.0, 600.0)))
        assertEquals(200, magnification(page(), FitSize(300.0, 600.0)) ?: 0)
        assertNull(magnification(page(), FitSize(0.0, 600.0)))
    }

    @Test
    fun notesCountScannedPagesAndFallbacks() {
        val fit = FitContentState(
            mode = FitContentMode.DOCUMENT,
            scannedPages = listOf(2, 3),
            fallbackPages = listOf(7),
            warnings = listOf("page 7: a page-sized picture, shown whole")
        )
        val notes = fitNotes(fit)
        assertTrue(notes.toString(), notes.any { it.contains("2 scanned page") })
        assertTrue(notes.toString(), notes.any { it.contains("1 page(s) keep their own box") })
        assertTrue(notes.toString(), notes.any { it.startsWith("page 7") })
    }

    @Test
    fun uniformIsOnlyClaimedWithASharedSize() {
        assertFalse(FitContentState().uniform)
        assertTrue(
            FitContentState(mode = FitContentMode.DOCUMENT, oddSize = FitSize(300.0, 600.0)).uniform
        )
        assertFalse(
            FitContentState(
                mode = FitContentMode.DOCUMENT,
                oddSize = FitSize(300.0, 600.0),
                fallbackPages = listOf(4)
            ).uniform
        )
    }

    @Test
    fun viewBoxesAreUnchangedWhenTheDocumentIsEmpty() {
        assertTrue(fitViewBoxes(emptyList(), state(FitContentMode.DOCUMENT)).isEmpty())
    }

    @Test
    fun aDetectedBoxOutsideThePageIsUsedAsGiven() {
        // The engine clamps to the page, but the viewer must not crash or
        // silently drop a box it did not expect.
        val pages = listOf(page())
        val boxes = mapOf(0 to PdfRect(-20.0, -10.0, 700.0, 900.0))
        val result = fitViewBoxes(pages, state(FitContentMode.PAGE, boxes))
        assertEquals(-20.0, result[0].left, 0.001)
        assertTrue(abs(result[0].width - 720.0) < 0.001)
    }
}
