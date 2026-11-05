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
import com.pdfpaper.app.data.pdfColorOf
import com.pdfpaper.app.ui.viewer.ViewerViewModel
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.PageTheme
import com.pdfpaper.engine.render.PageThemeMode
import com.pdfpaper.engine.render.contrastRatio
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * T32/T32b on-device acceptance, measured on real rendered pixels:
 *  - a text-only page on true black has a `#000000` background and nothing
 *    brighter than the ink (no white halo around the glyphs);
 *  - a photograph keeps its own colours within tolerance;
 *  - a white "paper" rectangle a document paints itself becomes the background;
 *  - a mid-tone box is darkened enough for off-white text to stay readable;
 *  - the mode is resolved per document and a switch drops stale bitmaps.
 */
@RunWith(AndroidJUnit4::class)
class PageThemeDeviceTest {

    private val context: Application =
        ApplicationProvider.getApplicationContext<Application>()

    private var originalTheme: PageTheme? = null

    @After
    fun restoreSettings() {
        originalTheme?.let { AppSettings.setPageTheme(context, it) }
        AppSettings.setPageThemeFollowsSystem(context, false)
    }

    private fun rememberTheme() {
        if (originalTheme == null) originalTheme = AppSettings.pageTheme(context)
    }

    // ------------------------------------------------------------- fixtures

    private fun write(name: String, bytes: ByteArray): File {
        val file = File(context.cacheDir, "$name-${System.nanoTime()}.pdf")
        file.writeBytes(bytes)
        return file
    }

    private fun textPage(): File {
        val builder = PdfBuilder(PageSize.A4)
        val page = builder.addPage()
        var y = 90.0
        while (y < page.height - 80.0) {
            page.drawText(
                "The quick brown fox jumps over the lazy dog",
                60.0,
                y,
                14.0
            )
            y += 22.0
        }
        return write("theme-text", builder.build())
    }

    private fun blankPage(): File {
        val builder = PdfBuilder(PageSize.A4)
        builder.addPage()
        return write("theme-blank", builder.build())
    }

    private fun paperPage(): File {
        val builder = PdfBuilder(PageSize.A4)
        val page = builder.addPage()
        // A document that paints its own white "paper" rectangle.
        page.drawRect(0.0, 0.0, page.width, page.height, fill = PdfColor.WHITE)
        page.drawText("White paper", 60.0, 120.0, 16.0)
        return write("theme-paper", builder.build())
    }

    private fun greyBoxPage(): File {
        val builder = PdfBuilder(PageSize.A4)
        val page = builder.addPage()
        page.drawRect(60.0, 60.0, page.width - 120.0, 160.0, fill = PdfColor(0.62, 0.62, 0.62))
        page.drawText("Text on a grey box", 80.0, 140.0, 16.0)
        return write("theme-box", builder.build())
    }

