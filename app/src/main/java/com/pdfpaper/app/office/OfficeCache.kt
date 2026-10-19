package com.pdfpaper.app.office

import com.pdfpaper.app.data.sanitizeDisplayName
import com.pdfpaper.app.data.uniqueFile
import java.io.File

/**
 * A PDF that was converted from an office file, together with what the
 * converter reported. The viewer finds [warnings] again through the sidecar
 * file next to the PDF, so the information survives process restarts and the
 * recent list re-opening the original document.
 */
data class ConvertedDocument(
    val pdf: File,
    val sourceName: String,
    val kind: OfficeKind,
    val warnings: List<String>
) {
    /** "Notes.docx" becomes "Notes.pdf" for Save as PDF. */
    val pdfName: String get() = OfficeCache.pdfNameFor(sourceName)
}

/**
 * Stores converted office PDFs in `<cacheDir>/converted/` and remembers the
 * source name, format and conversion warnings in a small text sidecar
 * (`<pdf>.office.txt`). Pure JVM so the format can be unit-tested.
 */
object OfficeCache {

    const val SIDECAR_SUFFIX = ".office.txt"

    /** "Notes.docx" becomes "Notes.pdf", whatever the provider called the source. */
    fun pdfNameFor(sourceName: String): String {
        val safe = sanitizeDisplayName(sourceName)
        val base = safe.substringBeforeLast('.')
        return if (base.isBlank()) safe else "$base.pdf"
    }

    fun store(
        cacheDir: File,
        sourceName: String,
        kind: OfficeKind,
        pdf: ByteArray,
        warnings: List<String>
    ): ConvertedDocument {
        val dir = File(cacheDir, "converted")
        require(dir.mkdirs() || dir.isDirectory) { "Unable to create cache directory: $dir" }
        val pdfFile = uniqueFile(dir, pdfNameFor(sourceName))
        pdfFile.writeBytes(pdf)
        sidecarFor(pdfFile).writeText(encode(sourceName, kind, warnings))
        return ConvertedDocument(pdfFile, sourceName, kind, warnings)
    }

    /** Reads the conversion notes of a cached PDF, or null for a plain PDF. */
    fun read(pdfPath: String): ConvertedDocument? {
        if (pdfPath.isBlank()) return null
        val pdf = File(pdfPath)
        val sidecar = sidecarFor(pdf)
        if (!sidecar.isFile) return null
        val lines = runCatching { sidecar.readLines() }.getOrNull() ?: return null
        val kind = OfficeKind.fromRecent(lines.getOrNull(0)) ?: return null
        val sourceName = lines.getOrNull(1).orEmpty().ifBlank { pdf.name }
        return ConvertedDocument(pdf, sourceName, kind, lines.drop(2).filter { it.isNotBlank() })
    }

    private fun sidecarFor(pdf: File): File = File(pdf.parentFile, pdf.name + SIDECAR_SUFFIX)

    private fun encode(sourceName: String, kind: OfficeKind, warnings: List<String>): String {
        val lines = ArrayList<String>(warnings.size + 2)
        lines += kind.recentValue
        lines += oneLine(sourceName)
        warnings.forEach { lines += oneLine(it) }
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun oneLine(text: String): String =
        text.replace('\n', ' ').replace('\r', ' ')
}
