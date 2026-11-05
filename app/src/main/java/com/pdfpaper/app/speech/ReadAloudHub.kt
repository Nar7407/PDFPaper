package com.pdfpaper.app.speech

import com.pdfpaper.app.ui.viewer.speech.ReadAloudPlaybackState
import com.pdfpaper.app.ui.viewer.speech.SleepOption
import com.pdfpaper.app.ui.viewer.speech.SpeechPosition
import com.pdfpaper.app.ui.viewer.speech.SpeechVoiceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The bridge between the viewer and the reading service (T64).
 *
 * The viewer never binds to the service: it sends commands through [commands]
 * and reads the state from [state]. The service owns the reading, so the two
 * can never disagree about what is being read.
 *
 * The bridge lives in the app's own process. When the process is killed the
 * service dies with it, so there is never a stale "still reading" state - the
 * price is that the reading does not survive the process, which is what the
 * reader expects anyway.
 */
object ReadAloudHub {

    private val _state = MutableStateFlow(ReadAloudPlaybackState())

    /** What the viewer shows: the state of the running reading. */
    val state: StateFlow<ReadAloudPlaybackState> = _state.asStateFlow()

    /**
     * The commands the viewer sends. The service registers itself here when it
     * is created and clears the slot when it is destroyed.
     */
    @Volatile
    var commands: Commands? = null

    /** The service publishes what the reading is doing. */
    fun publish(state: ReadAloudPlaybackState) {
        _state.value = state
    }

    /** Nothing is being read any more. */
    fun clear() {
        _state.value = ReadAloudPlaybackState()
    }

    /**
     * What the viewer can ask the service to do.
     *
     * Not a `fun interface`: it has one method per command, so a lambda cannot
     * implement it.
     */
    interface Commands {
        /** Reads another document, or the same one from the start. */
        fun start(
            location: String,
            displayName: String,
            password: String,
            position: SpeechPosition
        )

        fun pause()
        fun resume()
        fun stop()
        fun nextParagraph()
        fun previousParagraph()
        fun nextChapter()
        fun previousChapter()

        /** Changes the pace; the current paragraph is replayed at it. */
        fun setSpeed(rate: Float)

        /** Sets how long the reading keeps going. */
        fun setSleep(option: SleepOption)

        /** Switches the voice; null asks for the device's default voice. */
        fun setVoice(key: SpeechVoiceKey?)
    }
}
