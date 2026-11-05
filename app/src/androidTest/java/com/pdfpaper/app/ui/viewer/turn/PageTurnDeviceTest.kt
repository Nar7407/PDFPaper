package com.pdfpaper.app.ui.viewer.turn

import android.app.Application
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.input.ViewerKeyRouter
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.viewer.ViewerScreen
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T58 on-device acceptance: edge taps turn the page, a centre tap toggles the
 * toolbar, a centre double tap does not toggle it, the volume keys reach the
 * viewer through the shared [ViewerKeyRouter], and the one-time offer appears
 * once and can enable the keys.
 */
@RunWith(AndroidJUnit4::class)
class PageTurnDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Application =
        ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun readingDefaults() {
        // Instant turns keep the test independent of animation timing.
        AppSettings.setTurnSettings(
            context,
            TurnSettings(
                volumeKeys = true,
                edgeTaps = true,
                action = TurnAction.PAGE,
                mode = TurnMode.CONTINUOUS,
                animation = TurnAnimation.INSTANT
            )
        )
        AppSettings.setTurnVolumeHintShown(context, true)
    }

    @After
    fun restoreDefaults() {
        AppSettings.setTurnSettings(context, TurnSettings())
        // Never leave the one-time offer armed for another test class.
        AppSettings.setTurnVolumeHintShown(context, true)
    }

    private fun book(pages: Int = 3): File {
        val builder = PdfBuilder(PageSize.A4)
        for (index in 0 until pages) {
            val page = builder.addPage()
            page.drawText("Page ${index + 1}", 72.0, 80.0, 24.0)
            // A dense block of lines, so a long press anywhere in the middle of
            // the page lands on a word (the selection test needs that).
            var baseline = 120.0
            while (baseline < 800.0) {
                page.drawText("Select this line of words on page ${index + 1}", 72.0, baseline, 18.0)
                baseline += 22.0
            }
        }
        val file = File(context.cacheDir, "turn-book-${System.nanoTime()}.pdf")
        file.writeBytes(builder.build())
        return file
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

    private fun hideToolbar() {
        compose.onNodeWithTag(UiTags.VIEWER_PAGE_SURFACE).performTouchInput {
            click(center)
        }
        // Only the centre waits for a possible second tap.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(UiTags.VIEWER_SHOW_TOOLBAR)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tapEdge(right: Boolean) {
        compose.onNodeWithTag(UiTags.VIEWER_PAGE_SURFACE).performTouchInput {
            click(Offset(if (right) width - 60f else 60f, height / 2f))
        }
    }

    private fun waitForPage(label: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(label).assertExists()
    }

    /** Sends a whole volume press through the router the activity uses. */
    private fun volumeKey(keyCode: Int) {
        compose.runOnUiThread {
            assertTrue(ViewerKeyRouter.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, keyCode)))
            assertTrue(ViewerKeyRouter.dispatch(KeyEvent(KeyEvent.ACTION_UP, keyCode)))
        }
    }

    @Test
    fun edgeTapsTurnAndTheCentreTogglesTheToolbar() {
        show(book())

        // The toolbar starts visible; a centre tap hides it and the visible
        // strip is the way back.
        hideToolbar()
        waitForPage("1/3")

        // The right edge goes forward, the left edge goes back.
        tapEdge(right = true)
        waitForPage("2/3")
        tapEdge(right = true)
        waitForPage("3/3")
        // Past the last page nothing moves.
        tapEdge(right = true)
        waitForPage("3/3")
        tapEdge(right = false)
        waitForPage("2/3")

        // A single centre tap brings the toolbar back.
        compose.onNodeWithTag(UiTags.VIEWER_PAGE_SURFACE).performTouchInput {
            click(center)
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(UiTags.VIEWER_SHOW_TOOLBAR)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun aCentreDoubleTapZoomsInsteadOfTogglingTheToolbar() {
        show(book())
        hideToolbar()

        // Two taps inside the double-tap window: the first must not toggle the
        // toolbar, so the strip stays hidden after both.
        compose.onNodeWithTag(UiTags.VIEWER_PAGE_SURFACE).performTouchInput {
            click(center)
            advanceEventTime(80)
            click(center)
        }
        compose.waitForIdle()
        Thread.sleep(500)
        compose.waitForIdle()
        assertTrue(
            "a double tap must not toggle the toolbar",
            compose.onAllNodesWithTag(UiTags.VIEWER_SHOW_TOOLBAR)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun theVolumeKeysReachTheViewerThroughTheRouter() {
        show(book())
        hideToolbar()
        waitForPage("1/3")

        // The viewer installed its handler; both the press and the release are
        // consumed so the system never sees a half press.
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
        compose.runOnUiThread {
            assertTrue("the viewer must claim the volume-down press", ViewerKeyRouter.dispatch(down))
            assertTrue("the viewer must claim the volume-down release", ViewerKeyRouter.dispatch(up))
        }
        waitForPage("2/3")

        val upDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
        compose.runOnUiThread {
            assertTrue(ViewerKeyRouter.dispatch(upDown))
        }
        waitForPage("1/3")

        // A key the reader does not own goes back to the platform.
        compose.runOnUiThread {
            assertFalse(ViewerKeyRouter.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
        }
    }

    @Test
    fun aTwentyPageBookTurnsOneHanded() {
        show(book(pages = 20), pages = 20)
        hideToolbar()
        waitForPage("1/20")

        // Five forward turns, one page each, then two back.
        for (page in 2..6) {
            tapEdge(right = true)
            waitForPage("$page/20")
        }
        tapEdge(right = false)
        waitForPage("5/20")
        tapEdge(right = false)
        waitForPage("4/20")

        // The volume keys move relative to the page the readout names.
        volumeKey(KeyEvent.KEYCODE_VOLUME_DOWN)
        waitForPage("5/20")
        volumeKey(KeyEvent.KEYCODE_VOLUME_UP)
        waitForPage("4/20")
    }

    @Test
    fun horizontalPagingTurnsOnePagePerScreen() {
        AppSettings.setTurnSettings(
            context,
            TurnSettings(mode = TurnMode.HORIZONTAL, action = TurnAction.PAGE, animation = TurnAnimation.INSTANT)
        )
        show(book(pages = 20), pages = 20)
        hideToolbar()
        waitForPage("1/20")

        // The page is fitted inside its screen, so its edge zones sit under the
        // screen edges; the tap keeps clear of the page's own 8 dp padding.
        compose.onNodeWithTag(UiTags.VIEWER_PAGE_SURFACE).performTouchInput {
            click(Offset(width - 60f, height / 2f))
        }
        waitForPage("2/20")
        compose.onNodeWithTag(UiTags.VIEWER_PAGE_SURFACE).performTouchInput {
            click(Offset(60f, height / 2f))
        }
        waitForPage("1/20")
    }

    @Test
    fun aLongPressDragStillSelectsText() {
        show(book())
        // The page picture is the text area, and it appears with the first
        // render rather than with the surface.
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription("Page 1").fetchSemanticsNodes().isNotEmpty()
        }

        // Edge taps are on, so a plain tap turns or toggles; a long press plus a
        // drag must still select words and win over the turn layer.
        compose.onNodeWithContentDescription("Page 1").performTouchInput {
            down(center)
            advanceEventTime(800)
            moveBy(Offset(80f, 0f))
            up()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Select all").fetchSemanticsNodes().isNotEmpty()
        }
        // A selection owns the finger: turning stays off until it is cleared.
        compose.onNodeWithText("Clear").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Select all").fetchSemanticsNodes().isEmpty()
        }
        // The drag was a selection, not a turn: the document is still on page 1.
        hideToolbar()
        waitForPage("1/3")
    }

    @Test
    fun pinchingStillZoomsThePage() {
        show(book())

        // Two fingers must zoom even though the page now has a tap layer.
        compose.onNodeWithTag(UiTags.VIEWER_PAGE_SURFACE).performTouchInput {
            pinch(
                start0 = center + Offset(-120f, 0f),
                end0 = center + Offset(-360f, 0f),
                start1 = center + Offset(120f, 0f),
                end1 = center + Offset(360f, 0f)
            )
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("100%").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun theOneTimeOfferEnablesTheVolumeKeys() {
        AppSettings.setTurnVolumeHintShown(context, false)
        AppSettings.setTurnSettings(context, TurnSettings(volumeKeys = false))

        show(book())

        // The offer is a card, not a dialog, so it appears shortly after the
        // first page without blocking the document.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(UiTags.VIEWER_VOLUME_HINT)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UiTags.VIEWER_VOLUME_HINT_ENABLE).performClick()
        compose.waitUntil(5_000) {
            AppSettings.turnSettings(context).volumeKeys
        }
        assertTrue(AppSettings.turnVolumeHintShown(context))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(UiTags.VIEWER_VOLUME_HINT)
                .fetchSemanticsNodes().isEmpty()
        }
    }
}
