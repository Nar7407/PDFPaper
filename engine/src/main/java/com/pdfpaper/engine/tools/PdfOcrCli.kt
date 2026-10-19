package com.pdfpaper.engine.tools

import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.ocr.OcrBounds
import com.pdfpaper.engine.ocr.OcrImage
import com.pdfpaper.engine.ocr.OcrPageReport
import com.pdfpaper.engine.ocr.OcrPageStatus
import com.pdfpaper.engine.ocr.OcrProvider
import com.pdfpaper.engine.ocr.OcrWord
import com.pdfpaper.engine.ocr.PdfOcr
import java.io.File
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * OCR text-layer CLI (task T25).
 *
 * Usage:
 *   cli ocr <file.pdf> [--lang eng+hin] [--out <out.pdf>]
 *       [--pages 1,3-5] [--text <words...>]
 *
 * The engine has no OCR library; the provider is implemented by the app. This
 * command uses a deterministic stub provider that "recognizes" [--text] (or a
 * short default) on every image, laid out on a grid, so the whole
 * save/search/copy path can be exercised from the command line. Pages that
 * already have text are skipped unless --pages names them, in which case every
 * listed page is processed. Per-page word counts and confidence are printed.
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] != "ocr") {
        printUsage()
        kotlin.system.exitProcess(2)
    }
    val status = run(args.drop(1))
    kotlin.system.exitProcess(status)
}

private fun run(args: List<String>): Int = withParsed(args) { parsed ->
    val file = parsed.requireFile(0)
    val language = parsed.option("lang") ?: "eng"
    val text = parsed.option("text") ?: DEFAULT_TEXT
    val pages = parsed.option("pages")?.let(::parsePages)
    val out = File(parsed.option("out") ?: "out.pdf")
    val parent = out.absoluteFile.parentFile
    if (parent != null && !parent.isDirectory) failUsage("output directory does not exist: ${parent.path}")

    val document = open(file)
    document.use {
        println("OCR language: $language")
        val result = PdfOcr.makeSearchable(document, StubProvider(text), language, pages)
            .getOrElse { error -> fail("OCR failed: ${error.message ?: error::class.simpleName}") }
        for (report in result.pages) println(describe(report))
        out.writeBytes(result.bytes)
        println("Wrote ${out.absolutePath} (${result.pagesAdded} OCR page(s), ${result.wordsAdded} word(s))")
    }
    0
}

// -- Stub provider ------------------------------------------------------------

/**
 * Deterministic replacement for a real OCR engine: splits [text] into words
 * and places them on a grid that covers the image, so every word gets a
 * plausible box and confidence. The real provider lives in `:app`.
 */
private class StubProvider(text: String) : OcrProvider {

    private val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    override fun recognize(image: OcrImage, language: String): List<OcrWord> {
        if (words.isEmpty() || image.width <= 0 || image.height <= 0) return emptyList()
        val columns = ceil(sqrt(words.size.toDouble())).toInt().coerceAtLeast(1)
        val rows = (words.size + columns - 1) / columns
        val cellWidth = image.width.toDouble() / columns
        val cellHeight = image.height.toDouble() / rows
        return words.mapIndexed { index, word ->
            val column = index % columns
            val row = index / columns
            val left = column * cellWidth + cellWidth * 0.10
            val top = row * cellHeight + cellHeight * 0.25
            OcrWord(
                word,
                OcrBounds(left, top, left + cellWidth * 0.80, top + cellHeight * 0.50),
                0.97 - (index % 3) * 0.02
            )
        }
    }
}

private const val DEFAULT_TEXT = "PDFPaper sample OCR text"

// -- Output -------------------------------------------------------------------

private fun describe(report: OcrPageReport): String {
    val page = report.pageIndex + 1
    return when (report.status) {
        OcrPageStatus.ADDED -> {
            val image = report.imageWidth?.let { "${it}x${report.imageHeight}" } ?: "-"
            "page $page: added ${report.wordCount} word(s) from $image image, " +
                "confidence ${confidence(report.meanConfidence)} (min ${confidence(report.minConfidence)})"
        }
        OcrPageStatus.SKIPPED_HAS_TEXT -> "page $page: skipped (page already has text)"
        OcrPageStatus.NO_IMAGE -> "page $page: no image"
        OcrPageStatus.PROVIDER_FAILED ->
            "page $page: provider failed (${report.problems.firstOrNull() ?: "-"})"
        OcrPageStatus.NO_WORDS -> "page $page: no words recognized"
    }
}

