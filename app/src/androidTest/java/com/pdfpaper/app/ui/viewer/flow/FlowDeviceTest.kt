package com.pdfpaper.app.ui.viewer.flow

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.AutosaveMode
import com.pdfpaper.app.ui.viewer.ViewerViewModel
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.flow.FlowSegmentKind
import com.pdfpaper.engine.graphics.PdfColor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T59 on-device acceptance for column flow: the detected reading order of a
 * two-column page, a spanning title and figure that must not be cut, page
 * furniture that is not read, a single-column page, the deliberate fallback on
 * an ambiguous page, stepping across a page boundary, the manual correction that
 * survives a reopen, and a segment rendered at the width the screen asked for.
 *
 * The overlay itself is driven by [ColumnFlowScreenDeviceTest].
 */
@RunWith(AndroidJUnit4::class)
class FlowDeviceTest {

    private val context: Application =
        ApplicationProvider.getApplicationContext<Application>()

    @After
    fun restoreDefaults() {
        AppSettings.setFlowSettings(context, ColumnFlowSettings.DEFAULT)
    }

    private fun newModel(): Pair<ViewModelStore, ViewerViewModel> {
        AppSettings.setAutosaveMode(context, AutosaveMode.ASK)
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ViewerViewModel(context) as T
        }
        return store to ViewModelProvider(store, factory)[ViewerViewModel::class.java]
    }

    private fun waitUntil(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun write(name: String, bytes: ByteArray): File {
        val file = File(context.cacheDir, "$name-${System.nanoTime()}.pdf")
        file.writeBytes(bytes)
        return file
    }

    private fun open(viewModel: ViewerViewModel, file: File) {
        viewModel.load(file.absolutePath, file.name)
        waitUntil("the document to open") { !viewModel.state.value.isLoading }
        assertNull(viewModel.state.value.error)
    }

    /** Opens column flow and waits until the page at [page] has been detected. */
    private fun openFlow(viewModel: ViewerViewModel, page: Int, order: Int = 0) {
        viewModel.openColumnFlow(FlowPosition(page, order))
        waitUntil("column flow page $page") {
            viewModel.flow.value.active && viewModel.flow.value.pages[page] != null
        }
    }

    private fun body(
        page: com.pdfpaper.engine.create.PdfPageBuilder,
        x: Double,
        top: Double,
        lines: Int = 24
    ) {
        for (row in 0 until lines) {
            page.drawText("Body line $row of the column", x, top + row * 16.0, 10.0)
        }
    }

    /** Two columns of body text on page one, and the same again on page two. */
    private fun twoColumn(pages: Int = 1): File {
        val builder = PdfBuilder(PageSize.A4)
        for (index in 0 until pages) {
            val page = builder.addPage()
            body(page, 60.0, 130.0)
            body(page, 330.0, 130.0)
        }
        return write("flow-columns", builder.build())
    }

    /** A full-width title, two columns, then a full-width figure. */
    private fun titleAndFigure(): File {
        val builder = PdfBuilder(PageSize.A4)
        val page = builder.addPage()
        // Long enough to reach across the page's whole measure, which is what
        // "full-width title" means: 17 pt Helvetica gives about 470 pt of ink.
        page.drawText(
            "A title that runs all the way across both columns of this page",
            56.0, 90.0, 17.0
        )
        body(page, 60.0, 130.0)
        body(page, 330.0, 130.0)
        page.drawRect(
            56.0, 600.0, 480.0, 110.0,
            fill = null,
            stroke = PdfColor(0.2, 0.2, 0.6)
        )
        return write("flow-figure", builder.build())
    }

    /** One column with a running head and a page number. */
    private fun bookPage(): File {
        val builder = PdfBuilder(PageSize.A4)
        val page = builder.addPage()
        page.drawText("The Old Mill", 70.0, 42.0, 9.0)
        body(page, 70.0, 120.0)
        page.drawText("12", 290.0, 810.0, 10.0)
        return write("flow-book", builder.build())
    }

    /**
     * A full-height left column beside a short right one. The corridor between
     * them is real (ink on both sides at the same heights), but the right side
     * runs only a quarter of the page, so a cut there is a guess.
     */
    private fun ambiguous(): File {
        val builder = PdfBuilder(PageSize.A4)
        val page = builder.addPage()
        body(page, 60.0, 130.0, lines = 34)
        body(page, 330.0, 130.0, lines = 9)
        return write("flow-ambiguous", builder.build())
    }

    @Test
    fun twoColumnPageIsReadDownOneColumnThenTheOther() {
        val file = twoColumn()
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            openFlow(viewModel, page = 0)

            val page = viewModel.flow.value.page ?: error("no page")
            assertFalse(page.fallback)
            val segments = page.flowSegments
            assertEquals("one segment per column", 2, segments.size)
            assertEquals(FlowSegmentKind.TEXT, segments[0].kind)
            assertTrue(
                "the left column is read first",
                segments[0].bounds.left < segments[1].bounds.left
            )
            assertEquals(0, segments[0].column)
            assertEquals(1, segments[1].column)
            assertEquals("both columns are on this page", 2, viewModel.flow.value.segmentCount)
            assertEquals(0, viewModel.flow.value.position.order)

            // One step forward is the second column, not the next page.
            viewModel.flowStep(1)
            assertEquals(FlowPosition(0, 1), viewModel.flowPositionNow())
            assertEquals(1, viewModel.flow.value.position.order)
            // A step back returns to the first column.
            viewModel.flowStep(-1)
            assertEquals(FlowPosition(0, 0), viewModel.flowPositionNow())
        } finally {
            store.clear()
        }
    }

    @Test
    fun aSpanningTitleAndFigureAreNotCutByTheColumns() {
        val file = titleAndFigure()
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            openFlow(viewModel, page = 0)

            val page = viewModel.flow.value.page ?: error("no page")
            val segments = page.flowSegments
            assertTrue("segments: ${segments.size}", segments.size >= 4)

            val title = segments.first()
            assertEquals(FlowSegmentKind.TEXT, title.kind)
            assertTrue(
                "the title spans both columns (${title.bounds.width} pt)",
                title.bounds.width > page.page.width * 0.7
            )
            val figure = segments.firstOrNull { it.kind == FlowSegmentKind.FIGURE }
            assertNotNull("the figure is its own segment", figure)
            assertTrue(
                "the figure is shown whole",
                figure!!.bounds.width > page.page.width * 0.7
            )
            // Reading order: title, the two columns, then the figure below them.
            val order = segments.map { it.kind }
            assertEquals(FlowSegmentKind.TEXT, order[0])
            val figureIndex = segments.indexOf(figure)
            assertTrue("the figure is last", figureIndex == segments.size - 1)
            assertTrue(
                "the columns are read before the figure",
                segments.subList(1, figureIndex).all { it.kind == FlowSegmentKind.TEXT }
            )
        } finally {
            store.clear()
        }
    }

    @Test
    fun headersAndPageNumbersAreFurnitureNotReadingSegments() {
        val file = bookPage()
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            openFlow(viewModel, page = 0)

            val page = viewModel.flow.value.page ?: error("no page")
            val furniture = page.segments.filter { !it.flows }
            assertEquals("a running head and a page number", 2, furniture.size)
            assertTrue(furniture.any { it.kind == FlowSegmentKind.HEADER })
            assertTrue(furniture.any { it.kind == FlowSegmentKind.FOOTER })
            assertEquals(1, page.flowSegments.size)
            assertEquals(FlowSegmentKind.TEXT, page.flowSegments.first().kind)
            // The one segment is the body, not the whole page.
            assertTrue(
                "the body box starts below the head",
                page.flowSegments.first().bounds.top < page.page.top - 40.0
            )
        } finally {
            store.clear()
        }
    }

    @Test
    fun aSingleColumnPageIsOneSegmentFittedToItsContent() {
        val file = bookPage()
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            openFlow(viewModel, page = 0)

            val page = viewModel.flow.value.page ?: error("no page")
            assertFalse(page.fallback)
            assertEquals(1, page.flowSegments.size)
            val only = page.flowSegments.first()
            assertTrue("the body is narrower than the page", only.bounds.width < page.page.width)
            // The segment is fitted to the text (the T57 rule), so it is as wide
            // as the body lines and covers them top to bottom.
            assertTrue("but it holds the whole body", only.bounds.width > 100.0)
            assertTrue("the body is not cut", only.bounds.height > 300.0)
        } finally {
            store.clear()
        }
    }

    @Test
    fun anAmbiguousPageFallsBackToASingleBlock() {
        val file = ambiguous()
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            openFlow(viewModel, page = 0)

            val page = viewModel.flow.value.page ?: error("no page")
            assertTrue("the cut is not trusted", page.fallback)
            assertTrue("confidence ${page.confidence}", page.confidence < 0.45)
            assertEquals(1, page.flowSegments.size)
            assertTrue(viewModel.flow.value.fallback)
            assertTrue(
                "the reader is told why",
                viewModel.flow.value.message?.contains("not clear") == true
            )
            viewModel.dismissFlowMessage()
            assertNull(viewModel.flow.value.message)
        } finally {
            store.clear()
        }
    }

    @Test
    fun steppingCrossesThePageBoundaryInBothDirections() {
        val file = twoColumn(pages = 2)
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            openFlow(viewModel, page = 0)

            // Last column of page one, then the next step is page two.
            viewModel.flowStep(1)
            assertEquals(FlowPosition(0, 1), viewModel.flowPositionNow())
            viewModel.flowStep(1)
            assertEquals(FlowPosition(1, 0), viewModel.flowPositionNow())
            waitUntil("page two") { viewModel.flow.value.pages[1] != null }
            assertEquals(1, viewModel.flow.value.position.page)

            // Back from the first column of page two is the last of page one.
            viewModel.flowStep(-1)
            assertEquals(FlowPosition(0, 1), viewModel.flowPositionNow())
        } finally {
            store.clear()
        }
    }

    @Test
    fun aMergeCorrectionIsRememberedAndReappliedOnReopen() {
        val file = twoColumn()
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            openFlow(viewModel, page = 0)
            assertEquals(2, viewModel.flow.value.page?.flowSegments?.size)

            viewModel.flowMergeNext()
            val merged = viewModel.flow.value.page ?: error("no page")
            assertEquals("the two columns read as one", 1, merged.flowSegments.size)
            assertEquals("Merged two segments", viewModel.flow.value.message)
            assertTrue(AppSettings.flowCorrections(context, file.absolutePath).isNotEmpty())
            assertEquals(FlowPosition(0, 0), viewModel.flowPositionNow())
        } finally {
            store.clear()
        }

        // A fresh session reads the same document and applies the correction.
        val (secondStore, second) = newModel()
        try {
            open(second, file)
            openFlow(second, page = 0)
            assertEquals(1, second.flow.value.page?.flowSegments?.size)

            // "Use the detected order" forgets it again.
            second.flowResetPage()
            waitUntil("the detected order") {
                second.flow.value.pages[0]?.flowSegments?.size == 2
            }
            assertTrue(AppSettings.flowCorrections(context, file.absolutePath).isEmpty())
        } finally {
            AppSettings.setFlowCorrections(context, file.absolutePath, emptyMap())
            secondStore.clear()
        }
    }

    @Test
    fun aSegmentIsRenderedAtTheWidthTheScreenAskedFor() {
        val file = twoColumn()
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            openFlow(viewModel, page = 0)

            viewModel.requestFlowSegment(0, 0, 600, fullPage = false)
            waitUntil("the segment bitmap") {
                viewModel.flowBitmaps.value.containsKey(flowSegmentKey(0, 0, 600))
            }
            val rendered = viewModel.flowBitmaps.value.getValue(flowSegmentKey(0, 0, 600))
            assertEquals(600, rendered.pixelWidth)
            assertTrue("a real bitmap", rendered.bitmap.width > 0)

            // A column is narrow and tall, so at the same pixel width its bitmap
            // is *taller* than the page's: the height has to follow the box that
            // was asked for, not the page.
            val flowPage = viewModel.flow.value.pages[0] ?: error("no page")
            val segment = flowPage.segmentAt(0) ?: error("no segment")
            val segmentAspect = segment.bounds.height / segment.bounds.width
            assertTrue(
                "the column keeps its own aspect ($segmentAspect)",
                kotlin.math.abs(
                    rendered.bitmap.height - rendered.pixelWidth * segmentAspect
                ) <= 2.0
            )

            // The whole page is a different key, and it is the whole page.
            viewModel.requestFlowSegment(0, 0, 600, fullPage = true)
            waitUntil("the whole page bitmap") {
                viewModel.flowBitmaps.value.containsKey(flowSegmentKey(0, 0, 600, fullPage = true))
            }
            val whole = viewModel.flowBitmaps.value.getValue(
                flowSegmentKey(0, 0, 600, fullPage = true)
            )
            val pageAspect = flowPage.page.height / flowPage.page.width
            assertTrue(
                "the whole page keeps the page's aspect ($pageAspect)",
                kotlin.math.abs(
                    whole.bitmap.height - whole.pixelWidth * pageAspect
                ) <= 2.0
            )
            assertTrue(
                "the column render is not the page render",
                rendered.bitmap.height != whole.bitmap.height
            )
        } finally {
            store.clear()
        }
    }

    @Test
    fun leavingFlowReportsTheSegmentTheReaderWasOn() {
        val file = twoColumn(pages = 2)
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            openFlow(viewModel, page = 0)
            viewModel.flowStep(1)
            viewModel.flowStep(1)
            waitUntil("page two") { viewModel.flow.value.pages[1] != null }
            viewModel.flowStep(1)

            val position = viewModel.closeColumnFlow()
            assertEquals(FlowPosition(1, 1), position)
            assertFalse(viewModel.flow.value.active)
        } finally {
            store.clear()
        }
    }
}
