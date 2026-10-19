package com.pdfpaper.app.office

/**
 * The office formats the open flow understands. Everything that depends on the
 * format - the picker's MIME types, the value stored in the recent list, the
 * label shown while the file converts - lives here so the three cannot drift
 * apart.
 */
enum class OfficeKind(
    val label: String,
    val extension: String,
    val mimeType: String
) {
    WORD(
        label = "Word document",
        extension = "docx",
        mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    ),
    POWERPOINT(
        label = "PowerPoint presentation",
        extension = "pptx",
        mimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    );

    /** The value stored in the recent list (and read back from it). */
    val recentValue: String get() = extension

    companion object {
        /**
         * What the picker offers: PDFs, both supported formats, and the legacy
         * formats so a .doc or .ppt ends on a clear "save it as .docx" message
         * instead of being invisible.
         */
        val PICKER_MIME_TYPES: Array<String> = arrayOf(
            "application/pdf",
            WORD.mimeType,
            POWERPOINT.mimeType,
            "application/msword",
            "application/vnd.ms-powerpoint"
        )

        fun fromRecent(value: String?): OfficeKind? =
            entries.firstOrNull { it.recentValue == value }
    }
}
