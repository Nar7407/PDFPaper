package com.pdfpaper.app.ui.viewer.speech

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Opens the system's text-to-speech settings. Android exposes no public
 * constant for the screen, so the well-known settings action is tried first
 * and the accessibility settings (where the screen is listed) is the fallback.
 */
fun openTtsSettings(context: Context): Boolean {
    val engineSettings = Intent(TTS_SETTINGS_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(engineSettings) }.isSuccess) return true
    val accessibility = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(accessibility) }.isSuccess
}

private const val TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"
