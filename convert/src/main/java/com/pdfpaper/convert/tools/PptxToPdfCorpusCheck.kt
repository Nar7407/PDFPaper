package com.pdfpaper.convert.tools

import com.pdfpaper.convert.PptxToPdf
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.extractText
import java.io.File
import kotlin.system.exitProcess

/**
 * T20 acceptance harness: converts every PPTX the T17 corpus check produced
 * back to PDF, re-opens each result with the engine (the same reader the viewer
 * uses) and writes a summary report — the raw material for the PPTX→PDF
 * section of docs/CONVERT-LIMITS.md.
 *
 * usage: pptxToPdfCorpusCheck <corpusDir> <outDir> <reportFile>
 *
 * A file fails when it does not convert, when the PDF does not open or has no
 * pages, or when the engine reports a warning while reading it back. Font
 * substitution notes are expected on real presentations and are only listed.
 *
 * Exit code is non-zero when any file fails, so the Gradle task fails loudly.
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("usage: pptxToPdfCorpusCheck <corpusDir> <outDir> <reportFile>")
        exitProcess(1)
    }
    val corpusDir = File(args[0])
    val outDir = File(args[1])
    val reportFile = File(args[2])
    outDir.mkdirs()

    val decks = corpusDir.listFiles { file: File -> file.isFile && file.extension.equals("pptx", true) }
        ?.sortedBy { it.name } ?: emptyList()
    if (decks.isEmpty()) {
        System.err.println("no PPTX files in ${corpusDir.absolutePath}")
        exitProcess(1)
    }

    val report = StringBuilder()
    report.append("PDFPaper PPTX->PDF corpus check\n")
    report.append("corpus: ${corpusDir.absolutePath}\n\n")

    var ok = 0
    var failed = 0
    for (file in decks) {
        val out = File(outDir, "${file.nameWithoutExtension}.pdf")
        val result = try {
            val converted = PptxToPdf.convert(file.readBytes(), sourceName = file.name)
            out.writeBytes(converted.bytes)
            converted
        } catch (t: Throwable) {
            report.append("FAIL  ${file.name}: conversion error: ${t.message}\n")
            failed++
            continue
        }

        var pageCount = 0
        var characters = 0
        val engineWarnings = ArrayList<String>()
        try {
            PdfDocument.open(ByteArraySource(result.bytes)).getOrThrow().use { document ->
                for (page in document.pages()) {
                    val extracted = page.extractText()
                    pageCount++
                    characters += extracted.runs.sumOf { it.text.length }
                    engineWarnings += extracted.warnings.map { "page ${page.index}: $it" }
                }
            }
        } catch (t: Throwable) {
            report.append("FAIL  ${file.name}: the output does not open: ${t.message}\n")
            failed++
            continue
        }
        if (pageCount == 0) {
            report.append("FAIL  ${file.name}: the output has no pages\n")
            failed++
            continue
        }
        if (engineWarnings.isNotEmpty()) {
            report.append("FAIL  ${file.name}: the engine warned while reading the output\n")
            for (warning in engineWarnings.take(10)) report.append("        $warning\n")
            failed++
            continue
        }

        ok++
        val p = result.report
        report.append(
            "OK    ${file.name} -> ${out.name} (${result.bytes.size} bytes, ${p.slides} slide(s), " +
                "$characters characters read back, ${p.shapes} shapes, ${p.pictures} pictures, " +
                "${p.textBoxes} text boxes, ${p.listItems} list items)\n"
        )
        for (note in p.notes) report.append("        note: $note\n")
    }

    report.append("\n$ok ok, $failed failed\n")
    reportFile.parentFile?.mkdirs()
    reportFile.writeText(report.toString())
    print(report)
    exitProcess(if (failed == 0) 0 else 1)
}
