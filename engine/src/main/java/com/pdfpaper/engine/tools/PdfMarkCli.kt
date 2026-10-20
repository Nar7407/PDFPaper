package com.pdfpaper.engine.tools

import com.pdfpaper.engine.edit.StandardFont
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.mark.BatesOptions
import com.pdfpaper.engine.mark.BatesPlacement
import com.pdfpaper.engine.mark.HeaderFooterOptions
import com.pdfpaper.engine.mark.MarkAlign
import com.pdfpaper.engine.mark.MarkFont
import com.pdfpaper.engine.mark.NumberFormat
import com.pdfpaper.engine.mark.PageSelection
import com.pdfpaper.engine.mark.PdfMarks
import com.pdfpaper.engine.mark.WatermarkContent
import com.pdfpaper.engine.mark.WatermarkOptions
import com.pdfpaper.engine.mark.WatermarkPosition
import com.pdfpaper.engine.model.PdfDocument
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

/**
 * Command line access to the PDFPaper marks (task T44): watermarks, headers,
 * footers with page numbers, and Bates numbers.
 *
 * ```
 * mark watermark in.pdf --text "CONFIDENTIAL" --out out.pdf
 * mark number-pages in.pdf --footer "Page {page} of {pages}" --out out.pdf
 * mark bates in.pdf --prefix ABC- --digits 6 --out out.pdf
 * ```
 *
 * The original file is never changed: the marks go into a new file.
 */
fun main(args: Array<String>) {
    exitProcess(run(args))
}

/** The work of the command line tool, so tests can call it and get the code. */
internal fun run(args: Array<String>): Int {
    if (args.isEmpty() || args[0] == "--help" || args[0] == "-h") {
        usage()
        return if (args.isEmpty()) 2 else 0
    }
    // The tool is called as "mark <command> <file>", but the wrapper may pass
    // the leading "mark" as well.
    val tokens = if (args[0] == "mark") args.drop(1) else args.toList()
    val command = tokens.firstOrNull() ?: run {
        usage()
        return 2
    }
    val rest = tokens.drop(1)
    return try {
        val parsed = MarkCliArgs.parse(
            rest,
            valueOptions = setOf(
                "out", "text", "text-file", "image", "font", "font-file", "size", "color", "opacity", "rotate",
                "position", "pages", "header", "footer", "format", "margin-top", "margin-bottom",
                "margin-left", "margin-right", "start", "prefix", "digits", "suffix", "placement"
            ),
            flagOptions = setOf("behind", "over", "skip-first")
        )
        val input = parsed.requirePositional(0, "input PDF")
        val output = parsed.requireOption("out")
        val source = File(input)
        if (!source.isFile) throw MarkUsageError("There is no file at $input")
        val document = PdfDocument.open(FileSource(source)).getOrElse { error ->
            throw MarkUsageError("The PDF could not be read: ${error.message}")
        }
        document.use { open ->
            val bytes = when (command) {
                "watermark" -> watermark(open, parsed, source)
                "number-pages" -> numberPages(open, parsed, source)
                "bates" -> bates(open, parsed)
                "remove" -> PdfMarks.removeAdded(open)
                    .getOrElse { error ->
                        throw MarkUsageError("The added marks could not be removed: ${error.message}")
                    }
                else -> throw MarkUsageError("Unknown command '$command'")
            }
            val target = File(output)
            target.parentFile?.mkdirs()
            writeAtomically(target, bytes)
            println("Wrote $output (${bytes.size} bytes)")
        }
        0
    } catch (e: MarkUsageError) {
        System.err.println("Error: ${e.message}")
        usage()
        2
    } catch (e: Throwable) {
        System.err.println("Error: ${e.message}")
        1
    }
}

private fun watermark(
    document: PdfDocument,
    parsed: MarkCliArgs,
    source: File
): ByteArray {
    val imageFile = parsed.option("image")
    val textFile = parsed.option("text-file")
    val text = when {
        textFile != null -> {
            val file = File(textFile)
            if (!file.isFile) throw MarkUsageError("There is no text file at $textFile")
            // Read as UTF-8: the command line cannot carry every script.
            file.readText(Charsets.UTF_8).trim()
        }
        else -> parsed.option("text")
    }
    if (imageFile == null && text.isNullOrBlank()) {
        throw MarkUsageError("A watermark needs --text or --image")
    }
    val content = if (imageFile != null) {
        val file = File(imageFile)
        if (!file.isFile) throw MarkUsageError("There is no picture at $imageFile")
        WatermarkContent.Image(file.readBytes())
    } else {
        WatermarkContent.Text(text.orEmpty())
    }
    val options = WatermarkOptions(
        content = content,
        font = fontOption(parsed),
        fontSize = parsed.option("size")?.toDoubleOrNull() ?: 48.0,
        color = colorOption(parsed),
        opacity = parsed.option("opacity")?.toDoubleOrNull() ?: 0.3,
        rotationDegrees = parsed.option("rotate")?.toDoubleOrNull() ?: 45.0,
        position = positionOption(parsed),
        behindContent = !parsed.flag("over"),
        pages = pagesOption(parsed)
    )
    return PdfMarks.applyWatermark(document, options)
        .getOrElse { error -> throw MarkUsageError("The watermark could not be added: ${error.message}") }
}

