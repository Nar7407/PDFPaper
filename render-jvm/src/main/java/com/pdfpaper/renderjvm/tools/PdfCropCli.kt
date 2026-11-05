package com.pdfpaper.renderjvm.tools

import com.pdfpaper.engine.crop.CropRequest
import com.pdfpaper.engine.crop.CropSpec
import com.pdfpaper.engine.crop.MarginPreset
import com.pdfpaper.engine.crop.PdfCrop
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.mark.PageSelection
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.renderjvm.JvmImageDecoder
import com.pdfpaper.renderjvm.JvmPageRasterizer
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

/**
 * `crop <in.pdf> --margins 10,10,10,10 --pages all --out <out.pdf>`
 *
 * The CLI is the JVM host for T46: it supplies the `java.awt` rasterizer that
 * auto-crop needs and the ImageIO decoder the destructive "remove hidden
 * content" pass uses. All PDF work stays in `:engine`.
 *
 * Exit codes: 0 success, 1 an input/output or rewrite error, 2 usage error.
 */
fun main(args: Array<String>) {
    exitProcess(runCrop(args))
}

private val USAGE = """Usage:
  crop <in.pdf> (--margins L,B,R,T | --preset ID | --boxes "1:L,B,R,T;2:..." | --auto)
                --out <out.pdf> [--pages all|odd|even|1,3-5]
                [--remove-hidden] [--tolerance N] [--dpi N] [--same-size]

Margins and boxes are in PDF points, bottom-left origin (ISO 32000-1 §14.11.2).
--margins trims each selected page's current CropBox; the MediaBox is kept.
--remove-hidden rewrites the content outside the new box through the T28
redaction path, so it is really gone (a normal crop only hides it).
--auto detects white margins from a rendered page; --same-size grows the
proposals to one common size. --boxes uses one-based page numbers:
  --boxes "1:10,10,10,10;3:20,20,20,20"
Presets: ${MarginPreset.PRESETS.joinToString(", ") { "${it.id} (${it.label})" }}
"""

private val VALUE_OPTIONS = setOf("margins", "preset", "boxes", "out", "pages", "tolerance", "dpi")
private val FLAG_OPTIONS = setOf("auto", "same-size", "remove-hidden", "help")

internal fun runCrop(rawArgs: Array<String>): Int {
    val args = if (rawArgs.isNotEmpty() && rawArgs[0] == "crop") rawArgs.drop(1).toTypedArray() else rawArgs
    if (args.isEmpty()) {
        System.err.println(USAGE)
        return 2
    }
    return try {
        crop(args)
    } catch (error: CropUsageError) {
        System.err.println("Error: ${error.message}")
        System.err.println(USAGE)
        2
    } catch (error: Exception) {
        System.err.println("Error: ${error.message ?: error.javaClass.simpleName}")
        1
    }
}

