package com.pdfpaper.app.ui.viewer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.data.rgbOf
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.engine.render.ColorTransform
import com.pdfpaper.engine.render.PageFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T62 UI acceptance: the look sheet. A filter chip is a live change, the
 * warmth and tint controls belong to their filters, and "Reset to default" and
 * "Use as default for new documents" are separate actions - resettable only
 * when the document actually has a look of its own.
 */
@RunWith(AndroidJUnit4::class)
class LookSheetDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(
        look: ColorTransform = ColorTransform.NONE,
        fromDocument: Boolean = true,
        onApply: (ColorTransform) -> Unit = {},
        onReset: () -> Unit = {},
        onUseAsDefault: () -> Unit = {},
        onDismiss: () -> Unit = {}
    ) {
        compose.setContent {
            LookSheetContent(
                look = look,
                fromDocument = fromDocument,
                onApply = onApply,
                onReset = onReset,
                onUseAsDefault = onUseAsDefault,
                onDismiss = onDismiss
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun aFilterChipAppliesThatFilter() {
        var applied: ColorTransform? = null
        show(onApply = { applied = it })

        compose.onNodeWithTag(UiTags.lookFilterChip(PageFilter.TRUE_BLACK.name)).performClick()
        compose.waitUntil(timeoutMillis = 3_000) {
            applied?.filter == PageFilter.TRUE_BLACK
        }
        assertEquals(PageFilter.TRUE_BLACK, applied!!.filter)
    }

    @Test
    fun warmthBelongsToSepiaAndTheTintToInvertedMonochrome() {
        show(ColorTransform.NONE)
        compose.onNodeWithTag(UiTags.LOOK_WARMTH).assertDoesNotExist()

        compose.onNodeWithTag(UiTags.lookFilterChip(PageFilter.SEPIA.name)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.LOOK_WARMTH).assertExists()

        compose.onNodeWithTag(UiTags.lookFilterChip(PageFilter.INVERTED_MONO.name)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.LOOK_WARMTH).assertDoesNotExist()
        // The default blueprint blue is the first tint swatch.
        compose.onNodeWithTag(UiTags.lookTint(rgbOf(ColorTransform.DEFAULT_TINT).toString(16)))
            .assertExists()
    }

    @Test
    fun choosingATintAppliesIt() {
        var applied: ColorTransform? = null
        show(ColorTransform(filter = PageFilter.INVERTED_MONO), onApply = { applied = it })
        val amber = TINTS[2]
        compose.onNodeWithTag(UiTags.lookTint(rgbOf(amber).toString(16))).performClick()
        compose.waitUntil(timeoutMillis = 3_000) {
            applied != null && rgbOf(applied!!.tint) == rgbOf(amber)
        }
        assertEquals(PageFilter.INVERTED_MONO, applied!!.filter)
    }

    @Test
    fun resetIsOnlyEnabledForADocumentWithItsOwnLook() {
        var resets = 0
        show(ColorTransform(filter = PageFilter.SEPIA), fromDocument = false, onReset = { resets++ })
        compose.onNodeWithTag(UiTags.LOOK_RESET).assertIsNotEnabled()
        assertEquals(0, resets)
    }

    @Test
    fun resetAndUseAsDefaultAreSeparateActions() {
        var resets = 0
        var defaults = 0
        show(
            look = ColorTransform(filter = PageFilter.SEPIA),
            fromDocument = true,
            onReset = { resets++ },
            onUseAsDefault = { defaults++ }
        )
        compose.onNodeWithTag(UiTags.LOOK_RESET).assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, resets)
        assertEquals(0, defaults)

        compose.onNodeWithTag(UiTags.LOOK_USE_DEFAULT).performClick()
        compose.waitForIdle()
        assertEquals(1, defaults)
    }

    @Test
    fun everyFilterAndSliderIsReachable() {
        show()
        PageFilter.entries.forEach { filter ->
            compose.onNodeWithTag(UiTags.lookFilterChip(filter.name)).assertExists()
        }
        compose.onNodeWithTag(UiTags.LOOK_BRIGHTNESS).assertExists()
        compose.onNodeWithTag(UiTags.LOOK_CONTRAST).assertExists()
        compose.onNodeWithTag(UiTags.LOOK_GAMMA).assertExists()
        compose.onNodeWithTag(UiTags.LOOK_DONE).assertExists()
        assertNotNull(TINTS.first())
    }
}
