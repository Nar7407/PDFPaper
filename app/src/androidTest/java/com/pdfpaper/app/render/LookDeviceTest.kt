package com.pdfpaper.app.render

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
import com.pdfpaper.app.data.DocumentFingerprints
import com.pdfpaper.app.data.DocumentLookStore
import com.pdfpaper.app.ui.viewer.ViewerViewModel
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.ColorTransform
import com.pdfpaper.engine.render.PageFilter
import com.pdfpaper.engine.render.PageTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * T62 on-device acceptance, measured on real rendered pixels:
 *  - every filter paints what it promises (sepia is warm, inverted monochrome
 *    is a dark tinted page with light lines, dark/true black are T32 papers);
 *  - the brightness/contrast/gamma sliders move the page;
 *  - a saved look survives an app restart and is applied before the first frame;
 *  - the three acceptance documents (yellow scan, blueprint, textbook) reopen
 *    with their own saved filter;
 *  - the file on disk is byte-identical after all of it.
 */
@RunWith(AndroidJUnit4::class)
class LookDeviceTest {

    private val context: Application =
        ApplicationProvider.getApplicationContext<Application>()

    private val lookStore by lazy { DocumentLookStore(context) }

    @After
    fun resetDefaults() {
        AppSettings.setDefaultColorTransform(context, null)
        AppSettings.setPageTheme(context, PageTheme(mode = com.pdfpaper.engine.render.PageThemeMode.OFF))
        AppSettings.setPageThemeFollowsSystem(context, false)
    }

    // ------------------------------------------------------------- fixtures

    private fun write(name: String, bytes: ByteArray): File {
        val file = File(context.cacheDir, "$name-${System.nanoTime()}.pdf")
        file.writeBytes(bytes)
        return file
    }

    private fun textPage(title: String = "Look acceptance"): File {
        val builder = PdfBuilder(PageSize.A4)
        val page = builder.addPage()
        page.drawText(title, 60.0, page.height - 80.0, 20.0)
        var y = page.height - 130.0
        while (y > 80.0) {
            page.drawText("The quick brown fox jumps over the lazy dog", 60.0, y, 12.0)
            y -= 20.0
        }
        return write("look-text", builder.build())
    }

    /** A page that is one warm image: what a scan of an old book looks like. */
    private fun yellowScan(): File {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFF5EECD.toInt())
        // A few dark "text" strokes so the page is not only paper.
        for (y in 12 until size - 12 step 9) {
            for (x in 10 until size - 10) bitmap.setPixel(x, y, 0xFF332A22.toInt())
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        val image = EditImages.decode(out.toByteArray(), AndroidRasterDecoder).getOrThrow()
        val builder = PdfBuilder(PageSize.A4)
        val page = builder.addPage()
        page.drawImage(image, 0.0, 0.0, page.width, page.height)
        return write("look-scan", builder.build())
    }

    /** White paper with long rules: the geometry of a blueprint/CAD sheet. */
    private fun lineDrawing(): File {
        val builder = PdfBuilder(PageSize.A4)
        val page = builder.addPage()
        for (y in listOf(120.0, 400.0, 680.0)) {
            page.drawRect(40.0, y, page.width - 80.0, 4.0, fill = PdfColor.BLACK)
        }
        for (x in listOf(120.0, 300.0, 480.0)) {
            page.drawRect(x, 60.0, 4.0, page.height - 120.0, fill = PdfColor.BLACK)
        }
        return write("look-drawing", builder.build())
    }

    /** A textbook-like page: text above a large mid-grey figure block. */
    private fun textbook(): File {
        val builder = PdfBuilder(PageSize.A4)
        val page = builder.addPage()
        page.drawText("Chapter 4  Energy", 60.0, page.height - 80.0, 20.0)
        // The builder's y runs from the top of the page, so this is the bottom
        // band of the page (and the sample point below is inside it).
        page.drawRect(
            0.0,
            page.height * 0.62,
            page.width,
            page.height * 0.38,
            fill = PdfColor(0.55, 0.55, 0.55)
        )
        var y = page.height - 130.0
        while (y > 520.0) {
            page.drawText("Energy is conserved in a closed system.", 60.0, y, 12.0)
            y -= 20.0
        }
        return write("look-book", builder.build())
    }

    private fun render(
        file: File,
        transform: ColorTransform,
        scale: Double = 1.5
    ): Bitmap = PdfDocument.open(FileSource(file)).getOrThrow().use { document ->
        PageBitmapRenderer.render(
            document.pages().first(),
            scale,
            theme = PageTheme.OFF,
            transform = transform
        ).bitmap
    }

    private fun corner(bitmap: Bitmap, x: Int = 3, y: Int = 3): Int = bitmap.getPixel(x, y)