private fun crop(args: Array<String>): Int {
    val parsed = parse(args)
    if (parsed.flags.contains("help")) {
        println(USAGE)
        return 0
    }
    val input = parsed.positionals.singleOrNull()
        ?: throw CropUsageError("crop needs exactly one input PDF")
    val output = parsed.values["out"] ?: throw CropUsageError("crop needs --out <out.pdf>")
    if (!File(input).isFile) throw CropUsageError("no such PDF: $input")

    val selection = PageSelection.parse(parsed.values["pages"] ?: "all")
    if (selection is PageSelection.Custom && selection.pages.isEmpty()) {
        throw CropUsageError("--pages did not name any page")
    }

    val auto = parsed.flags.contains("auto")
    val marginsText = parsed.values["margins"]
    val presetText = parsed.values["preset"]
    val boxesText = parsed.values["boxes"]
    val operationCount = listOf(auto, marginsText != null, presetText != null, boxesText != null)
        .count { it }
    if (operationCount != 1) {
        throw CropUsageError("choose exactly one of --margins, --preset, --boxes or --auto")
    }

    val document = PdfDocument.open(FileSource(File(input))).getOrElse {
        System.err.println("Error: cannot open '$input': ${it.message}")
        return 1
    }
    val removeHidden = parsed.flags.contains("remove-hidden")
    val outcome = document.use { open ->
        try {
            when {
                auto -> {
                    val tolerance = parsed.values["tolerance"]?.let {
                        it.toIntOrNull() ?: throw CropUsageError("--tolerance needs a number")
                    } ?: PdfCrop.DEFAULT_TOLERANCE
                    val dpi = parsed.values["dpi"]?.let {
                        it.toIntOrNull() ?: throw CropUsageError("--dpi needs a number")
                    } ?: PdfCrop.DEFAULT_AUTO_DPI
                    val detected = PdfCrop.auto(
                        document = open,
                        rasterizer = JvmPageRasterizer(),
                        tolerance = tolerance,
                        dpi = dpi,
                        pages = selection,
                        sameSize = parsed.flags.contains("same-size")
                    ).getOrElse { throw it }
                    detected.warnings.forEach { System.err.println("Note: $it") }
                    if (detected.boxes.isEmpty()) {
                        System.err.println("Error: no page had any content to crop to.")
                        return@use null
                    }
                    PdfCrop.apply(
                        open,
                        CropRequest(CropSpec.PerPage(detected.boxes), selection),
                        removeHiddenContent = removeHidden,
                        decoder = JvmImageDecoder
                    ).getOrElse { throw it }
                }

                marginsText != null -> {
                    val margins = parseMargins(marginsText)
                    PdfCrop.apply(
                        open,
                        CropRequest(CropSpec.Margins(margins[0], margins[1], margins[2], margins[3]), selection),
                        removeHiddenContent = removeHidden,
                        decoder = JvmImageDecoder
                    ).getOrElse { throw it }
                }

                presetText != null -> {
                    val preset = MarginPreset.PRESETS.firstOrNull {
                        it.id.equals(presetText, ignoreCase = true) ||
                            it.label.equals(presetText, ignoreCase = true)
                    } ?: throw CropUsageError(
                        "unknown preset '$presetText' (use ${MarginPreset.PRESETS.joinToString(", ") { it.id }})"
                    )
                    val margins = preset.margins
                    PdfCrop.apply(
                        open,
                        CropRequest(CropSpec.Margins(margins[0], margins[1], margins[2], margins[3]), selection),
                        removeHiddenContent = removeHidden,
                        decoder = JvmImageDecoder
                    ).getOrElse { throw it }
                }

                else -> {
                    val boxes = parseBoxes(boxesText!!)
                    PdfCrop.apply(
                        open,
                        CropRequest(CropSpec.PerPage(boxes), selection),
                        removeHiddenContent = removeHidden,
                        decoder = JvmImageDecoder
                    ).getOrElse { throw it }
                }
            }
        } catch (error: IllegalArgumentException) {
            throw error
        }
    } ?: return 1

    val outputFile = File(output)
    outputFile.absoluteFile.parentFile?.mkdirs()
    outputFile.writeBytes(outcome.bytes)

    println("Cropped $input -> $output")
    // Read the media boxes once, not once per page: a large document should not
    // be reopened for every summary line.
    val mediaBoxes = openMediaBoxes(File(output))
    for ((index, box) in outcome.cropBoxes.toSortedMap()) {
        val media = mediaBoxes[index] ?: LETTER_BOX
        println("  page ${index + 1}: CropBox ${formatBox(box)}  MediaBox ${formatBox(media)} (unchanged)")
    }
    if (removeHidden) {
        if (outcome.hiddenContentRemoved) {
            println("  hidden content outside the CropBox was removed")
        } else {
            System.err.println("Warning: some hidden content could not be removed safely.")
            outcome.unredacted.forEach { System.err.println("  not removed: $it") }
        }
    } else {
        println("  A normal crop only hides the content outside the CropBox; use --remove-hidden to delete it.")
    }
    for (warning in outcome.warnings.distinct()) {
        if (!warning.contains("hidden content")) System.err.println("Note: $warning")
    }
    return if (removeHidden && !outcome.hiddenContentRemoved) 1 else 0
}

