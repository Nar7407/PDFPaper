package com.pdfpaper.app.ui.settings

import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T47: the settings shortcut opens Android's own app-defaults screen. The app
 * cannot become the default handler by itself, so the button only takes the
 * user to the place where Android lets them decide.
 */
@RunWith(AndroidJUnit4::class)
class SettingsDefaultAppsDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    /** Records the launch instead of leaving the app for the system Settings. */
    private class RecordingContext : ContextWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext
    ) {
        var started: Intent? = null

        override fun startActivity(intent: Intent) {
            started = intent
        }
    }

    @Test
    fun theButtonOpensTheSystemDefaultAppsScreen() {
        val recorder = RecordingContext()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides recorder) {
                PDFPaperTheme {
                    SettingsScreen(onBack = {})
                }
            }
        }
        compose.onNodeWithTag(UiTags.SETTINGS_DEFAULT_APPS)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        val intent = recorder.started
        assertNotNull("the button must start the system default-apps screen", intent)
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, intent!!.action)
        assertTrue(
            "the settings screen opens in its own task",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0
        )
    }
}
