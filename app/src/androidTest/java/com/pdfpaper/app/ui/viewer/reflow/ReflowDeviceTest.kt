package com.pdfpaper.app.ui.viewer.reflow

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.AutosaveMode
import com.pdfpaper.app.render.AndroidRasterDecoder
import com.pdfpaper.app.ui.viewer.ViewerViewModel
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.reflow.ReflowBlockKind
import com.pdfpaper.engine.reflow.ReflowPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * T33/T33b on-device acceptance for the reflow reading mode: the extracted
 * pages, the reading spot that survives switching modes, the column order of a
 * two-column paper, a scanned page that offers OCR instead of pretending to be
 * blank, the remembered settings, and the page cap that keeps a long book
 * inside its memory budget.
 *
 * The overlay itself is driven by [ReflowScreenDeviceTest].
 */
@RunWith(AndroidJUnit4::class)
class ReflowDeviceTest {

    private val context: Application =
        ApplicationProvider.getApplicationContext<Application>()

    @After
    fun restoreDefaults() {
        AppSettings.setReflowSettings(context, ReflowSettings.DEFAULT)
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

    /** Three pages, each with its own sentence and enough body text to size. */
    private fun book(pages: Int = 3): File {
        val builder = PdfBuilder(PageSize.A4)
        val names = listOf("one", "two", "three")
        for (index in 0 until pages) {
            val page = builder.addPage()
            val name = names.getOrElse(index) { "page" }
            page.drawText("Chapter $name", 72.0, 90.0, 20.0)
            for (row in 0 until 14) {
                page.drawText(
                    "The $name paragraph keeps running down the page",
                    72.0,
                    140.0 + row * 18.0,
                    11.0
                )
            }
        }
        return write("reflow-book", builder.build())
    }

    /** One page, two columns, each column a single unbroken paragraph. */
    private fun twoColumn(): File {
        val builder = PdfBuilder(PageSize.A4)
        val page = builder.addPage()
        for (i in 0 until 8) {
            page.drawText("Left column line number $i", 60.0, 120.0 + i * 14.0, 10.0)
            page.drawText("Right column line number $i", 340.0, 120.0 + i * 14.0, 10.0)
        }
        return write("reflow-columns", builder.build())
    }

    /** A full-page picture with no text layer: what a scan looks like. */
    private fun scan(): File {
        val bitmap = Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        for (y in 10 until 90 step 6) {
            for (x in 8 until 72) bitmap.setPixel(x, y, Color.BLACK)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        val image = EditImages.decode(out.toByteArray(), AndroidRasterDecoder).getOrThrow()

        val builder = PdfBuilder(PageSize.A4)
        val page = builder.addPage()
        page.drawImage(image, 0.0, 0.0, page.width, page.height)
        return write("reflow-scan", builder.build())
    }

    private fun open(viewModel: ViewerViewModel, file: File) {
        viewModel.load(file.absolutePath, file.name)
        waitUntil("the document to open") { !viewModel.state.value.isLoading }
        assertEquals(null, viewModel.state.value.error)
    }

    /** Opens reflow and waits until the page at [page] has been extracted. */
    private fun openReflow(viewModel: ViewerViewModel, page: Int, fraction: Float): ReflowPage {
        viewModel.openReflow(ReflowPosition(page, fraction))
        waitUntil("reflow page $page") {
            viewModel.reflow.value.active && viewModel.reflow.value.pages[page] != null
        }
        return viewModel.reflow.value.pages.getValue(page)
    }

    @Test
    fun reflowExtractsTheOpenPageAndHandsTheReadingSpotBack() {
        val file = book()
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            val page = openReflow(viewModel, page = 1, fraction = 0.4f)

            assertTrue(viewModel.reflow.value.active)
            assertEquals(1, page.index)
            assertTrue("the middle page has text", page.hasText)
            assertTrue(page.text, page.text.contains("The two paragraph"))
            assertEquals(ReflowPosition(1, 0.4f), viewModel.reflow.value.entry)
            // The heading is a heading, the body is a paragraph: the overlay can
            // size them differently.
            assertEquals(ReflowBlockKind.HEADING, page.blocks.first().kind)
            assertTrue(
                "a body block exists",
                page.blocks.any { it.kind == ReflowBlockKind.PARAGRAPH }
            )

            // The reader scrolls to the third page and leaves reflow.
            viewModel.updateReflowPosition(2, 0.75f)
            val position = viewModel.closeReflow()
            assertEquals(2, position.page)
            assertEquals(0.75f, position.fraction, 0.0001f)
            assertFalse(viewModel.reflow.value.active)
        } finally {
            store.clear()
        }
    }

    @Test
    fun everyPageIsReadOnItsOwnAndInPageOrder() {
        val file = book()
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            openReflow(viewModel, page = 0, fraction = 0f)
            viewModel.requestReflowPage(1)
            viewModel.requestReflowPage(2)
            waitUntil("all three pages") {
                (0..2).all { viewModel.reflow.value.pages[it] != null }
            }
            val pages = viewModel.reflow.value.pages
            assertTrue(pages.getValue(0).text.contains("The one paragraph"))
            assertTrue(pages.getValue(1).text.contains("The two paragraph"))
            assertTrue(pages.getValue(2).text.contains("The three paragraph"))
            // A block knows where it sits on the page, so leaving reflow can
            // land on the same paragraph rather than a pixel offset.
            val middle = pages.getValue(1)
            val block = middle.blockForFraction(0.5) ?: error("no block at half the page")
            val fraction = middle.fractionOf(block)
            assertTrue("fraction $fraction", fraction in 0.0..1.0)
            assertEquals(block, middle.blockForFraction(fraction))
        } finally {
            store.clear()
        }
    }

