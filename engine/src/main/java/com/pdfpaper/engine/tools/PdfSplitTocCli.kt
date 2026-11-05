package com.pdfpaper.engine.tools

import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.split.TocSplit
import com.pdfpaper.engine.outline.DocumentOutline
import com.pdfpaper.engine.split.TocSplitIssue
import com.pdfpaper.engine.split.TocSplitOptions
import com.pdfpaper.engine.split.TocSplitPlan
import com.pdfpaper.engine.split.TocSplitWriter
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * T66: `split-toc <book.pdf> --level N --out-dir <parts/> [--duplicate-boundary]
 * [--overwrite]`.
 *
 * The CLI is a thin wrapper over [TocSplit] and [TocSplitWriter]: it parses the
 * arguments, preflights every planned file name before writing anything, then
 * writes one part at a time. Exit codes: 0 success, 1 runtime failure (no
 * outline, existing file, unreadable PDF), 2 usage error.
 */
fun main(args: Array<String>) {
    exitProcess(runSplitToc(args))
}

private class SplitUsageError(message: String) : Exception(message)

internal fun runSplitToc(
    args: Array<String>,
    out: PrintStream = System.out,
    err: PrintStream = System.err
): Int {
    // The shell wrapper passes the command name through as well.
    val tokens = if (args.isNotEmpty() && args[0].equals("split-toc", ignoreCase = true)) {
        args.drop(1)
    } else {
        args.toList()
    }

    val positional = ArrayList<String>()
    val options = HashMap<String, String>()
    val flags = HashSet<String>()
    var index = 0
    while (index < tokens.size) {
        val token = tokens[index]
        if (token.startsWith("--")) {
            val name = token.removePrefix("--")
            if (name == "overwrite" || name == "duplicate-boundary") {
                flags += name
                index++
            } else {
                val value = tokens.getOrNull(index + 1)
                if (value == null || value.startsWith("--")) {
                    err.println("Usage error: --$name needs a value")
                    printUsage(err)
                    return 2
                }
                options[name] = value
                index += 2
            }
        } else {
            positional += token
            index++
        }
    }

    val input = positional.firstOrNull()
    if (input == null) {
        err.println("Usage error: missing input PDF")
        printUsage(err)
        return 2
    }
    val outDir = options["out-dir"]
    if (outDir.isNullOrBlank()) {
        err.println("Usage error: --out-dir <folder> is required")
        printUsage(err)
        return 2
    }
    val level = (options["level"] ?: "1").toIntOrNull()
    if (level == null || level < 1) {
        err.println("Usage error: --level must be a positive integer (1 = top-level chapters)")
        return 2
    }
    val inputFile = File(input)
    if (!inputFile.isFile) {
        err.println("Usage error: there is no file at $input")
        return 2
    }

    val document = PdfDocument.open(FileSource(inputFile)).getOrElse { error ->
        err.println("The PDF could not be read: ${error.message ?: error::class.simpleName}")
        return 1
    }
    return document.use { doc ->
        val planOptions = TocSplitOptions(
            level = level,
            duplicateBoundaryPage = flags.contains("duplicate-boundary")
        )
        val plan = when {
            DocumentOutline.diagnose(doc).isNotEmpty() -> TocSplit.planOutline(doc, planOptions)
            else -> TocSplit.planHeadings(doc, planOptions)
        }
        val usable = plan.parts
        if (usable.isEmpty()) {
            reportNoPlan(plan, err)
            return@use 1
        }

        reportIssues(plan, out)

        val directory = File(outDir)
        val targets = usable.map { File(directory, it.fileName) }
        val existing = targets.filter { it.exists() }
        if (existing.isNotEmpty() && !flags.contains("overwrite")) {
            for (file in existing) {
                err.println("${file.name} already exists; refusing to overwrite (use --overwrite)")
            }
            return@use 1
        }
        if (!directory.exists() && !directory.mkdirs()) {
            err.println("Could not create $outDir")
            return@use 1
        }

        val created = ArrayList<File>()
        val written = TocSplitWriter.write(
            document = doc,
            parts = usable,
            sink = { output ->
                val target = File(directory, output.part.fileName)
                val replace = flags.contains("overwrite") && target.exists()
                if (!replace && target.exists()) error("${target.name} already exists")
                try {
                    target.writeBytes(output.bytes)
                } catch (error: Exception) {
                    if (!replace) runCatching { target.delete() }
                    throw error
                }
                if (!replace) created += target
                for (issue in output.report.issues) {
                    if (issue is TocSplitIssue.DroppedExternalLink) {
                        out.println(
                            "Note: ${output.part.fileName} drops a link from page " +
                                "${issue.sourcePage + 1} to page ${issue.targetPage + 1} " +
                                "(that page is in another part)."
                        )
                    }
                }
                val range = if (output.part.pages.first == output.part.pages.last) {
                    "${output.part.pages.first + 1}"
                } else {
                    "${output.part.pages.first + 1}-${output.part.pages.last + 1}"
                }
                out.println("Wrote ${output.part.fileName} (pages $range)")
            }
        )
        if (written < usable.size) {
            // No partial overwrite: a file this run created for a part that did
            // not finish is removed again. (An --overwrite replacement cannot
            // be restored; the failure message says how far it got.)
            for (file in created) runCatching { file.delete() }
            err.println("Stopped after $written of ${usable.size} parts; no new files were left behind.")
            return@use 1
        }
        out.println("Done: $written of ${usable.size} parts written to $outDir")
        0
    }
}

