package com.pdfpaper.app.launcher

import android.content.Intent

/** The actions behind the static launcher shortcuts and the widget buttons. */
enum class LauncherAction {
    OPEN_PDF,
    SCAN,
    MERGE,
    RECENT
}

/**
 * One action delivered to the running activity. [id] distinguishes two taps
 * of the same shortcut, so consuming one action never clears a newer one.
 */
data class LauncherLaunchRequest(
    val id: Long,
    val action: LauncherAction
)

object LauncherActions {

    const val ACTION_OPEN_PDF = "com.pdfpaper.app.action.OPEN_PDF"
    const val ACTION_SCAN = "com.pdfpaper.app.action.SCAN"
    const val ACTION_MERGE = "com.pdfpaper.app.action.MERGE"
    const val ACTION_RECENT = "com.pdfpaper.app.action.RECENT"

    /** Returns null for an ordinary launcher or VIEW/SEND intent. */
    fun parse(intent: Intent?): LauncherAction? = when (intent?.action) {
        ACTION_OPEN_PDF -> LauncherAction.OPEN_PDF
        ACTION_SCAN -> LauncherAction.SCAN
        ACTION_MERGE -> LauncherAction.MERGE
        ACTION_RECENT -> LauncherAction.RECENT
        else -> null
    }

    fun request(intent: Intent?, id: Long): LauncherLaunchRequest? =
        parse(intent)?.let { LauncherLaunchRequest(id, it) }
}
