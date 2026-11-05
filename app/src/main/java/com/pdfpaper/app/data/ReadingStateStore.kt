package com.pdfpaper.app.data

import android.content.Context

/**
 * The document the reader touched last, for the Home screen's "Continue
 * reading" card (T38b).
 */
data class LastReading(
    val origin: String,
    val displayName: String?,
    val state: ReadingState
)

/**
 * Reads and writes one [ReadingState] per document in app-private
 * SharedPreferences (T38b). The key is the document's **origin** (the URI it
 * was opened from), never the cache copy the viewer happens to read: a cache
 * copy is named after the file and changes between opens, so keying on it would
 * forget the position every time.
 */
class ReadingStateStore(context: Context) {

    private val appContext = context.applicationContext
    private val preferences = appContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(origin: String): ReadingState? {
        if (origin.isBlank()) return null
        return ReadingStateCodec.decode(preferences.getString(KEY_DOC + key(origin), null))
    }

    /**
     * Saves [state] for [origin] and makes it the last document the reader had
     * open. [displayName] is only stored for the Home card; the state itself
     * carries no file name.
     */
    fun save(origin: String, displayName: String?, state: ReadingState) {
        if (origin.isBlank()) return
        preferences.edit()
            .putString(KEY_DOC + key(origin), ReadingStateCodec.encode(state))
            .putString(KEY_LAST_ORIGIN, origin)
            .putString(KEY_LAST_NAME, displayName.orEmpty())
            .apply()
    }

    /** Forgets where the reader was in [origin], keeping every other document. */
    fun forget(origin: String) {
        if (origin.isBlank()) return
        val edit = preferences.edit().remove(KEY_DOC + key(origin))
        if (preferences.getString(KEY_LAST_ORIGIN, null) == origin) {
            edit.remove(KEY_LAST_ORIGIN).remove(KEY_LAST_NAME)
        }
        edit.apply()
    }

    /** The last document with a saved session, or null when there is none. */
    fun lastDocument(): LastReading? {
        val origin = preferences.getString(KEY_LAST_ORIGIN, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val state = load(origin) ?: return null
        val name = preferences.getString(KEY_LAST_NAME, null)?.takeIf { it.isNotBlank() }
        return LastReading(origin, name, state)
    }

    private fun key(origin: String): String =
        origin.hashCode().toUInt().toString(16)

    private companion object {
        const val PREFERENCES = "pdfpaper_reading"
        const val KEY_DOC = "doc."
        const val KEY_LAST_ORIGIN = "last.origin"
        const val KEY_LAST_NAME = "last.name"
    }
}
