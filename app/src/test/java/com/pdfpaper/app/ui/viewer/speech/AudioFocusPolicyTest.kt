package com.pdfpaper.app.ui.viewer.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T64: what an audio-focus or headphone event does to the reading.
 *
 * The rules are here, apart from the platform, so they can be read in one
 * place: a permanent loss pauses, a transient loss pauses and comes back when
 * the focus returns, a duck is ignored (speech must not be quieted behind
 * another app), and unplugging the headphones pauses without a restart.
 */
class AudioFocusPolicyTest {

    @Test
    fun aPermanentLossPauses() {
        assertEquals(FocusAction.PAUSE, AudioFocusPolicy.action(FocusEvent.LOSS))
    }

    @Test
    fun aTransientLossPausesAndTheGainResumes() {
        assertEquals(FocusAction.PAUSE, AudioFocusPolicy.action(FocusEvent.LOSS_TRANSIENT))
        assertEquals(
            FocusAction.RESUME,
            AudioFocusPolicy.actionAfter(FocusEvent.LOSS_TRANSIENT, FocusEvent.GAIN)
        )
    }

    @Test
    fun aDuckIsIgnored() {
        assertEquals(
            FocusAction.NONE,
            AudioFocusPolicy.action(FocusEvent.LOSS_TRANSIENT_CAN_DUCK)
        )
        assertEquals(FocusAction.NONE, AudioFocusPolicy.action(FocusEvent.DUCK))
    }

    @Test
    fun theNoiseEventPauses() {
        assertEquals(FocusAction.PAUSE, AudioFocusPolicy.action(FocusEvent.NOISY))
        // A headphone unplug is not a focus event, so the next gain does not
        // restart the reading.
        assertEquals(
            FocusAction.NONE,
            AudioFocusPolicy.actionAfter(FocusEvent.NOISY, FocusEvent.GAIN)
        )
    }
}
