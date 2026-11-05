package com.pdfpaper.app.data

/**
 * What an incoming document is, decided from its first bytes (task T47).
 *
 * Share targets and VIEW intents are untrusted: a provider may send an image
 * with a PDF MIME type, a text file behind an image MIME type, or a corrupt
 * receiver therefore never decides from a name or a MIME type - these magic
 * numbers decide which screen the user is offered, and the full parser decides
 * afterwards whether the file actually opens.
 */
enum class IncomingKind {
    PDF,
    IMAGE,
    OFFICE,
    LEGACY_OFFICE,
    OTHER
}

/** Pure content sniffing for incoming intents (no Android imports). */
object IncomingKindDetector {

    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val ZIP_LOCAL = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val ZIP_EMPTY = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private val OLE_MAGIC = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
    )
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /**
     * Detects the family represented by [header]. The PDF header may occur
     * anywhere in the first 1024 bytes (ISO 32000-1 7.5.2), and some providers
     * prepend whitespace or a BOM.
     */
    fun detect(header: ByteArray): IncomingKind = when {
        containsPdfHeader(header) -> IncomingKind.PDF
        startsWith(header, ZIP_LOCAL) || startsWith(header, ZIP_EMPTY) -> IncomingKind.OFFICE
        startsWith(header, OLE_MAGIC) -> IncomingKind.LEGACY_OFFICE
        startsWith(header, PNG_MAGIC) -> IncomingKind.IMAGE
        isJpeg(header) || isGif(header) || isWebp(header) || isHeif(header) -> IncomingKind.IMAGE
        else -> IncomingKind.OTHER
    }

    /** True when a PDF header occurs in the sniffed bytes. */
    fun containsPdfHeader(header: ByteArray): Boolean {
        if (header.size < PDF_MAGIC.size) return false
        for (start in 0..header.size - PDF_MAGIC.size) {
            var matches = true
            for (index in PDF_MAGIC.indices) {
                if (header[start + index] != PDF_MAGIC[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        return prefix.indices.all { bytes[it] == prefix[it] }
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xD8 &&
            (bytes[2].toInt() and 0xFF) == 0xFF

    private fun isGif(bytes: ByteArray): Boolean =
        bytes.size >= 6 &&
            String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")

    private fun isWebp(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"

    private fun isHeif(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp" &&
            String(bytes, 8, 4, Charsets.US_ASCII) in setOf(
                "heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis"
            )
}
