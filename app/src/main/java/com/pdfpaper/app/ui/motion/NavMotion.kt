package com.pdfpaper.app.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

/**
 * Task T63: how a screen change looks.
 *
 * The default is a short fade. Under reduce motion there is no animation at
 * all - a reflective panel would show the fade as a trail of ghosting, and a
 * reader who asked for less movement must not get any.
 */
object NavMotion {

    /** How a screen arrives. */
    fun enter(reduceMotion: Boolean): EnterTransition =
        if (reduceMotion) EnterTransition.None else fadeIn()

    /** How a screen leaves. */
    fun exit(reduceMotion: Boolean): ExitTransition =
        if (reduceMotion) ExitTransition.None else fadeOut()
}
