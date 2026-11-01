package com.pdfpaper.engine.tools

import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.text.DEFAULT_REGEX_FLAGS
import com.pdfpaper.engine.text.RegexFlag
import com.pdfpaper.engine.text.RegexMatch
import com.pdfpaper.engine.text.RegexOptions
import com.pdfpaper.engine.text.RegexResult
import com.pdfpaper.engine.text.searchRegex
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * T65: the `search-regex` command. One pattern over one PDF, every match as a
 * CSV row.
 *
 * ```
 * search-regex in.pdf "AB-\d{4}" --out matches.csv
 *     [--case-sensitive] [--multiline] [--lines] [--whole-word]
 *     [--max N] [--timeout MS] [--run-timeout MS] [--context N]
 *     [--pages 1,3-5] [--no-bom]
 * ```
 *
 * The search itself is [com.pdfpaper.engine.text.RegexSearch], the same matcher
 * the viewer and the library use, so flags, context and the error wording are
 * identical everywhere. Pages are one-based here and zero-based inside the
 * engine; the CSV is UTF-8 with a BOM so a spreadsheet app reads it as UTF-8.
 */
fun main(args: Array<String>) {
    exitProcess(runSearchRegex(args))
}

/**
 * The work of the command, so a test can call it and read the code without
 * `exitProcess` ending the test JVM. [out]/[err] are a seam for the same
 * reason: the two stderr sentences are part of the contract.
 */
internal fun runSearchRegex(
    args: Array<String>,
    out: PrintStream = System.out,
    err: PrintStream = System.err
): Int {
    if (args.isEmpty() || args[0] == "--help" || args[0] == "-h") {
        printUsage(err)
        return if (args.isEmpty()) 2 else 0
    }
    // The wrapper passes the command name through as well; accept both forms.
    val tokens = if (args[0].equals("search-regex", ignoreCase = true)) args.drop(1) else args.toList()
    return try {
        val parsed = SearchCliArgs.parse(
            tokens,
            valueOptions = setOf("out", "max", "timeout", "run-timeout", "context", "pages"),
            flagOptions = setOf("case-sensitive", "multiline", "lines", "whole-word", "no-bom")
        )
        val input = parsed.requirePositional(0, "input PDF")
        val pattern = parsed.requirePositional(1, "pattern")
        val output = parsed.requireOption("out")
        val source = File(input)
        if (!source.isFile) throw SearchUsageError("There is no file at $input")

        val options = RegexOptions(
            flags = flagsOf(parsed),
            maxMatches = parsed.intOption("max") ?: RegexOptions.DEFAULT_MAX_MATCHES,
            timeLimitMs = parsed.longOption("timeout") ?: RegexOptions.DEFAULT_TIME_LIMIT_MS,
            runTimeLimitMs = parsed.longOption("run-timeout") ?: RegexOptions.DEFAULT_RUN_TIME_LIMIT_MS,
            contextRadius = parsed.intOption("context") ?: RegexOptions.DEFAULT_CONTEXT_RADIUS
        )
        val pages = parsed.option("pages")?.let { parsePageSet(it).sorted() }

        val document = PdfDocument.open(FileSource(source)).getOrElse { error ->
            throw SearchInputError(
                "The PDF could not be read: ${error.message ?: error::class.simpleName}"
            )
        }
        val result = document.use { open -> open.searchRegex(pattern, options, pages) }
        if (result is RegexResult.InvalidPattern) {
            val position = result.error.position
            if (position >= 0) {
                err.println("Invalid pattern: ${result.error.message} (at $position)")
            } else {
                err.println("Invalid pattern: ${result.error.message}")
            }
            return 1
        }
        val matches = (result as RegexResult.Matches).matches
        val target = File(output)
        target.parentFile?.mkdirs()
        target.writeText(renderCsv(source.name, matches, bom = !parsed.flag("no-bom")), Charsets.UTF_8)
        out.println("Wrote ${matches.size} match(es) to ${target.path}")
        val stopped = stoppedReason(result)
        if (stopped != null) err.println("Search stopped early: $stopped")
        0
    } catch (e: SearchUsageError) {
        err.println("Error: ${e.message}")
        printUsage(err)
        2
    } catch (e: SearchInputError) {
        err.println("Error: ${e.message}")
        1
    } catch (e: IllegalArgumentException) {
        // The shared page-range parser reports a bad list this way.
        err.println("Error: ${e.message}")
        printUsage(err)
        2
    } catch (e: Throwable) {
        err.println("Error: ${e.message ?: e::class.simpleName}")
        1
    }
}

/** The flags the command line asked for, starting from the shared default. */
private fun flagsOf(parsed: SearchCliArgs): Set<RegexFlag> {
    val flags = LinkedHashSet(DEFAULT_REGEX_FLAGS)
    if (parsed.flag("case-sensitive")) flags.remove(RegexFlag.CASE_INSENSITIVE)
    if (parsed.flag("multiline")) flags.add(RegexFlag.MULTILINE)
    if (parsed.flag("lines")) flags.remove(RegexFlag.ACROSS_LINES)
    if (parsed.flag("whole-word")) flags.add(RegexFlag.WHOLE_WORD)
    return flags
}

