package com.pdfpaper.app.ui.viewer.speech

/**
 * What the platform told us about the sound (T64).
 *
 * [GAIN] and the losses come from the audio-focus listener; [NOISY] comes from
 * the "becoming noisy" broadcast, which the platform sends when the headphones
 * are unplugged.
 */
enum class FocusEvent {
    GAIN,
    LOSS,
    LOSS_TRANSIENT,
    LOSS_TRANSIENT_CAN_DUCK,
    DUCK,
    NOISY
}

/** What the reading does about a [FocusEvent]. */
enum class FocusAction {
    /** Nothing changes. */
    NONE,

    /** The reading stops and waits. */
    PAUSE,

    /** The reading starts again where it stopped. */
    RESUME
}

/**
 * The rules for audio focus and headphones (T64), away from the platform so
 * they can be read in one place and tested without a device.
 *
 * A permanent loss pauses and stays paused: another app took the sound and the
 * reader has to press play. A transient loss pauses and comes back when the
 * focus returns, because a phone call that is answered and hung up should not
 * cost the reader their place. A duck is ignored - speech must not be quieted
 * behind another app - and unplugging the headphones pauses without a restart,
 * because nobody wants a document read aloud in a public place.
 */
object AudioFocusPolicy {

    /** What the event means on its own. */
    fun action(event: FocusEvent): FocusAction = when (event) {
        FocusEvent.GAIN -> FocusAction.RESUME
        FocusEvent.LOSS -> FocusAction.PAUSE
        FocusEvent.LOSS_TRANSIENT -> FocusAction.PAUSE
        FocusEvent.LOSS_TRANSIENT_CAN_DUCK -> FocusAction.NONE
        FocusEvent.DUCK -> FocusAction.NONE
        FocusEvent.NOISY -> FocusAction.PAUSE
    }

    /**
     * What the event means given what paused the reading.
     *
     * [previous] is the event that paused it, or null when the reading was not
     * paused by the sound. Only a transient loss is undone by a later gain: the
     * gain after a headphone unplug is not ours to act on, and a gain after a
     * permanent loss belongs to another app.
     */
    fun actionAfter(previous: FocusEvent?, event: FocusEvent): FocusAction {
        if (event != FocusEvent.GAIN) return action(event)
        return when (previous) {
            FocusEvent.LOSS_TRANSIENT -> FocusAction.RESUME
            else -> FocusAction.NONE
        }
    }
}
