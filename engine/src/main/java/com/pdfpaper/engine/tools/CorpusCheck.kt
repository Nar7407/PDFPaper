package com.pdfpaper.engine.tools

import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.page.PageTreeResult
import com.pdfpaper.engine.page.pageTree
import com.pdfpaper.engine.text.extractText
import java.io.File

private data class FileResult(
    val name: String,
    val ok: Boolean,
    val objects: Int,
    val rebuilt: Boolean,
    val streamsTotal: Int,
    val streamsDecoded: Int,
    val pages: Int,
    val textRuns: Int,
    val textPages: Int,
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
    val streamsTotal = results.sumOf { it.streamsTotal }
    val streamsDecoded = results.sumOf { it.streamsDecoded }
    val pagesTotal = results.sumOf { it.pages }
    val textRunsTotal = results.sumOf { it.textRuns }

    val lines = buildList {
        add("PDFPaper corpus report")
        add("Engine: ${engineVersion()}")
        add("Directory: ${dir.absolutePath}")
        add("Total: ${results.size} files, $okCount opened OK, ${results.size - okCount} failed")
        add("Pages: $pagesTotal")
        add("Streams: $streamsDecoded/$streamsTotal decoded")
        add("Text runs: $textRunsTotal")
        add("")
        for (r in results) {
            if (r.ok) {
                val suffix = if (r.rebuilt) ", rebuilt from scan" else ""
                add("[OK] ${r.name} (objects=${r.objects}$suffix) (streams=${r.streamsDecoded}/${r.streamsTotal} decoded) (pages=${r.pages}) (text runs=${r.textRuns})")
            } else {
                add("[FAIL] ${r.name}: ${r.reason}")
            }
        }
    }
    reportFile.parentFile?.mkdirs()
    reportFile.writeText(lines.joinToString("\n") + "\n")
    println(lines.take(6).joinToString("\n"))
    println("Report: ${reportFile.absolutePath}")
}

private fun checkOne(file: File): FileResult {
    return try {
        val result = PdfDocument.open(FileSource(file))
        result.fold(
            onSuccess = { doc ->
                doc.use {
                    var total = 0
                    var decoded = 0
                    for (objNum in it.file.entries.keys) {
                        val obj = try {
                            it.deref(objNum)
                        } catch (t: Throwable) {
                            null
                        } ?: continue
                        if (obj is PdfStream) {
                            total++
                            val ok = try {
                                it.decodeStream(obj).isSuccess
                            } catch (t: Throwable) {
                                false
                            }
                            if (ok) decoded++
                        }
                    }
                    val pageInfos = try {
                        val tree = pageTree(it) as? PageTreeResult.Ok
                        val ps = tree?.pages ?: emptyList()
                        var runs = 0
                        var textPages = 0
                        for (p in ps) {
                            val t = try {
                                p.extractText()
                            } catch (t: Throwable) {
                                null
                            }
                            if (t != null && t.runs.isNotEmpty()) {
                                runs += t.runs.size
                                textPages++
                            }
                        }
                        ps.size to (runs to textPages)
                    } catch (t: Throwable) {
                        0 to (0 to 0)
                    }
                    val (pages, runsInfo) = pageInfos
                    FileResult(
                        file.name, true, it.file.objectCount, it.file.rebuiltFromScan,
                        total, decoded, pages, runsInfo.first, runsInfo.second, ""
                    )
                }
            },
            onFailure = { e ->
                FileResult(file.name, false, 0, false, 0, 0, 0, 0, 0, shortReason(e))
            }
        )
    } catch (t: Throwable) {
        FileResult(file.name, false, 0, false, 0, 0, 0, 0, 0, shortReason(t))
    }
}

private fun PdfDocument.decodeStream(stream: PdfStream) =
    stream.decoded { ref: PdfRef -> deref(ref.objNum) }

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
