package com.pdfpaper.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.theme.AppTheme
import com.pdfpaper.app.ui.viewer.speech.SleepOption
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T34: the Settings card's defaults are stored and read back. The card is pure
 * UI over [AppSettings], so this needs no document and no speech engine.
 */
@RunWith(AndroidJUnit4::class)
class SpeechSettingsDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetDefaults() {
        AppSettings.setSpeechSpeed(context, 1f)
        AppSettings.setSpeechSleep(context, SleepOption.OFF)
        AppSettings.setSpeechAutoScroll(context, true)
        AppSettings.setSpeechVoice(context, null)
    }

    @Test
    fun theSpeedPresetIsStoredAndReadBack() {
        compose.setContent {
            AppTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SpeechSettingsCard()
                }
            }
        }

        compose.onNodeWithTag(UiTags.settingsSpeechSpeedTag(1.5f))
            .performScrollTo()
            .performClick()

        assertEquals(1.5f, AppSettings.speechSpeed(context), 0.001f)
    }

    @Test
    fun theSleepDefaultIsStoredAndReadBack() {
        compose.setContent {
            AppTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SpeechSettingsCard()
                }
            }
        }

        compose.onNodeWithTag(UiTags.settingsSpeechSleepTag(SleepOption.MINUTES_30.name))
            .performScrollTo()
            .performClick()

        assertEquals(SleepOption.MINUTES_30, AppSettings.speechSleep(context))
    }

    @Test
    fun theVoiceRowNamesTheVoiceAndOfflineState() {
        compose.setContent {
            AppTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SpeechSettingsCard()
                }
            }
        }

        compose.onNodeWithTag(UiTags.SETTINGS_SPEECH_CARD).assertExists()
        compose.onNodeWithTag(UiTags.SETTINGS_SPEECH_VOICE)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Default voice · offline voices only").assertExists()
    }

    @Test
    fun readAloudExplainsTheBatteryCost() {
        compose.setContent {
            AppTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SpeechSettingsCard()
                }
            }
        }

        compose.onNodeWithText("uses battery", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("never sends the document anywhere", substring = true)
            .assertExists()
    }
}
