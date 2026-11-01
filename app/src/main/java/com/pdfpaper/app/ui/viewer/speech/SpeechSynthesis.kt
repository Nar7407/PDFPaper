package com.pdfpaper.app.ui.viewer.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One voice of the device's speech engine (T64).
 *
 * [offline] is false for a voice that needs the network: the app has no network
 * permission, so such a voice is never offered and never chosen.
 */
data class SpeechVoice(
    val key: SpeechVoiceKey,
    val label: String,
    val offline: Boolean
)

/**
 * What the speech engine tells us (T64). The session (T64's playback executor)
 * implements this; the platform adapter forwards the engine's callbacks to it.
 */
interface SpeechListener {

    /** The engine is ready ([ok]) or not; [message] says why when it is not. */
    fun onReady(ok: Boolean, message: String?)

    /** The engine started speaking the range [start]..[end] of the utterance. */
    fun onRangeStart(utteranceId: String, start: Int, end: Int)

    /** The utterance was spoken to the end. */
    fun onDone(utteranceId: String)

    /** The utterance could not be spoken; [message] says what happened. */
    fun onError(utteranceId: String, message: String)
}

/**
 * The speech engine, as the rest of the app sees it (T64). The platform
 * adapter implements it; the session drives it. Nothing else in the app talks
 * to `TextToSpeech`, so the engine can be replaced or faked in a test.
 */
interface SpeechSynthesis {

    /** Speaks [text]; [utteranceId] comes back in the listener's callbacks. */
    fun speak(text: String, utteranceId: String, rate: Float)

    /** Stops the current utterance and keeps the engine usable. */
    fun stop()

    /** Releases the engine. Calling it twice is harmless. */
    fun shutdown()
}

/**
 * The voices of the device (T64).
 *
 * Only offline voices are offered: the reading must work without a network.
 * The list is sorted by locale and then by name, so the same voice is always
 * in the same place in the picker.
 */
object SpeechVoices {

    /** How long [list] waits for the engine to come up. */
    private const val INIT_TIMEOUT_SECONDS = 3L

    /**
     * The offline voices of the device, sorted by locale then name.
     *
     * The engine is created only to ask it for its voices and is shut down
     * again right away. Call this from a background thread: the engine has to
     * initialize before it can answer.
     */
    fun list(context: Context): List<SpeechVoice> {
        val app = context.applicationContext
        val initialized = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val engine = TextToSpeech(app) { result ->
            status = result
            initialized.countDown()
        }
        try {
            if (!initialized.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return emptyList()
            if (status != TextToSpeech.SUCCESS) return emptyList()
            return usable(engine.voices.orEmpty().map { it.toSpeechVoice() })
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return emptyList()
        } finally {
            engine.shutdown()
        }
    }

    /** The voice with this key, or null when the device no longer has it. */
    fun resolve(context: Context, key: SpeechVoiceKey?): SpeechVoice? {
        if (key == null) return null
        return resolve(list(context), key)
    }

    /** The pure lookup: the voice with this key, or null when there is none. */
    internal fun resolve(voices: List<SpeechVoice>, key: SpeechVoiceKey?): SpeechVoice? {
        if (key == null) return null
        return voices.firstOrNull { it.key == key }
    }

    /** The pure filter: offline only, sorted by locale then name. */
    internal fun usable(voices: List<SpeechVoice>): List<SpeechVoice> =
        voices.filter { it.offline }
            .sortedWith(compareBy({ it.key.locale }, { it.key.name }))
}

/** The platform voice as our own model. */
internal fun Voice.toSpeechVoice(): SpeechVoice {
    val tag = locale?.toLanguageTag() ?: Locale.getDefault().toLanguageTag()
    val voiceName = name.orEmpty()
    val features = features.orEmpty()
    return SpeechVoice(
        key = SpeechVoiceKey(engine = "", locale = tag, name = voiceName),
        label = voiceName.ifBlank { tag },
        // A voice that is not installed cannot be spoken at all, and one that
        // needs the network cannot be spoken without a connection.
        offline = !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) &&
            !isNetworkConnectionRequired &&
            !features.contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
    )
}
