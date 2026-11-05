package com.pdfpaper.engine.tools

import com.pdfpaper.engine.edit.AddImageEdit
import com.pdfpaper.engine.edit.AddTextEdit
import com.pdfpaper.engine.edit.DeleteImageEdit
import com.pdfpaper.engine.edit.DeleteTextEdit
import com.pdfpaper.engine.edit.EditFont
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.edit.EditList
import com.pdfpaper.engine.edit.MoveImageEdit
import com.pdfpaper.engine.edit.MoveTextEdit
import com.pdfpaper.engine.edit.PageEdit
import com.pdfpaper.engine.edit.PdfEditOps
import com.pdfpaper.engine.edit.ReplaceImageEdit
import com.pdfpaper.engine.edit.ReplaceTextEdit
import com.pdfpaper.engine.edit.StandardFont
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import java.io.File

/**
 * Text/image editing CLI (task T17).
 *
 * Usage:
 *   cli add-text    <file.pdf> <page> <x> <y> <size> --text <s> [--font <name>] [--color r,g,b] --out <out.pdf>
 *   cli add-image   <file.pdf> <page> <x> <y> <w> <h> --image <image> --out <out.pdf>
 *   cli list-images <file.pdf> <page>
 *   cli move-image  <file.pdf> <page> <id> <dx> <dy> --out <out.pdf>
 *   cli replace-image <file.pdf> <page> <id> --image <image> --out <out.pdf>
 *   cli delete-image  <file.pdf> <page> <id> --out <out.pdf>
 *
 * Text runs are listed and edited in place (task T16):
 *   cli list-text-runs <file.pdf> <page>
 *   cli replace-text   <file.pdf> <page> <id> --text <s> --out <out.pdf>
 *   cli delete-text    <file.pdf> <page> <id> --out <out.pdf>
 *   cli move-text      <file.pdf> <page> <id> <dx> <dy> --out <out.pdf>
 *
 * Page numbers are 1-based, matching the pages users see. The font is one of
 * the standard 14 or "fallback" (the embedded TrueType font).
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        usage()
        kotlin.system.exitProcess(2)
    }
    val status = when (val command = args[0]) {
        "add-text" -> addText(args.drop(1))
        "add-image" -> addImage(args.drop(1))
        "list-images" -> listImages(args.drop(1))
        "move-image" -> moveImage(args.drop(1))
        "replace-image" -> replaceImage(args.drop(1))
        "delete-image" -> deleteImage(args.drop(1))
        "list-text-runs" -> listTextRuns(args.drop(1))
        "replace-text" -> replaceText(args.drop(1))
        "delete-text" -> deleteText(args.drop(1))
        "move-text" -> moveText(args.drop(1))
        else -> fail("unknown command '$command'")
    }
    kotlin.system.exitProcess(status)
}

// -- Commands -----------------------------------------------------------------

private fun addText(args: List<String>): Int = withParsed(args) { parsed ->
    val file = parsed.requireFile(0)
    val page = parsed.requirePage(1)
    val x = parsed.requireDouble(2, "x")
    val y = parsed.requireDouble(3, "y")
    val size = parsed.requireDouble(4, "size")
    val text = parsed.option("text") ?: fail("--text is required")
    val font = parseFont(parsed.option("font"))
    val color = parsed.option("color")?.let(::parseColor) ?: PdfColor.BLACK
    val edits = EditList().apply { add(AddTextEdit(page - 1, text, x, y, font, size, color)) }
    save(file, parsed.requireOption("out"), edits)
}

private fun addImage(args: List<String>): Int = withParsed(args) { parsed ->
    val file = parsed.requireFile(0)
    val page = parsed.requirePage(1)
    val x = parsed.requireDouble(2, "x")
    val y = parsed.requireDouble(3, "y")
    val width = parsed.requireDouble(4, "width")
    val height = parsed.requireDouble(5, "height")
    val image = readImage(parsed)
    save(file, parsed.requireOption("out"), EditList().apply { add(AddImageEdit(page - 1, image, x, y, width, height)) })
}

private fun listImages(args: List<String>): Int = withParsed(args) { parsed ->
    val file = parsed.requireFile(0)
    val page = parsed.requirePage(1)
    open(file).use { document ->
        val images = PdfEditOps.listImages(document, page - 1)
        if (images.isEmpty()) println("page $page: no images")
        for (image in images) {
            val b = image.bounds
            println("${image.id}\t${image.width}x${image.height}\t[${b.left},${b.bottom},${b.right},${b.top}]")
        }
    }
    0
}

private fun moveImage(args: List<String>): Int = withParsed(args) { parsed ->
    val file = parsed.requireFile(0)
    val page = parsed.requirePage(1)
    val id = parsed.requirePositional(2, "id")
    val dx = parsed.requireDouble(3, "dx")
    val dy = parsed.requireDouble(4, "dy")
    save(file, parsed.requireOption("out"), EditList().apply { add(MoveImageEdit(page - 1, id, dx, dy)) })
}

private fun replaceImage(args: List<String>): Int = withParsed(args) { parsed ->
    val file = parsed.requireFile(0)
    val page = parsed.requirePage(1)
    val id = parsed.requirePositional(2, "id")
    val image = readImage(parsed)
    save(file, parsed.requireOption("out"), EditList().apply { add(ReplaceImageEdit(page - 1, id, image)) })
}

private fun deleteImage(args: List<String>): Int = withParsed(args) { parsed ->
    val file = parsed.requireFile(0)
    val page = parsed.requirePage(1)
    val id = parsed.requirePositional(2, "id")
    save(file, parsed.requireOption("out"), EditList().apply { add(DeleteImageEdit(page - 1, id)) })
}

private fun listTextRuns(args: List<String>): Int = withParsed(args) { parsed ->
    val file = parsed.requireFile(0)
    val page = parsed.requirePage(1)
    open(file).use { document ->
        val runs = PdfEditOps.listTextRuns(document, page - 1)
        if (runs.isEmpty()) println("page $page: no text runs")
        for (run in runs) {
            val b = run.bounds
            val color = "#%02X%02X%02X".format(
                (run.color.red * 255.0).toInt().coerceIn(0, 255),
                (run.color.green * 255.0).toInt().coerceIn(0, 255),
                (run.color.blue * 255.0).toInt().coerceIn(0, 255)
            )
            val flags = if (run.invisible) "\tinvisible" else ""
            println(
                "${run.id}\t${run.fontName ?: "?"}\t${run.fontSize}\t$color\t" +
                    "[${b.left},${b.bottom},${b.right},${b.top}]\t${run.text}$flags"
            )
        }
    }
    0
}

private fun replaceText(args: List<String>): Int = withParsed(args) { parsed ->
    val file = parsed.requireFile(0)
    val page = parsed.requirePage(1)
    val id = parsed.requireInt(2, "id")
    val text = parsed.option("text") ?: fail("--text is required")
    saveWithReport(file, parsed.requireOption("out"), EditList().apply { add(ReplaceTextEdit(page - 1, id, text)) })
}

private fun deleteText(args: List<String>): Int = withParsed(args) { parsed ->
    val file = parsed.requireFile(0)
    val page = parsed.requirePage(1)
    val id = parsed.requireInt(2, "id")
    saveWithReport(file, parsed.requireOption("out"), EditList().apply { add(DeleteTextEdit(page - 1, id)) })
}

private fun moveText(args: List<String>): Int = withParsed(args) { parsed ->
    val file = parsed.requireFile(0)
    val page = parsed.requirePage(1)
    val id = parsed.requireInt(2, "id")
    val dx = parsed.requireDouble(3, "dx")
    val dy = parsed.requireDouble(4, "dy")
    saveWithReport(file, parsed.requireOption("out"), EditList().apply { add(MoveTextEdit(page - 1, id, dx, dy)) })
}

// -- Shared helpers -----------------------------------------------------------

private fun save(source: File, out: String, edits: EditList): Int {
    val document = open(source)
    document.use {
        val data = PdfEditOps.save(it, edits.edits()).getOrElse { error ->
            System.err.println("Save failed: ${error.message ?: error::class.simpleName}")
            return 1
        }
        File(out).writeBytes(data)
        println("Wrote ${edits.size} edit(s) to ${File(out).absolutePath}")
    }
    return 0
}

/** Same as [save] but prints the text-edit reports (strategy, overflow). */
private fun saveWithReport(source: File, out: String, edits: EditList): Int {
    val document = open(source)
    document.use {
        val result = PdfEditOps.saveWithReport(it, edits.edits()).getOrElse { error ->
            System.err.println("Save failed: ${error.message ?: error::class.simpleName}")
            return 1
        }
        File(out).writeBytes(result.bytes)
        for (report in result.textReports) println(report)
        println("Wrote ${edits.size} edit(s) to ${File(out).absolutePath}")
    }
    return 0
}

