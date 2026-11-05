package com.pdfpaper.engine.flow

import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.ClosePath
import com.pdfpaper.engine.graphics.DisplayList
import com.pdfpaper.engine.graphics.DrawCommand
import com.pdfpaper.engine.graphics.DrawRasterImage
import com.pdfpaper.engine.graphics.DrawTextRun
import com.pdfpaper.engine.graphics.FillPath
import com.pdfpaper.engine.graphics.LineTo
import com.pdfpaper.engine.graphics.MoveTo
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.graphics.PdfPath
import com.pdfpaper.engine.image.RasterImage
import com.pdfpaper.engine.text.PdfTextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T59 column auto-flow. These build display lists by hand so the detector is
 * tested without a PDF: the X-Y cut, spanning detection, header/footer removal,
 * reading order, confidence/fallback, scanned pages and cross-page linking.
 */
class ColumnFlowTest {

    private val page = PdfRect(0.0, 0.0, 612.0, 792.0)

    private fun text(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        renderMode: Int = 0,
        fontSize: Double = 10.0
    ): DrawCommand = DrawTextRun(
        PdfTextRun(
            text = "word",
            fontSize = fontSize,
            fontName = "F1",
            x0 = x0,
            y0 = y0,
            x1 = x1,
            y1 = y1,
            renderMode = renderMode
        ),
        PdfMatrix.IDENTITY
    )

    /** An image whose unit square is placed at [l],[b]..[r],[t]. */
    private fun image(l: Double, b: Double, r: Double, t: Double): DrawCommand =
        DrawRasterImage(
            image = RasterImage(2, 2, ByteArray(16)),
            matrix = PdfMatrix(r - l, 0.0, 0.0, t - b, l, b),
            fillColor = PdfColor.BLACK,
            fillAlpha = 1.0
        )

    private fun rect(l: Double, b: Double, r: Double, t: Double): DrawCommand = FillPath(
        path = PdfPath(
            listOf(
                MoveTo(l, b),
                LineTo(r, b),
                LineTo(r, t),
                LineTo(l, t),
                ClosePath
            )
        ),
        matrix = PdfMatrix.IDENTITY,
        color = PdfColor.BLACK,
        alpha = 1.0,
        evenOdd = false
    )

    private fun pageOf(vararg commands: DrawCommand): FlowPage =
        pageAt(0, *commands)

    private fun pageAt(index: Int, vararg commands: DrawCommand): FlowPage =
        ColumnFlow.fromDisplayList(
            DisplayList(commands.toList(), emptyList()),
            pageIndex = index,
            page = page,
            rotate = 0
        )

    // ------------------------------------------------------------- core cases

    @Test
    fun singleColumnPageIsOneSegment() {
        val commands = (0..14).map { line ->
            val top = 700.0 - line * 40.0
            text(50.0, top - 12.0, 560.0, top)
        }
        val result = pageOf(*commands.toTypedArray())
        assertEquals(1, result.flowSegments.size)
        assertFalse(result.fallback)
        assertTrue(result.confidence > 0.8)
        assertEquals(FlowSegmentKind.TEXT, result.flowSegments[0].kind)
    }

    @Test
    fun twoColumnPaperReadsLeftColumnThenRight() {
        val commands = ArrayList<DrawCommand>()
        // A full-width title at the top (display size, so it is not a running head).
        commands += text(50.0, 760.0, 562.0, 776.0, fontSize = 16.0)
        // Left column, then right column.
        for (line in 0..9) {
            val top = 730.0 - line * 55.0
            commands += text(50.0, top - 12.0, 280.0, top)
            commands += text(320.0, top - 12.0, 562.0, top)
        }
        val result = pageOf(*commands.toTypedArray())
        val flow = result.flowSegments
        assertEquals(3, flow.size)
        // Title first (highest), then left, then right.
        assertEquals(FlowSegmentKind.TEXT, flow[0].kind)
        assertTrue(flow[0].bounds.width > flow[1].bounds.width)
        assertTrue(flow[1].bounds.centerX < flow[2].bounds.centerX)
        assertEquals(0, flow[1].column)
        assertEquals(1, flow[2].column)
        // The columns start below the title.
        assertTrue(flow[1].bounds.top < flow[0].bounds.bottom)
    }

