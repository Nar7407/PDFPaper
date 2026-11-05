package com.pdfpaper.engine.text

import java.util.regex.Pattern

/**
 * T65: the one vocabulary every regex surface speaks — the CLI flags, the
 * app's saved-pattern records and the UI chips are all this enum, so a flag
 * can never mean two things.
 */
enum class RegexFlag(val label: String) {
    /** `Ab-1234` matches `ab-1234` (`Pattern.CASE_INSENSITIVE or UNICODE_CASE`). */
    CASE_INSENSITIVE("Case"),
    /** `^`/`$` match at line breaks (`Pattern.MULTILINE`). */
    MULTILINE("Multiline"),
    /** A match may include a `\n`; off runs once per line, like T11. */
    ACROSS_LINES("Across lines"),
    /** A match whose neighbours are both word chars is rejected (a neighbour filter, never `\b`). */
    WHOLE_WORD("Whole word")
}

/** The shared default: case-insensitive, matches may cross a line break. */
val DEFAULT_REGEX_FLAGS: Set<RegexFlag> =
    setOf(RegexFlag.CASE_INSENSITIVE, RegexFlag.ACROSS_LINES)

/**
 * Everything a run needs to behave the same in the CLI, the viewer and the
 * library. Budgets are per run except [timeLimitMs], which is the per-page
 * deadline where a catastrophic pattern lives.
 */
data class RegexOptions(
    val flags: Set<RegexFlag> = DEFAULT_REGEX_FLAGS,
    val maxMatches: Int = DEFAULT_MAX_MATCHES,
    val timeLimitMs: Long = DEFAULT_TIME_LIMIT_MS,
    val runTimeLimitMs: Long = DEFAULT_RUN_TIME_LIMIT_MS,
    val contextRadius: Int = DEFAULT_CONTEXT_RADIUS
) {

    fun isOn(flag: RegexFlag): Boolean = flag in flags

    /** Clamps the budgets so a caller can never hand the matcher a zero window. */
    fun normalised(): RegexOptions = copy(
        maxMatches = maxMatches.coerceAtLeast(1),
        timeLimitMs = timeLimitMs.coerceAtLeast(1L),
        runTimeLimitMs = runTimeLimitMs.coerceAtLeast(1L),
        contextRadius = contextRadius.coerceAtLeast(0)
    )

    companion object {
        const val DEFAULT_MAX_MATCHES = 2000
        const val DEFAULT_TIME_LIMIT_MS = 1500L
        const val DEFAULT_RUN_TIME_LIMIT_MS = 60_000L
        const val DEFAULT_CONTEXT_RADIUS = 48

        val DEFAULT_FLAGS: Set<RegexFlag> get() = DEFAULT_REGEX_FLAGS
    }
}

/**
 * A `Pattern.compile` failure, carried instead of thrown so every caller can
 * draw the same caret under the same character.
 *
 * @param position the JDK's error index, or `-1` when it has none.
 */
data class RegexPatternError(val message: String, val position: Int)

/**
 * One match. [start]/[end] are character offsets in the text the match was
 * found in (the page's `PageLayout.text` in document scope, the stored page
 * text in library scope); [ordinal] is the match's position on that page, so a
 * library row can be re-found after the file is opened.
 */
data class RegexMatch(
    val pageIndex: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val before: String,
    val after: String,
    val rects: List<TextRect> = emptyList(),
    val ordinal: Int = 0
) {
    /** The one-line snippet a result row and the CSV show. */
    val contextText: String get() = before + text + after
}

/** The outcome of one run: matches, an invalid pattern, or nothing at all. */
sealed interface RegexResult {

    val truncated: Boolean
    val timedOut: Boolean
    val cancelled: Boolean

    data class Matches(
        val matches: List<RegexMatch>,
        override val truncated: Boolean = false,
        override val timedOut: Boolean = false,
        override val cancelled: Boolean = false,
        val pagesSearched: Int = 0
    ) : RegexResult

    data class InvalidPattern(
        val pattern: String,
        val error: RegexPatternError
    ) : RegexResult {
        override val truncated: Boolean get() = false
        override val timedOut: Boolean get() = false
        override val cancelled: Boolean get() = false
    }
}

/** One of the six built-in patterns, with the sample that proves it works. */
data class RegexExample(
    val id: String,
    val label: String,
    val pattern: String,
    val sample: String
)

/**
 * Exactly six examples, defined once in the engine so the CLI, the panel and
 * the tests cannot drift. The IPv4 one checks shape only, not validity.
 */
object RegexExamples {

