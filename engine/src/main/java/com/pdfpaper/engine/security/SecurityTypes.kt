package com.pdfpaper.engine.security

/**
 * Thrown when an encrypted document cannot be opened with the supplied
 * password (ISO 32000-1, §7.6). The viewer catches it to prompt for a password
 * rather than reporting a generic error.
 */
class PdfPasswordException(message: String) : Exception(message)

/**
 * Access permissions from the encryption dictionary `/P` entry (§7.6.3.2,
 * Table 22). Bit positions are 1-based in the table; a set bit grants the
 * permission. Revision 2 files only define the lower bits.
 */
class PdfPermissions(val raw: Int) {

    /** Bit 3: print the document. */
    val canPrint: Boolean get() = bit(3)

    /** Bit 4: modify the contents of the document. */
    val canModifyContents: Boolean get() = bit(4)

    /** Bit 5: copy text and graphics. */
    val canCopy: Boolean get() = bit(5)

    /** Bit 6: add or modify annotations and fill form fields. */
    val canAnnotate: Boolean get() = bit(6)

    /** Bit 9: fill in existing form fields (even when bit 6 is clear). */
    val canFillForms: Boolean get() = bit(9) || bit(6)

    /** Bit 10: extract text and graphics for accessibility. */
    val canExtractForAccessibility: Boolean get() = bit(10)

    /** Bit 11: assemble the document (insert, rotate, delete pages). */
    val canAssemble: Boolean get() = bit(11)

    /** Bit 12: print at high resolution. */
    val canPrintHighQuality: Boolean get() = bit(12)

    private fun bit(n: Int): Boolean = (raw and (1 shl (n - 1))) != 0

    override fun toString(): String =
        "PdfPermissions(print=$canPrint, modify=$canModifyContents, copy=$canCopy, " +
            "annotate=$canAnnotate, fill=$canFillForms, assemble=$canAssemble)"
}

/** Encryption method applied to strings and streams (§7.6.3.4, Table 25). */
internal enum class CryptMethod { RC4, AESV2, AESV3, IDENTITY }
