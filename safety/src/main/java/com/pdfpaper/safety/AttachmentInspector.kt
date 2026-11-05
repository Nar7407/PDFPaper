package com.pdfpaper.safety

import java.nio.charset.StandardCharsets
import java.util.Locale

/** What an embedded file appears to be, judged from its bytes and its name. */
internal data class AttachmentVerdict(
    val label: String,
    val severity: RiskLevel,
    val explanation: String,
    val warnings: List<String> = emptyList()
)

/**
 * Classifies an attachment by magic bytes first and by its name second. A name
 * can lie (and a double extension is itself a signal), so the bytes always win.
 */
internal object AttachmentInspector {

    private val executableExtensions = setOf(
        "exe", "dll", "scr", "com", "msi", "cpl", "sys", "drv", "bin", "elf",
        "so", "dylib", "apk", "app", "dmg", "pkg", "lnk", "reg", "jar"
    )
    private val scriptExtensions = setOf(
        "js", "jse", "vbs", "vbe", "wsf", "wsh", "ps1", "psm1", "bat", "cmd",
        "sh", "bash", "zsh", "py", "rb", "pl", "php", "hta", "scpt", "applescript"
    )
    private val macroOfficeExtensions = setOf(
        "docm", "dotm", "xlsm", "xltm", "pptm", "potm", "xlam", "ppam", "sldm"
    )
    private val legacyOfficeExtensions = setOf("doc", "dot", "xls", "xlt", "ppt", "pot")
    private val officeExtensions = setOf(
        "docx", "xlsx", "pptx", "odt", "ods", "odp"
    )
    private val archiveExtensions = setOf(
        "zip", "rar", "7z", "gz", "tgz", "bz2", "xz", "tar", "cab", "iso"
    )
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "tif", "tiff")
    private val benignDocumentExtensions = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "txt", "csv",
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "tif", "tiff"
    )

    fun inspect(name: String, bytes: ByteArray?): AttachmentVerdict {
        val safeName = name.ifBlank { "Unnamed attachment" }
        val lower = safeName.lowercase(Locale.ROOT)
        val extension = lower.substringAfterLast('.', "")
        val warnings = ArrayList<String>()

        if (safeName.any { it in BIDI_CONTROLS }) {
            warnings += "The file name contains invisible text-direction controls."
        }
        if (hasDoubleExtension(lower)) {
            warnings += "The name has two extensions. The last one is what the system would use."
        }

        val magic = bytes?.let { magicKind(it) }
        val verdict = when (magic) {
            MagicKind.WINDOWS_PROGRAM -> AttachmentVerdict(
                "Windows executable",
                RiskLevel.HIGH,
                "An attachment is a Windows program. It can run code if another app opens it."
            )
            MagicKind.ELF_PROGRAM -> AttachmentVerdict(
                "Executable program",
                RiskLevel.HIGH,
                "An attachment is a native executable program."
            )
            MagicKind.MACH_PROGRAM -> AttachmentVerdict(
                "macOS executable",
                RiskLevel.HIGH,
                "An attachment is a macOS executable program."
            )
            MagicKind.SCRIPT -> AttachmentVerdict(
                "Script",
                RiskLevel.HIGH,
                "An attachment is a script that another program could execute."
            )
            MagicKind.ZIP -> zipVerdict(extension, bytes ?: ByteArray(0), warnings)
            MagicKind.OLE -> oleVerdict(extension, warnings)
            MagicKind.RAR, MagicKind.SEVEN_ZIP, MagicKind.GZIP,
            MagicKind.BZIP2, MagicKind.XZ -> AttachmentVerdict(
                "Archive",
                RiskLevel.MEDIUM,
                "An attachment is a compressed archive. Archives often carry other files " +
                    "that were not inspected here."
            )
            MagicKind.PDF -> AttachmentVerdict(
                "PDF document",
                RiskLevel.LOW,
                "An attachment is another PDF document."
            )
            MagicKind.IMAGE -> AttachmentVerdict(
                "Image",
                RiskLevel.LOW,
                "An attachment is an image."
            )
            MagicKind.RTF -> AttachmentVerdict(
                "Rich Text document",
                RiskLevel.LOW,
                "An attachment is a Rich Text (RTF) document."
            )
            MagicKind.UNKNOWN, null -> byExtension(extension, warnings)
        }

        val finalWarnings = warnings.toMutableList()
        if (bytes == null) {
            finalWarnings += "The attachment data could not be read or was above the inspection limit."
        } else {
            val mismatch = extensionMismatch(extension, magic)
            if (mismatch != null) finalWarnings += mismatch
        }
        val severity = if (finalWarnings.any { it.contains("invisible") || it.contains("two extensions") }) {
            maxOf(verdict.severity, RiskLevel.MEDIUM)
        } else {
            verdict.severity
        }
        return verdict.copy(warnings = finalWarnings.distinct(), severity = severity)
    }

    private fun zipVerdict(
        extension: String,
        bytes: ByteArray,
        warnings: MutableList<String>
    ): AttachmentVerdict {
        val text = bytes.toString(StandardCharsets.ISO_8859_1)
        val hasMacroProject = text.contains("vbaProject.bin", ignoreCase = true)
        return when {
            hasMacroProject || extension in macroOfficeExtensions -> AttachmentVerdict(
                "Office document with macros",
                RiskLevel.HIGH,
                "An attachment is an Office file that can contain VBA macros. " +
                    "Macros can run code when the file is opened in Office."
            )
            extension in officeExtensions || text.contains("[Content_Types].xml") ->
                AttachmentVerdict(
                    "Office document",
                    RiskLevel.MEDIUM,
                    "An attachment is an Office document. It was not opened or converted."
                )
            else -> AttachmentVerdict(
                "ZIP archive",
                RiskLevel.MEDIUM,
                "An attachment is a ZIP archive. Its contents were not extracted."
            )
        }
    }

    private fun oleVerdict(extension: String, warnings: MutableList<String>): AttachmentVerdict {
        if (extension in macroOfficeExtensions) {
            return AttachmentVerdict(
                "Office document with macros",
                RiskLevel.HIGH,
                "An attachment is a macro-enabled Office file."
            )
        }
        if (extension in legacyOfficeExtensions) {
            warnings += "Legacy Office files can contain macros; this check cannot tell."
            return AttachmentVerdict(
                "Legacy Office document",
                RiskLevel.MEDIUM,
                "An attachment is a legacy .doc/.xls/.ppt file. It may contain macros."
            )
        }
        return AttachmentVerdict(
            "OLE compound file",
            RiskLevel.MEDIUM,
            "An attachment is an OLE compound file (often a legacy Office document)."
        )
    }

    private fun byExtension(extension: String, warnings: MutableList<String>): AttachmentVerdict =
        when (extension) {
            in executableExtensions -> AttachmentVerdict(
                "Executable (by file name)",
                RiskLevel.HIGH,
                "The attachment name ends in .$extension, an executable format."
            )
            in scriptExtensions -> AttachmentVerdict(
                "Script (by file name)",
                RiskLevel.HIGH,
                "The attachment name ends in .$extension, a script format."
            )
            in macroOfficeExtensions -> AttachmentVerdict(
                "Office document with macros",
                RiskLevel.HIGH,
                "The attachment name ends in .$extension, which allows macros."
            )
            in legacyOfficeExtensions -> {
                warnings += "Legacy Office file; macros cannot be ruled out without opening it."
                AttachmentVerdict(
                    "Legacy Office document",
                    RiskLevel.MEDIUM,
                    "The attachment name ends in .$extension."
                )
            }
            in officeExtensions -> AttachmentVerdict(
                "Office document",
                RiskLevel.MEDIUM,
                "The attachment name ends in .$extension."
            )
            in archiveExtensions -> AttachmentVerdict(
                "Archive",
                RiskLevel.MEDIUM,
                "The attachment name ends in .$extension."
            )
            "pdf" -> AttachmentVerdict(
                "PDF document",
                RiskLevel.LOW,
                "The attachment name ends in .pdf."
            )
            in imageExtensions -> AttachmentVerdict(
                "Image",
                RiskLevel.LOW,
                "The attachment name ends in .$extension."
            )
            else -> AttachmentVerdict(
                "Unrecognised attachment",
                RiskLevel.LOW,
                "The attachment type could not be recognised from its bytes."
            )
        }

    private fun extensionMismatch(extension: String, magic: MagicKind?): String? {
        if (extension.isBlank() || magic == null) return null
        val expected = when (magic) {
            MagicKind.WINDOWS_PROGRAM, MagicKind.ELF_PROGRAM, MagicKind.MACH_PROGRAM ->
                extension in executableExtensions
            MagicKind.SCRIPT -> extension in scriptExtensions
            MagicKind.ZIP -> extension in archiveExtensions ||
                extension in officeExtensions || extension in macroOfficeExtensions
            MagicKind.OLE -> extension in legacyOfficeExtensions ||
                extension in macroOfficeExtensions
            MagicKind.PDF -> extension == "pdf"
            MagicKind.IMAGE -> extension in imageExtensions
            MagicKind.RTF -> extension == "rtf"
            MagicKind.RAR, MagicKind.SEVEN_ZIP, MagicKind.GZIP,
            MagicKind.BZIP2, MagicKind.XZ -> extension in archiveExtensions
            MagicKind.UNKNOWN -> true
        }
        return if (expected) {
            null
        } else {
            "The name ends in .$extension but the bytes are ${magic.label}."
        }
    }

    private fun hasDoubleExtension(name: String): Boolean {
        val parts = name.split('.')
        if (parts.size < 3) return false
        val last = parts.last()
        val previous = parts[parts.size - 2]
        return last in executableExtensions + scriptExtensions + macroOfficeExtensions &&
            previous in benignDocumentExtensions
    }

    private fun magicKind(bytes: ByteArray): MagicKind? {
        fun at(offset: Int, vararg values: Int): Boolean {
            if (bytes.size < offset + values.size) return false
            return values.indices.all { bytes[offset + it].toInt() and 0xFF == values[it] }
        }
        return when {
            at(0, 0x4D, 0x5A) -> MagicKind.WINDOWS_PROGRAM
            at(0, 0x7F, 0x45, 0x4C, 0x46) -> MagicKind.ELF_PROGRAM
            at(0, 0xFE, 0xED, 0xFA, 0xCE) || at(0, 0xFE, 0xED, 0xFA, 0xCF) ||
                at(0, 0xCE, 0xFA, 0xED, 0xFE) || at(0, 0xCF, 0xFA, 0xED, 0xFE) ||
                at(0, 0xCA, 0xFE, 0xBA, 0xBE) -> MagicKind.MACH_PROGRAM
            at(0, 0x23, 0x21) -> MagicKind.SCRIPT
            at(0, 0x50, 0x4B, 0x03, 0x04) || at(0, 0x50, 0x4B, 0x05, 0x06) -> MagicKind.ZIP
            at(0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1) -> MagicKind.OLE
            at(0, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> MagicKind.RAR
            at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> MagicKind.SEVEN_ZIP
            at(0, 0x1F, 0x8B) -> MagicKind.GZIP
            at(0, 0x42, 0x5A, 0x68) -> MagicKind.BZIP2
            at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> MagicKind.XZ
            at(0, 0x25, 0x50, 0x44, 0x46, 0x2D) -> MagicKind.PDF
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ||
                at(0, 0xFF, 0xD8, 0xFF) || at(0, 0x47, 0x49, 0x46, 0x38) ||
                at(0, 0x42, 0x4D) || at(0, 0x52, 0x49, 0x46, 0x46) -> MagicKind.IMAGE
            at(0, 0x7B, 0x5C, 0x72, 0x74, 0x66) -> MagicKind.RTF
            else -> MagicKind.UNKNOWN
        }
    }

    private enum class MagicKind(val label: String) {
        WINDOWS_PROGRAM("a Windows program"),
        ELF_PROGRAM("a native executable"),
        MACH_PROGRAM("a macOS program"),
        SCRIPT("a script"),
        ZIP("a ZIP archive"),
        OLE("an OLE compound file"),
        RAR("a RAR archive"),
        SEVEN_ZIP("a 7-Zip archive"),
        GZIP("a gzip archive"),
        BZIP2("a bzip2 archive"),
        XZ("an xz archive"),
        PDF("a PDF"),
        IMAGE("an image"),
        RTF("an RTF document"),
        UNKNOWN("unrecognised data")
    }

    private val BIDI_CONTROLS: Set<Char> = setOf(
        '\u200E', '\u200F',
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069'
    )
}
