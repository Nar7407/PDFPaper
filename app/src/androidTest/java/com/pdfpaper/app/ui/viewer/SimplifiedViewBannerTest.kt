package com.pdfpaper.app.ui.viewer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The simplified-view banner the viewer puts above a converted office file:
 * its wording, its three actions and the "What's missing?" list.
 */
@RunWith(AndroidJUnit4::class)
class SimplifiedViewBannerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theBannerExplainsTheViewAndOffersItsActions() {
        var saved = 0
        var opened = 0
        var dismissed = 0
        composeRule.setContent {
            PDFPaperTheme {
                SimplifiedViewBanner(
                    warnings = listOf("font 'Calibri' is replaced by Helvetica"),
                    onSaveAsPdf = { saved++ },
                    onOpenInAnotherApp = { opened++ },
                    onDismiss = { dismissed++ }
                )
            }
        }

        composeRule.onNodeWithTag(UiTags.OFFICE_BANNER).assertIsDisplayed()
        composeRule
            .onNodeWithText("Simplified view: some elements may not display correctly")
            .assertIsDisplayed()

        composeRule.onNodeWithText("Save as PDF").performClick()
        composeRule.onNodeWithText("Open in another app").performClick()

        assertEquals(1, saved)
        assertEquals(1, opened)

        composeRule.onNodeWithTag(UiTags.OFFICE_BANNER_DISMISS).performClick()

        assertEquals(1, dismissed)
    }

    @Test
    fun whatsMissingListsTheConversionWarnings() {
        composeRule.setContent {
            PDFPaperTheme {
                SimplifiedViewBanner(
                    warnings = listOf("a chart is not converted"),
                    onSaveAsPdf = {},
                    onOpenInAnotherApp = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag(UiTags.OFFICE_BANNER_MISSING).performClick()

        composeRule.onNodeWithText("- a chart is not converted").assertIsDisplayed()

        composeRule.onNodeWithText("Close").performClick()

        composeRule.onNodeWithText("- a chart is not converted").assertDoesNotExist()
    }

    @Test
    fun aCleanConversionSaysNothingWasDropped() {
        composeRule.setContent {
            PDFPaperTheme {
                SimplifiedViewBanner(
                    warnings = emptyList(),
                    onSaveAsPdf = {},
                    onOpenInAnotherApp = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag(UiTags.OFFICE_BANNER_MISSING).performClick()

        composeRule.onNodeWithText("Nothing was dropped from this document.").assertIsDisplayed()
    }
}
