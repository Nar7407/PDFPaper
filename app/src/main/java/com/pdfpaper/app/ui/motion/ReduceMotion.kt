package com.pdfpaper.app.ui.motion

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationInstance
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.Color
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material.ripple.RippleTheme

/**
 * Task T63: the app-wide reduce-motion flag.
 *
 * A reflective (E-Ink) panel repaints slowly, so an animation is not just
 * decoration there: it is a trail of ghosting. The reader can ask the whole
 * app to stop moving, and every motion decision reads this local rather than
 * each screen inventing its own switch.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** A fling that does not coast; scrolling stops when the finger lifts. */
object NoFling : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float = 0f
}

/**
 * An indication that draws nothing. Replacing [androidx.compose.foundation.
 * LocalIndication] with it removes the press ripple from every clickable, the
 * largest remaining source of motion in the chrome.
 */
object NoIndication : Indication {

    private object Instance : IndicationInstance {
        override fun ContentDrawScope.drawIndication() {
            drawContent()
        }
    }

    @Composable
    override fun rememberUpdatedInstance(interactionSource: androidx.compose.foundation.interaction.InteractionSource): IndicationInstance =
        Instance
}

/** The Material ripple theme that paints no ripple. */
object NoRippleTheme : RippleTheme {
    @Composable
    override fun defaultColor(): Color = Color.Transparent

    @Composable
    override fun rippleAlpha(): RippleAlpha = RippleAlpha(0f, 0f, 0f, 0f)
}
