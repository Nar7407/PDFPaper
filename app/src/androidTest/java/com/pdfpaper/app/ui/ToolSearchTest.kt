package com.pdfpaper.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolSearchTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun searchFiltersByTitleAndKeyword() {
        composeRule.onNodeWithTag(UiTags.NAV_TOOLS).performClick()
        composeRule.onNodeWithTag(UiTags.TOOL_SEARCH).assertIsDisplayed()

        composeRule.onNodeWithTag(UiTags.TOOL_SEARCH).performTextInput("rot")
        composeRule.onNodeWithTag(UiTags.toolCard("rotate")).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.toolCard("merge")).assertDoesNotExist()

        composeRule.onNodeWithTag(UiTags.TOOL_SEARCH).performTextClearance()
        composeRule.onNodeWithTag(UiTags.TOOL_SEARCH).performTextInput("password")
        composeRule.onNodeWithTag(UiTags.toolCard("protect")).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.toolCard("info")).assertDoesNotExist()
    }

    @Test
    fun clearingSearchRestoresTheCatalogue() {
        composeRule.onNodeWithTag(UiTags.NAV_TOOLS).performClick()
        composeRule.onNodeWithTag(UiTags.TOOL_SEARCH).performTextInput("no such tool")
        composeRule.onNodeWithTag(UiTags.toolCard("merge")).assertDoesNotExist()

        composeRule.onNodeWithTag(UiTags.TOOL_SEARCH).performTextClearance()
        composeRule.onNodeWithTag(UiTags.TOOL_GRID)
            .performScrollToNode(hasTestTag(UiTags.toolCard("merge")))
        composeRule.onNodeWithTag(UiTags.toolCard("merge")).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.TOOL_GRID)
            .performScrollToNode(hasTestTag(UiTags.toolCard("info")))
        composeRule.onNodeWithTag(UiTags.toolCard("info")).assertIsDisplayed()
    }
}