/**
 * `file,page,match,context`, one row per match. [RegexMatch.contextText] is
 * already one line (its whitespace runs are collapsed), so only a match that
 * itself contains a newline or a comma needs quoting — and then it gets it.
 */
private fun renderCsv(fileName: String, matches: List<RegexMatch>, bom: Boolean): String {
    val builder = StringBuilder()
    if (bom) builder.append('\uFEFF')
    builder.append("file,page,match,context\n")
    for (match in matches) {
        builder.append(csvField(fileName)).append(',')
            .append(match.pageIndex + 1).append(',')
            .append(csvField(match.text)).append(',')
            .append(csvField(match.contextText)).append('\n')
    }
    return builder.toString()
}

/** RFC 4180: quote when the field holds a comma, a quote or a line break. */
internal fun csvField(value: String): String {
    val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuotes) return value
    return '"' + value.replace("\"", "\"\"") + '"'
}

/** Why the run stopped early, or `null` when it covered every selected page. */
private fun stoppedReason(result: RegexResult): String? {
    val reasons = ArrayList<String>()
    if (result.timedOut) reasons += "the time limit was reached"
    if (result.truncated) reasons += "the match limit was reached"
    if (result.cancelled) reasons += "the search was cancelled"
    return if (reasons.isEmpty()) null else reasons.joinToString("; ")
}

private fun printUsage(stream: PrintStream) {
    stream.println(
        """
        Usage:
          search-regex <in.pdf> "<pattern>" --out matches.csv
              [--case-sensitive] [--multiline] [--lines] [--whole-word]
              [--max N] [--timeout MS] [--run-timeout MS] [--context N]
              [--pages 1,3-5] [--no-bom]

        Writes one row per match as file,page,match,context (RFC 4180, UTF-8
        with a BOM unless --no-bom). Pages are one-based. Matching is
        case-insensitive and may cross line breaks unless --case-sensitive or
        --lines is given. A stopped run still writes the rows it found.
        """.trimIndent()
    )
}

/** A problem with the command line, not with the PDF. */
private class SearchUsageError(message: String) : IllegalArgumentException(message)

/** A file that cannot be searched (bad PDF, unreadable bytes). */
private class SearchInputError(message: String) : IllegalArgumentException(message)

/** The command line arguments: `--name value`, `--name=value` and flags. */
private class SearchCliArgs private constructor(
    private val positionals: List<String>,
    private val options: Map<String, MutableList<String>>,
    private val flags: Set<String>
) {

    fun option(name: String): String? = options[name]?.lastOrNull()

    fun flag(name: String): Boolean = name in flags

    fun requirePositional(index: Int, what: String): String =
        positionals.getOrNull(index) ?: throw SearchUsageError("The $what is missing")

    fun requireOption(name: String): String =
        option(name) ?: throw SearchUsageError("--$name is missing")

    fun intOption(name: String): Int? {
        val raw = option(name) ?: return null
        return raw.toIntOrNull() ?: throw SearchUsageError("--$name must be an integer")
    }

    fun longOption(name: String): Long? {
        val raw = option(name) ?: return null
        return raw.toLongOrNull() ?: throw SearchUsageError("--$name must be a whole number")
    }

    companion object {
        fun parse(
            tokens: List<String>,
            valueOptions: Set<String>,
            flagOptions: Set<String>
        ): SearchCliArgs {
            val positionals = ArrayList<String>()
            val options = HashMap<String, MutableList<String>>()
            val flags = HashSet<String>()
            var index = 0
            while (index < tokens.size) {
                val token = tokens[index]
                if (!token.startsWith("--")) {
                    positionals += token
                    index++
                    continue
                }
                val body = token.removePrefix("--")
                val equals = body.indexOf('=')
                if (equals >= 0) {
                    val name = body.substring(0, equals)
                    val value = body.substring(equals + 1)
                    if (flagOptions.contains(name)) {
                        flags += name
                    } else if (valueOptions.contains(name)) {
                        options.getOrPut(name) { ArrayList() } += value
                    } else {
                        throw SearchUsageError("Unknown option --$name")
                    }
                    index++
                    continue
                }
                if (flagOptions.contains(body)) {
                    flags += body
                    index++
                    continue
                }
                if (valueOptions.contains(body)) {
                    val value = tokens.getOrNull(index + 1)
                        ?: throw SearchUsageError("--$body needs a value")
                    options.getOrPut(body) { ArrayList() } += value
                    index += 2
                    continue
                }
                throw SearchUsageError("Unknown option --$body")
            }
            return SearchCliArgs(positionals, options, flags)
        }
    }
}