    @Test
    fun twoColumnPageReadsDownOneColumnThenTheOther() {
        val file = twoColumn()
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            val page = openReflow(viewModel, page = 0, fraction = 0f)

            assertFalse(page.scanned)
            assertTrue(page.blocks.size >= 2)
            val text = page.text
            for (i in 0 until 7) {
                val left = text.indexOf("Left column line number $i")
                val right = text.indexOf("Right column line number $i")
                assertTrue("line $i: left $left right $right", left in 0 until right)
            }
            // The whole left column comes before the whole right column.
            val lastLeft = text.indexOf("Left column line number 7")
            val firstRight = text.indexOf("Right column line number 0")
            assertTrue("left column $lastLeft must end before $firstRight", lastLeft < firstRight)
        } finally {
            store.clear()
        }
    }

    @Test
    fun scannedPageIsReportedForOcrInsteadOfLookingBlank() {
        val file = scan()
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            val page = openReflow(viewModel, page = 0, fraction = 0f)

            assertTrue("a page-sized picture is a scan", page.scanned)
            assertTrue(page.needsOcr)
            assertFalse(page.isBlank)
            assertTrue(page.blocks.isEmpty())
            assertFalse(page.hasText)
        } finally {
            store.clear()
        }
    }

    @Test
    fun settingsAreAppliedRememberedAndReextractWhenTheyChangeContent() {
        val file = book()
        val (store, viewModel) = newModel()
        val chosen = ReflowSettings(
            fontSizeSp = 24f,
            lineSpacing = 1.8f,
            paragraphSpacingDp = 8f,
            marginDp = 32f,
            align = ReflowAlign.JUSTIFIED,
            font = ReflowFont.MONO
        )
        try {
            open(viewModel, file)
            openReflow(viewModel, page = 0, fraction = 0f)

            // A layout-only change keeps the extracted pages.
            viewModel.setReflowSettings(chosen)
            assertEquals(chosen, viewModel.reflow.value.settings)
            assertEquals(1, viewModel.reflow.value.pages.size)
            assertEquals(chosen, AppSettings.reflowSettings(context))

            // A content change (hiding page numbers) has to read the pages again.
            viewModel.setReflowSettings(chosen.copy(hidePageNumbers = false))
            assertTrue(viewModel.reflow.value.pages.isEmpty())
            waitUntil("the page to come back") { viewModel.reflow.value.pages[0] != null }
            assertEquals(1, viewModel.reflow.value.pages.size)
        } finally {
            store.clear()
        }

        // A fresh session sees the saved choices without any page state.
        val (secondStore, second) = newModel()
        try {
            open(second, file)
            val page = openReflow(second, page = 0, fraction = 0f)
            assertEquals(24f, second.reflow.value.settings.fontSizeSp, 0.01f)
            assertEquals(ReflowFont.MONO, second.reflow.value.settings.font)
            assertEquals(ReflowAlign.JUSTIFIED, second.reflow.value.settings.align)
            assertFalse(second.reflow.value.settings.hidePageNumbers)
            assertEquals(0, page.index)
        } finally {
            secondStore.clear()
        }
    }

    @Test
    fun farPagesAreDroppedSoALongBookStaysInsideItsBudget() {
        val file = book(pages = 200)
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            openReflow(viewModel, page = 0, fraction = 0f)

            val last = REFLOW_PAGE_CACHE + 20
            for (index in 1..last) {
                viewModel.requestReflowPage(index)
                waitUntil("page $index") { viewModel.reflow.value.pages[index] != null }
            }

            val pages = viewModel.reflow.value.pages
            assertTrue(
                "kept ${pages.size} pages, cap is $REFLOW_PAGE_CACHE",
                pages.size <= REFLOW_PAGE_CACHE
            )
            assertFalse("the first page is far behind", pages.containsKey(0))
            assertTrue("the page under the reader is kept", pages.containsKey(last))
            // The pages around the reader stay, so scrolling back a little does
            // not re-extract.
            assertTrue(pages.containsKey(last - 1))
        } finally {
            store.clear()
        }
    }

    @Test
    fun theSmallTextHintIsSuggestedOnceAndRememberedPerDocument() {
        val file = book()
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            // A page drawn at 11 pt at 0.5 px/pt is about 4 sp on a 2x screen.
            viewModel.checkReflowHint(0, 0.5)
            waitUntil("the hint") { viewModel.reflow.value.hint }
            assertFalse(viewModel.reflow.value.active)

            viewModel.dismissReflowHint()
            assertFalse(viewModel.reflow.value.hint)
            assertTrue(viewModel.reflow.value.hintDismissed)
            assertTrue(AppSettings.reflowHintDismissed(context, file.absolutePath))

            // It never comes back for this document, and opening reflow clears it.
            viewModel.checkReflowHint(0, 0.5)
            Thread.sleep(300)
            assertFalse(viewModel.reflow.value.hint)
            assertNotNull(openReflow(viewModel, page = 0, fraction = 0f))
            assertFalse(viewModel.reflow.value.hint)
        } finally {
            AppSettings.setReflowHintDismissed(context, file.absolutePath, false)
            store.clear()
        }
    }
}
