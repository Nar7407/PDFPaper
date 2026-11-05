package com.pdfpaper.app.ui.viewer.flow

import com.pdfpaper.engine.flow.FlowPage
import com.pdfpaper.engine.flow.FlowSegment
import com.pdfpaper.engine.flow.FlowSegmentKind
import com.pdfpaper.engine.geom.PdfRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T59 column flow, app side. Everything geometric is pure so it is tested here
 * rather than on a device: which segments sit side by side, how a page box maps
 * to a mini-map, how a saved correction round-trips, and the labels the header
 * shows.
 */
class ColumnFlowModelTest {

    private val page = PdfRect(0.0, 0.0, 600.0, 800.0)

    private fun segment(
        order: Int,
        bounds: PdfRect,
        kind: FlowSegmentKind = FlowSegmentKind.TEXT,
        column: Int = 0
    ): FlowSegment = FlowSegment(0, kind, bounds, column, order)

    private fun flowPage(vararg segments: FlowSegment): FlowPage = FlowPage(
        pageIndex = 0,
        page = page,
        rotate = 0,
        segments = segments.toList(),
        confidence = 0.9,
        fallback = false,
        scanned = false,
        warnings = emptyList()
    )

    // ---------------------------------------------------------------- columns

    @Test
    fun aPhoneShowsOneColumnAndATabletTwo() {
        assertEquals(1, flowColumns(411f, sideBySide = true))
        assertEquals(1, flowColumns(699f, sideBySide = true))
        assertEquals(2, flowColumns(700f, sideBySide = true))
        assertEquals(2, flowColumns(1280f, sideBySide = true))
    }

    @Test
    fun sideBySideCanBeTurnedOff() {
        assertEquals(1, flowColumns(1280f, sideBySide = false))
    }

    // ----------------------------------------------------------------- window

    @Test
    fun oneColumnShowsOnlyTheCurrentSegment() {
        val page = flowPage(
            segment(0, PdfRect(0.0, 0.0, 290.0, 700.0), column = 0),
            segment(1, PdfRect(310.0, 0.0, 600.0, 700.0), column = 1)
        )
        val window = flowWindow(page, order = 1, columns = 1)
        assertEquals(1, window.size)
        assertEquals(1, window[0].order)
    }

    @Test
    fun twoNeighbouringColumnsAreShownTogether() {
        val page = flowPage(
            segment(0, PdfRect(0.0, 0.0, 290.0, 700.0), column = 0),
            segment(1, PdfRect(310.0, 0.0, 600.0, 700.0), column = 1)
        )
        val window = flowWindow(page, order = 0, columns = 2)
        assertEquals(listOf(0, 1), window.map { it.order })
    }

    @Test
    fun aSpanningFigureIsShownAloneEvenWithTwoColumns() {
        val page = flowPage(
            segment(0, PdfRect(0.0, 400.0, 290.0, 700.0), column = 0),
            segment(1, PdfRect(310.0, 400.0, 600.0, 700.0), column = 1),
            segment(2, PdfRect(0.0, 100.0, 600.0, 380.0), FlowSegmentKind.FIGURE, column = -1),
            segment(3, PdfRect(0.0, 0.0, 290.0, 90.0), column = 0),
            segment(4, PdfRect(310.0, 0.0, 600.0, 90.0), column = 1)
        )
        val figure = flowWindow(page, order = 2, columns = 2)
        assertEquals(1, figure.size)
        assertEquals(FlowSegmentKind.FIGURE, figure[0].kind)
    }

    @Test
    fun columnsFromDifferentBandsAreNotPaired() {
        val page = flowPage(
            segment(0, PdfRect(0.0, 500.0, 290.0, 700.0), column = 0),
            segment(1, PdfRect(310.0, 500.0, 600.0, 700.0), column = 1),
            segment(2, PdfRect(0.0, 0.0, 290.0, 400.0), column = 0),
            segment(3, PdfRect(310.0, 0.0, 600.0, 400.0), column = 1)
        )
        // Order 2 is the lower band; the upper band's right column is above it.
        val window = flowWindow(page, order = 2, columns = 2)
        assertEquals(listOf(2, 3), window.map { it.order })
    }

    @Test
    fun anUnknownOrderFallsBackToTheFirstSegment() {
        val page = flowPage(segment(0, PdfRect(0.0, 0.0, 600.0, 700.0)))
        assertEquals(1, flowWindow(page, order = 9, columns = 1).size)
        assertTrue(flowWindow(page, order = 9, columns = 2).isNotEmpty())
    }

    // --------------------------------------------------------------- stepping

    @Test
    fun steppingStaysInsideThePage() {
        val page = flowPage(
            segment(0, PdfRect(0.0, 400.0, 600.0, 700.0)),
            segment(1, PdfRect(0.0, 0.0, 600.0, 390.0))
        )
        assertEquals(1, flowStep(page, 0, 1)?.order)
        assertEquals(0, flowStep(page, 1, -1)?.order)
        assertNull(flowStep(page, 1, 1))
        assertNull(flowStep(page, 0, -1))
    }

