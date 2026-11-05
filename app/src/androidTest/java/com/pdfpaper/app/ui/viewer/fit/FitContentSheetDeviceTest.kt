package com.pdfpaper.app.ui.viewer.fit

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.render.RenderedPage
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.crop.CropMargins
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T57 UI acceptance. The manual editor used to live inside the bottom sheet,
 * where the sheet's own drag closed it as soon as a corner was dragged
 * downwards. This test proves the editor is a separate surface that survives a
 * corner drag and reports the dragged margins.
 */
@RunWith(AndroidJUnit4::class)
class FitContentSheetDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val page = PageBox(0.0, 0.0, 612.0, 792.0, 0)
    private val plan = PageGeometry.plan(page, PageGeometry.scaleForPixelWidth(page, 306))

    private fun rendered(): RenderedPage {
        val bitmap = Bitmap.createBitmap(plan.pixelWidth, plan.pixelHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        return RenderedPage(plan, bitmap, emptyList())
    }

    private fun show(
        mode: FitContentMode,
        margins: CropMargins = CropMargins.ZERO,
        onMode: (FitContentMode) -> Unit = {},
        onMargins: (CropMargins) -> Unit = {},
        onDismiss: () -> Unit = {}
    ) {
        compose.setContent {
            FitContentSheet(
                state = FitContentState(mode = mode, margins = margins),
                pages = listOf(page),
                currentPage = 0,
                renderPreview = { _, _ -> rendered() },
                onMode = onMode,
                onMargins = onMargins,
                onIgnoreHeaderFooter = {},
                onMakePermanent = {},
                onDismiss = onDismiss
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun manualModeOpensTheEditorAndACornerDragSetsMargins() {
        var applied: CropMargins? = null
        var dismissed = false
        show(FitContentMode.MANUAL, onMargins = { applied = it }, onDismiss = { dismissed = true })

        compose.onNodeWithTag(UiTags.FIT_CONTENT_ADJUST_DONE).assertIsDisplayed()

        // The top-left handle sits at the preview's origin; drag it inwards.
        compose.onNodeWithTag(UiTags.FIT_CONTENT_PREVIEW).performTouchInput {
            down(Offset(4f, 4f))
            moveTo(Offset(60f, 70f))
            moveTo(Offset(90f, 100f))
            up()
        }
        compose.waitForIdle()

        val margins = applied
        assertTrue("a corner drag must report margins, got $margins", margins != null)
        assertTrue("left margin ${margins!!.left}", margins!!.left > 5.0)
        assertTrue("top margin ${margins.top}", margins.top > 5.0)
        // The editor and the sheet behind it must survive the drag.
        compose.onNodeWithTag(UiTags.FIT_CONTENT_ADJUST_DONE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FIT_CONTENT_SHEET).assertExists()
        assertFalse(dismissed)
    }

    @Test
    fun theEditorClosesWithDoneAndKeepsTheMargins() {
        var applied: CropMargins? = null
        show(FitContentMode.MANUAL, onMargins = { applied = it })
        compose.onNodeWithTag(UiTags.FIT_CONTENT_ADJUST_DONE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FIT_CONTENT_ADJUST_DONE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.FIT_CONTENT_ADJUST_DONE).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.FIT_CONTENT_SHEET).assertExists()
        assertEquals(null, applied)
    }

    @Test
    fun theWholeDocumentChipAsksForThatMode() {
        var chosen: FitContentMode? = null
        show(FitContentMode.OFF, onMode = { chosen = it })
        compose.onNodeWithTag(UiTags.fitModeButton(FitContentMode.DOCUMENT.name)).performClick()
        compose.waitForIdle()
        assertEquals(FitContentMode.DOCUMENT, chosen)
    }
}
