package com.pdfpaper.app.ui.viewer.flow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.viewer.ViewerScreen
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T59 on-device acceptance for the column-flow overlay: the viewer's entry, one
 * column at a time with the page's own look, an edge tap that steps to the next
 * column, the whole-page toggle, the settings sheet and the manual correction.
 * [FlowDeviceTest] covers the detection and the state behind it.
 */
@RunWith(AndroidJUnit4::class)
class ColumnFlowScreenDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Application =
        ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun defaults() {
        AppSettings.setFlowSettings(context, ColumnFlowSettings.DEFAULT)
    }

    @After
    fun restore() {
        AppSettings.setFlowSettings(context, ColumnFlowSettings.DEFAULT)
    }

    private fun write(name: String, bytes: ByteArray): File {
        val file = File(context.cacheDir, "$name-${System.nanoTime()}.pdf")
        file.writeBytes(bytes)
        return file
    }

    /** Two pages of two columns of body text, the shape of a journal paper. */
    private fun paper(): File {
        val builder = PdfBuilder(PageSize.A4)
        for (index in 0 until 2) {
            val page = builder.addPage()
            page.drawText("A two column page", 56.0, 90.0, 17.0)
            for (row in 0 until 22) {
                page.drawText("Left column line $row of page ${index + 1}", 60.0, 130.0 + row * 16.0, 10.0)
                page.drawText("Right column line $row of page ${index + 1}", 330.0, 130.0 + row * 16.0, 10.0)
            }
        }
        return write("flow-screen-paper", builder.build())
    }

    private fun show(file: File) {
        compose.setContent {
            ViewerScreen(path = file.absolutePath, displayName = file.name)
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("2 pages", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag(UiTags.VIEWER_PAGE_SURFACE)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openFlow() {
        compose.onNodeWithTag(UiTags.VIEWER_FLOW).performScrollTo().performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag(UiTags.FLOW_OVERLAY).fetchSemanticsNodes().isNotEmpty()
        }
        // Detection is per page and asynchronous: wait for the first segment.
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("text 1 of 2", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tapRightEdge() {
        compose.onNodeWithTag(UiTags.FLOW_OVERLAY).performTouchInput {
            click(Offset(width * 0.88f, height * 0.6f))
        }
    }

    @Test
    fun theToolbarOpensOneColumnAtATimeAndAnEdgeTapSteps() {
        show(paper())
        openFlow()

        // The reader is on the first column of page one, and the header says so.
        compose.onNodeWithText("Page 1 of 2", substring = true).assertExists()
        compose.onNodeWithTag(UiTags.flowSegment(0, 0)).assertExists()
        // The mini-map is on by default: it is the "how much is left" view.
        compose.onNodeWithTag(UiTags.FLOW_MINIMAP).assertExists()

        tapRightEdge()
        waitForText("text 2 of 2")
        compose.onNodeWithTag(UiTags.flowSegment(0, 1)).assertExists()

        // Back to the page view, on the page the reader was reading.
        compose.onNodeWithTag(UiTags.FLOW_BACK).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(UiTags.FLOW_OVERLAY).fetchSemanticsNodes().isEmpty()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(UiTags.VIEWER_PAGE_SURFACE).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theWholePageToggleShowsThePageAndComesBackToTheSegment() {
        show(paper())
        openFlow()

        compose.onNodeWithTag(UiTags.FLOW_FULL_PAGE).performClick()
        // The whole page is shown, so no single segment is drawn.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(UiTags.flowSegment(0, 0)).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(UiTags.FLOW_FULL_PAGE).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(UiTags.flowSegment(0, 0)).fetchSemanticsNodes().isNotEmpty()
        }
        waitForText("text 1 of 2")
    }

    @Test
    fun theSettingsSheetChangesTheChoicesAndRemembersThem() {
        val file = paper()
        show(file)
        openFlow()

        compose.onNodeWithTag(UiTags.FLOW_SETTINGS).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(UiTags.FLOW_SETTINGS_SHEET).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UiTags.FLOW_MINIMAP_TOGGLE).performScrollTo().performClick()
        compose.waitUntil(10_000) { !AppSettings.flowSettings(context).showMiniMap }

        // A correction is applied from the same sheet and remembered for this
        // document only.
        compose.onNodeWithTag(UiTags.FLOW_MERGE).performScrollTo().performClick()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(UiTags.FLOW_SETTINGS_SHEET).fetchSemanticsNodes().isEmpty()
        }
        waitForText("Merged two segments")
        assertEquals(1, AppSettings.flowCorrections(context, file.absolutePath).size)
        // One segment now covers both columns of page one.
        waitForText("text 1 of 1")
    }

    private fun assertEquals(expected: Any?, actual: Any?) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