    // ----------------------------------------------------------------- boxes

    @Test
    fun aSegmentBoxKeepsThePageRotation() {
        val box = flowSegmentBox(PdfRect(10.0, 20.0, 310.0, 720.0), 90)
        assertEquals(10.0, box.left, 0.001)
        assertEquals(720.0, box.top, 0.001)
        assertEquals(90, box.rotate)
    }

    @Test
    fun aRotatedSegmentIsAsWideAsItsBoxIsTall() {
        val upright = flowSegmentScale(PdfRect(0.0, 0.0, 300.0, 700.0), 0, 900)
        assertEquals(3.0, upright, 0.001)
        val turned = flowSegmentScale(PdfRect(0.0, 0.0, 300.0, 700.0), 90, 700)
        assertEquals(1.0, turned, 0.001)
    }

    @Test
    fun aDegenerateSegmentDoesNotBlowUpTheScale() {
        assertEquals(1.0, flowSegmentScale(PdfRect(0.0, 0.0, 0.0, 700.0), 0, 900), 0.001)
        assertEquals(1.0, flowSegmentScale(PdfRect(0.0, 0.0, 300.0, 700.0), 0, 0), 0.001)
    }

    @Test
    fun theScaleIsClampedSoATinyBoxStaysRenderable() {
        val huge = flowSegmentScale(PdfRect(0.0, 0.0, 2.0, 2.0), 0, 4000)
        assertTrue(huge <= 6.0)
    }

    // --------------------------------------------------------------- minimap

    @Test
    fun theMinimapPutsTheTopLeftQuarterInTheTopLeft() {
        val rect = minimapRect(PdfRect(0.0, 400.0, 300.0, 800.0), page, 0)
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(0.5f, rect.right, 0.001f)
        assertEquals(0.5f, rect.bottom, 0.001f)
    }

