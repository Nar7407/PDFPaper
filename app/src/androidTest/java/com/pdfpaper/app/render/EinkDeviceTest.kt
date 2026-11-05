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
import com.pdfpaper.app.ui.viewer.ViewerViewModel
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.eink.EinkProfile
import com.pdfpaper.engine.eink.EinkSettings
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * T63 on-device acceptance, measured on real rendered pixels:
 *  - profile 3 is pure black and white at every threshold, and the threshold
 *    moves the amount of ink;
 *  - profile 2 dithers a grey photograph into both tones;
 *  - profile 1 keeps grey tones so small text stays readable;
 *  - a 0.2 pt hairline is still visible after the one-bit reduction;
 *  - a profile saved for the device is already in force for the first frame;
 *  - the file on disk is byte-identical after every render.
 *
 * It also writes the three profile screenshots to the app's external files
 * directory, where the acceptance run pulls them into `screenshots/`.
 */
@RunWith(AndroidJUnit4::class)
class EinkDeviceTest {

    private val context: Application =
        ApplicationProvider.getApplicationContext<Application>()

    private var original: EinkSettings? = null
    private var originalEnabled: Boolean = false

    @Before
    fun rememberSettings() {
        original = AppSettings.einkSettings(context)
        originalEnabled = AppSettings.einkEnabled(context)
    }

    @After
    fun restoreSettings() {
        original?.let { AppSettings.setEinkSettings(context, it) }
        AppSettings.setEinkEnabled(context, originalEnabled)
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
        page.drawRect(60.0, 60.0, page.width - 120.0, 140.0, fill = PdfColor(0.55, 0.55, 0.55))
        var y = 260.0
        while (y < page.height - 80.0) {
            page.drawText("The quick brown fox jumps over the lazy dog", 60.0, y, 14.0)
            y += 24.0
        }
        return write("eink-text", builder.build())
    }

    private fun hairlinePage(): File {
        val builder = PdfBuilder(PageSize.A4)
        val page = builder.addPage()
        page.drawLine(100.0, 100.0, 100.0, 740.0, width = 0.2)
        return write("eink-line", builder.build())
    }

    /** One full-page picture of a horizontal grey ramp. */
    private fun photoPage(): File {
        val width = 64
        val height = 64
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = 96 + (x * 96 / (width - 1)) // 96 .. 192, a mid-grey ramp
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        val image = EditImages.decode(out.toByteArray(), AndroidRasterDecoder).getOrThrow()
        val builder = PdfBuilder(PageSize.A4)
        val page = builder.addPage()
        page.drawImage(image, 0.0, 0.0, page.width, page.height)
        return write("eink-photo", builder.build())
    }

    private fun render(file: File, eink: EinkSettings, scale: Double = 2.0): Bitmap =
        PdfDocument.open(FileSource(file)).getOrThrow().use { document ->
            PageBitmapRenderer.render(document.pages().first(), scale, eink = eink).bitmap
        }

    private fun Int.isPureTone(): Boolean {
        val rgb = this and 0xFFFFFF
        return rgb == 0 || rgb == 0xFFFFFF
    }

