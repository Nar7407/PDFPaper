package com.pdfpaper.app.present

import android.view.KeyEvent
import com.pdfpaper.app.input.KeyAction
import com.pdfpaper.app.input.KeyMap

/**
 * What a presentation key does (T53). The mapping is deliberately free of UI
 * code so the clicker/keyboard contract is unit-tested on the JVM.
 */
enum class PresentCommand {
    NEXT,
    PREVIOUS,
    FIRST,
    LAST,
    TOGGLE_BLACK,
    TOGGLE_CONTROLS,
    TOGGLE_PEN,
    TOGGLE_TIMER,
    EXIT
}

/**
 * Maps hardware keys to presentation commands (T53, shared with T58).
 *
 * The paging/black/pen/exit table lives in [KeyMap] so the reader and the
 * presentation agree on what a clicker sends. Only the volume pair stays here:
 * while presenting, volume UP is "next" (the clicker convention), while the
 * reader maps it the other way round in
 * [com.pdfpaper.app.ui.viewer.turn.TurnKeys].
 */
object PresentKeyMapper {

    fun command(keyCode: Int, action: Int, repeatCount: Int = 0): PresentCommand? {
        if (action != KeyEvent.ACTION_DOWN || repeatCount > 0) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> PresentCommand.NEXT
            KeyEvent.KEYCODE_VOLUME_DOWN -> PresentCommand.PREVIOUS
            else -> KeyMap.action(keyCode)?.toPresentCommand()
        }
    }

    /** True when the key belongs to the presentation, including its release. */
    fun handles(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            KeyMap.handles(keyCode)

    private fun KeyAction.toPresentCommand(): PresentCommand = when (this) {
        KeyAction.NEXT -> PresentCommand.NEXT
        KeyAction.PREVIOUS -> PresentCommand.PREVIOUS
        KeyAction.FIRST -> PresentCommand.FIRST
        KeyAction.LAST -> PresentCommand.LAST
        KeyAction.TOGGLE_BLACK -> PresentCommand.TOGGLE_BLACK
        KeyAction.TOGGLE_CONTROLS -> PresentCommand.TOGGLE_CONTROLS
        KeyAction.TOGGLE_PEN -> PresentCommand.TOGGLE_PEN
        KeyAction.TOGGLE_TIMER -> PresentCommand.TOGGLE_TIMER
        KeyAction.EXIT -> PresentCommand.EXIT
    }
}

/**
 * The activity installs the presentation's key handler here while the mode is
 * active (T53). [MainActivity.dispatchKeyEvent] asks it before the platform so
 * volume and clicker keys do not change the volume or leak to another app.
 */
object PresentKeyRouter {
    @Volatile
    var handler: ((KeyEvent) -> Boolean)? = null

    fun dispatch(event: KeyEvent): Boolean = handler?.invoke(event) == true
}
