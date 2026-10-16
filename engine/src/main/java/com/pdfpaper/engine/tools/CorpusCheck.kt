package com.pdfpaper.engine.tools

import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.xref.PdfFile
import java.io.File

private data class FileResult(
    val name: String,
    val ok: Boolean,
    val objects: Int,
    val rebuilt: Boolean,
    val reason: String
)

/**
 * Corpus harness (T3): opens every `*.pdf` in the given directory with the
 * current engine. A crash or uncaught exception in any file counts as a
 * failure of that file, never of the harness: everything is caught per file.
 *
 * Usage: CorpusCheck <testdataDir> <reportFile>
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: CorpusCheck <testdataDir> <reportFile>" }
    val dir = File(args[0])
    val reportFile = File(args[1])
    val pdfs = dir.listFiles { file -> file.isFile && file.name.endsWith(".pdf", ignoreCase = true) }
        ?.sortedBy { it.name }
        ?: emptyList()

    val results = pdfs.map { checkOne(it) }
    val okCount = results.count { it.ok }

    val lines = buildList {
        add("PDFPaper corpus report")
        add("Engine: ${engineVersion()}")
        add("Directory: ${dir.absolutePath}")
        add("Total: ${results.size} files, $okCount opened OK, ${results.size - okCount} failed")
        add("")
        for (r in results) {
            if (r.ok) {
                val suffix = if (r.rebuilt) ", rebuilt from scan" else ""
                add("[OK] ${r.name} (objects=${r.objects}$suffix)")
            } else {
                add("[FAIL] ${r.name}: ${r.reason}")
            }
        }
    }
    reportFile.parentFile?.mkdirs()
    reportFile.writeText(lines.joinToString("\n") + "\n")
    println(lines.take(4).joinToString("\n"))
    println("Report: ${reportFile.absolutePath}")
}

private fun checkOne(file: File): FileResult {
    return try {
        val result = PdfFile.open(FileSource(file))
        result.fold(
            onSuccess = { pdf ->
                pdf.use {
                    FileResult(file.name, true, it.objectCount, it.rebuiltFromScan, "")
                }
            },
            onFailure = { e ->
                FileResult(file.name, false, 0, false, shortReason(e))
            }
        )
    } catch (t: Throwable) {
        FileResult(file.name, false, 0, false, shortReason(t))
    }
}

private fun shortReason(t: Throwable): String {
    val name = t::class.simpleName ?: "Throwable"
    val message = t.message?.replace(Regex("\\s+"), " ")?.take(120)
    return if (message.isNullOrBlank()) name else "$name: $message"
}

private fun engineVersion(): String {
    return try {
        com.pdfpaper.engine.PdfEngine().version()
    } catch (_: Exception) {
        "unknown"
    }
}