    /** One pixel deep inside the textbook's grey block (the bottom band). */
    private fun greyBlock(file: File, transform: ColorTransform): Int {
        val bitmap = render(file, transform)
        return bitmap.getPixel(bitmap.width / 2, (bitmap.height * 0.85).toInt())
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

    private fun open(viewModel: ViewerViewModel, file: File) {
        viewModel.load(file.absolutePath, file.name)
        waitUntil("the document to open") { !viewModel.state.value.isLoading }
        assertEquals(null, viewModel.state.value.error)
    }

    // --------------------------------------------------------------- pixels

    @Test
    fun everyFilterPaintsWhatItSays() {
        val file = textPage()

        val plain = render(file, ColorTransform.NONE)
        assertEquals(Color.WHITE, corner(plain))

        val sepia = render(file, ColorTransform(filter = PageFilter.SEPIA))
        val warm = corner(sepia)
        val warmR = Color.red(warm)
        val warmG = Color.green(warm)
        val warmB = Color.blue(warm)
        assertTrue("sepia paper is not warm: $warmR/$warmG/$warmB", warmR >= warmG && warmG > warmB)
        assertTrue("sepia paper is not cream: $warm", warmR - warmB > 20)

        val cold = render(file, ColorTransform(filter = PageFilter.SEPIA, warmth = 0.0))
        assertTrue(
            "warmth must change the paper",
            corner(sepia) and 0xFFFFFF != corner(cold) and 0xFFFFFF
        )

        assertEquals(0xFF121212.toInt(), corner(render(file, ColorTransform(filter = PageFilter.DARK))))
        assertEquals(Color.BLACK, corner(render(file, ColorTransform(filter = PageFilter.TRUE_BLACK))))

        val inverted = render(file, ColorTransform(filter = PageFilter.INVERTED_MONO))
        val paper = corner(inverted)
        assertTrue(
            "the blueprint paper should be a dark blue: ${Color.red(paper)}/" +
                "${Color.green(paper)}/${Color.blue(paper)}",
            Color.blue(paper) > Color.red(paper) && Color.blue(paper) < 0x40
        )
        // The text that was black is now the light tint.
        var brightest = 0
        var y = 0
        while (y < inverted.height) {
            var x = 0
            while (x < inverted.width) {
                val p = inverted.getPixel(x, y)
                brightest = maxOf(brightest, Color.blue(p))
                x += 3
            }
            y += 3
        }
        assertTrue("no light ink on the inverted page: $brightest", brightest > 0xC0)

        // High contrast darkens the grey figure block of a textbook page.
        val book = textbook()
        val normalBox = greyBlock(book, ColorTransform.NONE)
        val contrastBox = greyBlock(book, ColorTransform(filter = PageFilter.HIGH_CONTRAST))
        // Mid grey sits just above the pivot, so a stronger curve pushes it
        // further away from the middle (gamma 2 lowers it instead).
        assertTrue(
            "high contrast should spread the grey block: ${Color.red(normalBox)} -> ${Color.red(contrastBox)}",
            Color.red(contrastBox) > Color.red(normalBox) + 10
        )
        assertNotNull(plain)
    }

    @Test
    fun theSlidersMoveThePage() {
        // The textbook's 55 % grey figure box is a mid tone, so every slider
        // has room to move it (paper is already white and ink already black).
        val book = textbook()
        fun box(transform: ColorTransform): Int = greyBlock(book, transform)
        val plain = Color.red(box(ColorTransform.NONE))
        assertTrue("the fixture should have a mid-grey box: $plain", plain in 120..160)

        val brighter = Color.red(box(ColorTransform(brightness = 0.2)))
        assertTrue("brightness should lift the box: $plain -> $brighter", brighter > plain + 25)

        val darker = Color.red(box(ColorTransform(brightness = -0.2)))
        assertTrue("negative brightness should darken it: $plain -> $darker", darker < plain - 25)

        val gamma = Color.red(box(ColorTransform(gamma = 2.0)))
        assertTrue("gamma 2 should darken the mid tone: $plain -> $gamma", gamma < plain - 25)

        // Contrast pivots around mid grey; a 55 % box moves up, not down.
        val contrast = Color.red(box(ColorTransform(contrast = 2.0)))
        assertTrue("contrast should spread the mid tone: $plain -> $contrast", contrast > plain + 10)
    }

    // ----------------------------------------------------------- persistence

    @Test
    fun aSavedLookIsAppliedBeforeTheFirstFrameAfterARestart() {
        val file = textPage()
        val (store, first) = newModel()
        try {
            open(first, file)
            first.applyLook(ColorTransform(filter = PageFilter.SEPIA, warmth = 0.8))
            waitUntil("the sepia look") { first.look.value.filter == PageFilter.SEPIA }
            assertTrue(first.lookFromDocument.value)
        } finally {
            store.clear()
        }

        // A new view model with no state of its own is what an app restart is.
        val (secondStore, second) = newModel()
        try {
            open(second, file)
            // The look is already resolved when the document reports it is
            // loaded - there is no white frame to correct afterwards.
            assertEquals(PageFilter.SEPIA, second.look.value.filter)
            assertEquals(0.8, second.look.value.warmth, 1.0e-6)
            assertTrue(second.lookFromDocument.value)

            second.onPageVisible(0, 480)
            waitUntil("the first page") { second.bitmaps.value[0] != null }
            val bitmap = second.bitmaps.value.getValue(0)
            val pixel = bitmap.getPixel(3, 3)
            assertTrue(
                "the reopened page should be sepia at once: $pixel",
                Color.red(pixel) - Color.blue(pixel) > 15
            )

            // "Reset to default" forgets it again.
            second.resetLook()
            waitUntil("the reset") { second.look.value.filter != PageFilter.SEPIA }
            assertTrue(!second.lookFromDocument.value)
        } finally {
            secondStore.clear()
        }
    }

    @Test
    fun theThreeAcceptanceDocumentsReopenWithTheirSavedLook() {
        val cases = listOf(
            Triple(yellowScan(), PageFilter.SEPIA, "the yellow scan"),
            Triple(lineDrawing(), PageFilter.INVERTED_MONO, "the blueprint"),
            Triple(textbook(), PageFilter.HIGH_CONTRAST, "the textbook")
        )
        for ((file, filter, name) in cases) {
            // First open: this is where the reader chooses the filter.
            val (store, first) = newModel()
            try {
                open(first, file)
                first.applyLook(ColorTransform(filter = filter))
                waitUntil("the $name look") { first.look.value.filter == filter }
            } finally {
                store.clear()
            }

            // Restart.
            val (reopenedStore, reopened) = newModel()
            try {
                open(reopened, file)
                assertEquals("$name did not keep its filter", filter, reopened.look.value.filter)
                reopened.onPageVisible(0, 480)
                waitUntil("the $name page") { reopened.bitmaps.value[0] != null }
                val corner = reopened.bitmaps.value.getValue(0).getPixel(3, 3)
                when (filter) {
                    PageFilter.SEPIA -> assertTrue(
                        "$name should be warm, was $corner",
                        Color.red(corner) > Color.blue(corner)
                    )
                    PageFilter.INVERTED_MONO -> assertTrue(
                        "$name should be dark, was $corner",
                        Color.blue(corner) < 0x60
                    )
                    PageFilter.HIGH_CONTRAST -> assertTrue(
                        "$name should be light paper, was $corner",
                        Color.red(corner) > 0xE0
                    )
                    else -> Unit
                }
            } finally {
                reopenedStore.clear()
            }
        }
    }

    @Test
    fun theFileOnDiskIsNeverModified() {
        val file = textPage()
        val before = file.readBytes()
        val fingerprint = DocumentFingerprints.of(context, file.absolutePath)
        assertNotNull(fingerprint)
        try {
            val (store, viewModel) = newModel()
            try {
                open(viewModel, file)
                for (filter in PageFilter.entries) {
                    viewModel.applyLook(
                        ColorTransform(
                            filter = filter,
                            warmth = 0.9,
                            brightness = 0.1,
                            contrast = 1.3,
                            gamma = 0.9
                        )
                    )
                    viewModel.onPageVisible(0, 360)
                    waitUntil("the ${filter.name} render") {
                        viewModel.look.value.filter == filter &&
                            viewModel.bitmaps.value[0] != null
                    }
                }
            } finally {
                store.clear()
            }
            val after = file.readBytes()
            assertEquals("the PDF bytes changed", before.size, after.size)
            assertTrue("the PDF bytes changed", before.contentEquals(after))
            assertEquals(file.length(), before.size.toLong())
        } finally {
            lookStore.forget(fingerprint)
        }
    }

    // ------------------------------------------------------------ suggestion

    @Test
    fun theSuggestionOffersSepiaAndInvertedWithoutApplyingThem() {
        val (scanStore, scan) = newModel()
        try {
            open(scan, yellowScan())
            waitUntil("the sepia suggestion") { scan.lookHint.value != null }
            val hint = scan.lookHint.value!!
            assertEquals(PageFilter.SEPIA, hint.filter)
            assertTrue(hint.message.contains("Sepia", ignoreCase = true))
            // Offered, not applied: the page is still as authored.
            assertEquals(PageFilter.NORMAL, scan.look.value.filter)

            scan.dismissLookHint()
            waitUntil("the dismissed hint") { scan.lookHint.value == null }
        } finally {
            scanStore.clear()
        }

        val (drawingStore, drawing) = newModel()
        try {
            open(drawing, lineDrawing())
            waitUntil("the inverted suggestion") { drawing.lookHint.value != null }
            assertEquals(PageFilter.INVERTED_MONO, drawing.lookHint.value!!.filter)
            drawing.applyLookHint()
            waitUntil("the applied suggestion") {
                drawing.look.value.filter == PageFilter.INVERTED_MONO
            }
        } finally {
            drawingStore.clear()
        }
    }
}