private fun numberPages(
    document: PdfDocument,
    parsed: MarkCliArgs,
    source: File
): ByteArray {
    val header = parsed.option("header")
    val footer = parsed.option("footer")
    if (header == null && footer == null) {
        throw MarkUsageError("A header or a footer is needed (--header or --footer)")
    }
    val options = HeaderFooterOptions(
        header = header,
        footer = footer,
        format = formatOption(parsed),
        font = fontOption(parsed),
        fontSize = parsed.option("size")?.toDoubleOrNull() ?: 10.0,
        color = colorOption(parsed),
        align = MarkAlign.CENTER,
        marginTop = parsed.option("margin-top")?.toDoubleOrNull() ?: 36.0,
        marginBottom = parsed.option("margin-bottom")?.toDoubleOrNull() ?: 36.0,
        marginLeft = parsed.option("margin-left")?.toDoubleOrNull() ?: 54.0,
        marginRight = parsed.option("margin-right")?.toDoubleOrNull() ?: 54.0,
        startNumber = parsed.option("start")?.toIntOrNull() ?: 1,
        skipFirstPage = parsed.flag("skip-first"),
        pages = pagesOption(parsed)
    )
    return PdfMarks.applyHeaderFooter(document, options, source.name)
        .getOrElse { error -> throw MarkUsageError("The header and footer could not be added: ${error.message}") }
}

private fun bates(document: PdfDocument, parsed: MarkCliArgs): ByteArray {
    val options = BatesOptions(
        prefix = parsed.option("prefix").orEmpty(),
        digits = parsed.option("digits")?.toIntOrNull() ?: 6,
        startNumber = parsed.option("start")?.toIntOrNull() ?: 1,
        suffix = parsed.option("suffix").orEmpty(),
        placement = placementOption(parsed),
        font = fontOption(parsed),
        fontSize = parsed.option("size")?.toDoubleOrNull() ?: 9.0,
        color = colorOption(parsed),
        pages = pagesOption(parsed)
    )
    return PdfMarks.applyBates(document, options)
        .getOrElse { error -> throw MarkUsageError("The Bates numbers could not be added: ${error.message}") }
}

private fun fontOption(parsed: MarkCliArgs): MarkFont {
    val file = parsed.option("font-file")
    if (file != null) {
        val font = File(file)
        if (!font.isFile) throw MarkUsageError("There is no font at $file")
        val name = font.nameWithoutExtension.replace(Regex("[^A-Za-z0-9]"), "")
        return MarkFont.Embedded(font.readBytes(), name.ifEmpty { "PDFPaperEmbedded" })
    }
    val name = parsed.option("font") ?: return MarkFont.Standard(StandardFont.HELVETICA)
    val standard = StandardFont.byName(name)
        ?: throw MarkUsageError("There is no standard font called '$name'")
    return MarkFont.Standard(standard)
}

private fun colorOption(parsed: MarkCliArgs): PdfColor {
    val text = parsed.option("color") ?: return PdfColor(0.75, 0.75, 0.75)
    val parts = text.split(",").map { it.trim().toDoubleOrNull() }
    if (parts.size != 3 || parts.any { it == null }) {
        throw MarkUsageError("A color is written as r,g,b with numbers from 0 to 1")
    }
    return PdfColor(parts[0]!!, parts[1]!!, parts[2]!!)
}

private fun positionOption(parsed: MarkCliArgs): WatermarkPosition {
    val name = parsed.option("position") ?: return WatermarkPosition.CENTER
    return WatermarkPosition.entries.firstOrNull { it.name.equals(name.replace('-', '_'), ignoreCase = true) }
        ?: throw MarkUsageError("There is no position called '$name'")
}

private fun placementOption(parsed: MarkCliArgs): BatesPlacement {
    val name = parsed.option("placement") ?: return BatesPlacement.BOTTOM_RIGHT
    return BatesPlacement.entries.firstOrNull { it.name.equals(name.replace('-', '_'), ignoreCase = true) }
        ?: throw MarkUsageError("There is no placement called '$name'")
}

private fun formatOption(parsed: MarkCliArgs): NumberFormat {
    val name = parsed.option("format") ?: return NumberFormat.DECIMAL
    return when (name.trim().lowercase()) {
        "1", "decimal" -> NumberFormat.DECIMAL
        "i", "roman", "lower-roman" -> NumberFormat.LOWER_ROMAN
        "I", "upper-roman" -> NumberFormat.UPPER_ROMAN
        "page-of", "page of n" -> NumberFormat.PAGE_OF_TOTAL
        else -> throw MarkUsageError("There is no number format called '$name'")
    }
}

