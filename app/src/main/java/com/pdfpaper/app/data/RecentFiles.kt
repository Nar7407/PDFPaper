package com.pdfpaper.app.data

import android.content.Context
import com.pdfpaper.app.launcher.LauncherSurfaces
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
    val kind: String = KIND_PDF,
    /** Zero-based page the reader was on, for the resume line (T38b). */
    val page: Int = 0,
    /** The page count when [page] was saved; 0 when nothing is known yet. */
    val pageCount: Int = 0
) {
    /** A human label for the recent row. */
    val kindLabel: String
        get() = when (kind) {
            KIND_DOCX -> "Word document"
            KIND_PPTX -> "PowerPoint presentation"
            else -> "PDF"
        }

    /** "Page 48 of 210", or null when this file has no saved position yet. */
    val progressLabel: String? get() = readingProgressLabel(page, pageCount)

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
 * Each record is two to five Base64-URL fields separated by a tab, one record
 * per line; records written before the kind and progress fields existed decode
 * as PDFs with no saved position. Base64 keeps arbitrary URI/display-name
 * characters (including tabs and newlines) safe, and malformed records are
 * skipped rather than throwing.
 */
object RecentCodec {

    const val MAX_RECENT: Int = 20

    private const val FIELD_SEPARATOR = '\t'
    private const val RECORD_SEPARATOR = '\n'

    fun encode(files: List<RecentFile>): String =
        files.take(MAX_RECENT).joinToString(RECORD_SEPARATOR.toString()) { file ->
            "${encodeField(file.uri)}$FIELD_SEPARATOR${encodeField(file.displayName)}" +
                "$FIELD_SEPARATOR${encodeField(file.kind)}" +
                "$FIELD_SEPARATOR${encodeField(file.page.toString())}" +
                "$FIELD_SEPARATOR${encodeField(file.pageCount.toString())}"
        }

    fun decode(raw: String?): List<RecentFile> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(RECORD_SEPARATOR)
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val fields = line.split(FIELD_SEPARATOR, limit = 5)
                if (fields.size < 2) return@mapNotNull null
                val uri = decodeField(fields[0]) ?: return@mapNotNull null
                val name = decodeField(fields[1]) ?: ""
                val kind = fields.getOrNull(2)?.let { decodeField(it) }
                    ?.takeIf { it in RecentFile.KNOWN_KINDS }
                    ?: RecentFile.KIND_PDF
                val page = fields.getOrNull(3)?.let { decodeField(it) }?.toIntOrNull() ?: 0
                val pageCount = fields.getOrNull(4)?.let { decodeField(it) }?.toIntOrNull() ?: 0
                if (uri.isBlank()) {
                    null
                } else {
                    RecentFile(
                        uri = uri,
                        displayName = name,
                        kind = kind,
                        page = page.coerceAtLeast(0),
                        pageCount = pageCount.coerceAtLeast(0)
                    )
                }
            }
            .distinctBy { it.uri }
            .take(MAX_RECENT)
            .toList()
    }

    /**
     * Puts [file] first, removes an older entry for the same URI, and caps the
     * list. Re-adding a file must not lose the position already saved for it,
     * because opening a document re-adds it before the viewer knows the page.
     */
    fun add(files: List<RecentFile>, file: RecentFile): List<RecentFile> {
        val previous = files.firstOrNull { it.uri == file.uri }
        val merged = if (previous == null) {
            file
        } else {
            file.copy(
                page = if (file.pageCount > 0) file.page else previous.page,
                pageCount = if (file.pageCount > 0) file.pageCount else previous.pageCount
            )
        }
        return (listOf(merged) + files.filterNot { it.uri == file.uri }).take(MAX_RECENT)
    }

    private fun encodeField(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(UTF_8))

    private fun decodeField(value: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(value), UTF_8)
    }.getOrNull()
}

/** Reads and writes the recent list in app-private SharedPreferences. */
class RecentStore(context: Context) {

    private val appContext = context.applicationContext
    private val preferences = appContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun list(): List<RecentFile> = RecentCodec.decode(preferences.getString(KEY_RECENT, null))

    fun add(uri: String, displayName: String, kind: String = RecentFile.KIND_PDF) {
        val next = RecentCodec.add(list(), RecentFile(uri, displayName, kind))
        preferences.edit().putString(KEY_RECENT, RecentCodec.encode(next)).apply()
        LauncherSurfaces.onRecentsChanged(appContext)
    }

    /**
     * Records where the reader stopped in [uri] so the Recent row can say
     * "Page 48 of 210" (T38b). A file that is not in the list is ignored: the
     * recent list is the only place this line is shown.
     */
    fun setProgress(uri: String, page: Int, pageCount: Int) {
        if (uri.isBlank() || pageCount <= 0) return
        val files = list()
        val index = files.indexOfFirst { it.uri == uri }
        if (index < 0) return
        val current = files[index]
        val nextPage = page.coerceIn(0, pageCount - 1)
        if (current.page == nextPage && current.pageCount == pageCount) return
        val next = files.toMutableList().also {
            it[index] = current.copy(page = nextPage, pageCount = pageCount)
        }
        preferences.edit().putString(KEY_RECENT, RecentCodec.encode(next)).apply()
        LauncherSurfaces.onRecentsChanged(appContext)
    }

    fun clear() {
        preferences.edit().remove(KEY_RECENT).apply()
        LauncherSurfaces.onRecentsChanged(appContext)
    }

    private companion object {
        const val PREFERENCES = "pdfpaper_recent"
        const val KEY_RECENT = "files"
    }
}