    val ALL: List<RegexExample> = listOf(
        RegexExample("date", "ISO date", "\\b\\d{4}-\\d{2}-\\d{2}\\b", "2026-10-31"),
        RegexExample(
            "email",
            "E-mail address",
            "\\b[\\w.+-]+@[\\w-]+\\.[\\w.-]{2,}\\b",
            "reader@example.com"
        ),
        RegexExample("phone", "Phone number", "\\+?\\d[\\d\\s().-]{6,}\\d", "+1 555 010 0042"),
        RegexExample("url", "Web address", "https?://[^\\s<>\"]+", "https://example.com/a"),
        RegexExample(
            "ipv4",
            "IPv4 address",
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b",
            "192.168.0.1"
        ),
        RegexExample("code", "ID code", "\\b[A-Z]{2,3}-\\d{4,8}\\b", "AB-1234")
    )

    fun byId(id: String): RegexExample? = ALL.firstOrNull { it.id == id }
}

/**
 * The text on each side of a match for a result row: never more than [radius]
 * characters, whitespace runs collapsed to one space and newlines turned into
 * spaces (so a row is one line), with `…` marking a clipped side (T65 spec,
 * "Context"). A side with no visible text is empty rather than just `…`.
 */
fun contextAround(
    text: CharSequence,
    start: Int,
    end: Int,
    radius: Int = RegexOptions.DEFAULT_CONTEXT_RADIUS
): Pair<String, String> {
    val r = radius.coerceAtLeast(0)
    val textLength = text.length
    if (textLength == 0) return "" to ""
    val s = start.coerceIn(0, textLength)
    val e = end.coerceIn(s, textLength)

    val beforeFrom = (s - r).coerceAtLeast(0)
    // The space between the snippet and the match is kept: it is what makes a
    // row read "…ice AB-1234" instead of "…iceAB-1234". Only the outer edge of
    // the whole snippet (the clipped side) is trimmed.
    val before = collapseContext(text.subSequence(beforeFrom, s)).trimStart()
    val afterTo = (e + r).coerceAtMost(textLength)
    val after = collapseContext(text.subSequence(e, afterTo)).trimEnd()

    val beforeText = when {
        before.isEmpty() -> ""
        beforeFrom > 0 -> "\u2026$before"
        else -> before
    }
    val afterText = when {
        after.isEmpty() -> ""
        afterTo < textLength -> "$after\u2026"
        else -> after
    }
    return beforeText to afterText
}

private fun collapseContext(chars: CharSequence): String {
    if (chars.isEmpty()) return ""
    val builder = StringBuilder(chars.length)
    var pendingSpace = false
    for (ch in chars) {
        if (ch.isWhitespace()) {
            pendingSpace = true
            continue
        }
        if (pendingSpace) {
            builder.append(' ')
            pendingSpace = false
        }
        builder.append(ch)
    }
    // A run at the inner edge is a real separator; the caller trims the outer
    // edge, so it is kept here and dropped there.
    if (pendingSpace) builder.append(' ')
    return builder.toString()
}

/**
 * A normalised string plus the map back to the source it came from.
 * `offsets[i]` is the source index of the character that produced normalised
 * index `i`; the final entry is the source length, so a range ending at the
 * end of the text still maps back.
 */
class NormalisedText(val text: String, val offsets: IntArray) {

    /** Source index for a normalised offset, clamped to the text. */
    fun sourceIndex(index: Int): Int = offsets[index.coerceIn(0, text.length)]
}

/**
 * Reproduces the T50 index rule (`IndexTextPolicy.normalize`: collapse every
 * whitespace run and every character below 0x20 into one space, trim, cap at
 * [maxChars]) while recording where each kept character came from. A library
 * hit is found on this text and mapped back through [NormalisedText.offsets].
 */
fun normaliseWithOffsets(text: String, maxChars: Int = 200_000): NormalisedText {
    val cap = maxChars.coerceAtLeast(0)
    if (text.isEmpty()) return NormalisedText("", intArrayOf(0))
    val builder = StringBuilder(minOf(text.length, cap))
    val offsets = ArrayList<Int>(minOf(text.length, cap) + 1)
    var pendingSpace = false
    var pendingSpaceAt = 0
    for (index in text.indices) {
        val ch = text[index]
        if (ch.isWhitespace() || ch.code < 0x20) {
            if (builder.isNotEmpty() && !pendingSpace) {
                pendingSpace = true
                pendingSpaceAt = index
            }
            continue
        }
        if (builder.length >= cap) break
        if (pendingSpace) {
            builder.append(' ')
            // The collapsed space belongs to the first character of the run,
            // so the map stays inside the source it came from.
            offsets += pendingSpaceAt
            pendingSpace = false
            if (builder.length >= cap) break
        }
        builder.append(ch)
        offsets += index
    }
    offsets += text.length
    return NormalisedText(builder.toString(), offsets.toIntArray())
}

/** Compiles [pattern] the same way for every caller (used by the matcher). */
internal fun regexPattern(pattern: String, options: RegexOptions): Pattern {
    var flags = 0
    if (options.isOn(RegexFlag.CASE_INSENSITIVE)) {
        flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    }
    if (options.isOn(RegexFlag.MULTILINE)) flags = flags or Pattern.MULTILINE
    return Pattern.compile(pattern, flags)
}
