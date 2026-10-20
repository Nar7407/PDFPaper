package com.pdfpaper.renderjvm.tools

import com.pdfpaper.engine.export.ExportedPage
import com.pdfpaper.engine.export.ImageExtractOptions
import com.pdfpaper.engine.export.ImageFormat
import com.pdfpaper.engine.export.PageExportOptions
import com.pdfpaper.engine.export.PageRange
import com.pdfpaper.engine.export.PdfImageExtractor
import com.pdfpaper.engine.export.PdfPageExport
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.renderjvm.JvmImageDecoder
import com.pdfpaper.renderjvm.JvmImageEncoder
import com.pdfpaper.renderjvm.JvmPageRasterizer
import java.io.File
import kotlin.system.exitProcess

/**
 * Export pages as images and pull embedded images out of a PDF (task T45).
 *
 * Usage:
 *   cli export-images <in.pdf> --out-dir <dir> [--dpi 150] [--pages 1-5]
 *       [--format png|jpg|webp] [--quality 90] [--max-pixels n] [--force]
 *       [--password pw]
 *   cli extract-images <in.pdf> --out-dir <dir> [--min-size 32]
 *       [--keep-duplicates] [--list] [--force] [--password pw]
 *
 * Files are named `<document>-p003.png` and `<document>-img012.jpg`. An
 * existing file is never overwritten unless `--force` is passed.
 *
 * Exit codes: 0 success, 1 error (I/O, no such file, refusal), 2 usage error.
 * A refused page (over the pixel cap) is exit 1 with the engine's message.
 */
fun main(args: Array<String>) {
    exitProcess(runExport(args))
}

private const val USAGE = """Usage:
  export-images <in.pdf> --out-dir <dir> [--dpi 150] [--pages 1-5]
      [--format png|jpg|webp] [--quality 90] [--max-pixels n] [--force] [--password pw]
  extract-images <in.pdf> --out-dir <dir> [--min-size 32]
      [--keep-duplicates] [--list] [--force] [--password pw]"""

private val VALUE_OPTIONS = setOf("out-dir", "dpi", "pages", "format", "quality", "max-pixels", "password", "min-size")

private val FLAG_OPTIONS = setOf("force", "keep-duplicates", "list")

internal fun runExport(rawArgs: Array<String>): Int {
    val args = rawArgs.toList()
    if (args.isEmpty()) {
        System.err.println(USAGE)
        return 2
    }
    return try {
        when (args[0]) {
            "export-images" -> exportImages(args.drop(1))
            "extract-images" -> extractImages(args.drop(1))
            else -> {
                System.err.println(USAGE)
                2
            }
        }
    } catch (error: ExportUsageError) {
        System.err.println("Error: ${error.message}")
        System.err.println(USAGE)
        2
    } catch (error: Exception) {
        System.err.println("Error: ${error.message ?: error.javaClass.simpleName}")
        1
    }
}

private class ExportUsageError(message: String) : Exception(message)

private class ExportArgs(
    val positionals: List<String>,
    val values: Map<String, String>,
    val flags: Set<String>
)

private fun parseExportArgs(args: List<String>): ExportArgs {
    val positionals = ArrayList<String>()
    val values = LinkedHashMap<String, String>()
    val flags = LinkedHashSet<String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        if (arg.startsWith("--")) {
            val name = arg.substring(2)
            if (name in FLAG_OPTIONS) {
                flags += name
                i++
                continue
            }
            if (name !in VALUE_OPTIONS) throw ExportUsageError("unknown option --$name")
            val value = args.getOrNull(i + 1) ?: throw ExportUsageError("--$name needs a value")
            values[name] = value
            i += 2
            continue
        }
        positionals += arg
        i++
    }
    return ExportArgs(positionals, values, flags)
}

private fun openDocument(parsed: ExportArgs): PdfDocument {
    val input = parsed.positionals.singleOrNull() ?: throw ExportUsageError("expected exactly one input PDF")
    val file = File(input)
    if (!file.isFile) throw IllegalStateException("no such file: ${file.absolutePath}")
    return PdfDocument.open(FileSource(file), parsed.values["password"] ?: "")
        .getOrElse { throw IllegalStateException("cannot open $input: ${it.message ?: it.javaClass.simpleName}") }
}

private fun outDir(parsed: ExportArgs): File {
    val dir = File(parsed.values["out-dir"] ?: "out")
    if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("cannot create ${dir.absolutePath}")
    if (!dir.isDirectory) throw IllegalStateException("${dir.absolutePath} is not a directory")
    return dir
}

private fun formatOf(text: String?): ImageFormat = when (text?.lowercase()) {
    null, "png" -> ImageFormat.PNG
    "jpg", "jpeg" -> ImageFormat.JPEG
    "webp" -> ImageFormat.WEBP
    else -> throw ExportUsageError("unknown format \"$text\" (png, jpg or webp)")
}

