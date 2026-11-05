package com.pdfpaper.signatures.tools

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.signatures.PdfSigner
import com.pdfpaper.signatures.Pkcs12SigningKey
import com.pdfpaper.signatures.SigningOptions
import com.pdfpaper.signatures.SigningSubFilter
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.system.exitProcess

/**
 * `sign <file.pdf> --p12 <key.p12> --pass-env <ENV> [options]`
 *
 * The password is read from an environment variable and is never accepted as a
 * command-line argument. The original file is left untouched; the signed bytes
 * are written to --out (or <name>-signed.pdf).
 */
fun main(args: Array<String>) {
    val tokens = if (args.firstOrNull() == "sign") args.drop(1) else args.toList()
    if (tokens.isEmpty() || tokens[0] == "--help" || tokens[0] == "-h") usage(2)

    val input = File(tokens[0])
    val parsed = try {
        parse(tokens.drop(1))
    } catch (error: IllegalArgumentException) {
        System.err.println("error: ${error.message}")
        usage(2)
    }

    if (!input.isFile) {
        System.err.println("error: no such file: $input")
        exitProcess(2)
    }
    if (!parsed.p12.isFile) {
        System.err.println("error: no such PKCS#12 file: ${parsed.p12}")
        exitProcess(2)
    }
    if (parsed.image != null && !parsed.image.isFile) {
        System.err.println("error: no such image: ${parsed.image}")
        exitProcess(2)
    }
    val output = parsed.out ?: File(input.parentFile ?: File("."), "${input.nameWithoutExtension}-signed.pdf")
    if (output.parentFile?.exists() == false) {
        System.err.println("error: output directory does not exist: ${output.parentFile}")
        exitProcess(2)
    }

    val passwordText = System.getenv(parsed.passwordEnv)
    if (passwordText == null) {
        System.err.println("error: environment variable ${parsed.passwordEnv} is not set")
        exitProcess(2)
    }
    val password = passwordText.toCharArray()
    val date = parsed.date ?: Instant.now()
    val options = SigningOptions(
        fieldName = parsed.fieldName,
        pageIndex = parsed.pageIndex,
        rect = parsed.rect,
        visible = parsed.visible,
        name = parsed.name,
        date = date,
        reason = parsed.reason,
        location = parsed.location,
        contactInfo = parsed.contactInfo,
        image = parsed.image?.readBytes(),
        signingTime = date,
        subFilter = parsed.subFilter,
        reuseEmptyField = parsed.reuseEmptyField
    )

    try {
        val key = Pkcs12SigningKey.load(parsed.p12, password).getOrElse { error ->
            System.err.println("error: cannot read PKCS#12 key: ${error.message ?: error.javaClass.simpleName}")
            exitProcess(2)
        }
        val result = PdfSigner.sign(input.readBytes(), key, options).getOrElse { error ->
            System.err.println("error: could not sign ${input.name}: ${error.message ?: error.javaClass.simpleName}")
            exitProcess(1)
        }
        output.writeBytes(result.bytes)
        println("Signed ${input.name} -> ${output.path}")
        println("  Field:       ${result.fieldName} (${if (result.visible) "visible" else "invisible"})")
        println("  SubFilter:   ${result.subFilter}")
        println("  Signature:   object ${result.signatureObjectId}, ${result.cmsBytes} CMS bytes")
        println("  ByteRange:   ${result.byteRange.joinToString(", ")}")
        if (result.timestamped) println("  Timestamp:   present")
    } finally {
        password.fill('\u0000')
    }
}

private class Parsed(
    val p12: File,
    val passwordEnv: String,
    val out: File?,
    val fieldName: String?,
    val pageIndex: Int,
    val rect: PdfRect?,
    val visible: Boolean?,
    val name: String?,
    val date: Instant?,
    val reason: String?,
    val location: String?,
    val contactInfo: String?,
    val image: File?,
    val subFilter: SigningSubFilter,
    val reuseEmptyField: Boolean
)

