package com.pdfpaper.app.ui.search

import android.app.Application
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import com.pdfpaper.engine.text.RegexExamples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T65 Task 7: the regex panel is driven through the real SearchScreen, so the
 * test exercises the composed panel — the toggle, the flag chips, the example
 * row, the error caret and the library empty states — not a private copy.
 */
@RunWith(AndroidJUnit4::class)
class RegexPanelDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun panelShowsExamplesFlagsAndErrorCaret() {
        // Build the view models from the test application explicitly: the
        // default `viewModel()` factory in a ComposeRule host activity cannot
        // create an AndroidViewModel, and SearchScreen then never composes.
        val app = ApplicationProvider.getApplicationContext<Application>()
        compose.setContent {
            PDFPaperTheme {
                SearchScreen(
                    onBack = {},
                    onOpenSettings = {},
                    onOpenResult = { _, _, _, _ -> },
                    onNeedsOcr = { _, _, _ -> },
                    viewModel = SearchViewModel(app),
                    regexViewModel = RegexViewModel(app)
                )
            }
        }
        // The Regex toggle appears beside the search field.
        compose.onNodeWithTag(UiTags.REGEX_TOGGLE).performClick()
        compose.waitForIdle()
        // Every example chip exists by its label.
        for (example in RegexExamples.ALL) {
            compose.onNodeWithText(example.label).assertExists()
        }
        // The Whole word chip toggles and stays selected.
        compose.onNodeWithText("Whole word").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Whole word").assertIsSelected()
        // An invalid pattern shows the caret under the offending position.
        // Use a pattern that fails on compilation (unclosed class) - the probe
        // compiles it once; it will fail regardless of input text.
        compose.onNodeWithTag(UiTags.REGEX_PATTERN_FIELD).performTextInput("[A-Z")
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.REGEX_RUN).performClick()
        // The Run handler compiles the pattern and publishes the error; wait
        // for the caret to be composed instead of sleeping a fixed time.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(UiTags.REGEX_CARET).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UiTags.REGEX_CARET).assertExists()
    }

    @Test
    fun libraryScopeShowsTheInertFlagsNoteAndEmptyState() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        compose.setContent {
            PDFPaperTheme {
                SearchScreen(
                    onBack = {},
                    onOpenSettings = {},
                    onOpenResult = { _, _, _, _ -> },
                    onNeedsOcr = { _, _, _ -> },
                    viewModel = SearchViewModel(app),
                    regexViewModel = RegexViewModel(app)
                )
            }
        }
        compose.onNodeWithTag(UiTags.REGEX_TOGGLE).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(
            "Multiline and Across lines have no effect here: the indexed text has no line breaks."
        ).assertExists()
        // With a pattern typed but no indexed files, the library empty state
        // says so instead of a bare "No matches".
        compose.onNodeWithTag(UiTags.REGEX_PATTERN_FIELD).performTextInput("AB")
        compose.waitForIdle()
        compose.onNodeWithText("The library has no indexed files").assertExists()
    }
}
