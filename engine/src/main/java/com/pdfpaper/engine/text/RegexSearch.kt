package com.pdfpaper.engine.text

import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * T65: the one regular-expression matcher. Everything else (the CLI, the
 * viewer's document scope, the library scope) calls these functions, so flags,
 * context, the deadline and the error wording cannot drift.
 *
 * Three independent bounds keep a catastrophic pattern from taking the app
 * down: a deadline-checked [CharSequence] aborts the JDK matcher, a match cap
 * stops a pattern that matches everywhere, and a caught [StackOverflowError]
 * reports the pattern as too complex instead of crashing.
 */
object RegexSearch {

    /** Matches [pattern] over [text] (no page, so no boxes). */
    fun find(
        pattern: String,
        text: String,
        options: RegexOptions = RegexOptions()
    ): RegexResult {
        val opts = options.normalised()
        if (pattern.isEmpty()) return RegexResult.Matches(emptyList())
        val compiled = compile(pattern, opts) ?: return invalid(pattern, opts)
        return attempt(pattern) {
            val deadline = System.nanoTime() + opts.timeLimitMs * 1_000_000L
            val outcome = runPage(compiled, text, null, opts, opts.maxMatches, deadline)
            RegexResult.Matches(
                matches = outcome.matches,
                truncated = outcome.moreAvailable,
                timedOut = outcome.timedOut
            )
        }
    }

    /** Matches [pattern] over a page, attaching each match's [TextRect] boxes. */
    fun find(
        pattern: String,
        layout: PageLayout,
        options: RegexOptions = RegexOptions()
    ): RegexResult {
        val opts = options.normalised()
        if (pattern.isEmpty()) return RegexResult.Matches(emptyList())
        val compiled = compile(pattern, opts) ?: return invalid(pattern, opts)
        return attempt(pattern) {
            val deadline = System.nanoTime() + opts.timeLimitMs * 1_000_000L
            val outcome = runPage(compiled, layout.text, layout, opts, opts.maxMatches, deadline)
            RegexResult.Matches(
                matches = outcome.matches,
                truncated = outcome.moreAvailable,
                timedOut = outcome.timedOut,
                pagesSearched = 1
            )
        }
    }

    // ------------------------------------------------------------ compilation

    /**
     * The document walk's per-page entry point. It stays `internal` so the
     * public surface is only the two `find` overloads and `searchRegex`.
     */
    internal fun runLayout(
        pattern: Pattern,
        layout: PageLayout,
        options: RegexOptions,
        limit: Int,
        deadlineNanos: Long
    ): RegexResult.Matches {
        val outcome = runPage(pattern, layout.text, layout, options, limit, deadlineNanos)
        return RegexResult.Matches(
            matches = outcome.matches,
            truncated = outcome.moreAvailable,
            timedOut = outcome.timedOut,
            pagesSearched = 1
        )
    }

    private fun compile(pattern: String, options: RegexOptions): Pattern? = try {
        regexPattern(pattern, options)
    } catch (e: PatternSyntaxException) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    // Translate a possible JVM-side translate failure into the same result the
    // matcher already carries, so the CLI and both app scopes can expose the
    // pattern as invalid instead of letting the failure escape.
    private fun handleCompileFailure(pattern: String, cause: Throwable): RegexResult.InvalidPattern {
        return RegexResult.InvalidPattern(
            pattern = pattern,
            error = RegexPatternError(
                message = cause.message ?: TOO_COMPLEX,
                position = -1
            )
        )
    }

    /**
     * The JDK reports a pattern too deeply nested to compile as a
     * [PatternSyntaxException] whose description is "Stack overflow during
     * pattern compilation", not as a thrown [StackOverflowError]. Both cases
     * must reach the reader as the same sentence (T65 spec, "Errors").
     */
    private fun invalid(pattern: String, options: RegexOptions): RegexResult.InvalidPattern {
        val error = try {
            regexPattern(pattern, options)
            RegexPatternError(TOO_COMPLEX, -1)
        } catch (e: PatternSyntaxException) {
            regexSyntaxError(e)
        } catch (e: StackOverflowError) {
            RegexPatternError(TOO_COMPLEX, -1)
        }
        return RegexResult.InvalidPattern(pattern, error)
    }

    private inline fun attempt(pattern: String, block: () -> RegexResult): RegexResult = try {
        block()
    } catch (e: StackOverflowError) {
        RegexResult.InvalidPattern(pattern, RegexPatternError(TOO_COMPLEX, -1))
    }

    // -------------------------------------------------------------- one page

    private class RunOutcome {
        val matches = ArrayList<RegexMatch>()
        var timedOut = false
        var moreAvailable = false
        val stopped: Boolean get() = timedOut || moreAvailable
    }

    /**
     * Runs [pattern] over one page. With [RegexFlag.ACROSS_LINES] the whole
     * page text is one input (so a match may include `\n`); without it the
     * matcher runs once per line, and the offsets are rebased so a caller
     * never sees a line-local index.
     */
    private fun runPage(
        pattern: Pattern,
        text: String,
        layout: PageLayout?,
        options: RegexOptions,
        limit: Int,
        deadlineNanos: Long
    ): RunOutcome {
        val out = RunOutcome()
        if (limit <= 0) return out
        if (options.isOn(RegexFlag.ACROSS_LINES)) {
            collect(pattern, text, 0, text, layout, options, limit, deadlineNanos, out)
            return out
        }
        if (layout != null) {
            for (line in layout.lines) {
                if (out.stopped) break
                if (line.text.isEmpty()) continue
                collect(pattern, line.text, line.start, text, layout, options, limit, deadlineNanos, out)
            }
            return out
        }
        var start = 0
        while (start <= text.length && !out.stopped) {
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline
            val segment = text.substring(start, end)
            if (segment.isNotEmpty()) {
                collect(pattern, segment, start, text, null, options, limit, deadlineNanos, out)
            }
            if (newline < 0) break
            start = newline + 1
        }
        return out
    }

    private fun collect(
        pattern: Pattern,
        segment: String,
        regionStart: Int,
        pageText: String,
        layout: PageLayout?,
        options: RegexOptions,
        limit: Int,
        deadlineNanos: Long,
        out: RunOutcome
    ) {
        val matcher = try {
            pattern.matcher(DeadlineText(segment, deadlineNanos))
        } catch (e: SearchDeadlineExceeded) {
            out.timedOut = true
            return
        }
        var ordinal = out.matches.size
        while (true) {
            val found = try {
                matcher.find()
            } catch (e: SearchDeadlineExceeded) {
                out.timedOut = true
                return
            }
            if (!found) return
            val start = matcher.start()
            val end = matcher.end()
            // A pattern that can match nothing (a*, ^, (?:)) would otherwise
            // produce an endless stream of zero-width rows.
            if (start == end) continue
            if (options.isOn(RegexFlag.WHOLE_WORD) && !isWholeWord(segment, start, end)) continue
            if (out.matches.size >= limit) {
                out.moreAvailable = true
                return
            }
            val absoluteStart = regionStart + start
            val absoluteEnd = regionStart + end
            val (before, after) = contextAround(
                pageText,
                absoluteStart,
                absoluteEnd,
                options.contextRadius
            )
            out.matches += RegexMatch(
                pageIndex = 0,
                start = absoluteStart,
                end = absoluteEnd,
                text = segment.substring(start, end),
                before = before,
                after = after,
                rects = layout?.rectsFor(absoluteStart, absoluteEnd) ?: emptyList(),
                ordinal = ordinal
            )
            ordinal++
        }
    }

    private fun isWholeWord(text: String, start: Int, end: Int): Boolean {
        if (start > 0 && isWordChar(text[start - 1])) return false
        if (end < text.length && isWordChar(text[end])) return false
        return true
    }

    private fun isWordChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    // ------------------------------------------------------------- deadline

    private class SearchDeadlineExceeded : RuntimeException(null, null, false, false)

    /**
     * A [CharSequence] that stops the JDK matcher from inside: the check runs
     * every 1024 character reads, so a backtracking pattern is abandoned
     * instead of burning a core. A pattern that loops without reading cannot
     * be stopped this way (a documented limit); the app runs every search off
     * the main thread for that reason.
     */
    private class DeadlineText(
        private val text: String,
        private val deadlineNanos: Long
    ) : CharSequence {

        private var reads = 0

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            if (++reads >= 1024) {
                reads = 0
                if (System.nanoTime() >= deadlineNanos) throw SearchDeadlineExceeded()
            }
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            DeadlineText(text.substring(startIndex, endIndex), deadlineNanos)

        override fun toString(): String = text
    }
}

/**
 * Searches [this] document page by page, in order. Pages are never joined, so
 * a match can never span two pages. [pages] selects exact zero-based indices
 * (out-of-range entries are skipped); `null` visits every page.
 *
 * The run is bounded by [RegexOptions.runTimeLimitMs] and
 * [RegexOptions.maxMatches], and [isCancelled] is checked before every page and
 * after every progress report. A stopped run keeps what it found and says why.
 */
fun PdfDocument.searchRegex(
    pattern: String,
    options: RegexOptions = RegexOptions(),
    pages: List<Int>? = null,
    onProgress: ((pagesDone: Int, pagesTotal: Int) -> Unit)? = null,
    isCancelled: () -> Boolean = { false }
): RegexResult {
    val opts = options.normalised()
    if (pattern.isEmpty()) return RegexResult.Matches(emptyList())
    val compiled = try {
        regexPattern(pattern, opts)
    } catch (e: PatternSyntaxException) {
        return RegexResult.InvalidPattern(pattern, regexSyntaxError(e))
    } catch (e: StackOverflowError) {
        return RegexResult.InvalidPattern(pattern, RegexPatternError(TOO_COMPLEX, -1))
    }

    val documentPages = try {
        pages()
    } catch (e: Exception) {
        return RegexResult.Matches(emptyList())
    }
    val indices = (pages ?: documentPages.indices.toList()).filter { it in documentPages.indices }
    val total = indices.size
    val runDeadline = System.nanoTime() + opts.runTimeLimitMs * 1_000_000L

    val found = ArrayList<RegexMatch>()
    var truncated = false
    var timedOut = false
    var cancelled = false
    var done = 0

    for (index in indices) {
        if (isCancelled()) {
            cancelled = true
            break
        }
        val now = System.nanoTime()
        if (now >= runDeadline) {
            timedOut = true
            break
        }
        val remaining = opts.maxMatches - found.size
        if (remaining <= 0) {
            // The cap stopped the run; a later page may still hold a match.
            truncated = true
            break
        }
        val layout = try {
            documentPages[index].layout()
        } catch (e: Exception) {
            done++
            onProgress?.invoke(done, total)
            if (isCancelled()) {
                cancelled = true
                break
            }
            continue
        }
        val pageDeadline = minOf(now + opts.timeLimitMs * 1_000_000L, runDeadline)
        val outcome = try {
            RegexSearch.runLayout(compiled, layout, opts, remaining, pageDeadline)
        } catch (e: StackOverflowError) {
            return RegexResult.InvalidPattern(
                pattern,
                RegexPatternError("This pattern is too complex to run", -1)
            )
        }
        for (match in outcome.matches) found += match.copy(pageIndex = index)
        if (outcome.timedOut) timedOut = true
        if (outcome.truncated) truncated = true
        done++
        onProgress?.invoke(done, total)
        if (isCancelled()) {
            cancelled = true
            break
        }
        if (truncated || timedOut) break
    }

    return RegexResult.Matches(
        matches = found,
        truncated = truncated,
        timedOut = timedOut,
        cancelled = cancelled,
        pagesSearched = done
    )
}

private const val TOO_COMPLEX = "This pattern is too complex to run"

/**
 * Maps a compile failure to one message. The JDK reports a pattern too deeply
 * nested to compile as a [PatternSyntaxException] whose description is "Stack
 * overflow during pattern compilation", not as a thrown [StackOverflowError];
 * both must reach the reader as the same sentence (T65 spec, "Errors").
 */
private fun regexSyntaxError(e: PatternSyntaxException): RegexPatternError =
    if (e.description.orEmpty().contains("Stack overflow", ignoreCase = true)) {
        RegexPatternError(TOO_COMPLEX, -1)
    } else {
        RegexPatternError(e.description, e.index)
    }