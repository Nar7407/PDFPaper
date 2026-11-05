package com.pdfpaper.app.ui.viewer.speech

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.speech.ReadAloudHub
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.theme.AppTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T64 acceptance of the viewer's side of the reading.
 *
 * The bar is driven by [ReadAloudHub] exactly as the viewer drives it, so this
 * test proves the wiring the viewer relies on: a state published by the service
 * appears in the bar, and a press on the bar reaches the service's commands.
 * No service is started here - the hub is in the same process.
 */
@RunWith(AndroidJUnit4::class)
class ReadAloudViewerDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    /** The commands the bar is expected to send to the service. */
    private class RecordingCommands : ReadAloudHub.Commands {
        var started = 0
        var pauses = 0
        var resumes = 0
        var stops = 0
        var nexts = 0
        var previouses = 0

        override fun start(
            location: String,
            displayName: String,
            password: String,
            position: SpeechPosition
        ) {
            started++
        }

        override fun pause() {
            pauses++
        }

        override fun resume() {
            resumes++
        }

        override fun stop() {
            stops++
        }

        override fun nextParagraph() {
            nexts++
        }

        override fun previousParagraph() {
            previouses++
        }

        override fun nextChapter() = Unit

        override fun previousChapter() = Unit

        override fun setSpeed(rate: Float) = Unit

        override fun setSleep(option: SleepOption) = Unit

        override fun setVoice(key: SpeechVoiceKey?) = Unit
    }

    @After
    fun clearTheHub() {
        ReadAloudHub.commands = null
        ReadAloudHub.clear()
    }

    @Test
    fun theBarIsShownOnlyWhileAReadingIsActive() {
        compose.setContent { AppTheme { ReadAloudBar() } }
        compose.onNodeWithTag(UiTags.VIEWER_READ_ALOUD_BAR).assertDoesNotExist()

        ReadAloudHub.publish(
            ReadAloudPlaybackState(
                active = true,
                speaking = true,
                documentName = "A document",
                pageIndex = 2,
                paragraphText = "The quick brown fox"
            )
        )
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.VIEWER_READ_ALOUD_BAR).assertIsDisplayed()
        compose.onNodeWithText("Page 3").assertIsDisplayed()
        compose.onNodeWithText("The quick brown fox").assertIsDisplayed()
    }

    @Test
    fun theBarSendsTheTransportCommandsToTheService() {
        val commands = RecordingCommands()
        ReadAloudHub.commands = commands
        ReadAloudHub.publish(ReadAloudPlaybackState(active = true, speaking = true))

        compose.setContent { AppTheme { ReadAloudBar() } }

        compose.onNodeWithTag(UiTags.VIEWER_READ_ALOUD_PLAY).performClick()
        assertEquals("a speaking reading pauses", 1, commands.pauses)

        // Once paused, the same button resumes.
        ReadAloudHub.publish(ReadAloudPlaybackState(active = true, speaking = false))
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.VIEWER_READ_ALOUD_PLAY).performClick()
        assertEquals("a paused reading resumes", 1, commands.resumes)

        compose.onNodeWithTag(UiTags.VIEWER_READ_ALOUD_PREVIOUS).performClick()
        compose.onNodeWithTag(UiTags.VIEWER_READ_ALOUD_NEXT).performClick()
        compose.onNodeWithTag(UiTags.VIEWER_READ_ALOUD_STOP).performClick()
        assertEquals(1, commands.previouses)
        assertEquals(1, commands.nexts)
        assertEquals(1, commands.stops)
    }
}
