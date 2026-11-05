package com.pdfpaper.app.ui.viewer.speech

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.speech.ReadAloudHub
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.theme.AppTheme
import com.pdfpaper.engine.speech.ChapterSource
import com.pdfpaper.engine.speech.SpeechChapter
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The T34 acceptance screenshots. These capture the real control surfaces
 * (the bar, the options sheet, its chapter list and the voice row) with a
 * representative reading state; they do not record audio from the device's
 * speech engine.
 */
@RunWith(AndroidJUnit4::class)
class ReadAloudScreenshotDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun clearHub() {
        ReadAloudHub.commands = null
        ReadAloudHub.clear()
    }

    private val chapters = listOf(
        SpeechChapter("Introduction", 0, 2, 0.0, ChapterSource.OUTLINE),
        SpeechChapter("Method", 0, 8, 0.3, ChapterSource.OUTLINE),
        SpeechChapter("Results", 0, 14, 0.55, ChapterSource.OUTLINE),
        SpeechChapter("Discussion", 0, 20, 0.8, ChapterSource.OUTLINE)
    )

    private val reading = ReadAloudPlaybackState(
        active = true,
        speaking = true,
        documentName = "Listening to a Two-Column Page",
        pageIndex = 0,
        paragraphIndex = 2,
        chapterTitle = "Introduction",
        paragraphText = "The reading service must speak the left column first, from its heading to its last line, and only then move to the right column.",
        speed = 1.25f,
        sleep = SleepOption.MINUTES_30,
        chapters = chapters,
        chapterSource = ChapterSource.OUTLINE
    )

    @Test
    fun captureTheReadingBar() {
        ReadAloudHub.publish(reading)
        compose.setContent {
            AppTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFFFAFAF7))) {
                    Column(Modifier.padding(24.dp)) {
                        Text(
                            "Listening to a Two-Column Page",
                            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "The reading service must speak the left column first, from " +
                                "its heading to its last line, and only then move to the right column.",
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0x66FFC107))
                        )
                    }
                    ReadAloudBar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                    )
                }
            }
        }
        waitForBar()
        save("t34-reading.png")
    }

    @Test
    fun captureTheOptionsSheet() {
        showSheet()
        compose.onNodeWithTag(UiTags.READ_ALOUD_SHEET).assertExists()
        save("t34-sheet.png")
    }

    @Test
    fun captureTheChapterList() {
        showSheet()
        compose.onNodeWithTag(UiTags.READ_ALOUD_SHEET).performTouchInput { swipeUp() }
        compose.waitForIdle()
        save("t34-chapter.png")
    }

    @Test
    fun captureTheVoiceRow() {
        showSheet()
        compose.onNodeWithTag(UiTags.READ_ALOUD_SHEET).performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.READ_ALOUD_VOICE_ROW).performClick()
        // The voice list comes from the platform engine on a background thread.
        Thread.sleep(1_500L)
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.READ_ALOUD_SHEET).performTouchInput { swipeUp() }
        compose.waitForIdle()
        save("t34-voice.png")
    }

    private fun showSheet() {
        ReadAloudHub.publish(reading)
        compose.setContent {
            AppTheme {
                ReadAloudSheet(state = reading, onDismiss = {})
            }
        }
        compose.waitForIdle()
    }

    private fun waitForBar() {
        ReadAloudHub.publish(reading)
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.VIEWER_READ_ALOUD_BAR).assertExists()
    }

    private fun save(name: String) {
        compose.waitForIdle()
        // Compose is idle before the first frame has been drawn; give the
        // window one refresh so the screenshot is not a blank activity.
        Thread.sleep(600L)
        val bitmap: Bitmap = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .takeScreenshot()
        // The acceptance run pulls these through adb after Gradle has removed
        // the test app, so they go to the public Pictures folder, not app data.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PDFPaper")
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        )
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
        bitmap.recycle()
    }
}
