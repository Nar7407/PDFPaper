package com.pdfpaper.engine.tools

import com.pdfpaper.engine.create.ImageFit
import com.pdfpaper.engine.create.ImagePdfOptions
import com.pdfpaper.engine.create.PageOrientation
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.create.PdfFromImages
import com.pdfpaper.engine.create.PdfFromText
import com.pdfpaper.engine.create.PdfMetadata
import com.pdfpaper.engine.create.TextPdfOptions
import com.pdfpaper.engine.edit.StandardFont
import java.io.File

/**
 * PDF creation CLI (task T21).
 *
 * Usage:
 *   cli create blank --size A4 [--orientation portrait|landscape] [--out out.pdf]
 *   cli images-to-pdf <image>... --out out.pdf [--fit fit|fill|original] [--margin pt]
 *   cli text-to-pdf <in.txt> --out out.pdf [--margin pt] [--font name] [--font-size pt]
 *
 * `--size` accepts A4, Letter or WIDTHxHEIGHT in points. A shell wildcard
 * (`images-to-pdf *.jpg`) is expanded here, so it also works from cmd.exe and
 * PowerShell, which do not expand it for a batch file. Paths are resolved from
 * the current directory (the Gradle task runs in the repo root).
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        kotlin.system.exitProcess(2)
    }
    val status = when (args[0]) {
        "create" -> create(args.drop(1))
        "images-to-pdf" -> imagesToPdf(args.drop(1))
        "text-to-pdf" -> textToPdf(args.drop(1))
        else -> failUsage("unknown command '${args[0]}'")
    }
    kotlin.system.exitProcess(status)
}

// -- Commands -----------------------------------------------------------------

private fun create(args: List<String>): Int = withArguments(args) { parsed ->
    val what = parsed.positionals.firstOrNull() ?: failUsage("missing 'blank'")
    if (what != "blank") failUsage("create expects 'blank'")
    val size = sizeOf(parsed)
    val orientation = orientationOf(parsed)
    val out = outFile(parsed)
    val builder = PdfBuilder(size, orientation, metadataOf(parsed))
    builder.addPage()
    out.writeBytes(builder.build())
    println("Wrote 1 blank ${size.oriented(orientation)} page to ${out.absolutePath}")
    0
}

private fun imagesToPdf(args: List<String>): Int = withArguments(args) { parsed ->
    if (parsed.positionals.isEmpty()) failUsage("no images were given")
    val files = expandGlobs(parsed.positionals)
    val out = outFile(parsed)
    val options = ImagePdfOptions(
        pageSize = sizeOf(parsed),
        orientation = orientationOf(parsed),
        fit = fitOf(parsed),
        margin = marginOf(parsed, default = 0.0),
        metadata = metadataOf(parsed)
    )
    val bytes = PdfFromImages.createFiles(files, options).getOrElse { error ->
        abort("could not create PDF: ${error.message ?: error::class.simpleName}")
    }
    out.writeBytes(bytes)
    println("Wrote ${files.size} page(s) to ${out.absolutePath}")
    0
}

private fun textToPdf(args: List<String>): Int = withArguments(args) { parsed ->
    val input = File(parsed.requirePositional(0, "file"))
    val out = outFile(parsed)
    val options = TextPdfOptions(
        pageSize = sizeOf(parsed),
        orientation = orientationOf(parsed),
        margin = marginOf(parsed, default = 54.0),
        fontSize = doubleOption(parsed, "font-size") ?: 12.0,
        font = fontOf(parsed),
        lineSpacing = doubleOption(parsed, "line-spacing") ?: 1.2,
        metadata = metadataOf(parsed, defaultTitle = input.nameWithoutExtension)
    )
    val bytes = PdfFromText.createFile(input, options).getOrElse { error ->
        abort("could not create PDF: ${error.message ?: error::class.simpleName}")
    }
    out.writeBytes(bytes)
    println("Wrote text from ${input.name} to ${out.absolutePath}")
    0
}

// -- Options ------------------------------------------------------------------

private fun sizeOf(parsed: Arguments): PageSize {
    val value = parsed.option("size") ?: return PageSize.A4
    return PageSize.parse(value)
        ?: failUsage("unknown size '$value' (use A4, Letter or WIDTHxHEIGHT in points)")
}

private fun orientationOf(parsed: Arguments): PageOrientation =
    when (val value = parsed.option("orientation")?.trim()?.lowercase()) {
        null, "portrait" -> PageOrientation.PORTRAIT
        "landscape" -> PageOrientation.LANDSCAPE
        else -> failUsage("unknown orientation '$value' (use portrait or landscape)")
    }

private fun fitOf(parsed: Arguments): ImageFit =
    when (val value = parsed.option("fit")?.trim()?.lowercase()) {
        null, "fit" -> ImageFit.FIT
        "fill" -> ImageFit.FILL
        "original" -> ImageFit.ORIGINAL
        else -> failUsage("unknown fit '$value' (use fit, fill or original)")
    }

private fun marginOf(parsed: Arguments, default: Double): Double {
    val value = parsed.option("margin")?.toDoubleOrNull() ?: return default
    if (!value.isFinite() || value < 0.0) failUsage("--margin must be a non-negative number of points")
    return value
}

private fun doubleOption(parsed: Arguments, name: String): Double? {
    val raw = parsed.option(name) ?: return null
    val value = raw.toDoubleOrNull() ?: failUsage("--$name must be a number")
    if (!value.isFinite() || value <= 0.0) failUsage("--$name must be positive")
    return value
}

private fun fontOf(parsed: Arguments): StandardFont {
    val name = parsed.option("font") ?: return StandardFont.HELVETICA
    val font = StandardFont.byName(name) ?: failUsage("unknown font '$name'")
    if (!font.winAnsi) failUsage("${font.baseFont} is not supported (it does not use WinAnsi encoding)")
    return font
}

private fun metadataOf(parsed: Arguments, defaultTitle: String? = null): PdfMetadata =
    PdfMetadata(
        title = parsed.option("title") ?: defaultTitle,
        author = parsed.option("author"),
        subject = parsed.option("subject"),
        keywords = parsed.option("keywords")
    )

private fun outFile(parsed: Arguments): File {
    val file = File(parsed.option("out") ?: "out.pdf")
    val parent = file.absoluteFile.parentFile
    if (parent != null && !parent.isDirectory) failUsage("output directory does not exist: ${parent.path}")
    return file
}

// -- Wildcards ----------------------------------------------------------------

/** Expands `*` and `?` in [args] against the filesystem; leaves plain paths alone. */
private fun expandGlobs(args: List<String>): List<File> {
    val files = ArrayList<File>()
    for (arg in args) {
        if ('*' !in arg && '?' !in arg) {
            files += File(arg)
            continue
        }
        val path = File(arg)
        val directory = path.parentFile ?: File(".")
        val pattern = globToRegex(path.name)
        val matches = directory.listFiles()
            ?.filter { it.isFile && pattern.matches(it.name) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (matches.isEmpty()) failUsage("no files match '$arg'")
        files += matches
    }
    return files
}

private fun globToRegex(glob: String): Regex {
    val pattern = StringBuilder("^")
    for (character in glob) {
        when (character) {
            '*' -> pattern.append(".*")
            '?' -> pattern.append('.')
            else -> pattern.append(Regex.escape(character.toString()))
        }
    }
    pattern.append('$')
    return Regex(pattern.toString(), RegexOption.IGNORE_CASE)
}

// -- Argument parsing ---------------------------------------------------------

private class Arguments(
    val positionals: List<String>,
    private val options: Map<String, String>
) {
    fun requirePositional(index: Int, name: String): String =
        positionals.getOrNull(index) ?: failUsage("missing <$name>")

    fun option(name: String): String? = options[name]
}

/** Parses `--key value` and `--key=value`; everything else is positional. */
private fun withArguments(args: List<String>, block: (Arguments) -> Int): Int {
    val positionals = ArrayList<String>()
    val options = LinkedHashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val token = args[i]
        if (!token.startsWith("--")) {
            positionals += token
            i++
            continue
        }
        val body = token.removePrefix("--")
        val equals = body.indexOf('=')
        if (equals >= 0) {
            options[body.substring(0, equals)] = body.substring(equals + 1)
            i++
        } else {
            val value = args.getOrNull(i + 1) ?: failUsage("--$body needs a value")
            options[body] = value
            i += 2
        }
    }
    return block(Arguments(positionals, options))
}

private fun printUsage() {
    System.err.println(
        """
        Usage:
          cli create blank --size A4 [--orientation portrait|landscape] [--out out.pdf]
          cli images-to-pdf <image>... --out out.pdf [--size A4] [--fit fit|fill|original] [--margin pt]
          cli text-to-pdf <in.txt> --out out.pdf [--size A4] [--margin pt] [--font name] [--font-size pt]

        Common options:
          --orientation portrait|landscape   default portrait
          --margin <points>                  default 0 for images, 54 for text
          --title/--author/--subject/--keywords <text>
          --size A4|Letter|WIDTHxHEIGHT      default A4
        """.trimIndent()
    )
}

private fun failUsage(message: String): Nothing {
    System.err.println("Error: $message")
    printUsage()
    kotlin.system.exitProcess(2)
}

private fun abort(message: String): Nothing {
    System.err.println("Error: $message")
    kotlin.system.exitProcess(1)
}
