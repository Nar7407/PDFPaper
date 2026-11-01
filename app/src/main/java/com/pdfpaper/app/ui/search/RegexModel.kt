package com.pdfpaper.app.ui.search

import com.pdfpaper.engine.text.NormalisedText
import com.pdfpaper.engine.text.RegexFlag
import com.pdfpaper.engine.text.RegexMatch
import com.pdfpaper.engine.text.RegexOptions
import com.pdfpaper.engine.text.RegexPatternError
import com.pdfpaper.engine.text.RegexResult
import com.pdfpaper.engine.text.TextRect
import java.util.Base64
import java.util.Locale

/**
 * T65: the pure half of regex search — the row model both scopes share, the
 * grouping and de-duplication, the two exports, the sentences the panel shows
 * and the saved-pattern codec. Nothing here touches Android, a coroutine or a
 * document, so every rule can be unit-tested on the JVM.
 */

/** Which text a run searches: the open document, or the local index. */
enum class RegexScope(val label: String) {
    DOCUMENT("This document"),
    LIBRARY("Library")
}

/** The shared default: case-insensitive, matches may cross a line break. */
val DEFAULT_REGEX_FLAGS: Set<RegexFlag> =
    setOf(RegexFlag.CASE_INSENSITIVE, RegexFlag.ACROSS_LINES)

/** The library runs over stored text, so it is capped higher than a document. */
const val LIBRARY_MAX_MATCHES = 5000
const val LIBRARY_RUN_TIME_MS = 30_000L

/**
 * One result row. [fileId] is 0 and [documentUri] is empty in document scope,
 * because there is exactly one document; [ordinal] is the match's position on
 * its page, which is what a library tap uses to re-find the exact instance.
 */
data class RegexRow(
    val fileId: Long,
    val documentUri: String,
    val displayName: String,
    val pageIndex: Int,
    val ordinal: Int,
    val text: String,
    val before: String,
    val after: String,
    val rects: List<TextRect> = emptyList()
) {
    /** The one-line snippet a row, the TXT and the CSV show. */
    val context: String get() = before + text + after
}

data class RegexPageGroup(val pageIndex: Int, val rows: List<RegexRow>)

data class RegexGroup(
    val fileId: Long,
    val documentUri: String,
    val displayName: String,
    val pages: List<RegexPageGroup>
)

data class RegexProgress(val filesDone: Int, val filesTotal: Int, val currentName: String)

/** The one-line tester result, or the compile error that replaced it. */
data class RegexTesterResult(val text: String, val error: RegexPatternError? = null)

/** The redaction panel this sheet hands its pattern to (T28's own panel). */
data class RegexRedactionHandoff(val pattern: String)

/** A saved pattern: the name, the expression, its flags and when it was made. */
data class RegexPattern(
    val id: String,
    val name: String,
    val pattern: String,
    val flags: Set<RegexFlag>,
    val createdAt: Long
)

/**
 * Everything one regex sheet draws. [rows] is the run's flat result in reading
 * order; the getters below are derived, never stored, so a toggle cannot leave
 * a stale summary behind.
 */
data class RegexUiState(
    val scope: RegexScope,
    val pattern: String = "",
    val flags: Set<RegexFlag> = DEFAULT_REGEX_FLAGS,
    val running: Boolean = false,
    val rows: List<RegexRow> = emptyList(),
    val error: RegexPatternError? = null,
    val truncated: Boolean = false,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val progress: RegexProgress? = null,
    val filesTotal: Int = 0,
    val unsearchable: List<String> = emptyList(),
    val uniqueOnly: Boolean = false,
    val activeIndex: Int = -1,
    val tester: RegexTesterResult? = null,
    val handoff: RegexRedactionHandoff? = null
) {
    val visibleRows: List<RegexRow>
        get() = if (uniqueOnly) uniqueRows(rows) else rows

    val activeRow: RegexRow?
        get() = visibleRows.getOrNull(activeIndex)

    val summary: String
        get() = describeRegexSummary(
            matchCount = rows.size,
            pageCount = rows.map { it.pageIndex }.distinct().size,
            fileCount = if (scope == RegexScope.LIBRARY) rows.map { it.fileId }.distinct().size else null,
            truncated = truncated,
            timedOut = timedOut,
            cancelled = cancelled,
            unsearchableCount = unsearchable.size
        )
}

// ------------------------------------------------------------------- options

/** Turns one chip on or off without caring about the set's iteration order. */
fun toggleRegexFlag(flags: Set<RegexFlag>, flag: RegexFlag, on: Boolean): Set<RegexFlag> =
    if (on) flags + flag else flags - flag

/** The matcher options a run with these chips and budgets uses. */
fun regexOptions(flags: Set<RegexFlag>, maxMatches: Int, runTimeMs: Long): RegexOptions =
    RegexOptions(flags = flags, maxMatches = maxMatches, runTimeLimitMs = runTimeMs)

