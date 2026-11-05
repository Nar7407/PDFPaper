package com.pdfpaper.app.ui.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.data.EinkDisplay
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import com.pdfpaper.engine.eink.EinkDither
import com.pdfpaper.engine.eink.EinkProfile
import com.pdfpaper.engine.eink.EinkSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T63 UI acceptance: the E-Ink sheet. A profile is a live change, the device
 * switch and the per-document override are separate decisions, and the clear
 * screen action is reachable without closing the sheet.
 */
@RunWith(AndroidJUnit4::class)
class EinkSheetDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(
        display: EinkDisplay = EinkDisplay(
            settings = EinkSettings(profile = EinkProfile.TEXT_BITONAL),
            enabled = true
        ),
        autoClear: Int = 0,
        onApply: (EinkSettings, Boolean) -> Unit = { _, _ -> },
        onEnabled: (Boolean) -> Unit = {},
        onDocument: (Boolean) -> Unit = {},
        onAutoClear: (Int) -> Unit = {},
        onClear: () -> Unit = {},
        onDismiss: () -> Unit = {}
    ) {
        compose.setContent {
            PDFPaperTheme {
                EinkSheetContent(
                    display = display,
                    autoClearTurns = autoClear,
                    onApply = onApply,
                    onEnabledChange = onEnabled,
                    onDocumentOverride = onDocument,
                    onAutoClear = onAutoClear,
                    onClearScreen = onClear,
                    onDismiss = onDismiss
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theDeviceSwitchTurnsTheModeOffAndOn() {
        var enabled: Boolean? = null
        var display by mutableStateOf(
            EinkDisplay(settings = EinkSettings(profile = EinkProfile.TEXT_BITONAL), enabled = true)
        )
        compose.setContent {
            PDFPaperTheme {
                EinkSheetContent(
                    display = display,
                    autoClearTurns = 0,
                    onApply = { _, _ -> },
                    onEnabledChange = { value ->
                        enabled = value
                        display = display.copy(enabled = value)
                    },
                    onDocumentOverride = {},
                    onAutoClear = {},
                    onClearScreen = {},
                    onDismiss = {}
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.EINK_ENABLED).assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(false, enabled)

        // The caller applies the switch; the sheet recomposes with OFF and the
        // next tap must ask for ON rather than repeating the stale state.
        compose.runOnUiThread {
            display = EinkDisplay(settings = EinkSettings.OFF, enabled = false)
        }
        enabled = null
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.EINK_ENABLED).performClick()
        compose.waitForIdle()
        assertEquals(true, enabled)
    }

    @Test
    fun aProfileChipAppliesThatProfile() {
        var applied: EinkSettings? = null
        show(onApply = { settings, _ -> applied = settings })
        compose.onNodeWithTag(UiTags.einkProfileChip(EinkProfile.FULL_BITONAL.name)).performClick()
        compose.waitForIdle()
        assertNotNull(applied)
        assertEquals(EinkProfile.FULL_BITONAL, applied!!.profile)
    }

    @Test
    fun theDocumentOverrideIsSeparateFromTheDeviceSetting() {
        var document: Boolean? = null
        show(
            display = EinkDisplay(
                settings = EinkSettings(profile = EinkProfile.TEXT_BITONAL),
                enabled = true,
                fromDocument = false
            ),
            onDocument = { document = it }
        )
        compose.onNodeWithTag(UiTags.EINK_DOCUMENT_ONLY).performClick()
        compose.waitForIdle()
        assertEquals(true, document)
    }

    @Test
    fun clearingTheScreenCallsOutAndKeepsTheSheetOpen() {
        var cleared = 0
        var dismissed = false
        show(onClear = { cleared++ }, onDismiss = { dismissed = true })
        compose.onNodeWithTag(UiTags.EINK_CLEAR).performClick()
        compose.waitForIdle()
        assertEquals(1, cleared)
        assertEquals(false, dismissed)
        compose.onNodeWithTag(UiTags.EINK_DONE).performClick()
        compose.waitForIdle()
        assertEquals(true, dismissed)
    }

    @Test
    fun theAutoClearIntervalIsPickable() {
        var chosen = -1
        show(onAutoClear = { chosen = it })
        compose.onNodeWithTag(UiTags.einkAutoClearChip(5)).performClick()
        compose.waitForIdle()
        assertEquals(5, chosen)
    }

    @Test
    fun everyProfileAndControlIsReachable() {
        show()
        EinkProfile.entries.forEach { profile ->
            compose.onNodeWithTag(UiTags.einkProfileChip(profile.name)).assertExists()
        }
        EinkDither.entries.forEach { dither ->
            compose.onNodeWithTag(UiTags.einkDitherChip(dither.name)).assertExists()
        }
        compose.onNodeWithTag(UiTags.EINK_THRESHOLD).assertExists()
        compose.onNodeWithTag(UiTags.EINK_DOCUMENT_ONLY).assertExists()
        compose.onNodeWithTag(UiTags.EINK_CLEAR).assertExists()
        compose.onNodeWithTag(UiTags.EINK_DONE).assertExists()
    }
}