/** Reads every page's media box again for the printed summary; empty on failure. */
private fun openMediaBoxes(file: File): Map<Int, PdfRect> = try {
    PdfDocument.open(FileSource(file)).getOrNull()?.use { reopened ->
        reopened.pages().mapIndexedNotNull { index, page ->
            PdfRect.of(page.mediaBox)?.let { index to it }
        }.toMap()
    } ?: emptyMap()
} catch (_: Throwable) {
    emptyMap()
}

private val LETTER_BOX = PdfRect(0.0, 0.0, 612.0, 792.0)

private fun parseMargins(text: String): DoubleArray {
    val values = text.split(',').map { it.trim() }
    if (values.size != 4) {
        throw CropUsageError("--margins needs four numbers: left,bottom,right,top")
    }
    val parsed = DoubleArray(4) { i ->
        values[i].toDoubleOrNull() ?: throw CropUsageError("not a number: '${values[i]}'")
    }
    if (parsed.any { !it.isFinite() }) {
        throw CropUsageError("the margins must be finite numbers")
    }
    if (parsed.any { it < 0.0 }) {
        throw CropUsageError("the margins cannot be negative")
    }
    return parsed
}

/** Parses `"1:10,10,10,10;3:20,20,20,20"` into zero-based page → box. */
private fun parseBoxes(text: String): Map<Int, PdfRect> {
    val out = LinkedHashMap<Int, PdfRect>()
    for (raw in text.split(';')) {
        val piece = raw.trim()
        if (piece.isEmpty()) continue
        val colon = piece.indexOf(':')
        if (colon <= 0) throw CropUsageError("use page:left,bottom,right,top in --boxes ('$piece')")
        val page = piece.substring(0, colon).trim().toIntOrNull()
            ?: throw CropUsageError("not a page number: '${piece.substring(0, colon)}'")
        if (page < 1) throw CropUsageError("page numbers start at 1: '$page'")
        out[page - 1] = parseBox(piece.substring(colon + 1))
    }
    if (out.isEmpty()) throw CropUsageError("--boxes did not name any page")
    return out
}

/** Parses the absolute `left,bottom,right,top` box of one `--boxes` entry. */
private fun parseBox(text: String): PdfRect {
    val values = text.split(',').map { it.trim() }
    if (values.size != 4) {
        throw CropUsageError("a box needs four numbers: left,bottom,right,top")
    }
    val parsed = DoubleArray(4) { i ->
        values[i].toDoubleOrNull() ?: throw CropUsageError("not a number: '${values[i]}'")
    }
    if (parsed.any { !it.isFinite() }) {
        throw CropUsageError("a box must contain finite coordinates")
    }
    return PdfRect(parsed[0], parsed[1], parsed[2], parsed[3])
}

private fun formatBox(rect: PdfRect): String = String.format(
    Locale.US,
    "[%.1f %.1f %.1f %.1f]",
    rect.left,
    rect.bottom,
    rect.right,
    rect.top
)

private class CropParsed(
    val positionals: List<String>,
    val values: Map<String, String>,
    val flags: Set<String>
)

private fun parse(args: Array<String>): CropParsed {
    val positionals = ArrayList<String>()
    val values = HashMap<String, String>()
    val flags = HashSet<String>()
    var index = 0
    while (index < args.size) {
        val token = args[index]
        if (!token.startsWith("--") || token == "--") {
            positionals += token
            index++
            continue
        }
        val body = token.substring(2)
        val equals = body.indexOf('=')
        val name = if (equals >= 0) body.substring(0, equals) else body
        when {
            name in FLAG_OPTIONS -> {
                if (equals >= 0) throw CropUsageError("--$name does not take a value")
                flags += name
                index++
            }
            name in VALUE_OPTIONS -> {
                val value = if (equals >= 0) {
                    body.substring(equals + 1)
                } else {
                    val next = args.getOrNull(index + 1)
                        ?: throw CropUsageError("--$name needs a value")
                    index++
                    next
                }
                values[name] = value
                index++
            }
            else -> throw CropUsageError("unknown option --$name")
        }
    }
    return CropParsed(positionals, values, flags)
}

private class CropUsageError(message: String) : Exception(message)
