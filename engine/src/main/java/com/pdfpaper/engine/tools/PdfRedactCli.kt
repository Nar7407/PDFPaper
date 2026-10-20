package com.pdfpaper.engine.tools

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.redact.PdfRedaction
import com.pdfpaper.engine.redact.RedactionMark
import com.pdfpaper.engine.redact.RedactionMarkType
import com.pdfpaper.engine.redact.RedactionMarksJson
import com.pdfpaper.engine.redact.RedactionPreset
import com.pdfpaper.engine.redact.RedactionVerification
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

/**
 * Redaction CLI (task T28).
 *
 * ```
 * cli redact find <in.pdf> (--regex PATTERN | --text TEXT | --preset email|phone|number)
 *                   [--pages 1,3-5] [--overlay TEXT] [--out marks.json]
 * cli redact apply <in.pdf> <marks.json> --out <out.pdf> [--verify]
 * cli redact pages <in.pdf> --pages 3-5 --out <out.pdf>
 * cli redact pages 3-5 <in.pdf> --out <out.pdf>
 * cli redact verify <file.pdf> [--marks marks.json] [--text STRING]... [--json]
 * ```
 *
 * Page numbers on the command line and in `marks.json` are one-based. Outputs
 * are full rewrites, never incremental updates.
 */
fun main(args: Array<String>) {
    exitProcess(run(args.toList()))
}

private const val REDACT_USAGE = """Usage:
  cli redact find <in.pdf> (--regex PATTERN | --text TEXT | --preset email|phone|number)
                    [--pages 1,3-5] [--overlay TEXT] [--out marks.json]
  cli redact apply <in.pdf> <marks.json> --out <out.pdf> [--verify]
  cli redact pages <in.pdf> --pages 3-5 --out <out.pdf>
  cli redact pages 3-5 <in.pdf> --out <out.pdf>
  cli redact verify <file.pdf> [--marks marks.json] [--text STRING]... [--json]

Coordinates in marks.json are PDF user-space points from the page's bottom-left.
"""

private fun run(tokens: List<String>): Int {
    if (tokens.isEmpty()) {
        System.err.println(REDACT_USAGE)
        return 2
    }
    return try {
        when (tokens.first().lowercase()) {
            "find" -> findCommand(tokens.drop(1))
            "apply" -> applyCommand(tokens.drop(1))
            "pages", "delete-pages" -> pagesCommand(tokens.drop(1))
            "verify" -> verifyCommand(tokens.drop(1))
            "help", "--help", "-h" -> {
                println(REDACT_USAGE)
                0
            }
            else -> throw UsageError("unknown redact command '${tokens.first()}'")
        }
    } catch (error: UsageError) {
        System.err.println("redact: ${error.message}")
        System.err.println(REDACT_USAGE)
        2
    } catch (error: IllegalArgumentException) {
        System.err.println("redact: ${error.message}")
        2
    } catch (error: Exception) {
        System.err.println("redact: ${error.message ?: error.javaClass.simpleName}")
        1
    }
}

private fun findCommand(tokens: List<String>): Int {
    val args = CliArgs.parse(tokens, valueOptions = setOf(
        "regex", "text", "preset", "pages", "page", "overlay", "out", "color"
    ), flagOptions = setOf("help"))
    if (args.flag("help")) {
        println(REDACT_USAGE)
        return 0
    }
    val input = args.positionals.singleOrNull() ?: throw UsageError("find needs one input PDF")
    var regex = args.value("regex")
    val text = args.value("text")
    val preset = args.value("preset")?.let { RedactionPreset.parse(it) }
    if (regex == null && text == null && preset == null && args.positionals.size >= 2) {
        regex = args.positionals[1]
    }
    if (listOfNotNull(regex, text, preset).size != 1) {
        throw UsageError("choose exactly one of --regex, --text or --preset")
    }
    val pages = args.value("pages")?.let { parsePageSet(it) }
        ?: args.value("page")?.let { setOf(parsePage(it)) }
    val overlay = args.value("overlay")
    val color = args.value("color")?.let { parseColor(it) } ?: PdfColor.BLACK

    open(input).use { document ->
        val matches = PdfRedaction.find(
            document = document,
            query = text ?: regex ?: "",
            regex = regex != null,
            preset = preset,
            pageIndices = pages
        )
        val marks = matches.map { it.toMark(overlay, color) }
        val json = RedactionMarksJson.serialize(marks)
        val out = args.value("out")
        if (out != null) {
            writeAtomically(File(out), json.toByteArray(Charsets.UTF_8))
            println("Found ${marks.size} match(es); wrote ${File(out).absolutePath}.")
        } else {
            println(json)
        }
    }
    return 0
}