    @Test
    fun theMinimapFollowsAQuarterTurn() {
        // A 90° clockwise turn puts the user-space top-left region top-right.
        val rect = minimapRect(PdfRect(0.0, 400.0, 300.0, 800.0), page, 90)
        assertEquals(0.5f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(1f, rect.right, 0.001f)
        assertEquals(0.5f, rect.bottom, 0.001f)
    }

    @Test
    fun theMinimapHandlesACropBoxThatDoesNotStartAtTheOrigin() {
        val crop = PdfRect(50.0, 50.0, 545.0, 792.0)
        val rect = minimapRect(crop, crop, 0)
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(1f, rect.right, 0.001f)
        assertEquals(1f, rect.bottom, 0.001f)
    }

    @Test
    fun theMinimapClampsASegmentOutsideThePage() {
        val rect = minimapRect(PdfRect(-50.0, -50.0, 650.0, 850.0), page, 0)
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(1f, rect.right, 0.001f)
        assertEquals(1f, rect.bottom, 0.001f)
    }

    @Test
    fun aPageWithNoAreaFillsTheMinimap() {
        val rect = minimapRect(PdfRect(0.0, 0.0, 10.0, 10.0), PdfRect(0.0, 0.0, 0.0, 0.0), 0)
        assertEquals(FlowMiniRect(0f, 0f, 1f, 1f), rect)
    }

    // ------------------------------------------------------------- corrections

    @Test
    fun correctionsRoundTripThroughTheSettingsString() {
        val corrections = mapOf(
            2 to listOf(PdfRect(10.0, 20.0, 300.0, 700.0), PdfRect(10.0, 0.0, 300.0, 19.0)),
            0 to listOf(PdfRect(0.0, 0.0, 600.0, 800.0))
        )
        val decoded = FlowCorrectionCodec.decode(FlowCorrectionCodec.encode(corrections))
        assertEquals(setOf(0, 2), decoded.keys)
        assertEquals(1, decoded.getValue(0).size)
        assertEquals(2, decoded.getValue(2).size)
        assertEquals(20.0, decoded.getValue(2)[0].bottom, 0.001)
        assertEquals(700.0, decoded.getValue(2)[0].top, 0.001)
    }

    @Test
    fun correctionsAreWrittenInPageOrder() {
        val encoded = FlowCorrectionCodec.encode(
            mapOf(
                5 to listOf(PdfRect(0.0, 0.0, 10.0, 10.0)),
                1 to listOf(PdfRect(0.0, 0.0, 10.0, 10.0))
            )
        )
        assertTrue(encoded.indexOf("1:") < encoded.indexOf("5:"))
    }

    @Test
    fun anEmptyCorrectionSetEncodesToNothing() {
        assertEquals("", FlowCorrectionCodec.encode(emptyMap()))
        assertTrue(FlowCorrectionCodec.decode("").isEmpty())
        assertTrue(FlowCorrectionCodec.decode(null).isEmpty())
    }

    @Test
    fun aMalformedCorrectionIsDroppedNotThrown() {
        val cases = listOf(
            "x:1,2,3,4",
            "0",
            "0:",
            "0:1,2,3",
            "0:1,2,3,4,5",
            "0:1,2,3,nope",
            "-3:0,0,10,10",
            "0:0,0,0,0",
            "0:NaN,0,10,10",
            "|||",
            ":",
            "0:;"
        )
        for (raw in cases) {
            assertTrue("'$raw' should decode to nothing", FlowCorrectionCodec.decode(raw).isEmpty())
        }
    }

    @Test
    fun aGoodBoxSurvivesNextToABadOne() {
        val decoded = FlowCorrectionCodec.decode("0:0,0,0,0;10,20,300,700")
        assertEquals(1, decoded.getValue(0).size)
        assertEquals(300.0, decoded.getValue(0)[0].right, 0.001)
    }

    @Test
    fun anEmptyCorrectionLeavesTheDetectedPageAlone() {
        val detected = flowPage(segment(0, PdfRect(0.0, 0.0, 600.0, 800.0)))
        val same = applyFlowCorrection(detected, null)
        assertEquals(1, same.flowSegments.size)
        assertEquals(0.9, same.confidence, 0.001)
    }

    @Test
    fun aSavedCorrectionReplacesTheSegmentsAndKeepsTheRunningHead() {
        val detected = FlowPage(
            pageIndex = 0,
            page = page,
            rotate = 0,
            segments = listOf(
                FlowSegment(0, FlowSegmentKind.HEADER, PdfRect(280.0, 776.0, 330.0, 788.0), 0, 0),
                FlowSegment(0, FlowSegmentKind.TEXT, PdfRect(0.0, 0.0, 290.0, 700.0), 0, 1),
                FlowSegment(0, FlowSegmentKind.TEXT, PdfRect(310.0, 0.0, 600.0, 700.0), 1, 2)
            ),
            confidence = 0.8,
            fallback = false,
            scanned = false,
            warnings = emptyList()
        )
        val corrected = applyFlowCorrection(detected, listOf(PdfRect(0.0, 0.0, 600.0, 700.0)))
        assertEquals(1, corrected.flowSegments.size)
        assertEquals(600.0, corrected.flowSegments[0].bounds.right, 0.001)
        assertEquals(1, corrected.segments.count { it.kind == FlowSegmentKind.HEADER })
    }

    // ----------------------------------------------------------------- labels

    @Test
    fun theHeaderCountsTheSegmentsOfThePage() {
        val text = describeFlow(2, 20, order = 1, segmentCount = 4, kind = FlowSegmentKind.TEXT)
        assertEquals("Page 3 of 20 · text 2 of 4", text)
    }

    @Test
    fun theHeaderClampsANonsenseOrder() {
        val text = describeFlow(0, 1, order = 7, segmentCount = 2, kind = FlowSegmentKind.FIGURE)
        assertEquals("Page 1 of 1 · figure 2 of 2", text)
    }

    @Test
    fun theHeaderStillReadsWhenThePageCountIsUnknown() {
        val text = describeFlow(4, 0, order = 0, segmentCount = 1, kind = FlowSegmentKind.TABLE)
        assertEquals("Page 5 of 5 · table 1 of 1", text)
    }

    @Test
    fun kindLabelsArePlainWords() {
        assertEquals("Text", flowKindLabel(FlowSegmentKind.TEXT))
        assertEquals("Figure", flowKindLabel(FlowSegmentKind.FIGURE))
        assertEquals("Table", flowKindLabel(FlowSegmentKind.TABLE))
        assertEquals("Header", flowKindLabel(FlowSegmentKind.HEADER))
        assertEquals("Footer", flowKindLabel(FlowSegmentKind.FOOTER))
    }

    @Test
    fun theSettingsLineSaysWhatTheScreenWillDo() {
        val settings = ColumnFlowSettings.DEFAULT
        assertEquals("two columns together, animated", describeFlowSettings(settings, 900f))
        assertEquals("one column at a time, animated", describeFlowSettings(settings, 400f))
        assertEquals(
            "one column at a time",
            describeFlowSettings(settings.copy(animate = false), 400f)
        )
    }

    // ------------------------------------------------------------------- keys

    @Test
    fun aSegmentKeySeparatesTheFullPageView() {
        assertEquals("3:2:900", flowSegmentKey(3, 2, 900))
        assertEquals("3:whole:900", flowSegmentKey(3, 2, 900, fullPage = true))
        assertTrue(flowSegmentKey(3, 2, 900) != flowSegmentKey(3, 2, 901))
    }

    @Test
    fun theRenderWidthIsCapped() {
        assertEquals(1200, flowSegmentWidthPx(400f, 3f, maxPixels = 4000))
        assertEquals(900, flowSegmentWidthPx(400f, 3f, maxPixels = 900))
        assertEquals(1, flowSegmentWidthPx(0f, 3f, maxPixels = 4000))
    }
}
