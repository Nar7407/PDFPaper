package com.pdfpaper.convert.tools

import com.pdfpaper.convert.DocxToPdf
import com.pdfpaper.convert.DocxToPdfOptions
import com.pdfpaper.convert.PdfToDocx
import com.pdfpaper.convert.PdfToDocxOptions
import com.pdfpaper.convert.PdfToPptx
import com.pdfpaper.convert.PdfToPptxOptions
import com.pdfpaper.convert.PptxToPdf
import com.pdfpaper.convert.PptxToPdfOptions
import com.pdfpaper.convert.docx.DocxMode
import java.io.File
import kotlin.system.exitProcess

/**
 * `convert pdf-to-pptx <in.pdf> --out <out.pptx> [options]`
 * `convert pdf-to-docx <in.pdf> --out <out.docx> [options]`
 * `convert docx-to-pdf <in.docx> --out <out.pdf> [options]`
 * `convert pptx-to-pdf <in.pptx> --out <out.pdf> [options]`
 *
 * Exit codes: 0 success, 1 bad usage, 2 conversion failure.
 */
fun main(args: Array<String>) {
    exitProcess(run(args))
}

private fun run(rawArgs: Array<String>): Int {
    // Both entry points work: `cli convert pdf-to-pptx ...` (the wrapper passes
    // the word "convert" through) and `--args="pdf-to-pptx ..."`.
    val args = if (rawArgs.isNotEmpty() && rawArgs[0] == "convert") rawArgs.drop(1).toTypedArray() else rawArgs
    if (args.isEmpty()) {
        usage()
        return 1
    }
    return when (args[0]) {
        "pdf-to-pptx" -> pdfToPptx(args.drop(1))
        "pdf-to-docx" -> pdfToDocx(args.drop(1))
        "docx-to-pdf" -> docxToPdf(args.drop(1))
        "pptx-to-pdf" -> pptxToPdf(args.drop(1))
        "help", "--help", "-h" -> {
            usage()
            0
        }
        else -> {
            System.err.println("unknown subcommand: ${args[0]}")
            usage()
            1
        }
    }
}

private fun pdfToPptx(args: List<String>): Int {
    val input: String?
    var out: String? = null
    var report: String? = null
    var fidelity = false
    var dpi = 150.0
    var maxPages = Int.MAX_VALUE
    var quiet = false

    var i = 0
    var positional: String? = null
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--out" -> out = args.getOrNull(++i)
            arg.startsWith("--out=") -> out = arg.substringAfter('=')
            arg == "--report" -> report = args.getOrNull(++i)
            arg.startsWith("--report=") -> report = arg.substringAfter('=')
            arg == "--fidelity" -> fidelity = true
            arg == "--dpi" -> dpi = args.getOrNull(++i)?.toDoubleOrNull() ?: return badArg("--dpi", args.getOrNull(i))
            arg.startsWith("--dpi=") -> dpi = arg.substringAfter('=').toDoubleOrNull() ?: return badArg("--dpi", arg)
            arg == "--max-pages" -> maxPages =
                args.getOrNull(++i)?.toIntOrNull() ?: return badArg("--max-pages", args.getOrNull(i))
            arg.startsWith("--max-pages=") -> maxPages =
                arg.substringAfter('=').toIntOrNull() ?: return badArg("--max-pages", arg)
            arg == "--quiet" -> quiet = true
            arg.startsWith("--") -> {
                System.err.println("unknown option: $arg")
                usage()
                return 1
            }
            else -> {
                if (positional != null) {
                    System.err.println("unexpected argument: $arg")
                    usage()
                    return 1
                }
                positional = arg
            }
        }
        i++
    }
    input = positional

    if (input == null || out == null) {
        System.err.println("pdf-to-pptx needs an input file and --out <file.pptx>")
        usage()
        return 1
    }
    val inputFile = File(input)
    if (!inputFile.isFile) {
        System.err.println("input file not found: $input")
        return 2
    }
    val options = PdfToPptxOptions(fidelity = fidelity, dpi = dpi, maxPages = maxPages)
    return try {
        val result = PdfToPptx.convertFile(inputFile, options)
        val outputFile = File(out)
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(result.bytes)
        val reportFile = File(report ?: "$out.report.txt")
        reportFile.writeText(result.report.toText())

        if (!quiet) {
            val p = result.report
            println("converted $input -> $out")
            println("  slides: ${p.pageCount}, text boxes: ${p.textBoxes}, pictures: ${p.pictures}")
            if (p.hasLosses) {
                println("  some content was dropped or approximated (see ${reportFile.path})")
            }
        }
        0
    } catch (t: Throwable) {
        System.err.println("conversion failed: ${t.message}")
        2
    }
}