private fun applyCommand(tokens: List<String>): Int {
    val args = CliArgs.parse(tokens, valueOptions = setOf("out", "marks"), flagOptions = setOf("verify", "help"),
    )
    if (args.flag("help")) {
        println(REDACT_USAGE)
        return 0
    }
    val positionals = args.positionals.toMutableList()
    val marksPath = args.value("marks") ?: run {
        if (positionals.size < 2) throw UsageError("apply needs an input PDF and a marks.json")
        if (positionals[0].endsWith(".json", ignoreCase = true)) positionals.removeAt(0) else positionals.removeAt(1)
    }
    if (positionals.size != 1) throw UsageError("apply needs exactly one input PDF")
    val input = positionals[0]
    val out = args.value("out") ?: "out.pdf"
    open(input).use { document ->
        val marks = RedactionMarksJson.parse(File(marksPath).readText(Charsets.UTF_8))
        val result = PdfRedaction.apply(document, marks)
        writeAtomically(File(out), result.bytes)
        println(
            "Wrote ${File(out).absolutePath}: ${result.marksApplied} mark(s), " +
                "${result.pageCount} page(s)."
        )
        if (result.unredacted.isNotEmpty()) {
            System.err.println("redact: ${result.unredacted.size} item(s) could not be redacted safely:")
            result.unredacted.forEach { System.err.println("  - $it") }
        }
        if (args.flag("verify")) {
            val expected = marks.mapNotNull { it.expectedText }.filter { it.isNotBlank() }.distinct()
            open(out).use { output ->
                val verification = PdfRedaction.verify(output, expected, unredacted = result.unredacted)
                printVerification(verification, json = false)
                if (!verification.passed) return 1
            }
        }
    }
    return 0
}

private fun pagesCommand(tokens: List<String>): Int {
    val args = CliArgs.parse(tokens, valueOptions = setOf("out", "pages"), flagOptions = setOf("help"))
    if (args.flag("help")) {
        println(REDACT_USAGE)
        return 0
    }
    val positionals = args.positionals.toMutableList()
    var pages = args.value("pages")?.let { parsePageSet(it) }
    if (pages == null && positionals.size >= 2 && looksLikePageRange(positionals[0])) {
        pages = parsePageSet(positionals.removeAt(0))
    }
    if (pages == null) throw UsageError("pages needs --pages 3-5")
    if (positionals.size != 1) throw UsageError("pages needs exactly one input PDF")
    val out = args.value("out") ?: "out.pdf"
    open(positionals[0]).use { document ->
        val result = PdfRedaction.deletePages(document, pages)
        writeAtomically(File(out), result.bytes)
        println(
            "Wrote ${File(out).absolutePath}: deleted ${result.pagesDeleted} page(s), " +
                "${result.pageCount} remain."
        )
    }
    return 0
}

private fun verifyCommand(tokens: List<String>): Int {
    val args = CliArgs.parse(tokens, valueOptions = setOf("marks", "text"), flagOptions = setOf("json", "help"))
    if (args.flag("help")) {
        println(REDACT_USAGE)
        return 0
    }
    val input = args.positionals.singleOrNull() ?: throw UsageError("verify needs one PDF")
    val expected = LinkedHashSet<String>()
    args.values("text").forEach { expected += it }
    args.value("marks")?.let { path ->
        RedactionMarksJson.parse(File(path).readText(Charsets.UTF_8)).forEach { mark ->
            mark.expectedText?.takeIf { it.isNotBlank() }?.let { expected += it }
        }
    }
    if (expected.isEmpty()) {
        throw UsageError("verify needs --marks or at least one --text")
    }
    open(input).use { document ->
        val verification = PdfRedaction.verify(document, expected)
        printVerification(verification, json = args.flag("json"))
        return if (verification.passed) 0 else 1
    }
}

private fun printVerification(verification: RedactionVerification, json: Boolean) {
    if (json) {
        val remaining = verification.remaining.entries.joinToString(", ") { (text, count) ->
            "\"${jsonEscape(text)}\": $count"
        }
        val leaks = verification.leaks.joinToString(", ") { leak ->
            "{\"expected\":\"${jsonEscape(leak.expected)}\",\"page\":${leak.pageIndex + 1}," +
                "\"kind\":\"${leak.kind.name.lowercase()}\",\"detail\":\"${jsonEscape(leak.detail)}\"}"
        }
        println(
            "{\"passed\": ${verification.passed}, \"pages\": ${verification.checkedPages}, " +
                "\"remaining\": {$remaining}, \"leaks\": [$leaks], " +
                "\"unredacted\": [${verification.unredacted.joinToString(", ") { "\"${jsonEscape(it)}\"" }}]}"
        )
        return
    }
    if (verification.passed) {
        println("Redaction verification passed: ${verification.expected.size} string(s), ${verification.checkedPages} page(s).")
    } else {
        System.err.println("Redaction verification FAILED:")
        for (leak in verification.leaks) {
            val page = if (leak.pageIndex >= 0) "page ${leak.pageIndex + 1}" else "object ${leak.streamObjNum}"
            System.err.println("  - ${leak.kind.name.lowercase()} $page: ${leak.detail}")
        }
        verification.unredacted.forEach { System.err.println("  - unredacted: $it") }
    }
}

