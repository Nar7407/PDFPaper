package com.pdfpaper.app.data

import android.content.Context
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/**
 * A document the user opened before, stored as a SAF URI plus its display name
 * and the kind of file it was ("pdf", "docx" or "pptx") so Recent can reopen
 * an office file through the same conversion flow.
 */
data class RecentFile(
    val uri: String,
    val displayName: String,
    val kind: String = KIND_PDF
) {
    /** A human label for the recent row. */
    val kindLabel: String
        get() = when (kind) {
            KIND_DOCX -> "Word document"
            KIND_PPTX -> "PowerPoint presentation"
            else -> "PDF"
        }

    companion object {
        const val KIND_PDF = "pdf"
        const val KIND_DOCX = "docx"
        const val KIND_PPTX = "pptx"

        val KNOWN_KINDS = setOf(KIND_PDF, KIND_DOCX, KIND_PPTX)
    }
}

/**
 * Storage format for the recent list. Pure JVM so it is unit-testable.
 *
 * Each record is two or three Base64-URL fields separated by a tab, one record
 * per line; records written before the kind field existed decode as PDFs.
 * Base64 keeps arbitrary URI/display-name characters (including tabs and
 * newlines) safe, and malformed records are skipped rather than throwing.
 */
object RecentCodec {

    const val MAX_RECENT: Int = 20

    private const val FIELD_SEPARATOR = '\t'
    private const val RECORD_SEPARATOR = '\n'

    fun encode(files: List<RecentFile>): String =
        files.take(MAX_RECENT).joinToString(RECORD_SEPARATOR.toString()) { file ->
            "${encodeField(file.uri)}$FIELD_SEPARATOR${encodeField(file.displayName)}" +
                "$FIELD_SEPARATOR${encodeField(file.kind)}"
        }

    fun decode(raw: String?): List<RecentFile> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(RECORD_SEPARATOR)
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val fields = line.split(FIELD_SEPARATOR, limit = 3)
                if (fields.size < 2) return@mapNotNull null
                val uri = decodeField(fields[0]) ?: return@mapNotNull null
                val name = decodeField(fields[1]) ?: ""
                val kind = fields.getOrNull(2)?.let { decodeField(it) }
                    ?.takeIf { it in RecentFile.KNOWN_KINDS }
                    ?: RecentFile.KIND_PDF
                if (uri.isBlank()) null else RecentFile(uri, name, kind)
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

    fun add(uri: String, displayName: String, kind: String = RecentFile.KIND_PDF) {
        val next = RecentCodec.add(list(), RecentFile(uri, displayName, kind))
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
