package com.pdfpaper.app.ui.viewer.reflow

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.render.AndroidRasterDecoder
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.viewer.ViewerScreen
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.edit.EditImages
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * T33b on-device acceptance for the reflow overlay: the viewer's one-tap entry,
 * the reflowed text on screen, the reading settings sheet and the way back to
 * page view. [ReflowDeviceTest] covers the extraction behind it.
 */
@RunWith(AndroidJUnit4::class)
class ReflowScreenDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Application =
        ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun defaults() {
        AppSettings.setReflowSettings(context, ReflowSettings.DEFAULT)
    }

    @After
    fun restore() {
        AppSettings.setReflowSettings(context, ReflowSettings.DEFAULT)
    }

    private fun write(name: String, bytes: ByteArray): File {
        val file = File(context.cacheDir, "$name-${System.nanoTime()}.pdf")
        file.writeBytes(bytes)
        return file
    }

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
        return write("reflow-screen-book", builder.build())
    }

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
        return write("reflow-screen-scan", builder.build())
    }

    private fun show(file: File, pages: Int = 3) {
        compose.setContent {
            ViewerScreen(path = file.absolutePath, displayName = file.name)
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("$pages pages", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag(UiTags.VIEWER_PAGE_SURFACE)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openReflow() {
        compose.onNodeWithTag(UiTags.VIEWER_REFLOW).performScrollTo().performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag(UiTags.REFLOW_OVERLAY).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The reading settings line appears twice while the sheet is open (top bar
     * and sheet header), so this accepts any number of matches, not exactly one.
     */
    private fun waitForText(text: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "no node contains \"$text\"",
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun theToolbarButtonOpensReflowAndBackReturnsToThePage() {
        show(book())

        openReflow()
        // The extracted paragraph is on screen, in the reader's own typeface and
        // size, with no page border around it.
        waitForText("The one paragraph")
        compose.onNodeWithText("Page 1 of 3", substring = true).assertExists()

        compose.onNodeWithTag(UiTags.REFLOW_BACK).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(UiTags.REFLOW_OVERLAY).fetchSemanticsNodes().isEmpty()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(UiTags.VIEWER_PAGE_SURFACE).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theSettingsSheetChangesTheTextAndIsRemembered() {
        show(book())
        openReflow()
        waitForText("The one paragraph")

        compose.onNodeWithTag(UiTags.REFLOW_SETTINGS).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(UiTags.REFLOW_SETTINGS_SHEET).fetchSemanticsNodes().isNotEmpty()
        }
        // The sheet describes the current choices; a font chip changes them.
        waitForText("Serif 17 sp")
        compose.onNodeWithTag(UiTags.reflowFont("mono")).performScrollTo().performClick()
        waitForText("Mono 17 sp")

        compose.onNodeWithTag(UiTags.REFLOW_ALIGN_JUSTIFIED).performScrollTo().performClick()
        waitForText("justified")
        assertEquals(ReflowFont.MONO, AppSettings.reflowSettings(context).font)
        assertEquals(ReflowAlign.JUSTIFIED, AppSettings.reflowSettings(context).align)

        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(UiTags.REFLOW_SETTINGS_SHEET).fetchSemanticsNodes().isEmpty()
        }
        // The text is still there after the sheet closes.
        waitForText("The one paragraph")
    }

    @Test
    fun aScannedPageOffersOcrInsteadOfBlankText() {
        show(scan(), pages = 1)
        openReflow()

        waitForText("This page is scanned")
        compose.onNodeWithTag(UiTags.REFLOW_SCANNED).assertExists()
        compose.onNodeWithTag(UiTags.REFLOW_OCR_BUTTON).assertExists()
        compose.onNodeWithText("Run OCR").assertExists()
        // The language is a visible choice, not a stray "Hindi" label.
        compose.onNodeWithTag(UiTags.reflowOcrLanguage("eng")).assertExists()
        compose.onNodeWithTag(UiTags.reflowOcrLanguage("hin")).assertExists()
    }

    private fun assertEquals(expected: Any?, actual: Any?) {
        org.junit.Assert.assertEquals(expected, actual)
    }

    private fun assertTrue(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }
}