// ---------------------------------------------------------------------- rows

/**
 * Converts one page's result into rows. A document-scope row carries no file
 * identity: the sheet already names the open document.
 */
fun rowsForPage(
    scope: RegexScope,
    fileId: Long,
    documentUri: String,
    displayName: String,
    pageIndex: Int,
    result: RegexResult
): List<RegexRow> {
    val matches = (result as? RegexResult.Matches)?.matches ?: return emptyList()
    val id = if (scope == RegexScope.DOCUMENT) 0L else fileId
    val uri = if (scope == RegexScope.DOCUMENT) "" else documentUri
    return matches.map { match ->
        RegexRow(
            fileId = id,
            documentUri = uri,
            displayName = displayName,
            pageIndex = pageIndex,
            ordinal = match.ordinal,
            text = match.text,
            before = match.before,
            after = match.after,
            rects = match.rects
        )
    }
}

/** Files in the order they first appeared, each with its pages in reading order. */
fun groupRows(rows: List<RegexRow>): List<RegexGroup> {
    val byFile = LinkedHashMap<Long, MutableList<RegexRow>>()
    for (row in rows) byFile.getOrPut(row.fileId) { ArrayList() } += row
    return byFile.map { (fileId, fileRows) ->
        val byPage = LinkedHashMap<Int, MutableList<RegexRow>>()
        for (row in fileRows) byPage.getOrPut(row.pageIndex) { ArrayList() } += row
        RegexGroup(
            fileId = fileId,
            documentUri = fileRows.first().documentUri,
            displayName = fileRows.first().displayName,
            pages = byPage.entries
                .sortedBy { it.key }
                .map { RegexPageGroup(it.key, it.value.toList()) }
        )
    }
}

/** "Unique matches only": one row per (file, page, text), keeping the first. */
fun uniqueRows(rows: List<RegexRow>): List<RegexRow> {
    val seen = HashSet<Triple<Long, Int, String>>()
    return rows.filter { seen.add(Triple(it.fileId, it.pageIndex, it.text)) }
}

/** The match with this occurrence ordinal on its page, or null. */
fun rowForOrdinal(result: RegexResult, ordinal: Int): RegexMatch? =
    (result as? RegexResult.Matches)?.matches?.firstOrNull { it.ordinal == ordinal }

// ------------------------------------------------------------------- exports

/**
 * `page <n>: <text> — <context>`, one line per match. A library row is prefixed
 * with its file name, which a document row does not need.
 */
fun rowsToTxt(rows: List<RegexRow>): String {
    if (rows.isEmpty()) return ""
    val builder = StringBuilder()
    for (row in rows) {
        if (row.documentUri.isNotEmpty()) builder.append(row.displayName).append(' ')
        builder.append("page ").append(row.pageIndex + 1).append(": ")
            .append(row.text).append(" \u2014 ").append(row.context).append('\n')
    }
    return builder.toString()
}

/** `file,page,match,context`, RFC 4180, UTF-8 with a BOM so Excel reads it. */
fun rowsToCsv(rows: List<RegexRow>): String {
    val builder = StringBuilder()
    builder.append('\uFEFF')
    builder.append("file,page,match,context\n")
    for (row in rows) {
        builder.append(csvField(row.displayName)).append(',')
            .append(row.pageIndex + 1).append(',')
            .append(csvField(row.text)).append(',')
            .append(csvField(row.context)).append('\n')
    }
    return builder.toString()
}

/** Quotes a CSV field only when it holds a comma, a quote or a line break. */
fun csvField(value: String): String {
    val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuotes) return value
    return '"' + value.replace("\"", "\"\"") + '"'
}

/** The suggested export name: the document's own name, or "library". */
fun regexFileName(scope: RegexScope, baseName: String, csv: Boolean): String {
    val extension = if (csv) "csv" else "txt"
    if (scope == RegexScope.LIBRARY) return "library-matches.$extension"
    val stem = baseName.substringBeforeLast('.', baseName)
    val cleaned = stem.map { ch ->
        if (ch.isLetterOrDigit() || ch == ' ' || ch == '-' || ch == '_' || ch == '(' || ch == ')') ch else '-'
    }.joinToString("").trim()
    return "${cleaned.ifEmpty { "matches" }}-matches.$extension"
}

// ------------------------------------------------------------------ sentences

/**
 * "7 matches in 2 files (3 pages)", plus one clause per reason the run was not
 * complete. A run with no rows says "No matches" and still names the files it
 * could not search, because "0" would be a lie about those files.
 */