    @Test
    fun threeColumnNewsletterHasThreeColumnsInOrder() {
        val commands = ArrayList<DrawCommand>()
        val columns = listOf(50.0 to 220.0, 250.0 to 380.0, 410.0 to 562.0)
        for ((left, right) in columns) {
            for (line in 0..11) {
                val top = 700.0 - line * 50.0
                commands += text(left, top - 12.0, right, top)
            }
        }
        val result = pageOf(*commands.toTypedArray())
        val flow = result.flowSegments
        assertEquals(3, flow.size)
        val centers = flow.map { it.bounds.centerX }
        assertEquals(centers.sorted(), centers)
        assertEquals(listOf(0, 1, 2), flow.map { it.column })
    }

    @Test
    fun headerAndFooterAreSeparatedFromTheFlow() {
        val body = (0..12).map { line ->
            val top = 680.0 - line * 45.0
            text(60.0, top - 12.0, 550.0, top)
        }
        val commands = ArrayList<DrawCommand>()
        commands += text(280.0, 776.0, 330.0, 788.0) // running head
        commands += body
        commands += text(300.0, 20.0, 320.0, 32.0) // page number
        val result = pageOf(*commands.toTypedArray())
        assertEquals(1, result.flowSegments.size)
        val furniture = result.segments.filterNot { it.flows }
        assertEquals(2, furniture.size)
        assertEquals(
            setOf(FlowSegmentKind.HEADER, FlowSegmentKind.FOOTER),
            furniture.map { it.kind }.toSet()
        )
        assertTrue(furniture.first { it.kind == FlowSegmentKind.HEADER }.bounds.top > 700.0)
        assertTrue(furniture.first { it.kind == FlowSegmentKind.FOOTER }.bounds.bottom < 100.0)
    }

    @Test
    fun spanningFigureIsNotCutAndSitsBetweenTheColumnHalves() {
        val commands = ArrayList<DrawCommand>()
        for (line in 0..4) {
            val top = 700.0 - line * 55.0
            commands += text(50.0, top - 12.0, 280.0, top)
            commands += text(320.0, top - 12.0, 562.0, top)
        }
        // A wide figure across both columns in the middle.
        commands += image(50.0, 420.0, 562.0, 560.0)
        for (line in 0..4) {
            val top = 400.0 - line * 55.0
            commands += text(50.0, top - 12.0, 280.0, top)
            commands += text(320.0, top - 12.0, 562.0, top)
        }
        val result = pageOf(*commands.toTypedArray())
        val flow = result.flowSegments
        assertEquals(5, flow.size)
        val figureIndex = flow.indexOfFirst { it.kind == FlowSegmentKind.FIGURE }
        assertTrue(figureIndex > 0)
        // Two column segments above the figure, two below.
        assertEquals(2, flow.subList(0, figureIndex).count { it.kind == FlowSegmentKind.TEXT })
        assertEquals(2, flow.subList(figureIndex + 1, flow.size).count {
            it.kind == FlowSegmentKind.TEXT
        })
        // The figure's own rectangle is exactly the drawn picture.
        assertEquals(50.0, flow[figureIndex].bounds.left, 0.01)
        assertEquals(562.0, flow[figureIndex].bounds.right, 0.01)
    }

    @Test
    fun spanningTitleOverColumnsBecomesItsOwnSegment() {
        val commands = ArrayList<DrawCommand>()
        commands += text(50.0, 740.0, 562.0, 760.0, fontSize = 16.0)
        for (line in 0..9) {
            val top = 720.0 - line * 55.0
            commands += text(50.0, top - 12.0, 280.0, top)
            commands += text(320.0, top - 12.0, 562.0, top)
        }
        val result = pageOf(*commands.toTypedArray())
        val flow = result.flowSegments
        assertEquals(3, flow.size)
        // Left then right below the title.
        assertTrue(flow[1].bounds.left < flow[2].bounds.left)
        assertEquals(-1, flow[0].column)
    }

    // ------------------------------------------------------------- ambiguity