private fun badArg(name: String, value: String?): Int {
    System.err.println("invalid value for $name: ${value ?: "(missing)"}")
    return 1
}

private fun pdfToDocx(args: List<String>): Int {
    var out: String? = null
    var report: String? = null
    var mode = DocxMode.FLOW
    var dpi = 200.0
    var maxPages = Int.MAX_VALUE
    var quiet = false

    var i = 0
    var positional: String? = null
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--out" -> out = args.getOrNull(++i)
            arg.startsWith("--out=") -> out = arg.substringAfter('=')
            arg == "--report" -> report = args.getOrNull(++i)
            arg.startsWith("--report=") -> report = arg.substringAfter('=')
            arg == "--mode" -> {
                val value = args.getOrNull(++i)
                mode = DocxMode.of(value) ?: return badArg("--mode", value)
            }
            arg.startsWith("--mode=") -> {
                val value = arg.substringAfter('=')
                mode = DocxMode.of(value) ?: return badArg("--mode", value)
            }
            arg == "--dpi" -> dpi = args.getOrNull(++i)?.toDoubleOrNull() ?: return badArg("--dpi", args.getOrNull(i))
            arg.startsWith("--dpi=") -> dpi = arg.substringAfter('=').toDoubleOrNull() ?: return badArg("--dpi", arg)
            arg == "--max-pages" -> maxPages =
                args.getOrNull(++i)?.toIntOrNull() ?: return badArg("--max-pages", args.getOrNull(i))
            arg.startsWith("--max-pages=") -> maxPages =
                arg.substringAfter('=').toIntOrNull() ?: return badArg("--max-pages", arg)
            arg == "--quiet" -> quiet = true
            arg.startsWith("--") -> {
                System.err.println("unknown option: $arg")
                usage()
                return 1
            }
            else -> {
                if (positional != null) {
                    System.err.println("unexpected argument: $arg")
                    usage()
                    return 1
                }
                positional = arg
            }
        }
        i++
    }
    val input = positional

    if (input == null || out == null) {
        System.err.println("pdf-to-docx needs an input file and --out <file.docx>")
        usage()
        return 1
    }
    val inputFile = File(input)
    if (!inputFile.isFile) {
        System.err.println("input file not found: $input")
        return 2
    }
    val options = PdfToDocxOptions(mode = mode, dpi = dpi, maxPages = maxPages)
    return try {
        val result = PdfToDocx.convertFile(inputFile, options)
        val outputFile = File(out)
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(result.bytes)
        val reportFile = File(report ?: "$out.report.txt")
        reportFile.writeText(result.report.toText())

        if (!quiet) {
            val p = result.report
            println("converted $input -> $out (${mode.id})")
            println(
                "  paragraphs: ${p.paragraphs}, headings: ${p.headings}, " +
                    "list items: ${p.listItems}, tables: ${p.tables}, pictures: ${p.images}"
            )
            if (p.hasLosses) {
                println("  some content was dropped or approximated (see ${reportFile.path})")
            }
        }
        0
    } catch (t: Throwable) {
        System.err.println("conversion failed: ${t.message}")
        2
    }
}

