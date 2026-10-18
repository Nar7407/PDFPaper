package com.pdfpaper.app.data

import android.content.Context
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/** A document the user opened before, stored as a SAF URI plus its display name. */
data class RecentFile(
    val uri: String,
    val displayName: String
)

/**
 * Storage format for the recent list. Pure JVM so it is unit-testable.
 *
 * Each record is two Base64-URL fields separated by a tab, one record per line.
 * Base64 keeps arbitrary URI/display-name characters (including tabs and
 * newlines) safe, and malformed records are skipped rather than throwing.
 */
object RecentCodec {

    const val MAX_RECENT: Int = 20

    private const val FIELD_SEPARATOR = '\t'
    private const val RECORD_SEPARATOR = '\n'

    fun encode(files: List<RecentFile>): String =
        files.take(MAX_RECENT).joinToString(RECORD_SEPARATOR.toString()) { file ->
            "${encodeField(file.uri)}$FIELD_SEPARATOR${encodeField(file.displayName)}"
        }

    fun decode(raw: String?): List<RecentFile> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(RECORD_SEPARATOR)
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val fields = line.split(FIELD_SEPARATOR, limit = 2)
                if (fields.size != 2) return@mapNotNull null
                val uri = decodeField(fields[0]) ?: return@mapNotNull null
                val name = decodeField(fields[1]) ?: ""
                if (uri.isBlank()) null else RecentFile(uri, name)
            }
            .distinctBy { it.uri }
            .take(MAX_RECENT)
            .toList()
    }

    /** Puts [file] first, removes an older entry for the same URI, and caps the list. */
    fun add(files: List<RecentFile>, file: RecentFile): List<RecentFile> =
        (listOf(file) + files.filterNot { it.uri == file.uri }).take(MAX_RECENT)

    private fun encodeField(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(UTF_8))

    private fun decodeField(value: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(value), UTF_8)
    }.getOrNull()
}

/** Reads and writes the recent list in app-private SharedPreferences. */
class RecentStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun list(): List<RecentFile> = RecentCodec.decode(preferences.getString(KEY_RECENT, null))

    fun add(uri: String, displayName: String) {
        val next = RecentCodec.add(list(), RecentFile(uri, displayName))
        preferences.edit().putString(KEY_RECENT, RecentCodec.encode(next)).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_RECENT).apply()
    }

    private companion object {
        const val PREFERENCES = "pdfpaper_recent"
        const val KEY_RECENT = "files"
    }
}