    @Test
    fun ambiguousPageFallsBackToASingleSegment() {
        // A full-height left column and a short trailing block on the right:
        // the corridor is real enough to cut, but the right side runs only a
        // third of the page, so the cut is not trusted.
        val commands = ArrayList<DrawCommand>()
        for (line in 0..14) {
            val top = 700.0 - line * 40.0
            commands += text(50.0, top - 12.0, 280.0, top)
        }
        commands += text(330.0, 660.0, 560.0, 672.0)
        commands += text(330.0, 610.0, 560.0, 622.0)
        commands += text(330.0, 480.0, 560.0, 492.0)
        val result = pageOf(*commands.toTypedArray())
        assertTrue("expected a fallback, got ${result.confidence}", result.fallback)
        assertEquals(1, result.flowSegments.size)
    }

    @Test
    fun sideBySideBoxesWithoutVerticalOverlapAreNotColumns() {
        // Two boxes at opposite corners: no shared vertical extent, so no gutter.
        val commands = arrayOf(
            rect(50.0, 600.0, 200.0, 700.0),
            rect(300.0, 100.0, 450.0, 200.0)
        )
        val result = pageOf(*commands)
        assertEquals(1, result.flowSegments.size)
    }

    @Test
    fun aWideWordSpaceDoesNotSplitAColumn() {
        // Two runs on the same line with a small gap: one column, not two.
        val result = pageOf(
            text(50.0, 700.0, 250.0, 712.0),
            text(262.0, 700.0, 400.0, 712.0)
        )
        assertEquals(1, result.flowSegments.size)
    }

    // ------------------------------------------------------------- scanned

    @Test
    fun scanWithOcrLayerUsesTheOcrTextForStructure() {
        val commands = ArrayList<DrawCommand>()
        // A page-sized picture: the scan itself.
        commands += image(0.0, 0.0, 612.0, 792.0)
        // The invisible OCR layer (render mode 3) carries the structure.
        for (line in 0..9) {
            val top = 700.0 - line * 55.0
            commands += text(50.0, top - 12.0, 280.0, top, renderMode = 3)
            commands += text(320.0, top - 12.0, 562.0, top, renderMode = 3)
        }
        val result = pageOf(*commands.toTypedArray())
        assertTrue(result.scanned)
        val flow = result.flowSegments
        assertEquals(2, flow.size)
        assertTrue(flow[0].bounds.centerX < flow[1].bounds.centerX)
    }

    @Test
    fun emptyPageIsOneWholePageSegment() {
        val result = pageOf()
        assertEquals(1, result.flowSegments.size)
        assertTrue(result.fallback)
    }

    // ------------------------------------------------------------- linking

    @Test
    fun segmentsLinkAcrossColumnsAndPages() {
        val commands = ArrayList<DrawCommand>()
        for (line in 0..9) {
            val top = 700.0 - line * 55.0
            commands += text(50.0, top - 12.0, 280.0, top)
            commands += text(320.0, top - 12.0, 562.0, top)
        }
        val single = pageOf(*commands.toTypedArray())
        val doc = ColumnFlowResult(
            pages = ColumnFlow.link(listOf(single, pageAt(1, *commands.toTypedArray()))),
            warnings = emptyList()
        )
        val first = doc.first
        assertNotNull(first)
        // The first segment continues to the next segment, across the gutter.
        assertNotNull(first!!.continuesAt)
        assertTrue(first.continuesAt!!.pageIndex == 0)
        // The last segment of page 0 continues onto page 1.
        val last = doc.segments(0).last()
        assertEquals(1, last.continuesAt?.pageIndex)
        assertEquals(last.ref, doc.segments(1).first().continuesFrom)
    }

    @Test
    fun advanceWalksTheWholeDocument() {
        val commands = ArrayList<DrawCommand>()
        for (line in 0..9) {
            val top = 700.0 - line * 55.0
            commands += text(50.0, top - 12.0, 280.0, top)
            commands += text(320.0, top - 12.0, 562.0, top)
        }
        val single = pageOf(*commands.toTypedArray())
        val doc = ColumnFlowResult(
            pages = listOf(single, pageAt(1, *commands.toTypedArray())),
            warnings = emptyList()
        )
        var current = doc.first
        var visited = 0
        while (current != null) {
            visited++
            val ref = current.ref
            val next = doc.advance(ref, 1) ?: break
            if (next.ref == ref) break
            current = next
        }
        assertEquals(doc.segmentCount, visited)
    }