private fun docxToPdf(args: List<String>): Int {
    var out: String? = null
    var report: String? = null
    var quiet = false

    var i = 0
    var positional: String? = null
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--out" -> out = args.getOrNull(++i)
            arg.startsWith("--out=") -> out = arg.substringAfter('=')
            arg == "--report" -> report = args.getOrNull(++i)
            arg.startsWith("--report=") -> report = arg.substringAfter('=')
            arg == "--quiet" -> quiet = true
            arg.startsWith("--") -> {
                System.err.println("unknown option: $arg")
                usage()
                return 1
            }
            else -> {
                if (positional != null) {
                    System.err.println("unexpected argument: $arg")
                    usage()
                    return 1
                }
                positional = arg
            }
        }
        i++
    }
    val input = positional

    if (input == null || out == null) {
        System.err.println("docx-to-pdf needs an input file and --out <file.pdf>")
        usage()
        return 1
    }
    val inputFile = File(input)
    if (!inputFile.isFile) {
        System.err.println("input file not found: $input")
        return 2
    }
    return try {
        val result = DocxToPdf.convertFile(inputFile, DocxToPdfOptions())
        val outputFile = File(out)
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(result.bytes)
        val reportFile = File(report ?: "$out.report.txt")
        reportFile.writeText(result.report.toText())

        if (!quiet) {
            val p = result.report
            println("converted $input -> $out")
            println(
                "  pages: ${p.pages}, paragraphs: ${p.paragraphs}, headings: ${p.headings}, " +
                    "list items: ${p.listItems}, tables: ${p.tables}, pictures: ${p.pictures}"
            )
            if (p.hasLosses) {
                println("  some content was dropped or approximated (see ${reportFile.path})")
            }
        }
        0
    } catch (t: Throwable) {
        System.err.println("conversion failed: ${t.message}")
        2
    }
}

private fun pptxToPdf(args: List<String>): Int {
    var out: String? = null
    var report: String? = null
    var quiet = false

    var i = 0
    var positional: String? = null
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--out" -> out = args.getOrNull(++i)
            arg.startsWith("--out=") -> out = arg.substringAfter('=')
            arg == "--report" -> report = args.getOrNull(++i)
            arg.startsWith("--report=") -> report = arg.substringAfter('=')
            arg == "--quiet" -> quiet = true
            arg.startsWith("--") -> {
                System.err.println("unknown option: $arg")
                usage()
                return 1
            }
            else -> {
                if (positional != null) {
                    System.err.println("unexpected argument: $arg")
                    usage()
                    return 1
                }
                positional = arg
            }
        }
        i++
    }
    val input = positional

    if (input == null || out == null) {
        System.err.println("pptx-to-pdf needs an input file and --out <file.pdf>")
        usage()
        return 1
    }
    val inputFile = File(input)
    if (!inputFile.isFile) {
        System.err.println("input file not found: $input")
        return 2
    }
    return try {
        val result = PptxToPdf.convertFile(inputFile, PptxToPdfOptions())
        val outputFile = File(out)
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(result.bytes)
        val reportFile = File(report ?: "$out.report.txt")
        reportFile.writeText(result.report.toText())

        if (!quiet) {
            val p = result.report
            println("converted $input -> $out")
            println(
                "  slides: ${p.slides}, shapes: ${p.shapes}, pictures: ${p.pictures}, " +
                    "text boxes: ${p.textBoxes}, list items: ${p.listItems}"
            )
            if (p.hasLosses) {
                println("  some content was dropped or approximated (see ${reportFile.path})")
            }
        }
        0
    } catch (t: Throwable) {
        System.err.println("conversion failed: ${t.message}")
        2
    }
}

private fun usage() {
    System.err.println(
        """
        usage: convert <command> [options]

        commands:
          pdf-to-pptx <in.pdf> --out <out.pptx>
              [--fidelity]        render each page to a picture and put
                                  selectable text boxes on top
              [--dpi <n>]         raster resolution for --fidelity (default 150)
              [--max-pages <n>]   stop after n pages
              [--report <file>]   write the loss report here
                                  (default <out.pptx>.report.txt)
              [--quiet]

          pdf-to-docx <in.pdf> --out <out.docx>
              [--mode flow|exact] flow (default) rebuilds editable paragraphs,
                                  lists and tables; exact keeps every line at
                                  its original page position
              [--dpi <n>]         raster resolution for exact mode (default 200)
              [--max-pages <n>]   stop after n pages
              [--report <file>]   write the loss report here
                                  (default <out.docx>.report.txt)
              [--quiet]

          docx-to-pdf <in.docx> --out <out.pdf>
              [--report <file>]   write the loss report here
                                  (default <out.pdf>.report.txt)
              [--quiet]

          pptx-to-pdf <in.pptx> --out <out.pdf>
              [--report <file>]   write the loss report here
                                  (default <out.pdf>.report.txt)
              [--quiet]
        """.trimIndent()
    )
}
