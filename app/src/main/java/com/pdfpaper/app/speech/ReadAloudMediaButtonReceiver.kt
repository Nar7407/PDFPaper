package com.pdfpaper.app.speech

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.IntentCompat

/**
 * The headset and lock-screen media buttons (T64).
 *
 * The platform sends the media keys as a broadcast; this receiver turns them
 * into the same commands the viewer sends, so one button works everywhere.
 */
class ReadAloudMediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = keyEvent(intent) ?: return
        val commands = ReadAloudHub.commands ?: return
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> commands.resume()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> commands.pause()
            KeyEvent.KEYCODE_MEDIA_NEXT -> commands.nextParagraph()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> commands.previousParagraph()
            KeyEvent.KEYCODE_MEDIA_STOP -> commands.stop()
            else -> Unit
        }
    }

    /**
     * The key event of the broadcast.
     *
     * Newer platforms put the [KeyEvent] straight into the extra; older ones
     * put an [Intent] that carries it.
     */
    private fun keyEvent(intent: Intent): KeyEvent? {
        val extra = intent.extras?.get(Intent.EXTRA_KEY_EVENT)
        return when (extra) {
            is KeyEvent -> extra
            is Intent -> IntentCompat.getParcelableExtra(
                extra,
                Intent.EXTRA_KEY_EVENT,
                KeyEvent::class.java
            )
            else -> null
        }
    }
}
