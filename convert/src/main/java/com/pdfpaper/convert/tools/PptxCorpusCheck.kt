package com.pdfpaper.convert.tools

import com.pdfpaper.convert.PdfToPptx
import com.pdfpaper.convert.PdfToPptxOptions
import com.pdfpaper.convert.PptxValidator
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import java.io.File
import kotlin.system.exitProcess

/**
 * T17 acceptance harness: converts every PDF in the corpus to PPTX, runs the
 * structural validator on each result and writes a summary report.
 *
 * usage: pptxCorpusCheck <corpusDir> <outDir> <reportFile>
 *
 * Exit code is non-zero when any file fails to convert or validate, so the
 * Gradle task fails loudly.
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("usage: pptxCorpusCheck <corpusDir> <outDir> <reportFile>")
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
    report.append("PDFPaper PDF->PPTX corpus check\n")
    report.append("corpus: ${corpusDir.absolutePath}\n\n")

    var ok = 0
    var failed = 0
    for (pdf in pdfs) {
        val out = File(outDir, pdf.nameWithoutExtension + ".pptx")
        val converted = try {
            val result = PdfToPptx.convertFile(pdf, PdfToPptxOptions())
            out.writeBytes(result.bytes)
            result
        } catch (t: Throwable) {
            report.append("FAIL  ${pdf.name}: conversion error: ${t.message}\n")
            failed++
            continue
        }
        val problems = PptxValidator.validate(converted.bytes)
        val pages = try {
            PdfDocument.open(FileSource(pdf)).getOrThrow().use { it.pages().size }
        } catch (t: Throwable) {
            -1
        }
        if (problems.isEmpty()) {
            ok++
            report.append(
                "OK    ${pdf.name}: $pages page(s) -> ${out.name} " +
                    "(${converted.bytes.size} bytes, ${converted.report.textBoxes} text, " +
                    "${converted.report.pictures} pictures)\n"
            )
        } else {
            failed++
            report.append("FAIL  ${pdf.name}: ${problems.size} structural problem(s)\n")
            for (problem in problems.take(10)) report.append("        $problem\n")
        }
    }

    report.append("\n$ok ok, $failed failed\n")
    reportFile.parentFile?.mkdirs()
    reportFile.writeText(report.toString())
    print(report)
    exitProcess(if (failed == 0) 0 else 1)
}
