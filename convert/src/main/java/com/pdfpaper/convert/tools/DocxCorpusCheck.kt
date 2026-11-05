package com.pdfpaper.convert.tools

import com.pdfpaper.convert.DocxValidator
import com.pdfpaper.convert.PdfToDocx
import com.pdfpaper.convert.PdfToDocxOptions
import com.pdfpaper.convert.docx.DocxMode
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import java.io.File
import kotlin.system.exitProcess

/**
 * T18 acceptance harness: converts every PDF in the corpus to DOCX in both
 * modes, runs the structural validator on each result and writes a summary
 * report — the raw material for the quality table in docs/CONVERT-LIMITS.md.
 *
 * usage: docxCorpusCheck <corpusDir> <outDir> <reportFile>
 *
 * Exit code is non-zero when any file fails to convert or validate, so the
 * Gradle task fails loudly.
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("usage: docxCorpusCheck <corpusDir> <outDir> <reportFile>")
        exitProcess(1)
    }
    val corpusDir = File(args[0])
    val outDir = File(args[1])
    val reportFile = File(args[2])
    outDir.mkdirs()

    val pdfs = corpusDir.listFiles { file: File -> file.isFile && file.extension.equals("pdf", true) }
        ?.sortedBy { it.name } ?: emptyList()
    if (pdfs.isEmpty()) {
        System.err.println("no PDFs in ${corpusDir.absolutePath}")
        exitProcess(1)
    }

    val report = StringBuilder()
    report.append("PDFPaper PDF->DOCX corpus check\n")
    report.append("corpus: ${corpusDir.absolutePath}\n\n")

    var ok = 0
    var failed = 0
    for (pdf in pdfs) {
        val pages = try {
            PdfDocument.open(FileSource(pdf)).getOrThrow().use { it.pages().size }
        } catch (t: Throwable) {
            -1
        }
        for (mode in DocxMode.entries) {
            val out = File(outDir, "${pdf.nameWithoutExtension}.${mode.id}.docx")
            val result = try {
                val converted = PdfToDocx.convertFile(pdf, PdfToDocxOptions(mode = mode))
                out.writeBytes(converted.bytes)
                converted
            } catch (t: Throwable) {
                report.append("FAIL  ${pdf.name} [${mode.id}]: conversion error: ${t.message}\n")
                failed++
                continue
            }
            val problems = DocxValidator.validate(result.bytes)
            if (problems.isNotEmpty()) {
                failed++
                report.append("FAIL  ${pdf.name} [${mode.id}]: ${problems.size} structural problem(s)\n")
                for (problem in problems.take(10)) report.append("        $problem\n")
                continue
            }
            ok++
            val p = result.report
            report.append(
                "OK    ${pdf.name} [$pages page(s)] [${mode.id}]: ${out.name} " +
                    "(${result.bytes.size} bytes, ${p.paragraphs} paragraphs, ${p.headings} headings, " +
                    "${p.listItems} list items, ${p.tables} tables, ${p.images} pictures, " +
                    "${p.runningHeadsRemoved} running heads removed)\n"
            )
            val notes = p.notes + p.pages.flatMap { page ->
                page.warnings.map { "p${page.pageNumber}: $it" } +
                    page.imagesSkipped.map { "p${page.pageNumber}: image skipped: $it" }
            }
            for (note in notes.distinct()) report.append("        note: $note\n")
        }
    }

    report.append("\n$ok ok, $failed failed\n")
    reportFile.parentFile?.mkdirs()
    reportFile.writeText(report.toString())
    print(report)
    exitProcess(if (failed == 0) 0 else 1)
}
