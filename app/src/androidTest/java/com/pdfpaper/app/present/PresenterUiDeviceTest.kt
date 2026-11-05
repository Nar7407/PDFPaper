package com.pdfpaper.app.present

import android.graphics.Bitmap
import android.graphics.Color
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.render.RenderedPage
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.present.PresenterOverlay
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T53 on-device UI: slide navigation by button and by a clicker key routed
 * through [PresentKeyRouter], the black screen, and exit.
 */
@RunWith(AndroidJUnit4::class)
class PresenterUiDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun controlsClickerAndBlackScreenWork() {
        val plan = PageGeometry.plan(PageBox(0.0, 0.0, 300.0, 400.0, 0), 1.0)
        val bitmap = Bitmap.createBitmap(
            plan.pixelWidth,
            plan.pixelHeight,
            Bitmap.Config.ARGB_8888
        ).apply { eraseColor(Color.WHITE) }
        val rendered = RenderedPage(plan, bitmap, emptyList())
        var exited = false
        val savedInk = mutableListOf<Int>()

        compose.setContent {
            PDFPaperTheme {
                PresenterOverlay(
                    path = "present-ui-${System.nanoTime()}.pdf",
                    pageCount = 5,
                    startPage = 0,
                    displayName = "Test deck",
                    renderPage = { _, _ -> rendered },
                    onExit = { exited = true },
                    onSaveInk = { specs -> savedInk.addAll(specs.map { it.pageIndex }) }
                )
            }
        }

        compose.onNodeWithTag(UiTags.PRESENT_ROOT).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PRESENT_PAGE_LABEL).assertTextEquals("1 / 5")

        compose.onNodeWithTag(UiTags.PRESENT_NEXT).performClick()
        compose.onNodeWithTag(UiTags.PRESENT_PAGE_LABEL).assertTextEquals("2 / 5")

        // A presentation clicker sends PAGE_DOWN/PAGE_UP; the activity forwards
        // both actions through PresentKeyRouter.
        compose.runOnUiThread {
            PresentKeyRouter.dispatch(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN)
            )
            PresentKeyRouter.dispatch(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAGE_DOWN)
            )
        }
        compose.onNodeWithTag(UiTags.PRESENT_PAGE_LABEL).assertTextEquals("3 / 5")

        compose.runOnUiThread {
            PresentKeyRouter.dispatch(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_UP)
            )
            PresentKeyRouter.dispatch(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAGE_UP)
            )
        }
        compose.onNodeWithTag(UiTags.PRESENT_PAGE_LABEL).assertTextEquals("2 / 5")

        compose.onNodeWithTag(UiTags.PRESENT_BLACK).performClick()
        compose.onNodeWithTag(UiTags.PRESENT_BLACK_SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PRESENT_BLACK_SCREEN).performClick()
        compose.onNodeWithTag(UiTags.PRESENT_ROOT).assertIsDisplayed()

        // Swipes move one slide and are not also counted as a tap zone.
        val slide = compose.onNodeWithTag(UiTags.PRESENT_SLIDE)
        slide.performTouchInput { swipeLeft() }
        compose.onNodeWithTag(UiTags.PRESENT_PAGE_LABEL).assertTextEquals("3 / 5")
        slide.performTouchInput { swipeRight() }
        compose.onNodeWithTag(UiTags.PRESENT_PAGE_LABEL).assertTextEquals("2 / 5")

        // Tapping the left and right thirds steps back and forward.
        slide.performTouchInput {
            click(Offset(width * 0.05f, height / 2f))
        }
        compose.onNodeWithTag(UiTags.PRESENT_PAGE_LABEL).assertTextEquals("1 / 5")
        slide.performTouchInput {
            click(Offset(width * 0.95f, height / 2f))
        }
        compose.onNodeWithTag(UiTags.PRESENT_PAGE_LABEL).assertTextEquals("2 / 5")

        // Pen mode: a drag draws a stroke (and never navigates), while a tap
        // without movement brings the controls back.
        compose.onNodeWithTag(UiTags.PRESENT_PEN).performClick()
        slide.performTouchInput { swipeLeft() }
        slide.performTouchInput { click(Offset(width / 2f, height / 2f)) }
        compose.onNodeWithTag(UiTags.PRESENT_CONTROLS).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PRESENT_PAGE_LABEL).assertTextEquals("2 / 5")
        compose.onNodeWithTag(UiTags.PRESENT_OPTIONS).performClick()
        compose.onNodeWithTag(UiTags.PRESENT_SAVE_INK).performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(savedInk.isNotEmpty())
        }

        compose.onNodeWithTag(UiTags.PRESENT_EXIT).performClick()
        compose.runOnIdle { assertTrue(exited) }
    }
}
