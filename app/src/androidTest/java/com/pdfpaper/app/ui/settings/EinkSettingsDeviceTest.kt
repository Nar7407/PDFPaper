package com.pdfpaper.app.ui.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import com.pdfpaper.engine.eink.EinkProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T63 UI acceptance: the Settings card is the device-wide home of the E-Ink
 * profile, the clear-screen interval, reduce motion and direct sunlight.
 */
@RunWith(AndroidJUnit4::class)
class EinkSettingsDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Application =
        ApplicationProvider.getApplicationContext<Application>()

    private var enabled = false
    private var profile = EinkProfile.TEXT_BITONAL
    private var autoClear = 0
    private var reduce = false
    private var sunlight = false

    @Before
    fun rememberSettings() {
        enabled = AppSettings.einkEnabled(context)
        profile = AppSettings.einkSettings(context).profile
        autoClear = AppSettings.einkAutoClearTurns(context)
        reduce = AppSettings.reduceMotion(context)
        sunlight = AppSettings.sunlightMode(context)
    }

    @After
    fun restoreSettings() {
        AppSettings.setEinkEnabled(context, enabled)
        AppSettings.setEinkSettings(context, AppSettings.einkSettings(context).copy(profile = profile))
        AppSettings.setEinkAutoClearTurns(context, autoClear)
        AppSettings.setReduceMotion(context, reduce)
        AppSettings.setSunlightMode(context, sunlight)
    }

    private fun show() {
        compose.setContent {
            PDFPaperTheme {
                SettingsScreen(onBack = {})
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theSwitchAndProfileAreSavedForTheDevice() {
        show()
        compose.onNodeWithTag(UiTags.SETTINGS_EINK_ENABLED)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        assertTrue(AppSettings.einkEnabled(context))

        compose.onNodeWithTag(UiTags.settingsEinkProfile(EinkProfile.FULL_BITONAL.name))
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        assertEquals(EinkProfile.FULL_BITONAL, AppSettings.einkSettings(context).profile)
    }

    @Test
    fun motionAndSunlightAreSeparateSwitches() {
        show()
        compose.onNodeWithTag(UiTags.SETTINGS_REDUCE_MOTION)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        assertEquals(!reduce, AppSettings.reduceMotion(context))

        compose.onNodeWithTag(UiTags.SETTINGS_SUNLIGHT)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        assertEquals(!sunlight, AppSettings.sunlightMode(context))
        // They are independent preferences.
        assertEquals(!reduce, AppSettings.reduceMotion(context))
    }

    @Test
    fun theAutoClearIntervalIsSaved() {
        show()
        compose.onNodeWithTag(UiTags.einkAutoClearChip(10))
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        assertEquals(10, AppSettings.einkAutoClearTurns(context))
    }

    @Test
    fun everyEinkControlIsReachable() {
        show()
        compose.onNodeWithTag(UiTags.SETTINGS_EINK_ENABLED).performScrollTo().assertExists()
        EinkProfile.entries.forEach { profile ->
            compose.onNodeWithTag(UiTags.settingsEinkProfile(profile.name))
                .performScrollTo()
                .assertExists()
        }
        compose.onNodeWithTag(UiTags.SETTINGS_EINK_THRESHOLD).performScrollTo().assertExists()
        compose.onNodeWithTag(UiTags.SETTINGS_REDUCE_MOTION).performScrollTo().assertExists()
        compose.onNodeWithTag(UiTags.SETTINGS_SUNLIGHT).performScrollTo().assertExists()
    }
}
