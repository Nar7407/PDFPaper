package com.pdfpaper.app.ui.viewer.ink

import android.graphics.Bitmap
import android.graphics.Color
import android.content.Context
import android.os.PowerManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T54 on-device UI: the focused low-latency ink canvas opens with its page,
 * routes the tool buttons, and closes without disturbing the caller.
 */
@RunWith(AndroidJUnit4::class)
class InkFocusOverlayDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun opensWithTheFocusedPageAndCloses() {
        val plan = PageGeometry.plan(PageBox(0.0, 0.0, 300.0, 400.0, 0), 1.0)
        val bitmap = Bitmap.createBitmap(
            plan.pixelWidth,
            plan.pixelHeight,
            Bitmap.Config.ARGB_8888
        ).apply { eraseColor(Color.WHITE) }
        var mode = InkToolMode.PEN
        var color = PdfColor.BLACK
        var width = 2.0
        var closed = false

        compose.setContent {
            PDFPaperTheme {
                InkFocusOverlay(
                    pageIndex = 0,
                    pageCount = 3,
                    bitmap = bitmap,
                    plan = plan,
                    aspect = 300f / 400f,
                    state = InkUiState(),
                    onNeedPage = { _, _ -> },
                    onStart = { _, _, _, _, _, _ -> },
                    onExtend = { _, _, _, _, _ -> },
                    onEnd = { },
                    onMode = { mode = it },
                    onColor = { color = it },
                    onWidth = { width = it },
                    onUndo = { },
                    onRedo = { },
                    onClose = { closed = true }
                )
            }
        }

        compose.onNodeWithText("Ink canvas · page 1 of 3").assertIsDisplayed()
        compose.onNodeWithText("Highlighter").performClick()
        compose.onNodeWithText("Pen").performClick()
        compose.runOnIdle {
            assertEquals(InkToolMode.PEN, mode)
            assertEquals(0.0, color.red, 0.0001)
            assertEquals(2.0, width, 0.0001)
            assertFalse(closed)
        }
        compose.onNodeWithContentDescription("Close ink canvas").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test
    fun asksForThePageBitmapWhenItIsMissing() {
        val plan = PageGeometry.plan(PageBox(0.0, 0.0, 300.0, 400.0, 90), 1.0)
        var requested: Pair<Int, Int>? = null
        var closed = false

        compose.setContent {
            PDFPaperTheme {
                InkFocusOverlay(
                    pageIndex = 1,
                    pageCount = 4,
                    bitmap = null,
                    plan = plan,
                    aspect = 400f / 300f,
                    state = InkUiState(mode = InkToolMode.HIGHLIGHTER),
                    onNeedPage = { index, width -> requested = index to width },
                    onStart = { _, _, _, _, _, _ -> },
                    onExtend = { _, _, _, _, _ -> },
                    onEnd = { },
                    onMode = { },
                    onColor = { },
                    onWidth = { },
                    onUndo = { },
                    onRedo = { },
                    onClose = { closed = true }
                )
            }
        }

        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1 to 1600, requested) }
        compose.onNodeWithText("Ink canvas · page 2 of 4").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close ink canvas").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test
    fun aTouchStrokeIsDrawnOnTheFocusedSurface() {
        val plan = PageGeometry.plan(PageBox(0.0, 0.0, 300.0, 400.0, 0), 1.0)
        val bitmap = Bitmap.createBitmap(
            plan.pixelWidth,
            plan.pixelHeight,
            Bitmap.Config.ARGB_8888
        ).apply { eraseColor(Color.WHITE) }
        var ended = false
        var started = false
        var extended = false

        compose.setContent {
            PDFPaperTheme {
                InkFocusOverlay(
                    pageIndex = 0,
                    pageCount = 1,
                    bitmap = bitmap,
                    plan = plan,
                    aspect = 300f / 400f,
                    state = InkUiState(mode = InkToolMode.PEN, width = 6.0),
                    onNeedPage = { _, _ -> },
                    onStart = { _, _, _, _, _, _ -> started = true },
                    onExtend = { _, _, _, _, _ -> extended = true },
                    onEnd = { ended = true },
                    onMode = { },
                    onColor = { },
                    onWidth = { },
                    onUndo = { },
                    onRedo = { },
                    onClose = { }
                )
            }
        }

        // The SurfaceView needs a real surface before the front-buffered
        // renderer exists; Compose idle does not wait for that.
        Thread.sleep(600)
        compose.onNodeWithTag(UiTags.INK_FOCUS_SURFACE).performTouchInput {
            val start = center + Offset(-140f, 0f)
            down(start)
            moveTo(start + Offset(70f, 0f))
            moveTo(start + Offset(140f, 0f))
            moveTo(start + Offset(210f, 0f))
            moveTo(start + Offset(280f, 0f))
            up()
        }
        compose.waitForIdle()
        Thread.sleep(400)
        compose.runOnIdle {
            assertTrue("the focused view should receive the down event", started)
            assertTrue("the focused view should receive the move events", extended)
            assertTrue("the focused view should receive the up event", ended)
        }

        // Full-screen capture goes through SurfaceFlinger, so it sees both the
        // multi-buffered page and the front-buffered segment. A horizontal black
        // run over the blank white page is the stroke.
        val shot = captureScreen()
        val pixels = IntArray(shot.width * shot.height)
        shot.getPixels(pixels, 0, shot.width, 0, 0, shot.width, shot.height)
        var longestDarkRun = 0
        for (y in shot.height / 4 until shot.height * 3 / 4) {
            var run = 0
            for (x in shot.width / 4 until shot.width * 3 / 4) {
                val color = pixels[y * shot.width + x]
                val dark = Color.red(color) < 80 &&
                    Color.green(color) < 80 &&
                    Color.blue(color) < 80
                if (dark) {
                    run++
                    if (run > longestDarkRun) longestDarkRun = run
                } else {
                    run = 0
                }
            }
        }
        assertTrue("no ink stroke in the screenshot (longest dark run $longestDarkRun)", longestDarkRun >= 60)
    }

    /**
     * `takeScreenshot` returns null while the display is off or asleep, which a
     * long device run can hit. Wake the screen and retry instead of failing on
     * a null bitmap.
     */
    private fun captureScreen(): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val power = instrumentation.targetContext
            .getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wake = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "ink-focus-screenshot"
        )
        wake.acquire(15_000)
        try {
            repeat(20) {
                val shot = instrumentation.uiAutomation.takeScreenshot()
                if (shot != null) return shot
                Thread.sleep(250)
            }
        } finally {
            wake.release()
        }
        throw AssertionError("the screen could not be captured")
    }
}