private fun confidence(value: Double?): String = value?.let { "%.2f".format(it) } ?: "-"

private fun printUsage() {
    System.err.println(
        """
        Usage:
          cli ocr <file.pdf> [--lang eng+hin] [--out out.pdf]
              [--pages 1,3-5] [--text <words...>]

        Adds an invisible text layer to scanned pages (pages without text; a
        --pages list forces the named 1-based pages even when they have text).
        --lang is passed to the provider. The engine ships no OCR library, so a
        deterministic stub provider recognizes --text (default: "$DEFAULT_TEXT")
        on every page image. --text takes the words up to the next --option.
        """.trimIndent()
    )
}

// -- Argument parsing ---------------------------------------------------------

private class OcrArguments(
    val positionals: List<String>,
    private val options: Map<String, List<String>>
) {
    fun requireFile(index: Int): File = File(
        positionals.getOrNull(index) ?: failUsage("missing <file.pdf>")
    )

    fun option(name: String): String? = options[name]?.lastOrNull()
}

/** Parses `--key value`, `--key=value` and `--text` (words up to next option). */
private fun withParsed(args: List<String>, block: (OcrArguments) -> Int): Int {
    val positionals = ArrayList<String>()
    val options = LinkedHashMap<String, MutableList<String>>()
    fun add(name: String, value: String) {
        options.getOrPut(name) { ArrayList() } += value
    }
    var i = 0
    while (i < args.size) {
        val token = args[i]
        if (!token.startsWith("--")) {
            positionals += token
            i++
            continue
        }
        val body = token.removePrefix("--")
        val equals = body.indexOf('=')
        when {
            equals >= 0 -> {
                add(body.substring(0, equals), body.substring(equals + 1))
                i++
            }
            body == "text" -> {
                val text = StringBuilder()
                i++
                while (i < args.size && !args[i].startsWith("--")) {
                    if (text.isNotEmpty()) text.append(' ')
                    text.append(args[i])
                    i++
                }
                if (text.isEmpty()) failUsage("--text needs a value")
                add("text", text.toString())
            }
            else -> {
                val value = args.getOrNull(i + 1) ?: failUsage("--$body needs a value")
                add(body, value)
                i += 2
            }
        }
    }
    return block(OcrArguments(positionals, options))
}

/** "1,3-5" (1-based) to zero-based page indices. */
private fun parsePages(raw: String): List<Int> {
    val out = ArrayList<Int>()
    for (part in raw.split(',')) {
        val token = part.trim()
        if (token.isEmpty()) continue
        val dash = token.indexOf('-')
        if (dash > 0) {
            val from = token.substring(0, dash).trim().toIntOrNull()
            val to = token.substring(dash + 1).trim().toIntOrNull()
            if (from == null || to == null || from < 1 || to < from) {
                failUsage("--pages expects 1-based numbers like 1,3-5")
            }
            for (n in from..to) out += n - 1
        } else {
            val n = token.toIntOrNull()
            if (n == null || n < 1) failUsage("--pages expects 1-based numbers like 1,3-5")
            out += n - 1
        }
    }
    if (out.isEmpty()) failUsage("--pages needs at least one page")
    return out
}

private fun open(file: File): PdfDocument =
    PdfDocument.open(FileSource(file)).getOrElse { error ->
        System.err.println("Could not open ${file.absolutePath}: ${error.message ?: error::class.simpleName}")
        kotlin.system.exitProcess(1)
    }

private fun failUsage(message: String): Nothing {
    System.err.println("Error: $message")
    printUsage()
    kotlin.system.exitProcess(2)
}

private fun fail(message: String): Nothing {
    System.err.println("Error: $message")
    kotlin.system.exitProcess(1)
}