    /** A page that is one opaque PNG, so the whole bitmap is photograph. */
    private fun photoPage(): File {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val colours = intArrayOf(
            Color.RED, Color.GREEN, Color.BLUE, Color.WHITE,
            Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.GRAY
        )
        for (y in 0 until size) {
            for (x in 0 until size) {
                val quadrant = (if (y < size / 2) 0 else 2) + (if (x < size / 2) 0 else 1)
                val base = colours[quadrant * 2 % colours.size]
                // A second tone inside each quadrant so interpolation is visible.
                val even = (x / 8 + y / 8) % 2 == 0
                bitmap.setPixel(x, y, if (even) base else darker(base))
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        val image = EditImages.decode(out.toByteArray(), AndroidRasterDecoder).getOrThrow()

        val builder = PdfBuilder(PageSize.A4)
        val page = builder.addPage()
        page.drawImage(image, 0.0, 0.0, page.width, page.height)
        return write("theme-photo", builder.build())
    }

    private fun darker(colour: Int): Int = Color.rgb(
        ((colour shr 16) and 0xFF) * 3 / 4,
        ((colour shr 8) and 0xFF) * 3 / 4,
        (colour and 0xFF) * 3 / 4
    )

    private fun render(file: File, theme: PageTheme, scale: Double = 1.0): RenderedPage =
        PdfDocument.open(FileSource(file)).getOrThrow().use { document ->
            PageBitmapRenderer.render(document.pages().first(), scale, theme = theme)
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

    // --------------------------------------------------------------- tests

    @Test
    fun aTextPageOnTrueBlackHasABlackBackgroundAndNoHalo() {
        val bitmap = render(textPage(), PageTheme(mode = PageThemeMode.TRUE_BLACK), 2.0).bitmap
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var exactBlack = 0
        var inkLike = 0
        var coloured = 0
        var brighterThanInk = 0
        for (pixel in pixels) {
            if (pixel == Color.BLACK) exactBlack++
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val peak = maxOf(r, g, b)
            val low = minOf(r, g, b)
            // Anti-aliased glyph edges are blends of the ink and the paper; a
            // per-pixel inversion of a coloured glyph would leave a fringe here.
            if (peak - low > 8) coloured++
            if (peak > 0xE6 + 2) brighterThanInk++
            if (low >= 0xC0) inkLike++
        }
        val total = pixels.size.toDouble()
        assertTrue(
            "only ${exactBlack / total} of the page is #000000",
            exactBlack / total >= 0.5
        )
        assertTrue("$brighterThanInk pixels are brighter than the ink", brighterThanInk == 0)
        assertTrue("$coloured pixels carry a colour fringe", coloured / total < 0.005)
        assertTrue("the page drew no ink", inkLike > 0)
        // The acceptance pixel: every background pixel is exactly #000000.
        assertEquals(Color.BLACK, bitmap.getPixel(0, 0))
        assertEquals(Color.BLACK, bitmap.getPixel(bitmap.width - 1, bitmap.height - 1))
        assertNotNull(bitmap)
    }

    @Test
    fun aPhotographKeepsItsOwnColours() {
        val file = photoPage()
        val plain = render(file, PageTheme.OFF).bitmap
        val themed = render(file, PageTheme(mode = PageThemeMode.TRUE_BLACK)).bitmap
        assertEquals(plain.width, themed.width)
        assertEquals(plain.height, themed.height)

        // The 2 px inset avoids the outer ring, where the page edge could blend
        // with the background that differs between the two renders.
        var compared = 0
        var differing = 0
        for (y in 2 until plain.height - 2) {
            for (x in 2 until plain.width - 2) {
                val a = plain.getPixel(x, y)
                val b = themed.getPixel(x, y)
                compared++
                if ((a and 0xFFFFFF) != (b and 0xFFFFFF)) differing++
            }
        }
        assertTrue("compared only $compared pixels", compared > 100_000)
        assertEquals("$differing pixels of the photograph changed", 0, differing)
    }

    @Test
    fun theDarkAndTrueBlackPapersAreTheExactColours() {
        val file = blankPage()
        assertEquals(0xFF121212.toInt(), render(file, PageTheme(mode = PageThemeMode.DARK)).bitmap.getPixel(4, 4))
        assertEquals(
            Color.BLACK,
            render(file, PageTheme(mode = PageThemeMode.TRUE_BLACK)).bitmap.getPixel(4, 4)
        )
        assertEquals(Color.WHITE, render(file, PageTheme.OFF).bitmap.getPixel(4, 4))
    }

    @Test
    fun aWhitePaperRectangleBecomesTheBackground() {
        val bitmap = render(paperPage(), PageTheme(mode = PageThemeMode.TRUE_BLACK), 1.0).bitmap
        // Inside the document's own white rectangle: the themed paper, not a slab.
        for ((x, y) in listOf(4 to 4, bitmap.width / 2 to bitmap.height / 2, 20 to bitmap.height - 20)) {
            assertEquals("pixel $x,$y", Color.BLACK, bitmap.getPixel(x, y))
        }
        assertNotNull(bitmap)
    }

    @Test
    fun textOnAColouredBoxStaysReadable() {
        val theme = PageTheme(mode = PageThemeMode.TRUE_BLACK)
        val bitmap = render(greyBoxPage(), theme, 1.0).bitmap
        // Sample the box away from the text and from its edges.
        val sample = bitmap.getPixel(bitmap.width / 2, (bitmap.height * 0.15).toInt())
        val r = (sample shr 16) and 0xFF
        val g = (sample shr 8) and 0xFF
        val b = sample and 0xFF
        val peak = maxOf(r, g, b)
        assertTrue("the box was not darkened: $peak", peak <= 0x66)
        assertTrue("the box vanished into the paper", peak >= 0x20)
        val box = pdfColorOf(sample and 0xFFFFFF)
        val ratio = contrastRatio(theme.ink, box)
        assertTrue("contrast of the ink on the box is $ratio", ratio >= 4.5)
    }

    @Test
    fun theModeIsResolvedPerDocumentAndASwitchDropsStalePages() {
        rememberTheme()
        val file = textPage()
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ViewerViewModel(context) as T
        }
        val viewModel = ViewModelProvider(store, factory)[ViewerViewModel::class.java]
        try {
            AppSettings.setAutosaveMode(context, AutosaveMode.ASK)
            AppSettings.setPageTheme(context, PageTheme(mode = PageThemeMode.TRUE_BLACK))
            AppSettings.setPageThemeFollowsSystem(context, true)

            viewModel.load(file.absolutePath, file.name)
            waitUntil("the document to open") { !viewModel.state.value.isLoading }
            assertEquals(null, viewModel.state.value.error)

            // Follow the system: a light system turns the page theme off.
            viewModel.refreshPageTheme(systemDark = false)
            assertEquals(PageThemeMode.OFF, viewModel.pageTheme.value.mode)

            viewModel.refreshPageTheme(systemDark = true)
            assertEquals(PageThemeMode.TRUE_BLACK, viewModel.pageTheme.value.mode)

            // A rendered page is dropped when the theme changes.
            viewModel.onPageVisible(0, 480)
            waitUntil("the first page") { viewModel.bitmaps.value[0] != null }
            val generation = viewModel.themeGeneration.value

            viewModel.setDocumentThemeMode(PageThemeMode.DARK)
            waitUntil("the theme switch") { viewModel.pageTheme.value.mode == PageThemeMode.DARK }
            assertTrue("the generation must advance", viewModel.themeGeneration.value > generation)
            assertEquals(null, viewModel.bitmaps.value[0])
            assertEquals(PageThemeMode.DARK, viewModel.documentThemeMode.value)

            // The document override outlives the system setting.
            viewModel.refreshPageTheme(systemDark = false)
            assertEquals(PageThemeMode.DARK, viewModel.pageTheme.value.mode)
        } finally {
            AppSettings.setDocumentThemeMode(context, file.absolutePath, null)
            store.clear()
        }
    }
}
