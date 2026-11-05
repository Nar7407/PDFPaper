package com.pdfpaper.app.ui.viewer.session

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.data.LastReading
import com.pdfpaper.app.data.ReadingState
import com.pdfpaper.app.data.ReadingStateStore
import com.pdfpaper.app.data.RecentStore
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.home.HomeScreen
import com.pdfpaper.app.ui.recent.RecentScreen
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T38b: the two surfaces that offer a way back into a document. Recent says
 * where the reader stopped, and Home has a one-tap "Continue reading" card for
 * the last document. Both read app data, so they are primed here the way the
 * viewer primes them.
 */
@RunWith(AndroidJUnit4::class)
class ResumeSurfacesDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Application =
        ApplicationProvider.getApplicationContext<Application>()

    private val recentUri = "content://pdfpaper.test/recent-${System.nanoTime()}"
    private val origin = "content://pdfpaper.test/home-${System.nanoTime()}"

    @After
    fun cleanUp() {
        ReadingStateStore(context).forget(origin)
    }

    @Test
    fun theRecentRowSaysWhereTheReaderStopped() {
        val store = RecentStore(context)
        store.add(recentUri, "Quarterly report.pdf")
        store.setProgress(recentUri, page = 46, pageCount = 210)

        compose.setContent {
            PDFPaperTheme {
                RecentScreen(onOpenDocument = {})
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Page 47 of 210", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            compose.onAllNodesWithTag(UiTags.RECENT_PROGRESS).fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun homeOffersToContinueReadingTheLastDocument() {
        ReadingStateStore(context).save(
            origin,
            "Session book.pdf",
            ReadingState(pageIndex = 47, pageFraction = 0.2f, pageCount = 210, zoom = 1.5f)
        )
        var continued: LastReading? = null

        compose.setContent {
            PDFPaperTheme {
                HomeScreen(
                    onOpen = {},
                    onNewPdf = {},
                    onBrowseTools = {},
                    onBrowseRecent = {},
                    onOpenTool = {},
                    onContinueReading = { continued = it }
                )
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag(UiTags.HOME_CONTINUE).fetchSemanticsNodes().isNotEmpty()
        }
        // The card names the document and the page, not just the file.
        assertTrue(
            compose.onAllNodesWithText("Page 48 of 210", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
        compose.onNodeWithTag(UiTags.HOME_CONTINUE).performClick()

        val reading = continued
        assertNotNull("the card must ask to reopen the document", reading)
        assertEquals(origin, reading?.origin)
        assertEquals(47, reading?.state?.pageIndex)
    }
}
