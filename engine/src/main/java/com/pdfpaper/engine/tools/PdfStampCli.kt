package com.pdfpaper.engine.tools

import com.pdfpaper.engine.annot.AnnotationType
import com.pdfpaper.engine.annot.PdfAnnotation
import com.pdfpaper.engine.annot.PdfComments
import com.pdfpaper.engine.annot.PdfDate
import com.pdfpaper.engine.annot.StampSpec
import com.pdfpaper.engine.annot.annotations
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.stamp.StampCatalogue
import com.pdfpaper.engine.stamp.StampJson
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Stamp CLI (task T23).
 *
 * Usage:
 *   cli stamp list [--custom stamps.json]
 *   cli stamp list <file.pdf> [--custom stamps.json]
 *   cli stamp add <in.pdf> --page N --stamp ID --x X --y Y --size W
 *       [--rotate 0|90|180|270] [--opacity 0..1] [--author a] [--date when]
 *       [--custom stamps.json] --out out.pdf
 *
 * Coordinates are PDF user-space points from the page's bottom-left corner.
 * --x and --y are the bottom-left corner of the placed box; --size is the
 * width of the *unrotated* stamp and the height follows the design's aspect
 * ratio. --rotate turns the stamp counter-clockwise in quarter turns, which
 * exchanges the box's width and height. A dynamic stamp shows "By <author> at
 * <date time>" from --author and --date; a plain date or date time is read as
 * UTC, and without --date the moment of placement is used. --custom loads
 * extra stamps from a JSON list of text or base64 image stamps (see
 * [StampJson]); the file is self-contained, so it can be edited or shared.
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] != "stamp") {
        printUsage()
        kotlin.system.exitProcess(2)
    }
    val status = when (args.getOrNull(1)) {
        "list" -> list(args.drop(2))
        "add" -> add(args.drop(2))
        else -> failUsage("use 'stamp list' or 'stamp add'")
    }
    kotlin.system.exitProcess(status)
}

// -- Commands -----------------------------------------------------------------

private fun list(args: List<String>): Int = withParsed(args) { parsed ->
    val catalogue = catalogue(parsed)
    val file = parsed.positionals.firstOrNull()
    if (file == null) {
        printLibrary(catalogue)
        return@withParsed 0
    }
    val document = open(File(file))
    var count = 0
    for ((pageIndex, page) in document.pages().withIndex()) {
        for (annot in page.annotations()) {
            if (annot.type != AnnotationType.STAMP) continue
            count++
            println(line(annot, pageIndex))
        }
    }
    if (count == 0) println("No stamps") else println("$count stamp(s)")
    0
}

private fun add(args: List<String>): Int = withParsed(args) { parsed ->
    val document = open(parsed.requireFile(0))
    val pageNumber = parsed.requireInt("page")
    val pages = document.pages()
    val pageIndex = pageNumber - 1
    if (pageIndex !in pages.indices) {
        fail("page $pageNumber is out of range (1..${pages.size})")
    }

    val catalogue = catalogue(parsed)
    val id = parsed.requireOption("stamp")
    val design = catalogue.find(id) ?: fail("unknown stamp '$id' (run 'stamp list')")
    val x = finite(parsed.requireOption("x"), "--x")
    val y = finite(parsed.requireOption("y"), "--y")
    val size = finite(parsed.requireOption("size"), "--size")
    if (size <= 0.0) failUsage("--size must be a positive number")
    val rotation = rotationOf(parsed.option("rotate"))
    val opacity = opacityOf(parsed.option("opacity"))
    val author = parsed.option("author")
    val timestamp = parsed.option("date")?.let(::parseWhen)

    val custom = catalogue.custom(design.id)
    val image = if (custom?.isImage == true) {
        val bytes = custom.image ?: fail("stamp '${design.id}' has no image data")
        EditImages.decode(bytes).getOrElse { error ->
            fail("stamp '${design.id}': ${error.message ?: "the image could not be decoded"}")
        }
    } else null

    val out = File(parsed.option("out") ?: "out.pdf")
    val parent = out.absoluteFile.parentFile
    if (parent != null && !parent.isDirectory) failUsage("output directory does not exist: ${parent.path}")
    val spec = StampSpec(pageIndex, design, x, y, size, rotation, opacity, author, timestamp, image)
    val data = PdfComments.edit(document, listOf(spec)).getOrElse { error ->
        fail(error.message ?: error::class.simpleName ?: "save failed")
    }
    out.writeBytes(data)
    println("Wrote stamp '${design.id}' to ${out.absolutePath}")
    0
}

// -- Output -------------------------------------------------------------------

/** Prints the library by group, custom stamps included. */
private fun printLibrary(catalogue: StampCatalogue) {
    for ((group, stamps) in catalogue.groups()) {
        println(group.label)
        if (stamps.isEmpty()) println("  (none)")
        for (design in stamps) println("  ${design.id}\t${design.label}")
    }
}

private fun line(annot: PdfAnnotation, pageIndex: Int): String {
    val rect = annot.rect
        ?.let { "[${number(it.left)},${number(it.bottom)},${number(it.right)},${number(it.top)}]" }
        ?: "-"
    val name = (annot.dict["Name"] as? PdfName)?.name ?: "-"
    return "#${annot.ref?.objNum ?: 0}\tpage ${pageIndex + 1}\t$rect\t$name\t" +
        "${annot.title ?: "-"}\t${date(annot)}\t${annot.contents.orEmpty()}"
}

