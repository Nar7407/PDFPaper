package com.pdfpaper.app.speech

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.MainActivity
import com.pdfpaper.app.ui.viewer.speech.SpeechPosition
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T64 on-device acceptance: the reader really keeps reading with the screen
 * off.
 *
 * The service is started the way the viewer starts it, and the test checks
 * that the platform sees a foreground service with a media notification and an
 * active media session - the things the lock screen and the headset buttons
 * need. It also checks that reading a document never changes the document's
 * bytes.
 */
@RunWith(AndroidJUnit4::class)
class ReadAloudServiceDeviceTest {

    private val context: Application =
        ApplicationProvider.getApplicationContext<Application>()

    /** The app has to be in the foreground: a background app may not start a
     *  foreground service (Android 12 and later). The viewer does the same. */
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun launchTheApp() {
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(
                    context.packageName,
                    android.Manifest.permission.POST_NOTIFICATIONS
                )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun stopTheService() {
        val intent = ReadAloudService.commandIntent(context, ReadAloudService.ACTION_STOP)
        runCatching { context.startService(intent) }
        waitUntil(5_000) { !ReadAloudHub.state.value.active }
        scenario?.close()
        scenario = null
    }

    /** A PDF with readable text, written into the app's own cache. */
    private fun buildDocument(): File {
        val pdf = PdfBuilder(PageSize.A4).apply {
            addPage().drawText("The quick brown fox jumps over the lazy dog.", 72.0, 700.0, 18.0)
            addPage().drawText("The second page says something else entirely.", 72.0, 700.0, 18.0)
        }.build()
        val file = File(context.cacheDir, "t64-read-aloud-${System.nanoTime()}.pdf")
        file.writeBytes(pdf)
        return file
    }

    private fun waitUntil(millis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun startReading(file: File) {
        val intent = ReadAloudService.startIntent(
            context,
            file.absolutePath,
            "Read aloud acceptance",
            "",
            SpeechPosition()
        )
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun startingTheServicePostsAMediaNotificationAndAnActiveSession() {
        val file = buildDocument()
        val bytesBefore = file.readBytes()

        startReading(file)

        // The service opens the document and starts the reading.
        assertTrue(
            "the service must report an active reading",
            waitUntil(20_000) { ReadAloudHub.state.value.active }
        )
        assertTrue(
            "the reading must actually be speaking before the key arrives",
            waitUntil(20_000) { ReadAloudHub.state.value.speaking }
        )

        // The platform sees a foreground service with a media notification.
        val notifications = context.getSystemService(NotificationManager::class.java)
            .activeNotifications
        val ours = notifications.firstOrNull { it.id == ReadAloudService.NOTIFICATION_ID }
        assertNotNull("the reading must post a notification", ours)
        val extra = ours!!.notification.extras
        val mediaSession = extra?.get(Notification.EXTRA_MEDIA_SESSION)
        assertNotNull("the notification must carry the media session", mediaSession)

        // The notification is the foreground service's own: the platform keeps
        // the process up with the screen off because of it. (The session token
        // in the extras is what draws the lock-screen transport; the
        // MediaSessionManager calls that would name the key session need
        // MEDIA_CONTENT_CONTROL, which no app holds.)
        assertTrue(
            "the notification must be the foreground-service notification",
            ours.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        )

        // Reading never writes to the document.
        assertTrue("the PDF's bytes must not change", bytesBefore.contentEquals(file.readBytes()))
    }

    @Test
    fun aDocumentTheServiceCannotOpenReportsTheReason() {
        val missing = File(context.cacheDir, "t64-does-not-exist.pdf")

        val intent = ReadAloudService.startIntent(
            context,
            missing.absolutePath,
            "Missing document",
            "",
            SpeechPosition()
        )
        androidx.core.content.ContextCompat.startForegroundService(context, intent)

        val reported = waitUntil(20_000) {
            !ReadAloudHub.state.value.message.isNullOrBlank()
        }
        assertTrue("the failure must be reported", reported)
        val message = ReadAloudHub.state.value.message.orEmpty()
        assertTrue(
            "the message must say the document could not be opened: $message",
            message.contains("could not be opened")
        )
    }

    @Test
    fun aHeadsetKeyPausesTheReadingAndItStaysPaused() {
        val file = buildDocument()
        startReading(file)
        assertTrue(
            "the service must report an active reading",
            waitUntil(20_000) { ReadAloudHub.state.value.active }
        )
        assertTrue(
            "the reading must actually be speaking before the key arrives",
            waitUntil(20_000) { ReadAloudHub.state.value.speaking }
        )

        // The platform sends a headset or lock-screen key to this receiver;
        // the app's own receiver must turn it into the same pause the
        // notification sends. (AUDIO_BECOMING_NOISY cannot be sent by an app
        // on API 34, so the button path is what a test can drive.)
        context.sendBroadcast(
            Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setClass(context, ReadAloudMediaButtonReceiver::class.java)
                putExtra(
                    Intent.EXTRA_KEY_EVENT,
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
                )
            }
        )

        assertTrue(
            "a media key must pause the reading",
            waitUntil(5_000) { !ReadAloudHub.state.value.speaking }
        )
        // The reading must not go on by itself after it was stopped from
        // outside; only the reader starts it again.
        Thread.sleep(1_500)
        assertTrue(
            "the reading must not resume behind the reader's back",
            !ReadAloudHub.state.value.speaking
        )
    }
}