    private fun countTones(bitmap: Bitmap): Pair<Int, Int> {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val black = pixels.count { (it and 0xFFFFFF) == 0 }
        val white = pixels.count { (it and 0xFFFFFF) == 0xFFFFFF }
        return black to white
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
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
    fun profileThreeIsPureBlackAndWhiteAtEveryThreshold() {
        val file = textPage()
        for (threshold in listOf(0.25, 0.5, 0.75)) {
            val bitmap = render(
                file,
                EinkSettings(profile = EinkProfile.FULL_BITONAL, threshold = threshold)
            )
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val wrong = pixels.count { !it.isPureTone() }
            assertEquals("threshold $threshold left $wrong non 1-bit pixels", 0, wrong)
            val (black, white) = countTones(bitmap)
            assertTrue("threshold $threshold drew no ink", black > 1000)
            assertTrue("threshold $threshold has no paper", white > 10_000)
        }
    }

    @Test
    fun aHigherThresholdPutsMoreInkOnThePage() {
        val file = textPage()
        val light = countTones(
            render(file, EinkSettings(profile = EinkProfile.FULL_BITONAL, threshold = 0.3))
        ).first
        val heavy = countTones(
            render(file, EinkSettings(profile = EinkProfile.FULL_BITONAL, threshold = 0.7))
        ).first
        assertTrue("threshold 0.7 must be darker: $light vs $heavy", heavy > light)
    }

    @Test
    fun profileTwoDithersAGreyPhotograph() {
        val file = photoPage()
        val bitmap = render(
            file,
            EinkSettings(profile = EinkProfile.TEXT_BITONAL, threshold = 0.5)
        )
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val wrong = pixels.count { !it.isPureTone() }
        assertEquals("profile 2 must be 1-bit, $wrong pixels were not", 0, wrong)
        val (black, white) = countTones(bitmap)
        val ratio = black.toDouble() / (black + white)
        assertTrue("the ramp was not dithered, black ratio $ratio", ratio in 0.15..0.85)
        assertTrue("the dither pattern is missing", black > 1000 && white > 1000)
    }

    @Test
    fun profileOneKeepsGreyTones() {
        val file = textPage()
        val bitmap = render(file, EinkSettings(profile = EinkProfile.GRAYSCALE))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val grey = pixels.count { pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            r == g && g == b && r in 30..225
        }
        assertTrue("the grey profile produced $grey grey pixels", grey > 1000)
        // The 55 % grey block (drawn near the top of the page; the builder uses
        // top-left coordinates) survives as a grey, not as a hard 1-bit slab.
        val sample = bitmap.getPixel(bitmap.width / 2, (bitmap.height * 0.20).toInt())
        val r = (sample shr 16) and 0xFF
        assertTrue("grey block sample $r", r in 40..220)
        assertEquals(r, (sample shr 8) and 0xFF)
        assertEquals(r, sample and 0xFF)
    }

    @Test
    fun aHairlineSurvivesTheOneBitReduction() {
        val file = hairlinePage()
        val bitmap = render(
            file,
            EinkSettings(profile = EinkProfile.FULL_BITONAL, threshold = 0.5)
        )
        // The 0.2 pt rule at page x=100 is drawn at 2x, so around x=200.
        val rows = (bitmap.height * 0.15).toInt()..(bitmap.height * 0.85).toInt()
        val darkestColumn = (198..202).maxOf { x ->
            rows.count { y -> (bitmap.getPixel(x, y) and 0xFFFFFF) == 0 }
        }
        assertTrue(
            "the hairline disappeared: best column has $darkestColumn black rows",
            darkestColumn > rows.count() * 0.9
        )
    }

    @Test
    fun theSavedProfileIsInForceBeforeTheFirstFrame() {
        AppSettings.setEinkSettings(
            context,
            EinkSettings(profile = EinkProfile.FULL_BITONAL, threshold = 0.5)
        )
        AppSettings.setEinkEnabled(context, true)
        val file = textPage()
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ViewerViewModel(context) as T
        }
        val viewModel = ViewModelProvider(store, factory)[ViewerViewModel::class.java]
        try {
            viewModel.load(file.absolutePath, file.name)
            waitUntil("the document to open") { !viewModel.state.value.isLoading }
            assertEquals(null, viewModel.state.value.error)
            // The look is resolved before the load flag falls, so the first
            // frame is already reduced.
            assertTrue("E-Ink must be active", viewModel.eink.value.active)
            assertEquals(EinkProfile.FULL_BITONAL, viewModel.eink.value.settings.profile)
            viewModel.onPageVisible(0, 480)
            waitUntil("the first page") { viewModel.bitmaps.value[0] != null }
            val bitmap = viewModel.bitmaps.value.getValue(0)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertEquals(0, pixels.count { !it.isPureTone() })
        } finally {
            store.clear()
        }
    }

    @Test
    fun theFileOnDiskIsNeverModified() {
        val file = photoPage()
        val before = sha256(file)
        render(file, EinkSettings(profile = EinkProfile.FULL_BITONAL))
        render(file, EinkSettings(profile = EinkProfile.GRAYSCALE))
        render(file, EinkSettings(profile = EinkProfile.TEXT_BITONAL))
        assertEquals(before, sha256(file))
    }

    /** Writes the acceptance screenshots; the run pulls them out of the app. */
    @Test
    fun profileScreenshotsAreWritten() {
        val dir = File(context.getExternalFilesDir(null), "eink-shots")
        assertTrue(dir.isDirectory || dir.mkdirs())
        val file = textPage()
        val shots = listOf(
            "t63-profile1-grey.png" to EinkSettings(profile = EinkProfile.GRAYSCALE),
            "t63-profile2-text.png" to EinkSettings(profile = EinkProfile.TEXT_BITONAL),
            "t63-profile3-onebit.png" to EinkSettings(profile = EinkProfile.FULL_BITONAL)
        )
        for ((name, settings) in shots) {
            val bitmap = render(file, settings, scale = 1.4)
            File(dir, name).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            assertNotNull(bitmap)
        }
        val photo = render(photoPage(), EinkSettings(profile = EinkProfile.TEXT_BITONAL), 1.4)
        File(dir, "t63-profile2-photo.png").outputStream().use { out ->
            photo.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        assertTrue(File(dir, "t63-profile1-grey.png").length() > 0)
        assertTrue(File(dir, "t63-profile3-onebit.png").length() > 0)
    }
}