private fun pagesOption(parsed: MarkCliArgs): PageSelection {
    val value = parsed.option("pages") ?: return PageSelection.All
    return PageSelection.parse(value)
}

/** Writes the file in one go, so a failure never leaves half a PDF behind. */
private fun writeAtomically(target: File, bytes: ByteArray) {
    val temp = File(target.parentFile, "${target.name}.tmp")
    temp.writeBytes(bytes)
    try {
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (e: Throwable) {
        temp.copyTo(target, overwrite = true)
        temp.delete()
    }
}

private fun usage() {
    System.err.println(
        """
        Usage:
          mark watermark in.pdf --text "CONFIDENTIAL" --out out.pdf [options]
          mark watermark in.pdf --image logo.png --out out.pdf [options]
          mark number-pages in.pdf --footer "Page {page} of {pages}" --out out.pdf [options]
          mark bates in.pdf --prefix ABC- --digits 6 --out out.pdf [options]
          mark remove in.pdf --out out.pdf

        Watermark options:
          --text <text>          the watermark text
          --text-file <file>     read the watermark text from a UTF-8 file
          --image <file>         use a picture instead of text
          --font <name>          Helvetica, Times-Roman, Courier, ...
          --font-file <file>     embed a TrueType font (for other scripts)
          --size <points>        text size (default 48)
          --color <r,g,b>        cover colour, 0 to 1 (default 0.75,0.75,0.75)
          --opacity <0-1>        how solid the watermark is (default 0.3)
          --rotate <degrees>     rotation (default 45)
          --position <where>     center, tiled, top-left, top-right, bottom-left, bottom-right
          --over                 draw the watermark over the content (default: behind)

        Page number options:
          --header <text>        the header line
          --footer <text>        the footer line
          --format <format>     1, i, I or "Page 1 of N"
          --size <points>        text size (default 10)
          --margin-top <points>  distance from the top edge (default 36)
          --margin-bottom <p>    distance from the bottom edge (default 36)
          --margin-left <points> distance from the left edge (default 54)
          --margin-right <p>     distance from the right edge (default 54)
          --start <number>       the number of the first page (default 1)
          --skip-first           leave the first page without a header or footer

        Bates options:
          --prefix <text>        text in front of the number
          --suffix <text>        text after the number
          --digits <count>       how many digits the number has (default 6)
          --start <number>       the number of the first page (default 1)
          --placement <where>    top-left, top-center, top-right, bottom-left, ...

        Shared options:
          --pages <range>        all, odd, even or 1,3-5 (one-based)
          --out <file>           where to write the new PDF (required)

        A header or footer may use the tokens {page}, {pages}, {date},
        {filename} and {title}.
        """.trimIndent()
    )
}

/** A problem with the command line, not with the PDF. */
private class MarkUsageError(message: String) : IllegalArgumentException(message)

/**
 * The command line arguments: `--name value`, `--name=value` and flags.
 */
private class MarkCliArgs private constructor(
    private val positionals: List<String>,
    private val options: Map<String, MutableList<String>>,
    private val flags: Set<String>
) {

    fun option(name: String): String? = options[name]?.lastOrNull()

    fun flag(name: String): Boolean = name in flags

    fun requirePositional(index: Int, what: String): String =
        positionals.getOrNull(index) ?: throw MarkUsageError("The $what is missing")

    fun requireOption(name: String): String =
        option(name) ?: throw MarkUsageError("--$name is missing")

    companion object {
        fun parse(
            tokens: List<String>,
            valueOptions: Set<String>,
            flagOptions: Set<String>
        ): MarkCliArgs {
            val positionals = ArrayList<String>()
            val options = HashMap<String, MutableList<String>>()
            val flags = HashSet<String>()
            var index = 0
            while (index < tokens.size) {
                val token = tokens[index]
                if (!token.startsWith("--")) {
                    positionals += token
                    index++
                    continue
                }
                val body = token.removePrefix("--")
                val equals = body.indexOf('=')
                if (equals >= 0) {
                    val name = body.substring(0, equals)
                    val value = body.substring(equals + 1)
                    if (flagOptions.contains(name)) {
                        flags += name
                    } else if (valueOptions.contains(name)) {
                        options.getOrPut(name) { ArrayList() } += value
                    } else {
                        throw MarkUsageError("Unknown option --$name")
                    }
                    index++
                    continue
                }
                if (flagOptions.contains(body)) {
                    flags += body
                    index++
                    continue
                }
                if (valueOptions.contains(body)) {
                    val value = tokens.getOrNull(index + 1)
                        ?: throw MarkUsageError("--$body needs a value")
                    options.getOrPut(body) { ArrayList() } += value
                    index += 2
                    continue
                }
                throw MarkUsageError("Unknown option --$body")
            }
            return MarkCliArgs(positionals, options, flags)
        }
    }
}
