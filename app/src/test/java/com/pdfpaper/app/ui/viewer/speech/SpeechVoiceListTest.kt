package com.pdfpaper.app.ui.viewer.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T64: the voice list the reader may pick from. Only the device's own offline
 * voices are offered — a voice that needs the network is never in the list,
 * because the app has no network permission and the reading must work on a
 * plane.
 */
class SpeechVoiceListTest {

    private fun voice(
        locale: String,
        name: String,
        offline: Boolean = true
    ): SpeechVoice = SpeechVoice(
        key = SpeechVoiceKey("engine", locale, name),
        label = name,
        offline = offline
    )

    @Test
    fun aNetworkVoiceIsNeverOffered() {
        val voices = listOf(
            voice("en-US", "Network voice", offline = false),
            voice("en-US", "Offline voice")
        )

        assertEquals(
            listOf("Offline voice"),
            SpeechVoices.usable(voices).map { it.label }
        )
    }

    @Test
    fun voicesAreSortedByLocaleThenName() {
        val voices = listOf(
            voice("en-US", "Zoe"),
            voice("de-DE", "Anna"),
            voice("en-US", "Adam"),
            voice("en-GB", "Mia")
        )

        assertEquals(
            listOf("Anna", "Mia", "Adam", "Zoe"),
            SpeechVoices.usable(voices).map { it.label }
        )
    }

    @Test
    fun resolvingAMissingVoiceReturnsNull() {
        val voices = listOf(voice("en-US", "Adam"))

        assertNull(SpeechVoices.resolve(voices, SpeechVoiceKey("engine", "en-US", "Gone")))
        assertNull(SpeechVoices.resolve(voices, null))
    }
}
