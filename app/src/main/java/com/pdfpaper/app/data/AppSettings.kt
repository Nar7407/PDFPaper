package com.pdfpaper.app.data

import android.content.Context

/**
 * Small, app-private settings store (task UI-2). The author name is written
 * into comments, replies and dynamic stamps, so it must survive restarts but
 * never leaves the device.
 */
/** What a tap on an internal link or reference does (T61). */
enum class LinkTapBehavior {
    /** Show the floating preview card first (default). */
    PREVIEW_FIRST,

    /** Jump to the target immediately. */
    JUMP_DIRECTLY
}

object AppSettings {

    const val DEFAULT_AUTHOR = "You"

    private const val PREFS = "pdfpaper_settings"
    private const val KEY_AUTHOR = "author_name"
    private const val KEY_LINK_TAP = "link_tap_behavior"
    private const val MAX_AUTHOR_LENGTH = 80

    fun authorName(context: Context): String =
        prefs(context).getString(KEY_AUTHOR, null)
            ?.trim()
            ?.take(MAX_AUTHOR_LENGTH)
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_AUTHOR

    /** Stores [name]; a blank name falls back to [DEFAULT_AUTHOR] when read. */
    fun setAuthorName(context: Context, name: String) {
        prefs(context).edit()
            .putString(KEY_AUTHOR, name.trim().take(MAX_AUTHOR_LENGTH))
            .apply()
    }

    /** Defaults to [LinkTapBehavior.PREVIEW_FIRST] for existing installs. */
    fun linkTapBehavior(context: Context): LinkTapBehavior {
        val raw = prefs(context).getString(KEY_LINK_TAP, null)
        return LinkTapBehavior.entries.firstOrNull { it.name == raw }
            ?: LinkTapBehavior.PREVIEW_FIRST
    }

    fun setLinkTapBehavior(context: Context, behavior: LinkTapBehavior) {
        prefs(context).edit()
            .putString(KEY_LINK_TAP, behavior.name)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
