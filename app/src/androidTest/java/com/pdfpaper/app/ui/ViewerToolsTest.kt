package com.pdfpaper.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import com.pdfpaper.app.ui.viewer.ViewerToolsSheet
import com.pdfpaper.app.ui.viewer.ViewerToolsSheetContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The viewer's tools menu: the MoreVert button opens a searchable sheet, and
 * typing "rotate" narrows it to the Rotate item.
 */
@RunWith(AndroidJUnit4::class)
class ViewerToolsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun moreButtonOpensSheetAndSearchFindsRotate() {
        composeRule.setContent {
            PDFPaperTheme {
                var open by remember { mutableStateOf(false) }
                // Stand-in for the viewer top bar's MoreVert control.
                IconButton(
                    onClick = { open = true },
                    modifier = Modifier.testTag(UiTags.VIEWER_TOOLS_BUTTON)
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More tools")
                }
                if (open) {
                    ViewerToolsSheet(
                        fileName = "Report.pdf",
                        demoEngine = true,
                        onDismiss = { open = false },
                        onSelect = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag(UiTags.VIEWER_TOOLS_BUTTON).performClick()
        composeRule.onNodeWithTag(UiTags.VIEWER_TOOLS_SEARCH).assertIsDisplayed()

        composeRule.onNodeWithTag(UiTags.VIEWER_TOOLS_SEARCH).performTextInput("rotate")
        composeRule.onNodeWithTag(UiTags.viewerToolItem("rotate")).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.viewerToolItem("merge")).assertDoesNotExist()
    }

    @Test
    fun unavailableToolsAreShownAsComingSoonAndDoNotOpen() {
        var selected: String? = null
        var reported: String? = null
        composeRule.setContent {
            PDFPaperTheme {
                ViewerToolsSheetContent(
                    fileName = "Report.pdf",
                    demoEngine = false,
                    onSelect = { selected = it.id },
                    onUnavailable = { reported = it.id }
                )
            }
        }

        // Underline has no engine support, so it is listed but inert.
        composeRule.onNodeWithTag(UiTags.VIEWER_TOOLS_SEARCH).performTextInput("underline")
        composeRule.onNodeWithTag(UiTags.viewerToolItem("underline")).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.viewerToolItem("underline")).performClick()

        composeRule.runOnIdle {
            assertEquals("underline", reported)
            assertNull(selected)
        }
    }
}