private fun reportNoPlan(plan: TocSplitPlan, err: PrintStream) {
    val noOutline = plan.issues.any { it is TocSplitIssue.NoOutline }
    if (noOutline) {
        err.println("This document has no outline or table of contents.")
        err.println("Try \"split every N pages\" instead, or use the app's detected-headings mode.")
    } else {
        err.println("This document has no usable outline entries, so there is no table of contents to split at.")
        err.println("Try \"split every N pages\" instead, or use the app's detected-headings mode.")
        for (issue in plan.issues) {
            if (issue is TocSplitIssue.InvalidDestination) {
                err.println("  Skipped \"${issue.title}\": its destination does not resolve to a page.")
            }
        }
    }
}

private fun reportIssues(plan: TocSplitPlan, out: PrintStream) {
    for (issue in plan.issues) {
        when (issue) {
            is TocSplitIssue.OutOfOrder ->
                out.println(
                    "Warning: out-of-order outline entry \"${issue.title}\" was sorted by page " +
                        "(outline position ${issue.originalPage + 1} -> ${issue.sortedPage + 1})."
                )
            is TocSplitIssue.InvalidDestination ->
                out.println(
                    "Warning: skipped \"${issue.title}\": its destination does not resolve to a page."
                )
            is TocSplitIssue.SamePage ->
                out.println(
                    "Warning: \"${issue.title}\" starts on page ${issue.page + 1}, " +
                        "which the previous chapter already starts; the first entry keeps it."
                )
            is TocSplitIssue.EmptyRange ->
                out.println("Warning: skipped \"${issue.title}\": its page range would be empty.")
            TocSplitIssue.NoUsableEntries ->
                out.println("Warning: no usable outline entries were found.")
            TocSplitIssue.NoOutline -> Unit
            is TocSplitIssue.DroppedExternalLink -> Unit
            is TocSplitIssue.OutputNameAdjusted -> Unit
        }
    }
}

private fun printUsage(err: PrintStream) {
    err.println("Usage: split-toc <book.pdf> --level N --out-dir <folder> [--duplicate-boundary] [--overwrite]")
    err.println("  --level N            chapter depth: 1 = top-level bookmarks")
    err.println("  --out-dir <folder>   folder for the new PDFs (created if missing)")
    err.println("  --duplicate-boundary keep the page a chapter starts on at the end of the previous part")
    err.println("  --overwrite          replace files that already exist")
}


