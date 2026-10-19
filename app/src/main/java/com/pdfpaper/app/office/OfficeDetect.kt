package com.pdfpaper.app.office

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** What a picked document turns out to be, decided from its content. */
sealed interface DocumentKind {

    /** A PDF: nothing to convert, the viewer can open it as it is. */
    data object Pdf : DocumentKind

    /** A .docx or .pptx the converter understands. */
    data class Office(val kind: OfficeKind) : DocumentKind

    /** A Word 97-2003 / PowerPoint 97-2003 file (an OLE compound file). */
    data object LegacyOffice : DocumentKind

    /** A readable file that is none of the above; [description] says what it is. */
    data class Other(val description: String) : DocumentKind

    /** The bytes cannot be opened at all (damaged container). */
    data object Unreadable : DocumentKind
}

/**
 * Decides what a document is from its bytes, not from its name. Providers hand
 * out files whose extension lies (a renamed .pdf, a .docx that is really a
 * legacy .doc), and a clear message beats a failing converter.
 *
 * Pure JVM (only `java.io` and `java.util.zip`) so the rules are unit-testable.
 */
object OfficeDetect {

    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val ZIP_LOCAL = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val ZIP_EMPTY = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private val OLE_MAGIC = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
    )

    fun detect(file: File): DocumentKind {
        if (!file.isFile) return DocumentKind.Unreadable
        val header = readHeader(file)
        return when {
            header.startsWith(PDF_MAGIC) -> DocumentKind.Pdf
            header.startsWith(ZIP_LOCAL) || header.startsWith(ZIP_EMPTY) -> fromZip(file)
            header.startsWith(OLE_MAGIC) -> DocumentKind.LegacyOffice
            else -> DocumentKind.Other("it has no Office or PDF content")
        }
    }

    private fun fromZip(file: File): DocumentKind {
        val names = try {
            ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }.toHashSet() }
        } catch (e: IOException) {
            return DocumentKind.Unreadable
        }
        return when {
            "[Content_Types].xml" !in names ->
                DocumentKind.Other("it is a ZIP archive, not an Office package")
            "word/document.xml" in names -> DocumentKind.Office(OfficeKind.WORD)
            "ppt/presentation.xml" in names -> DocumentKind.Office(OfficeKind.POWERPOINT)
            "xl/workbook.xml" in names -> DocumentKind.Other("it is an Excel workbook")
            else -> DocumentKind.Other("it is a ZIP package without Word or PowerPoint parts")
        }
    }

    private fun readHeader(file: File): ByteArray {
        if (!file.isFile) return ByteArray(0)
        val header = ByteArray(8)
        return try {
            file.inputStream().use { input ->
                var read = 0
                while (read < header.size) {
                    val count = input.read(header, read, header.size - read)
                    if (count < 0) break
                    read += count
                }
            }
            header.copyOf()
        } catch (e: IOException) {
            ByteArray(0)
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }
}
