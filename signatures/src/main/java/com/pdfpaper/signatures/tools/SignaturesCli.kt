package com.pdfpaper.signatures.tools

import com.pdfpaper.signatures.PdfSignatures
import com.pdfpaper.signatures.SignatureReport
import com.pdfpaper.signatures.SignatureState
import com.pdfpaper.signatures.TrustStores
import java.io.File
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

/**
 * `signatures verify <file.pdf> [--truststore <cert-or-keystore>] [--password <pw>]`
 *
 * Exit codes: 0 when every signature is INTACT_AND_TRUSTED (or the PDF has no
 * signature fields); 1 when any signature is not trusted; 2 for usage/IO errors.
 */
fun main(args: Array<String>) {
    if (args.size < 3 || args[0] != "signatures" || args[1] != "verify") usage()

    val file = File(args[2])
    var trustStoreFile: File? = null
    var password: CharArray? = null
    var index = 3
    while (index < args.size) {
        when (args[index]) {
            "--truststore" -> {
                if (index + 1 >= args.size) usage()
                trustStoreFile = File(args[index + 1])
                index += 2
            }
            "--password" -> {
                if (index + 1 >= args.size) usage()
                password = args[index + 1].toCharArray()
                index += 2
            }
            else -> usage()
        }
    }

    if (!file.isFile) {
        System.err.println("error: no such file: $file")
        exitProcess(2)
    }
    if (trustStoreFile != null && !trustStoreFile.isFile) {
        System.err.println("error: no such trust store: $trustStoreFile")
        exitProcess(2)
    }

    val trustStore = try {
        if (trustStoreFile != null) {
            TrustStores.load(trustStoreFile, password ?: "changeit".toCharArray())
        } else {
            TrustStores.system()
        }
    } catch (error: Exception) {
        System.err.println("error: cannot read trust store: ${error.message}")
        exitProcess(2)
    }

    val verification = PdfSignatures.verify(file, trustStore).getOrElse { error ->
        System.err.println("error: could not verify ${file.name}: ${error.message}")
        exitProcess(2)
    }

    if (verification.signatures.isEmpty()) {
        println("No signature fields found in ${file.name}.")
        return
    }

    println("${file.name}: ${verification.signatures.size} signature field(s), ${verification.fileLength} bytes")
    verification.signatures.forEachIndexed { position, report ->
        println()
        printReport(position + 1, report)
    }
    println()
    println("Result: ${verification.state?.name}")

    if (!verification.allTrusted) exitProcess(1)
}

private fun printReport(index: Int, report: SignatureReport) {
    println("Signature $index: ${report.fieldName}")
    println("  State:         ${report.state.name}")
    report.subFilter?.let { println("  SubFilter:     $it") }
    report.filter?.let { println("  Filter:        $it") }

    val signer = report.signer
    if (signer != null) {
        println("  Signer:        ${signer.subject}")
        println("  Issuer:        ${signer.issuer}")
        println("  Serial:        ${signer.serialNumber}")
        println("  Certificate:   ${TIME_FORMAT.format(signer.notBefore)} to ${TIME_FORMAT.format(signer.notAfter)}")
    } else {
        println("  Signer:        unknown (certificate not available)")
    }
    report.declaredName?.let { println("  /Name:         $it") }
    report.signingTime?.let { println("  Signed:        ${TIME_FORMAT.format(it)}") }
        ?: report.signingTimeText?.let { println("  /M:            $it") }
    report.reason?.let { println("  Reason:        $it") }
    report.location?.let { println("  Location:      $it") }

    val digest = report.digestAlgorithm ?: "unknown"
    val digestResult = when (report.digestMatches) {
        true -> "matches"
        false -> "DOES NOT MATCH"
        null -> "not embedded (covered by the CMS signature)"
    }
    println("  Digest:        $digest, $digestResult")
    println("  CMS signature: ${when (report.cmsSignatureValid) {
        true -> "valid"
        false -> "INVALID"
        null -> "not checked"
    }}")
    println("  Chain:         ${report.chainStatus.name}" + (report.trustAnchor?.let { " (anchor: $it)" } ?: ""))
    println("  Revocation:    ${report.revocation.name}" + (report.revocationNote?.let { " ($it)" } ?: ""))
    val ranges = if (report.ranges.isEmpty()) {
        "not read"
    } else {
        report.ranges.joinToString(", ") { "${it.offset}..${it.end}" }
    }
    println("  ByteRange:     $ranges of ${report.fileLength} bytes")
    println("  Whole file:    ${if (report.coversWholeFile) "yes" else "no"}")

    if (report.updatesAfterSigning.isNotEmpty()) {
        println("  Updates after signing: ${report.updatesAfterSigning.size}")
        for (update in report.updatesAfterSigning) {
            val eofs = if (update.eofOffsets.isEmpty()) {
                "no %%EOF marker"
            } else {
                "%%EOF at ${update.eofOffsets.joinToString(", ")}"
            }
            println("    ${update.offset}..${update.offset + update.length} (${update.length} bytes, $eofs)")
        }
    }

    if (report.problems.isNotEmpty()) {
        println("  Problems:")
        for (problem in report.problems) println("    - $problem")
    }
}

private fun usage(): Nothing {
    System.err.println(
        "Usage: signatures verify <file.pdf> [--truststore <pem-or-keystore>] [--password <password>]"
    )
    exitProcess(2)
}
