package com.pdfpaper.renderjvm.tools

import com.pdfpaper.engine.compress.CompressOptions
import com.pdfpaper.engine.compress.CompressPreset
import com.pdfpaper.engine.compress.CompressReport
import com.pdfpaper.engine.compress.PdfCompressor
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.renderjvm.JvmImageDecoder
import com.pdfpaper.renderjvm.JvmImageEncoder
import java.io.File
import kotlin.system.exitProcess

/**
 * `compress <in.pdf> --preset <light|balanced|strong> --out <out.pdf> [options]`
 *
 * The CLI is the JVM host for the engine's compressor: it supplies the JPEG
 * codec (`javax.imageio`) the engine needs to re-encode images, then prints
 * what the run changed.
 *
 * Exit codes: 0 success, 1 error, 2 usage error (also used when the document
 * carries a digital signature and `--force` was not given).
 */
fun main(args: Array<String>) {
    exitProcess(run(args))
}

private const val USAGE = "Usage: compress <in.pdf> --preset <light|balanced|strong> --out <out.pdf> " +
    "[--dpi <n>] [--quality <n>] [--grayscale] [--strip-metadata] [--strip-thumbnails] " +
    "[--no-object-streams] [--no-xref-streams]"

private val VALUE_OPTIONS = setOf("preset", "dpi", "quality", "out")

private val FLAG_OPTIONS = setOf(
    "grayscale",
    "strip-metadata",
    "strip-thumbnails",
    "no-object-streams",
    "no-xref-streams",
    "force",
    "allow-signatures"
)

/**
 * The whole command, returning the exit code. [main] only turns it into a
 * process exit, so the tests can call this directly.
 */
internal fun run(rawArgs: Array<String>): Int {
    // Both entry points work: `cli compress ...` (the wrapper passes the word
    // "compress" through) and `--args="compress ..."`.
    val args = if (rawArgs.isNotEmpty() && rawArgs[0] == "compress") {
        rawArgs.drop(1).toTypedArray()
    } else {
        rawArgs
    }
    if (args.isEmpty()) {
        System.err.println(USAGE)
        return 2
    }
    return try {
        compress(args)
    } catch (error: UsageError) {
        System.err.println("Error: ${error.message}")
        System.err.println(USAGE)
        2
    } catch (error: Exception) {
        System.err.println("Error: ${error.message ?: error.javaClass.simpleName}")
        1
    }
}

