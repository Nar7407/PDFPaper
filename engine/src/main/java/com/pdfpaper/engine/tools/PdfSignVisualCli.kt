package com.pdfpaper.engine.tools

import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.edit.StandardFont
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.sign.SignatureArtwork
import com.pdfpaper.engine.sign.SignatureCaption
import com.pdfpaper.engine.sign.SignaturePlacement
import com.pdfpaper.engine.sign.SignaturePoint
import com.pdfpaper.engine.sign.SignatureStroke
import com.pdfpaper.engine.sign.VisualSignatureOptions
import com.pdfpaper.engine.sign.VisualSignatures
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Visual signature CLI (task T26).
 *
 * Usage:
 *   cli sign-visual <in.pdf> --page N --image sig.png --x X --y Y --width W
 *       --out out.pdf [--transparent] [--rotate 0|90|180|270]
 *       [--name text] [--date text] [--author text] [--flatten]
 *   cli sign-visual <in.pdf> --page N --typed "Alice" [--font Helvetica-Oblique] ...
 *   cli sign-visual <in.pdf> --page N --strokes "x,y[,p] x,y[,p]; ..." ...
 *
 * Coordinates are PDF user-space points from the page's bottom-left corner.
 * --x/--y are the bottom-left of the placed box and --width is the unrotated
 * width; the height follows the source's aspect ratio and caption lines.
 *
 * This writes a *visual* signature only: no certificate, hash or CMS data is
 * created. Output always reports visualOnly=true.
 */
fun main(args: Array<String>) {
    val tokens = if (args.firstOrNull() == "sign-visual") args.drop(1).toList() else args.toList()
    exitProcess(run(tokens))
}

private fun run(tokens: List<String>): Int {
    if (tokens.isEmpty()) {
        System.err.println(USAGE)
        return 2
    }
    return try {
        val args = SignVisualArguments.parse(tokens)
        if (args.flag("help")) {
            println(USAGE)
            return 0
        }
        val input = args.positionals.joinToString(" ").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("an input PDF is required")
        val inputFile = File(input)
        if (!inputFile.isFile) throw IllegalArgumentException("no such PDF: $input")

        val page = args.requiredInt("page")
        if (page < 1) throw IllegalArgumentException("--page must be 1 or greater")
        val x = args.requiredDouble("x")
        val y = args.requiredDouble("y")
        val width = args.requiredDouble("width")
        if (width <= 0.0) throw IllegalArgumentException("--width must be positive")
        val rotation = args.value("rotate")?.let {
            it.toIntOrNull() ?: throw IllegalArgumentException("--rotate must be 0, 90, 180 or 270")
        } ?: 0
        if (rotation % 90 != 0) throw IllegalArgumentException("--rotate must be 0, 90, 180 or 270")
        val out = args.value("out") ?: "out.pdf"
        val transparent = args.flag("transparent")
        val flatten = args.flag("flatten")

        val imagePath = args.value("image")
        val typed = args.value("typed")
        val strokes = args.value("strokes")
        val sources = listOfNotNull(imagePath, typed, strokes)
        if (sources.size != 1) {
            throw IllegalArgumentException("choose exactly one of --image, --typed or --strokes")
        }
        if (transparent && imagePath == null) {
            throw IllegalArgumentException("--transparent is only valid with --image")
        }
        val artwork = when {
            imagePath != null -> {
                val bytes = File(imagePath).readBytes()
                val image = EditImages.decode(bytes).getOrElse {
                    throw IllegalArgumentException("cannot read image '$imagePath': ${it.message}")
                }
                SignatureArtwork.Image(image, transparentBackground = transparent)
            }
            typed != null -> {
                val font = args.value("font")?.let {
                    StandardFont.byName(it) ?: throw IllegalArgumentException("unknown font '$it'")
                } ?: StandardFont.HELVETICA_OBLIQUE
                if (!font.winAnsi) throw IllegalArgumentException("${font.baseFont} has no Latin text encoding")
                SignatureArtwork.Typed(typed, font)
            }
            else -> SignatureArtwork.Drawn(
                strokes = parseStrokes(assertNotNull(strokes)),
                width = args.value("stroke-width")?.toDoubleOrNull() ?: 2.0
            )
        }

        val options = VisualSignatureOptions(
            caption = SignatureCaption(args.value("name"), args.value("date")),
            author = args.value("author"),
            flatten = flatten,
            id = args.value("id")
        )
        val document = PdfDocument.open(FileSource(inputFile)).getOrElse {
            throw IllegalArgumentException("cannot open '$input': ${it.message}")
        }
        val result = document.use {
            VisualSignatures.place(
                document = it,
                artwork = artwork,
                placement = SignaturePlacement(page - 1, x, y, width, rotation),
                options = options,
                decoder = EditImages.defaultDecoder()
            ).getOrElse { error -> throw IllegalArgumentException(error.message ?: "could not place signature") }
        }
        val output = File(out)
        output.parentFile?.mkdirs()
        output.writeBytes(result.bytes)

        val placement = result.rect?.let {
            "at x=${decimal(it.left)}, y=${decimal(it.bottom)}, " +
                "w=${decimal(it.width)}, h=${decimal(it.height)}"
        } ?: "flattened into page content"
        println("Wrote ${output.absolutePath} (page $page, id=${result.signatureId}, $placement).")
        if (result.flattened) println("The signature is flattened and can no longer be edited as an annotation.")
        println("visualOnly=true - ${VisualSignatures.NOTE}")
        0
    } catch (error: IllegalArgumentException) {
        System.err.println("sign-visual: ${error.message}")
        2
    } catch (error: Exception) {
        System.err.println("sign-visual: ${error.message ?: error.javaClass.simpleName}")
        1
    }
}