private fun open(path: String): PdfDocument {
    val file = File(path)
    if (!file.isFile) throw IllegalArgumentException("no such PDF: $path")
    return PdfDocument.open(FileSource(file)).getOrElse {
        throw IllegalArgumentException("cannot open '$path': ${it.message}")
    }
}

private fun writeAtomically(target: File, bytes: ByteArray) {
    target.absoluteFile.parentFile?.mkdirs()
    val directory = target.absoluteFile.parentFile ?: File(".")
    val temp = File.createTempFile(".redact-", ".tmp", directory)
    try {
        temp.writeBytes(bytes)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        if (temp.exists()) temp.delete()
    }
}

/** Parses one-based page lists such as "1,3-5". */
internal fun parsePageSet(value: String): Set<Int> {
    val out = LinkedHashSet<Int>()
    for (part in value.split(',')) {
        val token = part.trim()
        if (token.isEmpty()) continue
        val dash = token.indexOf('-', startIndex = 1)
        if (dash < 0) {
            out += parsePage(token)
        } else {
            val from = token.substring(0, dash).trim().toIntOrNull()
                ?: throw UsageError("bad page range '$token'")
            val to = token.substring(dash + 1).trim().toIntOrNull()
                ?: throw UsageError("bad page range '$token'")
            if (from < 1 || to < from) throw UsageError("bad page range '$token'")
            for (page in from..to) out += page - 1
        }
    }
    if (out.isEmpty()) throw UsageError("empty page list")
    return out
}

private fun parsePage(value: String): Int {
    val page = value.trim().toIntOrNull() ?: throw UsageError("bad page number '$value'")
    if (page < 1) throw UsageError("page numbers are one-based")
    return page - 1
}

private fun looksLikePageRange(value: String): Boolean =
    value.matches(Regex("\\d+(\\s*-\\s*\\d+)?(,\\d+(\\s*-\\s*\\d+)?)*"))

private fun parseColor(value: String): PdfColor {
    val hex = value.removePrefix("#")
    if (hex.length != 6) throw IllegalArgumentException("--color must be #RRGGBB")
    val rgb = hex.toIntOrNull(16) ?: throw IllegalArgumentException("--color is not hexadecimal")
    return PdfColor(
        ((rgb shr 16) and 0xFF) / 255.0,
        ((rgb shr 8) and 0xFF) / 255.0,
        (rgb and 0xFF) / 255.0
    )
}

private fun jsonEscape(value: String): String = buildString {
    for (ch in value) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
}

private class UsageError(message: String) : IllegalArgumentException(message)

private class CliArgs private constructor(
    val positionals: List<String>,
    private val options: Map<String, MutableList<String>>,
    private val flags: Set<String>
) {
    fun value(name: String): String? = options[name]?.lastOrNull()
    fun values(name: String): List<String> = options[name].orEmpty()
    fun flag(name: String): Boolean = name in flags

    companion object {
        fun parse(tokens: List<String>, valueOptions: Set<String>, flagOptions: Set<String>): CliArgs {
            val positionals = ArrayList<String>()
            val options = HashMap<String, MutableList<String>>()
            val flags = HashSet<String>()
            var index = 0
            while (index < tokens.size) {
                val token = tokens[index]
                if (token == "--") {
                    positionals += tokens.subList(index + 1, tokens.size)
                    break
                }
                if (!token.startsWith("--") || token == "-") {
                    positionals += token
                    index++
                    continue
                }
                val body = token.substring(2)
                val equals = body.indexOf('=')
                val name = if (equals >= 0) body.substring(0, equals) else body
                when {
                    name in flagOptions -> {
                        if (equals >= 0) throw UsageError("--$name does not take a value")
                        flags += name
                        index++
                    }
                    name in valueOptions -> {
                        val value = if (equals >= 0) {
                            body.substring(equals + 1)
                        } else {
                            if (index + 1 >= tokens.size) throw UsageError("--$name needs a value")
                            tokens[++index]
                        }
                        options.getOrPut(name) { ArrayList() } += value
                        index++
                    }
                    else -> throw UsageError("unknown option --$name")
                }
            }
            return CliArgs(positionals, options, flags)
        }
    }
}

