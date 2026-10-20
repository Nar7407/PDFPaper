package com.pdfpaper.app.data

import android.content.Context
import com.pdfpaper.engine.stamp.CustomStamp
import com.pdfpaper.engine.stamp.StampJson
import java.io.File

/**
 * Persists the user's custom stamps as the engine's JSON list. The list lives
 * in app-private storage, so image stamps (whose original bytes are embedded
 * in the JSON) survive restarts. Corrupt files read as an empty list.
 */
class CustomStampStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun load(): List<CustomStamp> = StampJson.load(file).getOrDefault(emptyList())

    /** Adds or replaces [stamp] and returns the new list. */
    fun add(stamp: CustomStamp): Result<List<CustomStamp>> = runCatching {
        val stamps = load().filterNot { it.id.equals(stamp.id, ignoreCase = true) } + stamp
        StampJson.save(file, stamps).getOrThrow()
        stamps
    }

    private companion object {
        const val FILE_NAME = "custom-stamps.json"
    }
}