private fun parse(args: List<String>): Parsed {
    var p12: File? = null
    var passEnv: String? = null
    var out: File? = null
    var fieldName: String? = null
    var page = 1
    var rect: PdfRect? = null
    var x: Double? = null
    var y: Double? = null
    var width: Double? = null
    var height: Double? = null
    var visible: Boolean? = null
    var name: String? = null
    var date: Instant? = null
    var reason: String? = null
    var location: String? = null
    var contactInfo: String? = null
    var image: File? = null
    var subFilter = SigningSubFilter.CADES_DETACHED
    var reuseEmptyField = true

    var index = 0
    while (index < args.size) {
        val option = args[index]
        fun value(): String {
            if (index + 1 >= args.size) throw IllegalArgumentException("$option needs a value")
            index++
            return args[index]
        }
        when (option) {
            "--p12" -> p12 = File(value())
            "--pass-env" -> passEnv = value()
            "--out" -> out = File(value())
            "--field" -> fieldName = value()
            "--page" -> page = value().toIntOrNull()
                ?: throw IllegalArgumentException("--page expects an integer")
            "--rect" -> {
                val parts = value().split(',')
                if (parts.size != 4) throw IllegalArgumentException("--rect expects l,b,r,t")
                val values = parts.map {
                    it.trim().toDoubleOrNull()
                        ?: throw IllegalArgumentException("--rect expects four numbers")
                }
                rect = PdfRect(values[0], values[1], values[2], values[3])
            }
            "--x" -> x = number(value(), option)
            "--y" -> y = number(value(), option)
            "--width" -> width = number(value(), option)
            "--height" -> height = number(value(), option)
            "--invisible" -> visible = false
            "--visible" -> visible = true
            "--name" -> name = value()
            "--date" -> date = parseDate(value())
            "--reason" -> reason = value()
            "--location" -> location = value()
            "--contact-info", "--contact" -> contactInfo = value()
            "--image" -> image = File(value())
            "--subfilter" -> subFilter = when (val text = value().lowercase()) {
                "cades", "cades-detached", "etsi.cades.detached" -> SigningSubFilter.CADES_DETACHED
                "pkcs7", "pkcs7-detached", "adbe.pkcs7.detached" -> SigningSubFilter.PKCS7_DETACHED
                else -> throw IllegalArgumentException("unknown subfilter: $text")
            }
            "--no-reuse-field" -> reuseEmptyField = false
            "--help", "-h" -> usage(0)
            else -> throw IllegalArgumentException("unknown option: $option")
        }
        index++
    }

    if (page < 1) throw IllegalArgumentException("--page is 1-based")
    if (x != null || y != null || width != null || height != null) {
        if (x == null || y == null || width == null || height == null) {
            throw IllegalArgumentException("--x, --y, --width and --height must be used together")
        }
        rect = PdfRect(x, y, x + width, y + height)
    }
    if (visible == true && rect == null) {
        throw IllegalArgumentException("--visible needs --rect or --x/--y/--width/--height")
    }
    val signatureRect = rect
    if (signatureRect != null && (signatureRect.width <= 0.0 || signatureRect.height <= 0.0)) {
        throw IllegalArgumentException("signature rectangle must have a positive size")
    }
    return Parsed(
        p12 = p12 ?: throw IllegalArgumentException("--p12 is required"),
        passwordEnv = passEnv ?: throw IllegalArgumentException("--pass-env is required"),
        out = out,
        fieldName = fieldName,
        pageIndex = page - 1,
        rect = rect,
        visible = visible,
        name = name,
        date = date,
        reason = reason,
        location = location,
        contactInfo = contactInfo,
        image = image,
        subFilter = subFilter,
        reuseEmptyField = reuseEmptyField
    )
}

private fun number(text: String, option: String): Double =
    text.toDoubleOrNull() ?: throw IllegalArgumentException("$option expects a number")

private fun parseDate(text: String): Instant {
    runCatching { Instant.parse(text) }.getOrNull()?.let { return it }
    runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()?.let { return it }
    runCatching { LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant() }
        .getOrNull()?.let { return it }
    throw IllegalArgumentException("--date expects ISO-8601 (for example 2026-10-20T12:00:00Z)")
}

private fun usage(code: Int): Nothing {
    val out = if (code == 0) System.out else System.err
    out.println(
        "Usage: sign <file.pdf> --p12 <key.p12> --pass-env <ENV> [--out <signed.pdf>] [options]\n" +
            "  --field <name>       reuse an empty field, or create one with this name\n" +
            "  --page <n>           1-based page for a new visible field (default 1)\n" +
            "  --rect <l,b,r,t>     visible signature rectangle in PDF points\n" +
            "  --x/--y/--width/--height <pt>  rectangle from its lower-left corner\n" +
            "  --invisible          force an invisible signature\n" +
            "  --name <text>        signer name shown in the appearance\n" +
            "  --date <ISO-8601>    signing time (defaults to now, UTC)\n" +
            "  --reason <text>      reason shown in the appearance and signature dictionary\n" +
            "  --location <text>    location shown in the appearance and signature dictionary\n" +
            "  --contact-info <s>   contact information in the signature dictionary\n" +
            "  --image <file.png|jpg> optional appearance image\n" +
            "  --subfilter cades|pkcs7   default: cades\n" +
            "  --no-reuse-field     always create a new signature field"
    )
    exitProcess(code)
}
