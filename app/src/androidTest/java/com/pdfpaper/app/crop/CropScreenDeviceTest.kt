package com.pdfpaper.app.crop

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.crop.CropScreen
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The crop screen on a device: the preview renders, a preset moves the box,
 * auto-crop detects the painted content, applying writes a crop and the result
 * offers a save. This is the same view model, renderer and engine path the
 * viewer entry uses.
 */
@RunWith(AndroidJUnit4::class)
class CropScreenDeviceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun document(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = PdfBuilder(PageSize.A4).apply {
            addPage().apply {
                drawRect(120.0, 220.0, 240.0, 260.0)
            }
        }.build()
        return File(context.cacheDir, "crop-screen-test.pdf").apply { writeBytes(bytes) }
    }

    @Test
    fun theCropScreenRendersDetectsAndApplies() {
        val file = document()
        composeRule.setContent {
            PDFPaperTheme {
                CropScreen(path = file.absolutePath, onBack = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithTag(UiTags.CROP_PREVIEW).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(UiTags.CROP_PREVIEW).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.CROP_COMPARISON).assertIsDisplayed()

        composeRule.onNodeWithTag(UiTags.cropPreset("half-inch")).performScrollTo().performClick()
        composeRule.onNodeWithTag(UiTags.CROP_REMOVE_HIDDEN).performScrollTo().performClick()
        composeRule.onNodeWithTag(UiTags.CROP_APPLY).performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(UiTags.CROP_RESULT).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(UiTags.CROP_RESULT).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Ready to save").assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.CROP_SAVE).assertIsDisplayed()

        // Changing the box after an apply makes that result stale; the screen
        // must not offer to save the old bytes.
        composeRule.onNodeWithTag(UiTags.cropPreset("one-inch")).performScrollTo().performClick()
        composeRule.onNodeWithTag(UiTags.CROP_RESULT).assertDoesNotExist()
    }

    @Test
    fun autoCropFillsTheBoxFromTheRenderedPage() {
        val file = document()
        composeRule.setContent {
            PDFPaperTheme {
                CropScreen(path = file.absolutePath, onBack = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithTag(UiTags.CROP_PREVIEW).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(UiTags.CROP_AUTO).performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(UiTags.CROP_AUTO_SUMMARY).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(UiTags.CROP_AUTO_SUMMARY).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.CROP_APPLY).performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(UiTags.CROP_RESULT).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(UiTags.CROP_RESULT).performScrollTo().assertIsDisplayed()
    }
}
