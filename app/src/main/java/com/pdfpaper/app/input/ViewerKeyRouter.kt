package com.pdfpaper.app.input

import android.view.KeyEvent

/**
 * The viewer installs its key handler here while it is the visible screen
 * (T58). [MainActivity.dispatchKeyEvent] asks the presentation router first and
 * this one second, so a document pages with the volume keys while a deck still
 * behaves like a deck. A handler that is null means "no viewer in front", which
 * is exactly when the volume keys must go back to the system.
 */
object ViewerKeyRouter {
    @Volatile
    var handler: ((KeyEvent) -> Boolean)? = null

    fun dispatch(event: KeyEvent): Boolean = handler?.invoke(event) == true
}