    @Test
    fun optionsAreValidated() {
        val bad = runCatching { ColumnFlowOptions(gutterRatio = 0.9) }
        assertTrue(bad.isFailure)
    }

    // ------------------------------------------------------------- tables

    /** A thin rule: [rect] with a 1 pt thickness reads as a table rule. */
    private fun hRule(y: Double, left: Double, right: Double): DrawCommand =
        rect(left, y, right, y + 1.0)

    private fun vRule(x: Double, bottom: Double, top: Double): DrawCommand =
        rect(x, bottom, x + 1.0, top)

    @Test
    fun aRuledTableSpansTheColumnsAsOneSegment() {
        val commands = ArrayList<DrawCommand>()
        for (line in 0..3) {
            val top = 740.0 - line * 50.0
            commands += text(50.0, top - 12.0, 280.0, top)
            commands += text(320.0, top - 12.0, 562.0, top)
        }
        // A grid of rules across the full width, with text in its cells.
        commands += hRule(560.0, 50.0, 562.0)
        commands += hRule(480.0, 50.0, 562.0)
        commands += hRule(400.0, 50.0, 562.0)
        commands += vRule(50.0, 400.0, 561.0)
        commands += vRule(300.0, 400.0, 561.0)
        commands += vRule(561.0, 400.0, 561.0)
        commands += text(60.0, 500.0, 140.0, 512.0)
        commands += text(320.0, 500.0, 400.0, 512.0)
        val result = pageOf(*commands.toTypedArray())
        val tables = result.flowSegments.filter { it.kind == FlowSegmentKind.TABLE }
        assertEquals(1, tables.size)
        assertTrue("the table must span the page", tables[0].bounds.width > 400.0)
        // The table is read after the text above it.
        val index = result.flowSegments.indexOf(tables[0])
        assertTrue(index > 0)
    }

    @Test
    fun rulesWithoutAGridAreNotATable() {
        // Two horizontal rules only: a divider, not a table.
        val commands = ArrayList<DrawCommand>()
        for (line in 0..3) {
            val top = 740.0 - line * 50.0
            commands += text(50.0, top - 12.0, 280.0, top)
            commands += text(320.0, top - 12.0, 562.0, top)
        }
        commands += hRule(560.0, 50.0, 562.0)
        commands += hRule(400.0, 50.0, 562.0)
        val result = pageOf(*commands.toTypedArray())
        assertTrue(result.flowSegments.none { it.kind == FlowSegmentKind.TABLE })
    }

    // ------------------------------------------------------------- in-column

    @Test
    fun aPictureInsideAColumnKeepsTheTextInOrder() {
        val commands = ArrayList<DrawCommand>()
        // Left column: text, a column-wide picture, then more text.
        for (line in 0..2) {
            val top = 740.0 - line * 40.0
            commands += text(50.0, top - 12.0, 280.0, top)
        }
        commands += image(60.0, 450.0, 270.0, 550.0)
        for (line in 0..2) {
            val top = 420.0 - line * 40.0
            commands += text(50.0, top - 12.0, 280.0, top)
        }
        // Right column: one block.
        for (line in 0..9) {
            val top = 740.0 - line * 40.0
            commands += text(320.0, top - 12.0, 562.0, top)
        }
        val result = pageOf(*commands.toTypedArray())
        val flow = result.flowSegments
        assertEquals(4, flow.size)
        assertEquals(FlowSegmentKind.TEXT, flow[0].kind)
        assertEquals(FlowSegmentKind.FIGURE, flow[1].kind)
        assertEquals(FlowSegmentKind.TEXT, flow[2].kind)
        // The right column is read last, not between the left halves.
        assertEquals(1, flow[3].column)
        assertTrue(flow[0].bounds.top > flow[1].bounds.top)
        assertTrue(flow[2].bounds.top < flow[1].bounds.bottom)
    }

    // ------------------------------------------------------------- sidebars

    @Test
    fun aNarrowSidebarIsReadAfterTheMainColumn() {
        val commands = ArrayList<DrawCommand>()
        // A narrow sidebar on the left, a wide main column on the right.
        for (line in 0..3) {
            val top = 700.0 - line * 40.0
            commands += text(50.0, top - 10.0, 170.0, top)
        }
        for (line in 0..13) {
            val top = 720.0 - line * 40.0
            commands += text(250.0, top - 12.0, 562.0, top)
        }
        val result = pageOf(*commands.toTypedArray())
        val flow = result.flowSegments
        assertEquals(2, flow.size)
        // The main column comes first even though the sidebar is on the left.
        assertEquals(1, flow[0].column)
        assertEquals(0, flow[1].column)
        assertFalse(result.fallback)
    }