private fun exportIntOption(parsed: ExportArgs, name: String, default: Int): Int =
    parsed.values[name]?.toIntOrNull() ?: if (parsed.values.containsKey(name)) {
        throw ExportUsageError("--$name needs a number")
    } else {
        default
    }

private fun write(target: File, bytes: ByteArray, force: Boolean) {
    if (target.exists() && !force) {
        throw IllegalStateException("${target.path} already exists (pass --force to overwrite)")
    }
    target.writeBytes(bytes)
}

private fun exportImages(args: List<String>): Int {
    val parsed = parseExportArgs(args)
    val format = formatOf(parsed.values["format"])
    val dpi = exportIntOption(parsed, "dpi", 150)
    val pages = PageRange.parse(parsed.values["pages"] ?: "all")
        .getOrElse { throw ExportUsageError(it.message ?: "bad --pages") }
    val maxPixels = parsed.values["max-pixels"]?.let {
        it.toLongOrNull() ?: throw ExportUsageError("--max-pixels needs a number")
    } ?: PageExportOptions.DEFAULT_MAX_PIXELS
    val quality = exportIntOption(parsed, "quality", 90)
    val force = "force" in parsed.flags
    val directory = outDir(parsed)
    val document = openDocument(parsed)
    document.use {
        val name = (parsed.positionals.singleOrNull() ?: "document").substringAfterLast('/').substringAfterLast('\\')
        val options = PageExportOptions(
            documentName = name,
            dpi = dpi,
            format = format,
            pages = pages,
            quality = quality,
            maxPixels = maxPixels
        )
        var totalBytes = 0L
        val result = PdfPageExport.export(
            document,
            options,
            JvmPageRasterizer(),
            JvmImageEncoder,
            sink = { page: ExportedPage ->
                val target = File(directory, page.fileName)
                write(target, page.bytes, force)
                totalBytes += page.bytes.size
                println(
                    "page ${page.pageNumber}: ${page.pixelWidth} x ${page.pixelHeight} " +
                        "${page.format.label} ${page.bytes.size / 1024} KB -> ${target.path}"
                )
            }
        ).getOrElse { throw IllegalStateException(it.message ?: "export failed") }
        if (result.cancelled) println("Stopped after ${result.exported} page(s)")
        for (warning in result.warnings) System.err.println("warning: $warning")
        println("Exported ${result.exported} page(s) at $dpi dpi to ${directory.absolutePath} (${totalBytes / 1024} KB)")
    }
    return 0
}

private fun extractImages(args: List<String>): Int {
    val parsed = parseExportArgs(args)
    val force = "force" in parsed.flags
    val listOnly = "list" in parsed.flags
    val minSize = exportIntOption(parsed, "min-size", 0)
    val directory = if (listOnly) null else outDir(parsed)
    val document = openDocument(parsed)
    document.use {
        val name = (parsed.positionals.singleOrNull() ?: "document").substringAfterLast('/').substringAfterLast('\\')
        val options = ImageExtractOptions(
            documentName = name,
            minSize = minSize,
            skipDuplicates = "keep-duplicates" !in parsed.flags
        )
        val result = PdfImageExtractor.extract(document, options, JvmImageDecoder)
            .getOrElse { throw IllegalStateException(it.message ?: "extraction failed") }
        if (listOnly) {
            println("page  obj     width x height  bpc  colour space     filter                    bytes")
            for (info in result.listed) {
                println(
                    "${info.pageIndex + 1}".padEnd(5) +
                        "${info.objectNumber}".padEnd(8) +
                        "${info.width} x ${info.height}".padEnd(17) +
                        "${info.bitsPerComponent}".padEnd(5) +
                        info.colorSpace.padEnd(18) +
                        info.filter.padEnd(26) +
                        info.byteSize
                )
            }
            println("${result.listed.size} image XObject(s)")
            return 0
        }
        for (image in result.images) {
            val bytes = image.bytes
            val fileName = image.fileName
            if (bytes == null || fileName == null) {
                println("page ${image.pageIndex + 1} ${image.width}x${image.height} ${image.filter}: skipped (${image.skipped})")
                continue
            }
            val target = File(directory!!, fileName)
            write(target, bytes, force)
            println("page ${image.pageIndex + 1}: ${image.width} x ${image.height} ${image.format} -> ${target.path}")
            for (note in image.notes.distinct()) System.err.println("  note: $note")
        }
        for (warning in result.warnings) System.err.println("warning: $warning")
        println("Extracted ${result.exported} image(s), skipped ${result.skipped} -> ${directory!!.absolutePath}")
    }
    return 0
}