private fun assertNotNull(value: String?): String =
    value ?: throw IllegalArgumentException("--strokes is required")

private fun parseStrokes(value: String): List<SignatureStroke> {
    val strokes = ArrayList<SignatureStroke>()
    for (strokeText in value.split(';')) {
        val trimmed = strokeText.trim()
        if (trimmed.isEmpty()) continue
        val points = ArrayList<SignaturePoint>()
        for (token in trimmed.split(Regex("\\s+"))) {
            if (token.isBlank()) continue
            val parts = token.split(',')
            if (parts.size !in 2..3) {
                throw IllegalArgumentException("stroke point '$token' must be x,y or x,y,pressure")
            }
            val x = parts[0].trim().toDoubleOrNull()
                ?: throw IllegalArgumentException("bad x coordinate in '$token'")
            val y = parts[1].trim().toDoubleOrNull()
                ?: throw IllegalArgumentException("bad y coordinate in '$token'")
            val pressure = if (parts.size == 3) {
                parts[2].trim().toDoubleOrNull()
                    ?: throw IllegalArgumentException("bad pressure in '$token'")
            } else {
                1.0
            }
            if (!x.isFinite() || !y.isFinite() || !pressure.isFinite()) {
                throw IllegalArgumentException("stroke point '$token' must be finite")
            }
            points += SignaturePoint(x, y, pressure)
        }
        if (points.isEmpty()) throw IllegalArgumentException("a stroke needs at least one point")
        strokes += SignatureStroke(points)
    }
    if (strokes.isEmpty()) throw IllegalArgumentException("--strokes needs at least one stroke")
    return strokes
}

private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

private class SignVisualArguments private constructor(
    val positionals: List<String>,
    private val options: Map<String, String>,
    private val flags: Set<String>
) {

    fun value(name: String): String? = options[name]

    fun flag(name: String): Boolean = name in flags

    fun requiredInt(name: String): Int =
        value(name)?.toIntOrNull() ?: throw IllegalArgumentException("--$name is required")

    fun requiredDouble(name: String): Double =
        value(name)?.toDoubleOrNull() ?: throw IllegalArgumentException("--$name is required")

    companion object {
        private val VALUE_OPTIONS = setOf(
            "page", "x", "y", "width", "rotate", "image", "typed", "strokes", "font",
            "stroke-width", "name", "date", "author", "out", "id"
        )
        private val FLAG_OPTIONS = setOf("transparent", "flatten", "help")

        /**
         * Gradle's `--args` splits the wrapper's command line on whitespace and
         * drops the quotes around `--typed "Alice Smith"` or `--strokes "0,0
         * 10,10"`. Re-join the fragments until the next option so the wrappers
         * accept the same text as a direct invocation.
         */
        private val JOINED_OPTIONS = setOf("image", "typed", "strokes", "name", "date", "author", "out", "id")

        fun parse(tokens: List<String>): SignVisualArguments {
            val positionals = ArrayList<String>()
            val options = LinkedHashMap<String, String>()
            val flags = LinkedHashSet<String>()
            var index = 0
            while (index < tokens.size) {
                val token = tokens[index]
                if (!token.startsWith("--")) {
                    positionals += token
                    index++
                    continue
                }
                val body = token.removePrefix("--")
                if (body.isEmpty()) throw IllegalArgumentException("empty option")
                if ('=' in body) {
                    val name = body.substringBefore('=')
                    val value = body.substringAfter('=')
                    if (name !in VALUE_OPTIONS) throw IllegalArgumentException("unknown option --$name")
                    options[name] = value
                    index++
                    continue
                }
                if (body in FLAG_OPTIONS) {
                    flags += body
                    index++
                    continue
                }
                if (body !in VALUE_OPTIONS) throw IllegalArgumentException("unknown option --$body")
                if (body in JOINED_OPTIONS) {
                    val parts = ArrayList<String>()
                    var next = index + 1
                    while (next < tokens.size && !tokens[next].startsWith("--")) {
                        parts += tokens[next]
                        next++
                    }
                    if (parts.isEmpty()) throw IllegalArgumentException("--$body needs a value")
                    options[body] = parts.joinToString(" ")
                    index = next
                } else {
                    if (index + 1 >= tokens.size) throw IllegalArgumentException("--$body needs a value")
                    options[body] = tokens[index + 1]
                    index += 2
                }
            }
            return SignVisualArguments(positionals, options, flags)
        }
    }
}

private val USAGE = """
Usage:
  cli sign-visual <in.pdf> --page N --image sig.png --x X --y Y --width W --out out.pdf
      [--transparent] [--rotate 0|90|180|270] [--name text] [--date text]
      [--author text] [--flatten] [--id text]
  cli sign-visual <in.pdf> --page N --typed "Alice" [--font Helvetica-Oblique] ...
  cli sign-visual <in.pdf> --page N --strokes "x,y[,p] x,y[,p]; ..." [--stroke-width 2] ...

Coordinates are PDF points from the page's bottom-left. --width is the unrotated
width; the height follows the artwork and caption. --transparent removes
near-white pixels from an image scan. --flatten writes the appearance directly
into page content instead of keeping an editable annotation.

This places a VISUAL signature only (visualOnly=true); it is not a
cryptographic signature. Use "cli signatures verify" to inspect digital ones.
""".trimIndent()