    @Test
    fun aWideShortBandSpansButAWideTallColumnDoesNot() {
        // A paper-like page: a title 89 % of the content width above two columns.
        val commands = ArrayList<DrawCommand>()
        commands += text(50.0, 748.0, 505.0, 760.0, fontSize = 16.0)
        for (line in 0..11) {
            val top = 720.0 - line * 50.0
            commands += text(50.0, top - 12.0, 280.0, top)
            commands += text(320.0, top - 12.0, 562.0, top)
        }
        val paper = ColumnFlow.fromDisplayList(
            DisplayList(commands.toList(), emptyList()), 0, page, 0, ColumnFlowOptions()
        )
        // Wide but only twelve points tall: a band, so the columns below it are
        // still found and read left then right.
        assertEquals(3, paper.flowSegments.size)
        assertEquals(-1, paper.flowSegments[0].column)
        assertTrue(paper.flowSegments[1].bounds.left < paper.flowSegments[2].bounds.left)
        assertFalse(paper.fallback)

        // The height half of the rule: a wide *tall* main column beside a narrow
        // sidebar stays a column even at a ratio below its width, because it is
        // not a band. Width alone would take it out of the gutter search and the
        // page could never be cut.
        val sidebar = ArrayList<DrawCommand>()
        for (line in 0..3) {
            val top = 700.0 - line * 40.0
            sidebar += text(50.0, top - 10.0, 170.0, top)
        }
        for (line in 0..13) {
            val top = 720.0 - line * 40.0
            sidebar += text(250.0, top - 12.0, 562.0, top)
        }
        val wide = ColumnFlow.fromDisplayList(
            DisplayList(sidebar.toList(), emptyList()), 0, page, 0,
            ColumnFlowOptions(spanningRatio = 0.5)
        )
        assertEquals(2, wide.flowSegments.size)
    }

    // ------------------------------------------------------------- corrections

    @Test
    fun aManualMergeAndSplitRewriteThePage() {
        val commands = ArrayList<DrawCommand>()
        for (line in 0..9) {
            val top = 700.0 - line * 55.0
            commands += text(50.0, top - 12.0, 280.0, top)
            commands += text(320.0, top - 12.0, 562.0, top)
        }
        val page0 = pageOf(*commands.toTypedArray())
        assertEquals(2, page0.flowSegments.size)

        val merged = ColumnFlow.mergeBoxes(page0, page0.flowSegments.map { it.ref })
        assertNotNull(merged)
        val one = ColumnFlow.withSegments(page0, listOf(merged!!))
        assertEquals(1, one.flowSegments.size)
        assertEquals(50.0, one.flowSegments[0].bounds.left, 0.01)
        assertEquals(562.0, one.flowSegments[0].bounds.right, 0.01)

        val halves = ColumnFlow.splitBox(one.flowSegments[0], one.flowSegments[0].bounds.centerY)
        assertEquals(2, halves.size)
        val two = ColumnFlow.withSegments(page0, halves)
        assertEquals(2, two.flowSegments.size)
        // The upper half is read first.
        assertTrue(two.flowSegments[0].bounds.top > two.flowSegments[1].bounds.top)
    }

    @Test
    fun withSegmentsKeepsTheRunningHead() {
        val body = (0..9).map { line ->
            val top = 680.0 - line * 45.0
            text(60.0, top - 12.0, 550.0, top)
        }
        val commands = ArrayList<DrawCommand>()
        commands += text(280.0, 776.0, 330.0, 788.0)
        commands += body
        val page0 = pageOf(*commands.toTypedArray())
        val corrected = ColumnFlow.withSegments(
            page0,
            listOf(PdfRect(60.0, 400.0, 550.0, 680.0), PdfRect(60.0, 200.0, 550.0, 390.0))
        )
        assertEquals(2, corrected.flowSegments.size)
        assertEquals(1, corrected.segments.count { it.kind == FlowSegmentKind.HEADER })
    }
}
