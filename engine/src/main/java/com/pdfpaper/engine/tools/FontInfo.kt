package com.pdfpaper.engine.tools

import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.FontDiagnostics
import com.pdfpaper.engine.text.FontResolver
import com.pdfpaper.engine.text.extractText
import java.io.File
import kotlin.system.exitProcess

/**
 * Diagnostic command for the font fallbacks logged by text extraction.
 *
 * Usage: cli fontinfo <file.pdf> --page <n>
 * Page numbers are 1-based, matching the pages users see.
 */
fun main(args: Array<String>) {
    val options = parseArgs(args)
    if (options == null) {
        System.err.println("Usage: cli fontinfo <file.pdf> --page <n>")
        exitProcess(2)
    }

    val file = File(options.file)
    val doc = PdfDocument.open(FileSource(file)).getOrElse { error ->
        System.err.println("Could not open ${file.absolutePath}: ${error.message ?: error::class.simpleName}")
        exitProcess(1)
    }

    doc.use {
        val pages = it.pages()
        if (options.page < 1 || options.page > pages.size) {
            System.err.println(
                "Page ${options.page} is out of range; ${file.name} has ${pages.size} page(s)"
            )
            exitProcess(1)
        }
        printPage(it, pages[options.page - 1], options.page, file)
    }
}

private class Options(val file: String, val page: Int)

private fun parseArgs(args: Array<String>): Options? {
    var index = 0
    if (args.firstOrNull() == "fontinfo") index = 1

    var file: String? = null
    var page: Int? = null
    while (index < args.size) {
        val arg = args[index]
        when {
            arg == "--page" -> {
                index++
                page = args.getOrNull(index)?.toIntOrNull() ?: return null
            }
            arg.startsWith("--page=") -> page = arg.substringAfter('=').toIntOrNull() ?: return null
            file == null -> file = arg
            else -> return null
        }
        index++
    }
    return if (file != null && page != null && page >= 1) Options(file, page) else null
}

private fun printPage(doc: PdfDocument, page: PdfPage, pageNumber: Int, file: File) {
    println("Fonts on page $pageNumber of ${doc.pages().size} in ${file.absolutePath}")
    val fontDict = resolveDict(page.resources?.get("Font"), doc)
    if (fontDict == null) {
        println("  (no /Font resources on this page)")
        return
    }

    val entries = fontDict.entries.entries.sortedBy { it.key.name }
    if (entries.isEmpty()) {
        println("  (empty /Font resource dictionary)")
        return
    }
    println("  ${entries.size} resource(s): ${entries.joinToString(", ") { "/${it.key.name}" }}")

    for ((name, raw) in entries) {
        val font = FontResolver.resolve(raw, doc)
        println()
        println("/${name.name}")
        if (font == null) {
            println("  Font dictionary could not be resolved")
            continue
        }
        printDiagnostics(font.diagnostics)
    }

    val fontWarnings = page.extractText().warnings.filter { it.startsWith("font ") }
    if (fontWarnings.isNotEmpty()) {
        println()
        println("Extraction warnings:")
        for (warning in fontWarnings) println("  $warning")
    }
}

private fun printDiagnostics(diagnostics: FontDiagnostics) {
    println("  Subtype: ${diagnostics.subtype ?: "(missing)"}")
    println("  BaseFont: ${diagnostics.baseFont ?: "(missing)"}")
    println("  Encoding: ${diagnostics.encoding ?: "(missing)"}")
    println("  /Widths: ${presence(diagnostics.hasWidths, diagnostics.widthsKind)}")
    println("  /W: ${presence(diagnostics.hasW, diagnostics.wKind)}")
    println("  /ToUnicode: ${if (diagnostics.hasToUnicode) "present" else "absent"}")
    println(
        "  /FontFile*: " +
            if (diagnostics.fontFiles.isEmpty()) "none" else diagnostics.fontFiles.joinToString(", ")
    )
    println(
        "  Degraded: " +
            if (diagnostics.reasons.isEmpty()) {
                "no"
            } else {
                "yes (${diagnostics.reasons.joinToString("; ")})"
            }
    )
}

private fun presence(exists: Boolean, kind: String): String = when {
    exists -> "present ($kind)"
    kind == "absent" -> "absent"
    else -> "absent ($kind)"
}

private fun resolveDict(raw: PdfObject?, doc: PdfDocument): PdfDict? = when (raw) {
    is PdfDict -> raw
    is PdfRef -> doc.resolve(raw) as? PdfDict
    else -> null
}
