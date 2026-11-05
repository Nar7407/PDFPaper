package com.pdfpaper.app.ui.viewer.turn

import android.content.Context
import android.provider.Settings

/**
 * The two system switches page turning has to respect (T58). Both live behind
 * a platform read, so the pure model takes their result as a parameter.
 */
object TurnSystem {

    /**
     * True when the user asked Android to remove animations. The accessibility
     * setting is the animator duration scale, which also covers the transition
     * scale some devices expose separately.
     */
    fun animationsRemoved(context: Context): Boolean {
        val resolver = context.contentResolver
        val animator = runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        val transition = runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        }.getOrDefault(1f)
        return animator <= 0f || transition <= 0f
    }

    /** True when the system haptic feedback switch is on (the default). */
    fun hapticsEnabled(context: Context): Boolean = runCatching {
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1
        ) != 0
    }.getOrDefault(true)
}