private fun compress(args: Array<String>): Int {
    val parsed = parse(args)
    if (parsed.help) {
        println(USAGE)
        return 0
    }
    val input = parsed.positionals.singleOrNull()
        ?: throw UsageError("compress needs exactly one input PDF")
    val output = parsed.values["out"] ?: throw UsageError("compress needs --out <out.pdf>")

    val presetId = parsed.values["preset"]?.lowercase() ?: CompressPreset.BALANCED.id
    val preset = CompressPreset.entries.firstOrNull { it.id == presetId }
        ?: throw UsageError(
            "unknown preset '$presetId' (use ${CompressPreset.entries.joinToString(", ") { it.id }})"
        )
    val presetOptions = preset.options()
    val dpi = parsed.values["dpi"]?.let {
        it.toIntOrNull() ?: throw UsageError("--dpi needs a number")
    }
    val quality = parsed.values["quality"]?.let {
        it.toIntOrNull() ?: throw UsageError("--quality needs a number")
    }

    val options = try {
        CompressOptions(
            targetImageDpi = dpi ?: presetOptions.targetImageDpi,
            jpegQuality = quality ?: presetOptions.jpegQuality,
            grayscale = parsed.flags.contains("grayscale") || presetOptions.grayscale,
            stripMetadata = parsed.flags.contains("strip-metadata"),
            stripThumbnails = parsed.flags.contains("strip-thumbnails"),
            useObjectStreams = !parsed.flags.contains("no-object-streams"),
            useXrefStreams = !parsed.flags.contains("no-xref-streams")
        )
    } catch (error: IllegalArgumentException) {
        throw UsageError(error.message ?: "bad options")
    }

    val inputFile = File(input)
    if (!inputFile.isFile) throw UsageError("no such PDF: $input")

    val document = PdfDocument.open(FileSource(inputFile)).getOrElse {
        System.err.println("Error: cannot open '$input': ${it.message}")
        return 1
    }
    val allowSignatures = parsed.flags.contains("force") || parsed.flags.contains("allow-signatures")
    // The rewrite always drops the old revision, so a signature cannot stay
    // valid. The check comes before the work: there is no point in compressing
    // a file the user is not allowed to write.
    if (PdfCompressor.detectSignatures(document) && !allowSignatures) {
        warnAboutSignatures()
        document.close()
        return 2
    }
    val result = document.use { open ->
        val originalBytes = inputFile.readBytes()
        PdfCompressor.compress(
            document = open,
            originalBytes = originalBytes,
            options = options,
            decoder = JvmImageDecoder,
            encoder = JvmImageEncoder
        ).getOrElse {
            System.err.println("Error: compression failed: ${it.message}")
            return 1
        }
    }

    if (result.hasSignatures && !allowSignatures) {
        warnAboutSignatures()
        return 2
    }

    val outputFile = File(output)
    outputFile.absoluteFile.parentFile?.mkdirs()
    outputFile.writeBytes(result.bytes)

    printReport(result.report, input, output)
    return 0
}

/** Warns that a signed document cannot keep its signatures through a rewrite. */
private fun warnAboutSignatures() {
    System.err.println(
        "Warning: this PDF carries a digital signature. The signatures do not survive the rewrite."
    )
    System.err.println("Re-run with --force (or --allow-signatures) to compress it anyway.")
}

/** Prints what the run changed, in the order the report lists it. */
private fun printReport(report: CompressReport, input: String, output: String) {
    println("Compressed $input -> $output")
    println(
        "  size: ${report.originalBytes} bytes -> ${report.compressedBytes} bytes " +
            "(saved ${report.savedBytes} bytes, ${report.savedPercent}%)"
    )
    if (report.usedOriginal) {
        println("Not smaller: kept the original file.")
    }
    for (category in report.categories) {
        println("  ${category.label}: saved ${category.savedBytes} bytes")
    }
    println("  images re-encoded: ${report.imagesRecompressed} (${report.imagesDownsampled} downsampled)")
    println("  streams repacked: ${report.streamsRecompressed}")
    println("  duplicate streams shared: ${report.duplicatesShared}")
    println("  unused objects removed: ${report.unusedObjectsRemoved}")
    for (line in report.skipped) {
        println("  skipped: $line")
    }
}

/** The command line, split into positionals, `--name value` options and flags. */
private class Parsed(
    val positionals: List<String>,
    val values: Map<String, String>,
    val flags: Set<String>,
    val help: Boolean
)

private fun parse(args: Array<String>): Parsed {
    val positionals = ArrayList<String>()
    val values = HashMap<String, String>()
    val flags = HashSet<String>()
    var help = false
    var index = 0
    while (index < args.size) {
        val token = args[index]
        if (token == "--help" || token == "-h") {
            help = true
            index++
            continue
        }
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
                if (equals >= 0) throw UsageError("--$name does not take a value")
                flags += name
                index++
            }
            name in VALUE_OPTIONS -> {
                val value = if (equals >= 0) {
                    body.substring(equals + 1)
                } else {
                    val next = args.getOrNull(index + 1)
                        ?: throw UsageError("--$name needs a value")
                    index++
                    next
                }
                values[name] = value
                index++
            }
            else -> throw UsageError("unknown option --$name")
        }
    }
    return Parsed(positionals, values, flags, help)
}

private class UsageError(message: String) : Exception(message)