private fun open(file: File): PdfDocument =
    PdfDocument.open(FileSource(file)).getOrElse { error ->
        System.err.println("Could not open ${file.absolutePath}: ${error.message ?: error::class.simpleName}")
        kotlin.system.exitProcess(1)
    }

private fun readImage(parsed: Parsed): com.pdfpaper.engine.edit.ImageData {
    val path = parsed.requireOption("image")
    val bytes = File(path).let {
        if (!it.isFile) fail("image file not found: $path")
        it.readBytes()
    }
    return EditImages.decode(bytes).getOrElse { error ->
        fail("could not read image $path: ${error.message ?: error::class.simpleName}")
    }
}

private fun parseFont(name: String?): EditFont {
    if (name == null) return EditFont.Standard(StandardFont.HELVETICA)
    if (name.equals("fallback", true) || name.equals("noto", true)) return EditFont.Fallback
    return StandardFont.byName(name)?.let { EditFont.Standard(it) }
        ?: fail("unknown font '$name' (use a standard font name or 'fallback')")
}

private fun parseColor(value: String): PdfColor {
    val parts = value.split(',').map { it.trim() }
    if (parts.size != 3) fail("--color expects 'r,g,b'")
    val components = parts.map { it.toDoubleOrNull() ?: fail("--color expects numbers") }
    val scaled = components.map { if (it > 1.0) it / 255.0 else it }
    return PdfColor(scaled[0].coerceIn(0.0, 1.0), scaled[1].coerceIn(0.0, 1.0), scaled[2].coerceIn(0.0, 1.0))
}

