package com.pdfpaper.app.input

import android.view.KeyEvent

/**
 * The navigation actions a hardware key or a Bluetooth clicker can trigger
 * (T58). This is the one table both the reader and the presentation use, so a
 * clicker that pages forward in a deck also pages forward in a document.
 */
enum class KeyAction {
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
 * The shared key table (T58).
 *
 * Clickers are inconsistent: most send PAGE_DOWN/PAGE_UP, others send arrows,
 * Enter or the media keys, and keyboards add Space and Escape. Presentation
 * mode keeps its own meaning for the volume pair (there, up is "next"); the
 * reader maps the volume keys through
 * [com.pdfpaper.app.ui.viewer.turn.TurnKeys] instead, because a reader expects
 * volume DOWN to go forward.
 */
object KeyMap {

    fun action(keyCode: Int): KeyAction? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> KeyAction.NEXT

        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_REWIND -> KeyAction.PREVIOUS

        KeyEvent.KEYCODE_MOVE_HOME -> KeyAction.FIRST
        KeyEvent.KEYCODE_MOVE_END -> KeyAction.LAST

        // "B" (and the period some clickers send) is the usual black-screen
        // shortcut in presentation software.
        KeyEvent.KEYCODE_B,
        KeyEvent.KEYCODE_PERIOD -> KeyAction.TOGGLE_BLACK

        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_TAB -> KeyAction.TOGGLE_CONTROLS

        KeyEvent.KEYCODE_P -> KeyAction.TOGGLE_PEN

        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE -> KeyAction.TOGGLE_TIMER

        KeyEvent.KEYCODE_ESCAPE -> KeyAction.EXIT

        else -> null
    }

    /** True when [keyCode] is in the shared table (its press or its release). */
    fun handles(keyCode: Int): Boolean = keyCode in handledKeys

    /** The reading subset: only the actions that move through pages. */
    fun isPaging(action: KeyAction): Boolean =
        action == KeyAction.NEXT || action == KeyAction.PREVIOUS ||
            action == KeyAction.FIRST || action == KeyAction.LAST

    val handledKeys: Set<Int> = setOf(
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MOVE_HOME,
        KeyEvent.KEYCODE_MOVE_END,
        KeyEvent.KEYCODE_B,
        KeyEvent.KEYCODE_PERIOD,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_P,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_ESCAPE
    )
}
