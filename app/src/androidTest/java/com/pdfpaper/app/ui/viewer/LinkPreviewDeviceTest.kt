package com.pdfpaper.app.ui.viewer

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.AutosaveMode
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.links.LinkDestination
import com.pdfpaper.engine.links.LinkFit
import com.pdfpaper.engine.links.LinkKind
import com.pdfpaper.engine.links.LinkTarget
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.layout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * T61 on device: the app-side link index finds the reference under a tap and
 * the preview renderer crops the right target, caches it and stays bounded.
 */
@RunWith(AndroidJUnit4::class)
class LinkPreviewDeviceTest {

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

    /** A two-page paper: one citation line, one missing reference, one entry. */
    private fun paper(): File {
        val builder = PdfBuilder(PageSize.A4)
        val page = builder.addPage()
        page.drawText("Results", 72.0, 760.0, 16.0)
        page.drawText("As shown in the study, the effect is clear.", 72.0, 730.0, 11.0)
        page.drawText("[1]", 72.0, 700.0, 11.0)
        page.drawText("[9]", 72.0, 676.0, 11.0)
        val refs = builder.addPage()
        refs.drawText("References", 72.0, 760.0, 14.0)
        refs.drawText(
            "[1] Smith, J. (2019). A Study of PDF Links. Journal of Documents, 12(3), 45-58.",
            72.0,
            730.0,
            10.0
        )
        val file = File(context.cacheDir, "link-paper-${System.nanoTime()}.pdf")
        file.writeBytes(builder.build())
        return file
    }

    private fun open(viewModel: ViewerViewModel, file: File) {
        viewModel.load(file.absolutePath, file.name)
        waitUntil("the document to open") { !viewModel.state.value.isLoading }
        assertEquals(null, viewModel.state.value.error)
    }

    /**
     * The page fraction of [line]'s centre, taken from the engine's own line
     * bounds so the tap lands on the reference and not near it.
     */
    private fun fractionOf(
        file: File,
        viewModel: ViewerViewModel,
        pageIndex: Int,
        text: String
    ): Pair<Float, Float> {
        PdfDocument.open(FileSource(file)).getOrThrow().use { document ->
            val layout = document.pages()[pageIndex].layout()
            val line = layout.lines.first { it.text.trim() == text }
            val plan = viewModel.plans.value.getValue(pageIndex)
            val b = line.bounds
            val point = plan.matrix.apply((b.x0 + b.x1) / 2.0, (b.y0 + b.y1) / 2.0)
            return (point[0] / plan.pixelWidth).toFloat() to
                (point[1] / plan.pixelHeight).toFloat()
        }
    }

    @Test
    fun aCitationTapFindsItsTargetAndThePreviewIsCached() {
        val file = paper()
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            viewModel.onPageVisible(0, 900)
            waitUntil("the first page plan") { viewModel.plans.value[0] != null }
            val (x, y) = fractionOf(file, viewModel, 0, "[1]")
            waitUntil("the link index") {
                viewModel.linkAtFraction(0, x, y) != null
            }

            val link = viewModel.linkAtFraction(0, x, y)
            assertNotNull("the citation should be under the tap", link)
            assertEquals(LinkKind.CITATION, link!!.kind)
            assertEquals("[1]", link.text)
            assertEquals(1, link.targets.size)
            val target = link.targets.single()
            assertEquals(1, target.pageIndex)
            assertTrue(
                "the title should name the entry: ${target.title}",
                target.title.contains("[1]") && target.title.contains("Smith")
            )
            assertTrue(target.copyText.contains("A Study of PDF Links"))
            assertTrue(target.previewRect.height > 10.0)

            val preview = runBlocking { viewModel.linkPreview(target, 700) }
            assertNotNull("the preview should render", preview)
            assertTrue("the preview should have ink", hasInk(preview!!))
            assertTrue("the crop stays small", preview.width <= 700)
            assertSame(
                "a second card for the same target reuses the bitmap",
                preview,
                runBlocking { viewModel.linkPreview(target, 700) }
            )
        } finally {
            store.clear()
        }
    }

    @Test
    fun aReferenceWithNoTargetSaysSoAndARenderIsBounded() {
        val file = paper()
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            viewModel.onPageVisible(0, 900)
            waitUntil("the first page plan") { viewModel.plans.value[0] != null }
            val (x, y) = fractionOf(file, viewModel, 0, "[9]")
            waitUntil("the link index") {
                viewModel.linkAtFraction(0, x, y) != null
            }
            val missing = viewModel.linkAtFraction(0, x, y)
            assertNotNull(missing)
            assertTrue(missing!!.targets.isEmpty())
            assertTrue(
                "the note should be clear: ${missing.note}",
                missing.note.orEmpty().contains("not found")
            )

            // A very large request is clamped, not rendered at that width.
            val wide = LinkTarget(
                pageIndex = 0,
                destination = LinkDestination(0, LinkFit.FIT),
                title = "Page 1",
                previewRect = PdfRect(60.0, 600.0, 500.0, 760.0),
                pageFractionTop = 0.1,
                label = "Page",
                copyText = ""
            )
            val preview = runBlocking { viewModel.linkPreview(wide, 9_000) }
            assertNotNull(preview)
            assertTrue("clamped width: ${preview!!.width}", preview.width <= 1_400)

            // A target outside the document is a null result, not a crash.
            val outside = LinkTarget(
                pageIndex = 99,
                destination = LinkDestination(99, LinkFit.FIT),
                title = "Nowhere",
                previewRect = PdfRect(0.0, 0.0, 10.0, 10.0),
                pageFractionTop = 0.0,
                label = "Page",
                copyText = ""
            )
            assertEquals(null, runBlocking { viewModel.linkPreview(outside, 600) })
        } finally {
            store.clear()
        }
    }

    private fun hasInk(bitmap: android.graphics.Bitmap): Boolean {
        val stepX = (bitmap.width / 40).coerceAtLeast(1)
        val stepY = (bitmap.height / 40).coerceAtLeast(1)
        var darker = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(pixel) < 160) darker++
                x += stepX
            }
            y += stepY
        }
        return darker > 3
    }
}