fun describeRegexSummary(
    matchCount: Int,
    pageCount: Int,
    fileCount: Int?,
    truncated: Boolean,
    timedOut: Boolean,
    cancelled: Boolean,
    unsearchableCount: Int
): String {
    val parts = ArrayList<String>(4)
    if (matchCount == 0) {
        parts += "No matches"
    } else {
        val matches = plural(matchCount, "match", "matches")
        val pages = plural(pageCount, "page", "pages")
        parts += if (fileCount != null) {
            "$matches in ${plural(fileCount, "file", "files")} ($pages)"
        } else {
            "$matches on $pages"
        }
    }
    if (truncated) parts += "stopped after the match limit"
    if (timedOut) parts += "timed out"
    if (cancelled) parts += "cancelled"
    if (unsearchableCount > 0) {
        parts += "${plural(unsearchableCount, "file", "files")} could not be searched"
    }
    return parts.joinToString("; ")
}

/**
 * Library mode runs over the T50 index, which stored the page text with every
 * whitespace run collapsed — so the two line-related chips cannot do anything
 * there, and the panel says so instead of letting them look broken.
 */
fun describeRegexFlagsNote(scope: RegexScope): String? =
    if (scope == RegexScope.LIBRARY) {
        "Multiline and Across lines have no effect here: the indexed text has no line breaks."
    } else {
        null
    }

/**
 * The confirmation before highlight-all writes a copy: what it will add, and
 * the two reasons the copy is not simply the document on screen.
 */
fun regexConfirmText(
    matchCount: Int,
    truncated: Boolean,
    timedOut: Boolean,
    hasUnsavedEdits: Boolean
): String {
    val builder = StringBuilder()
    builder.append("Add ")
        .append(plural(matchCount, "highlight", "highlights"))
        .append(" to a new copy? The original file is not changed.")
    if (truncated || timedOut) {
        builder.append(" The search stopped early, so only the matches it found are marked.")
    }
    if (hasUnsavedEdits) {
        builder.append(" Unsaved annotations and form values are not in the copy.")
    }
    return builder.toString()
}

// -------------------------------------------------------------------- mapping

/** The union of [rects] as `l,b,r,t` with three decimals, or null when empty. */
fun boundsOf(rects: List<TextRect>): String? {
    if (rects.isEmpty()) return null
    var left = Double.MAX_VALUE
    var bottom = Double.MAX_VALUE
    var right = -Double.MAX_VALUE
    var top = -Double.MAX_VALUE
    for (rect in rects) {
        left = minOf(left, rect.x0)
        bottom = minOf(bottom, rect.y0)
        right = maxOf(right, rect.x1)
        top = maxOf(top, rect.y1)
    }
    return listOf(left, bottom, right, top).joinToString(",") {
        String.format(Locale.ROOT, "%.3f", it)
    }
}

/**
 * Maps a range of [normalised] text back to the source it came from, so a hit
 * found on the index's text can be given the boxes of the page's own text.
 */
fun layoutRangeFor(normalised: NormalisedText, start: Int, end: Int): IntRange {
    val from = start.coerceIn(0, normalised.text.length)
    val to = end.coerceIn(from, normalised.text.length)
    val sourceFrom = normalised.sourceIndex(from)
    if (to <= from) return sourceFrom until sourceFrom
    val last = (normalised.sourceIndex(to) - 1).coerceAtLeast(sourceFrom)
    return sourceFrom..last
}

// ------------------------------------------------------------ saved patterns

/**
 * The saved-pattern list in one string preference: one record per line,
 * `id|base64(name)|base64(pattern)|FLAG,FLAG|createdAt`. The name and the
 * pattern are Base64 so a tab, a newline or a `|` in either survives; a record
 * that cannot be read is dropped, never thrown.
 */
object RegexPatternCodec {

    fun encode(patterns: List<RegexPattern>): String =
        patterns.joinToString("\n") { pattern ->
            listOf(
                pattern.id,
                base64(pattern.name),
                base64(pattern.pattern),
                pattern.flags.joinToString(",") { it.name },
                pattern.createdAt.toString()
            ).joinToString("|")
        }

    fun decode(raw: String?): List<RegexPattern> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split('\n').mapNotNull { decodeRecord(it) }
    }

    private fun decodeRecord(line: String): RegexPattern? {
        val fields = line.split('|')
        if (fields.size != 5) return null
        val id = fields[0]
        if (id.isEmpty()) return null
        val name = unbase64(fields[1]) ?: return null
        val pattern = unbase64(fields[2]) ?: return null
        val flags = fields[3].split(',')
            .mapNotNull { name -> RegexFlag.values().firstOrNull { it.name == name.trim() } }
            .toSet()
        val createdAt = fields[4].toLongOrNull() ?: return null
        return RegexPattern(id, name, pattern, flags, createdAt)
    }

    private fun base64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun unbase64(value: String): String? = try {
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        null
    }
}

private fun plural(count: Int, singular: String, plural: String): String =
    if (count == 1) "1 $singular" else "$count $plural"