// -- Argument parsing ---------------------------------------------------------

private class Parsed(
    val positionals: List<String>,
    private val options: Map<String, String>
) {
    fun requirePositional(index: Int, name: String): String =
        positionals.getOrNull(index) ?: fail("missing <$name>")

    fun requireFile(index: Int): File = File(requirePositional(index, "file"))

    fun requirePage(index: Int): Int =
        requirePositional(index, "page").toIntOrNull()?.takeIf { it >= 1 } ?: fail("page must be a positive integer")

    fun requireDouble(index: Int, name: String): Double =
        requirePositional(index, name).toDoubleOrNull() ?: fail("<$name> must be a number")

    fun requireInt(index: Int, name: String): Int =
        requirePositional(index, name).toIntOrNull() ?: fail("<$name> must be an integer")

    fun option(name: String): String? = options[name]

    fun requireOption(name: String): String = options[name] ?: fail("--$name is required")
}

/** Parses `--key value`, `--key=value`, and `--text` (which swallows words up to the next `--`). */
private fun withParsed(args: List<String>, block: (Parsed) -> Int): Int {
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
        } else if (body == "text") {
            val text = StringBuilder()
            i++
            while (i < args.size && !args[i].startsWith("--")) {
                if (text.isNotEmpty()) text.append(' ')
                text.append(args[i])
                i++
            }
            options[body] = text.toString()
        } else {
            val value = args.getOrNull(i + 1) ?: fail("--$body needs a value")
            options[body] = value
            i += 2
        }
    }
    return block(Parsed(positionals, options))
}

private fun usage() {
    System.err.println(
        """
        Usage:
          cli add-text      <file.pdf> <page> <x> <y> <size> --text <s> [--font <name>] [--color r,g,b] --out <out.pdf>
          cli add-image     <file.pdf> <page> <x> <y> <w> <h> --image <image> --out <out.pdf>
          cli list-images   <file.pdf> <page>
          cli move-image    <file.pdf> <page> <id> <dx> <dy> --out <out.pdf>
          cli replace-image <file.pdf> <page> <id> --image <image> --out <out.pdf>
          cli delete-image  <file.pdf> <page> <id> --out <out.pdf>
          cli list-text-runs <file.pdf> <page>
          cli replace-text  <file.pdf> <page> <id> --text <s> --out <out.pdf>
          cli delete-text   <file.pdf> <page> <id> --out <out.pdf>
          cli move-text     <file.pdf> <page> <id> <dx> <dy> --out <out.pdf>
        """.trimIndent()
    )
}

private fun fail(message: String): Nothing {
    System.err.println("Error: $message")
    usage()
    kotlin.system.exitProcess(2)
}
