package com.pdfpaper.app.ui.viewer.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * The device's speech engine, as the app's [SpeechSynthesis] (T64).
 *
 * The engine is the platform's own `TextToSpeech`, so nothing is downloaded and
 * nothing leaves the device. The engine reports the character range it is
 * speaking through `onRangeStart`, which is what makes the spoken word
 * highlightable.
 */
class AndroidSpeechSynthesis(
    context: Context,
    private val listener: SpeechListener,
    private val voice: SpeechVoiceKey? = null
) : SpeechSynthesis {

    private val appContext = context.applicationContext

    /** Null once [shutdown] ran; the engine is never used after that. */
    private var engine: TextToSpeech? = TextToSpeech(appContext) { status ->
        onInit(status)
    }

    private var ready = false
    private var stopped = false

    private fun onInit(status: Int) {
        if (stopped) return
        if (status != TextToSpeech.SUCCESS) {
            listener.onReady(false, "This device has no speech engine")
            return
        }
        val tts = engine ?: return
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onRangeStart(
                utteranceId: String?,
                start: Int,
                end: Int,
                charCount: Int
            ) {
                if (utteranceId == null) return
                listener.onRangeStart(utteranceId, start, end)
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == null) return
                listener.onDone(utteranceId)
            }

            @Deprecated("Kept for API levels below 21; the app's minSdk is 26.")
            override fun onError(utteranceId: String?) {
                if (utteranceId == null) return
                listener.onError(utteranceId, "The speech engine failed")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == null) return
                listener.onError(utteranceId, "The speech engine failed")
            }
        })
        val message = applyVoice(tts)
        ready = true
        listener.onReady(true, message)
    }

    /**
     * Applies the stored voice. Returns the message to show the reader: a
     * voice the device no longer has is reported honestly instead of failing
     * silently.
     */
    private fun applyVoice(tts: TextToSpeech): String? {
        val wanted = voice ?: return null
        val available = tts.voices.orEmpty().map { it.toSpeechVoice() }
        val chosen = SpeechVoices.resolve(available, wanted)
        if (chosen == null) {
            return "The saved voice is gone, so the default voice is used"
        }
        val platform = tts.voices.orEmpty().firstOrNull {
            it.name == chosen.key.name && it.locale?.toLanguageTag() == chosen.key.locale
        }
        if (platform == null) {
            return "The saved voice is gone, so the default voice is used"
        }
        tts.voice = platform
        return null
    }

    override fun speak(text: String, utteranceId: String, rate: Float) {
        val tts = engine ?: return
        if (!ready) {
            listener.onError(utteranceId, "The speech engine is not ready yet")
            return
        }
        if (text.isBlank()) {
            listener.onDone(utteranceId)
            return
        }
        tts.setSpeechRate(rate.coerceIn(MIN_RATE, MAX_RATE))
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    override fun stop() {
        engine?.stop()
    }

    override fun shutdown() {
        if (stopped) return
        stopped = true
        ready = false
        engine?.shutdown()
        engine = null
    }

    private companion object {
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2f
    }
}
