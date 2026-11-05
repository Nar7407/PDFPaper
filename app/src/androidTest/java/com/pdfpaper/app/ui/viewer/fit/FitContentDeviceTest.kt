package com.pdfpaper.app.ui.viewer.fit

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.AutosaveMode
import com.pdfpaper.app.ui.crop.CropMargins
import com.pdfpaper.app.ui.viewer.ViewerViewModel
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.fit.ContentFit
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T57 on-device acceptance: the viewer's fitted boxes, the renderplan built
 * from them, persistence per document and the guarantee that no text run of
 * the source falls outside the shown box.
 */
@RunWith(AndroidJUnit4::class)
class FitContentDeviceTest {

    private val context: Application =
        ApplicationProvider.getApplicationContext<Application>()

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

    private fun waitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    /** A small book: header/footer plus a varying body on each page. */
    private fun book(pages: Int = 4): File {
        val builder = PdfBuilder(PageSize.A4)
        for (i in 0 until pages) {
            val page = builder.addPage()
            page.drawText("Chapter 3 - Running head", 72.0, 30.0, 9.0)
            page.drawText("Page ${i + 1}", 72.0, page.height - 24.0, 9.0)
            val line = "The quick brown fox jumps over the lazy dog and keeps on running"
            // One character of difference per page: enough for the detected
            // widths to vary, small enough that every page stays an inlier and
            // shares the document box.
            for (row in 0 until 18) {
                page.drawText(line.take(20 + i), 90.0, 150.0 + row * 18.0, 11.0)
            }
        }
        val file = File(context.cacheDir, "fit-book-${System.nanoTime()}.pdf")
        file.writeBytes(builder.build())
        return file
    }

    private fun open(viewModel: ViewerViewModel, file: File) {
        viewModel.load(file.absolutePath, file.name)
        waitUntil("the document to open") { !viewModel.state.value.isLoading }
        assertEquals(null, viewModel.state.value.error)
    }

    private fun waitForFit(viewModel: ViewerViewModel, expected: FitContentMode? = null) {
        if (expected != null) {
            waitUntil("the $expected mode to be restored") {
                viewModel.fit.value.mode == expected
            }
        }
        waitUntil("the content fit") { !viewModel.fit.value.loading }
        waitUntil("the view boxes") {
            viewModel.viewBoxes.value.size == viewModel.state.value.pageCount
        }
    }

    @Test
    fun documentModeShrinksTheViewBoxAndRendersOnlyIt() {
        val file = book()
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            val full = viewModel.pageBoxes.value.first()
            viewModel.setFitMode(FitContentMode.DOCUMENT)
            waitForFit(viewModel)

            val fitted = viewModel.viewBoxes.value.first()
            assertTrue(
                "fitted width ${fitted.width} must be narrower than ${full.width}",
                fitted.width < full.width * 0.9
            )
            assertEquals(0, fitted.rotate)
            val size = viewModel.fit.value.oddSize
            assertNotNull(size)
            assertEquals(size!!.width, fitted.width, 0.01)
            // Every page shares the same size, so the zoom cannot jump.
            for (box in viewModel.viewBoxes.value) {
                assertEquals(size.width, box.width, 0.01)
                assertEquals(size.height, box.height, 0.01)
            }

            // The plan paints the box, not the page: its pixel size follows the
            // fitted aspect and its matrix maps the box to the bitmap.
            viewModel.onPageVisible(0, 480)
            waitUntil("the first fitted page") { viewModel.plans.value[0] != null }
            val plan = viewModel.plans.value.getValue(0)
            assertEquals(fitted.rotatedWidth / fitted.rotatedHeight, plan.pixelWidth.toDouble() / plan.pixelHeight, 0.02)
            val left = plan.matrix.apply(fitted.left, fitted.bottom)
            val right = plan.matrix.apply(fitted.right, fitted.top)
            assertEquals(0.0, minOf(left[0], right[0]), 1.5)
            assertEquals(0.0, minOf(left[1], right[1]), 1.5)
            assertEquals(plan.pixelWidth.toDouble(), maxOf(left[0], right[0]), 1.5)
            assertEquals(plan.pixelHeight.toDouble(), maxOf(left[1], right[1]), 1.5)
        } finally {
            store.clear()
        }
    }

    @Test
    fun noTextRunFallsOutsideTheShownBox() {
        val file = book()
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            viewModel.setFitMode(FitContentMode.DOCUMENT)
            waitForFit(viewModel)

            // The engine's own answer for the same source, checked page by page
            // against the boxes the viewer publishes.
            PdfDocument.open(FileSource(file)).getOrThrow().use { document ->
                val expected = ContentFit.compute(document).getOrThrow()
                for (index in document.pages().indices) {
                    val text = expected.pages[index].text ?: continue
                    val shown = viewModel.viewBoxes.value[index]
                    val crop = PdfRect(shown.left, shown.bottom, shown.right, shown.top)
                    assertTrue(
                        "page ${index + 1}: $crop must contain $text",
                        crop.left <= text.left + 0.01 && crop.bottom <= text.bottom + 0.01 &&
                            crop.right >= text.right - 0.01 && crop.top >= text.top - 0.01
                    )
                }
            }
        } finally {
            store.clear()
        }
    }

    @Test
    fun perPageModeAndManualMarginsAndOffRestore() {
        val file = book()
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            val full = viewModel.viewBoxes.value.toList()

            viewModel.setFitMode(FitContentMode.PAGE)
            waitForFit(viewModel)
            val pageBoxes = viewModel.viewBoxes.value.toList()
            // The body width changes from page to page, so per-page boxes do too.
            assertTrue(
                "per-page widths must differ: ${pageBoxes.map { it.width }}",
                pageBoxes.map { it.width }.distinct().size > 1
            )

            viewModel.setFitMargins(CropMargins(30.0, 40.0, 50.0, 60.0))
            waitForFit(viewModel)
            val manual = viewModel.viewBoxes.value.first()
            assertEquals(30.0, manual.left, 0.01)
            assertEquals(40.0, manual.bottom, 0.01)
            assertEquals(full[0].right - 50.0, manual.right, 0.01)
            assertEquals(full[0].top - 60.0, manual.top, 0.01)
            assertEquals(FitContentMode.MANUAL, viewModel.fit.value.mode)

            viewModel.setFitMode(FitContentMode.OFF)
            waitForFit(viewModel)
            assertEquals(FitContentMode.OFF, viewModel.fit.value.mode)
            assertEquals(full.map { it.width }, viewModel.viewBoxes.value.map { it.width })
        } finally {
            store.clear()
        }
    }

    @Test
    fun theModeIsRememberedForTheDocument() {
        val file = book()
        val (firstStore, first) = newModel()
        try {
            open(first, file)
            first.setFitMode(FitContentMode.DOCUMENT)
            waitForFit(first)
        } finally {
            firstStore.clear()
        }

        val (secondStore, second) = newModel()
        try {
            open(second, file)
            waitForFit(second, expected = FitContentMode.DOCUMENT)
            assertEquals(FitContentMode.DOCUMENT, second.fit.value.mode)
            assertTrue(second.viewBoxes.value.first().width < second.pageBoxes.value.first().width)
        } finally {
            secondStore.clear()
            AppSettings.setFitContentMode(context, file.absolutePath, FitContentMode.OFF)
        }
    }
}