private fun date(annot: PdfAnnotation): String {
    val raw = annot.creationDate ?: annot.modifiedDate ?: return "-"
    val millis = PdfDate.millis(raw) ?: return raw
    return DATE_FORMAT.format(Instant.ofEpochMilli(millis))
}

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

private val SPACE_DATE_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]")

private fun number(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else com.pdfpaper.engine.write.PdfSerializer.formatReal(value)

// -- Input --------------------------------------------------------------------

private fun open(file: File): PdfDocument =
    PdfDocument.open(FileSource(file)).getOrElse { error ->
        System.err.println("Could not open ${file.absolutePath}: ${error.message ?: error::class.simpleName}")
        kotlin.system.exitProcess(1)
    }

private fun catalogue(parsed: StampArguments): StampCatalogue {
    val path = parsed.option("custom") ?: return StampCatalogue()
    val file = File(path)
    if (!file.isFile) fail("custom stamp list not found: ${file.absolutePath}")
    val stamps = StampJson.load(file).getOrElse { error ->
        fail("could not read ${file.absolutePath}: ${error.message ?: "invalid stamp list"}")
    }
    return StampCatalogue(stamps)
}

/** A number that may not be NaN or infinite. */
private fun finite(raw: String, what: String): Double {
    val value = raw.toDoubleOrNull() ?: failUsage("$what must be a number")
    if (!value.isFinite()) failUsage("$what must be a finite number")
    return value
}

private fun rotationOf(raw: String?): Int {
    if (raw == null) return 0
    val value = raw.toIntOrNull() ?: failUsage("--rotate must be a multiple of 90 (0, 90, 180, 270)")
    val normalised = ((value % 360) + 360) % 360
    if (normalised % 90 != 0) failUsage("--rotate must be a multiple of 90 (0, 90, 180, 270)")
    return normalised
}

private fun opacityOf(raw: String?): Double {
    if (raw == null) return 1.0
    val value = raw.toDoubleOrNull() ?: failUsage("--opacity must be a number between 0 and 1")
    if (!value.isFinite() || value < 0.0 || value > 1.0) failUsage("--opacity must be between 0 and 1")
    return value
}

/** "yyyy-MM-dd", "yyyy-MM-dd HH:mm[:ss]" (UTC) or an ISO-8601 timestamp. */
private fun parseWhen(raw: String): Long {
    val value = raw.trim()
    runCatching { return Instant.parse(value).toEpochMilli() }
    runCatching { return OffsetDateTime.parse(value).toInstant().toEpochMilli() }
    runCatching { return LocalDateTime.parse(value, SPACE_DATE_TIME).toInstant(ZoneOffset.UTC).toEpochMilli() }
    runCatching { return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli() }
    runCatching { return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    failUsage("--date must be yyyy-MM-dd, yyyy-MM-dd HH:mm or an ISO-8601 timestamp")
}

// -- Argument parsing ---------------------------------------------------------

private class StampArguments(
    val positionals: List<String>,
    private val options: Map<String, List<String>>
) {
    fun requireFile(index: Int): File = File(requirePositional(index, "file"))

    fun requirePositional(index: Int, name: String): String =
        positionals.getOrNull(index) ?: failUsage("missing <$name>")

    fun option(name: String): String? = options[name]?.lastOrNull()

    fun requireOption(name: String): String = option(name) ?: failUsage("--$name is required")

    fun requireInt(name: String): Int =
        requireOption(name).toIntOrNull() ?: failUsage("--$name must be an integer")
}

/** Parses `--key value` and `--key=value`; every option takes a value. */
private fun withParsed(args: List<String>, block: (StampArguments) -> Int): Int {
    val positionals = ArrayList<String>()
    val options = LinkedHashMap<String, MutableList<String>>()
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
            options.getOrPut(body.substring(0, equals)) { ArrayList() } += body.substring(equals + 1)
            i++
        } else {
            val value = args.getOrNull(i + 1) ?: failUsage("--$body needs a value")
            options.getOrPut(body) { ArrayList() } += value
            i += 2
        }
    }
    return block(StampArguments(positionals, options))
}

private fun printUsage() {
    System.err.println(
        """
        Usage:
          cli stamp list [--custom stamps.json]
          cli stamp list <file.pdf> [--custom stamps.json]
          cli stamp add <in.pdf> --page N --stamp ID --x X --y Y --size W
              [--rotate 0|90|180|270] [--opacity 0..1] [--author a] [--date when]
              [--custom stamps.json] --out out.pdf

        'stamp list' without a file lists the built-in library (Dynamic, Sign
        here, Standard business) plus any custom stamps from --custom. With a
        file it lists the stamps already in the document. Coordinates are PDF
        points from the bottom-left of the page; --size is the unrotated width
        and --rotate turns the stamp counter-clockwise, exchanging width and
        height. Dynamic stamps add "By <author> at <date time>"; a plain --date
        is UTC, and without it the current time is used.
        """.trimIndent()
    )
}

private fun failUsage(message: String): Nothing {
    System.err.println("Error: $message")
    printUsage()
    kotlin.system.exitProcess(2)
}

private fun fail(message: String): Nothing {
    System.err.println("Error: $message")
    kotlin.system.exitProcess(1)
}
