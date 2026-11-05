package com.pdfpaper.app.ui.annot

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.annot.AnnotationDocumentInput
import com.pdfpaper.app.annot.AnnotationKind
import com.pdfpaper.app.annot.AnnotationRepository
import com.pdfpaper.app.annot.ExtractedAnnotation
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T56: the hub screen itself — rows grouped by document, the filter chips and
 * the search box, and a tap on a row handing the viewer the document, the page
 * and the rectangle to highlight.
 *
 * The repository is the app's own (the screen builds its view model from the
 * application), so this test owns the index for its run and clears it again.
 */
@RunWith(AndroidJUnit4::class)
class AnnotationsScreenDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val repository: AnnotationRepository get() = AnnotationRepository.get(context)
    private val now = System.currentTimeMillis()

    @Before
    fun seed() = runBlocking<Unit> {
        repository.deleteAll()
        repository.store(
            AnnotationDocumentInput(
                fingerprint = "hub-budget",
                documentUri = "content://hub/budget",
                displayName = "budget.pdf",
                sizeBytes = 2_048,
                lastModified = now,
                pageCount = 4
            ),
            listOf(
                ExtractedAnnotation(
                    kind = AnnotationKind.HIGHLIGHT,
                    pageIndex = 2,
                    color = "#F5D547",
                    author = "Alice",
                    createdAt = now,
                    modifiedAt = null,
                    quotedText = "quarterly budget",
                    noteText = "check the appendix",
                    bounds = "10.0,20.0,30.0,40.0",
                    objectNumber = 12
                )
            )
        )
        repository.store(
            AnnotationDocumentInput(
                fingerprint = "hub-notes",
                documentUri = "content://hub/notes",
                displayName = "notes.pdf",
                sizeBytes = 1_024,
                lastModified = now,
                pageCount = 2
            ),
            listOf(
                ExtractedAnnotation(
                    kind = AnnotationKind.NOTE,
                    pageIndex = 0,
                    color = "#4A9BE8",
                    author = "Alice",
                    createdAt = now,
                    modifiedAt = null,
                    quotedText = null,
                    noteText = "meeting notes",
                    bounds = "40.0,50.0,60.0,70.0",
                    objectNumber = 13
                )
            )
        )
    }

    @After
    fun clear() = runBlocking { repository.deleteAll() }

    @Test
    fun rowsFiltersSearchAndTheOpenTarget() {
        val viewModel = AnnotationsViewModel(context)
        var opened: AnnotationOpenTarget? = null
        compose.setContent {
            PDFPaperTheme {
                AnnotationsScreen(onOpenAnnotation = { opened = it }, viewModel = viewModel)
            }
        }
        compose.waitUntil(15_000) { viewModel.state.value.items.size == 2 }

        // Grouped by document, with both documents' rows on screen.
        compose.onNodeWithText("budget.pdf").assertIsDisplayed()
        // A row preview joins the quoted text and the note.
        compose.onNodeWithText("quarterly budget", substring = true).assertIsDisplayed()
        compose.onNodeWithText("meeting notes").assertIsDisplayed()

        // A kind filter narrows the list without touching the index. The panel
        // itself is behind the toolbar's Filters button.
        compose.onNodeWithContentDescription("Filters").performClick()
        compose.onNodeWithTag(UiTags.ANNOT_FILTERS).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.annotKind(AnnotationKind.HIGHLIGHT.name)).performClick()
        compose.waitUntil(15_000) { viewModel.state.value.filtered.size == 1 }
        compose.onNodeWithText("meeting notes").assertDoesNotExist()
        // The clear button sits at the end of the horizontally scrolling row.
        compose.onNodeWithTag(UiTags.ANNOT_FILTER_CLEAR).performScrollTo().performClick()
        compose.waitUntil(15_000) { viewModel.state.value.filtered.size == 2 }

        // Search is debounced, so wait for the filtered state, not the keystroke.
        compose.onNodeWithTag(UiTags.ANNOT_SEARCH).performTextInput("budget")
        compose.waitUntil(15_000) { viewModel.state.value.filtered.size == 1 }
        assertEquals("quarterly budget", viewModel.state.value.filtered.single().quotedText)

        // Tapping a row asks the app to open that document on that page.
        val item = viewModel.state.value.filtered.single()
        compose.onNodeWithTag(UiTags.annotItem(item.id)).performClick()
        val target = opened
        assertNotNull("tapping a row must ask for the document to open", target)
        assertEquals("content://hub/budget", target!!.documentUri)
        assertEquals(2, target.pageIndex)
        assertEquals("10.0,20.0,30.0,40.0", target.bounds)
    }
}
