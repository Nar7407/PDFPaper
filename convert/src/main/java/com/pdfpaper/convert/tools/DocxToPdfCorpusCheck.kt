package com.pdfpaper.convert.tools

import com.pdfpaper.convert.DocxToPdf
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.extractText
import java.io.File
import kotlin.system.exitProcess

/**
 * T19 acceptance harness: converts every DOCX in the corpus to PDF, re-opens
 * each result with the engine (the same reader the viewer uses) and writes a
 * summary report — the raw material for the DOCX→PDF section of
 * docs/CONVERT-LIMITS.md.
 *
 * usage: docxToPdfCorpusCheck <corpusDir> <outDir> <reportFile>
 *
 * A file fails when it does not convert, when the PDF does not open or has no
 * pages, or when the engine reports a warning while reading it back. Font
 * substitution notes are expected on real documents and are only listed.
 *
 * Exit code is non-zero when any file fails, so the Gradle task fails loudly.
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("usage: docxToPdfCorpusCheck <corpusDir> <outDir> <reportFile>")
        exitProcess(1)
    }
    val corpusDir = File(args[0])
    val outDir = File(args[1])
    val reportFile = File(args[2])
    outDir.mkdirs()

    val docx = corpusDir.listFiles { file: File -> file.isFile && file.extension.equals("docx", true) }
        ?.sortedBy { it.name } ?: emptyList()
    if (docx.isEmpty()) {
        System.err.println("no DOCX files in ${corpusDir.absolutePath}")
        exitProcess(1)
    }

    val report = StringBuilder()
    report.append("PDFPaper DOCX->PDF corpus check\n")
    report.append("corpus: ${corpusDir.absolutePath}\n\n")

    var ok = 0
    var failed = 0
    for (file in docx) {
        val out = File(outDir, "${file.nameWithoutExtension}.pdf")
        val result = try {
            val converted = DocxToPdf.convert(file.readBytes(), sourceName = file.name)
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
            "OK    ${file.name} -> ${out.name} (${result.bytes.size} bytes, ${p.pages} page(s), " +
                "$characters characters read back, ${p.paragraphs} paragraphs, ${p.headings} headings, " +
                "${p.listItems} list items, ${p.tables} tables, ${p.pictures} pictures)\n"
        )
        for (note in p.notes) report.append("        note: $note\n")
    }

    report.append("\n$ok ok, $failed failed\n")
    reportFile.parentFile?.mkdirs()
    reportFile.writeText(report.toString())
    print(report)
    exitProcess(if (failed == 0) 0 else 1)
}
