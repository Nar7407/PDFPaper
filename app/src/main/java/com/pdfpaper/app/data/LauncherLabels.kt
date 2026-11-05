package com.pdfpaper.app.data

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/**
 * The labels shown on the launcher and in the widget (task T48).
 *
 * A recent document's name is private: it is off by default and only shown
 * when the user turns on "Show file names on launcher/widget". This object is
 * pure JVM data so the privacy rules are unit-testable.
 */
object LauncherLabels {

    /** File names are hidden unless the user opts in. */
    const val DEFAULT_SHOW_NAMES: Boolean = false

    /** Launchers truncate long labels; keep the important part of a name. */
    const val MAX_LABEL_LENGTH: Int = 40

    const val HIDDEN_PINNED_LABEL: String = "PDF document"

    /**
     * The label for the shortcut or widget row at [index] (zero-based).
     * With names off this is the deliberately anonymous "Recent PDF 1".
     */
    fun recentLabel(index: Int, displayName: String, showNames: Boolean): String {
        val fallback = "Recent PDF ${index + 1}"
        if (!showNames) return fallback
        return cleanName(displayName)?.take(MAX_LABEL_LENGTH) ?: fallback
    }

    /** A longer accessibility label for the same recent document. */
    fun recentLongLabel(index: Int, displayName: String, showNames: Boolean): String {
        val label = recentLabel(index, displayName, showNames)
        return if (showNames) "Open $label" else "Open recent PDF ${index + 1}"
    }

    /** A pinned shortcut follows the same privacy choice as the recent list. */
    fun pinnedLabel(displayName: String, showNames: Boolean): String {
        if (!showNames) return HIDDEN_PINNED_LABEL
        return cleanName(displayName)?.take(MAX_LABEL_LENGTH) ?: HIDDEN_PINNED_LABEL
    }

    fun pinnedLongLabel(displayName: String, showNames: Boolean): String =
        if (showNames) {
            "Open ${pinnedLabel(displayName, true)}"
        } else {
            "Open a PDF document"
        }

    /**
     * A stable shortcut id for a document URI. Hashing keeps the id short and
     * independent of the (possibly very long) content URI.
     */
    fun pinnedId(uri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(UTF_8))
        val hex = buildString(16) {
            digest.take(8).forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
        return "pinned-$hex"
    }

    private fun cleanName(name: String): String? =
        name.replace(WHITESPACE, " ").trim().takeIf { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")
    private const val HEX = "0123456789abcdef"
}
