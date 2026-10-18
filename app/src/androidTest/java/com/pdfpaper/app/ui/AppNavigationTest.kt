package com.pdfpaper.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.MainActivity
import com.pdfpaper.app.ui.tools.ToolCatalogue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottomNavigationSwitchesBetweenHomeToolsAndRecent() {
        composeRule.onNodeWithTag(UiTags.HOME_SCREEN).assertIsDisplayed()

        composeRule.onNodeWithTag(UiTags.NAV_TOOLS).performClick()
        composeRule.onNodeWithTag(UiTags.TOOLS_SCREEN).assertIsDisplayed()

        composeRule.onNodeWithTag(UiTags.NAV_RECENT).performClick()
        composeRule.onNodeWithTag(UiTags.RECENT_SCREEN).assertIsDisplayed()

        composeRule.onNodeWithTag(UiTags.NAV_HOME).performClick()
        composeRule.onNodeWithTag(UiTags.HOME_SCREEN).assertIsDisplayed()
    }

    @Test
    fun everyToolCardOpensItsOwnScreen() {
        composeRule.onNodeWithTag(UiTags.NAV_TOOLS).performClick()
        composeRule.onNodeWithTag(UiTags.TOOLS_SCREEN).assertIsDisplayed()

        for (tool in ToolCatalogue.tools) {
            composeRule.onNodeWithTag(UiTags.TOOL_GRID)
                .performScrollToNode(hasTestTag(UiTags.toolCard(tool.id)))
            composeRule.onNodeWithTag(UiTags.toolCard(tool.id)).performClick()

            composeRule.onNodeWithTag(UiTags.TOOL_DETAIL_SCREEN).assertIsDisplayed()
            composeRule.onNodeWithTag(UiTags.TOOL_DETAIL_TITLE).assertTextEquals(tool.title)

            composeRule.onNodeWithContentDescription("Back").performClick()
            composeRule.onNodeWithTag(UiTags.TOOLS_SCREEN).assertIsDisplayed()
        }
    }
}
