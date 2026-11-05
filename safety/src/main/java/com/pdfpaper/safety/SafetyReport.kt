package com.pdfpaper.safety

/**
 * The risk a static check can support.
 *
 * LOW does not mean "safe": it means no known risky feature was found. T49
 * deliberately never emits a "safe" verdict.
 */
enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH;

    val label: String
        get() = when (this) {
            LOW -> "Low risk"
            MEDIUM -> "Medium risk"
            HIGH -> "High risk"
        }

    /** Higher sorts first in the details list. */
    val rank: Int
        get() = when (this) {
            LOW -> 0
            MEDIUM -> 1
            HIGH -> 2
        }
}

/** What kind of feature produced a finding. */
enum class SafetyCategory(val label: String) {
    JAVASCRIPT("JavaScript"),
    ACTION("Action"),
    EMBEDDED_FILE("Attachment"),
    RICH_MEDIA("Rich media"),
    XFA("XFA form"),
    LINK("Link"),
    STRUCTURE("File structure"),
    OBFUSCATION("Hidden or obfuscated content"),
    UNUSUAL("Unusual object"),
    ENCRYPTION("Encryption")
}

/**
 * Where a finding came from. Page numbers are one-based for people; object
 * numbers are the PDF object numbers used by the file (and by comments).
 */
data class SafetyLocation(
    val pageIndex: Int? = null,
    val objectNumber: Int? = null,
    val detail: String? = null
) {
    fun label(): String {
        val parts = ArrayList<String>()
        if (pageIndex != null) parts += "Page ${pageIndex + 1}"
        if (objectNumber != null && objectNumber >= 0) parts += "Object $objectNumber"
        if (!detail.isNullOrBlank()) parts += detail
        return if (parts.isEmpty()) "Document" else parts.joinToString(" · ")
    }
}

/**
 * Everything the UI needs to explain a web link before the user opens it.
 * [host] is the real host from the address, never the visible text. The
 * inspector does no network lookup.
 */
data class LinkSafety(
    val uri: String,
    val scheme: String? = null,
    val host: String? = null,
    val displayDomain: String? = null,
    val visibleText: String? = null,
    val warnings: List<String> = emptyList(),
    val risk: RiskLevel = RiskLevel.LOW
) {
    val hasWarnings: Boolean get() = warnings.isNotEmpty()

    fun domainLabel(): String = displayDomain
        ?: host
        ?: "No readable domain"
}

/** One plain-language result of the static check. */
data class SafetyFinding(
    val id: String,
    val category: SafetyCategory,
    val severity: RiskLevel,
    val title: String,
    val explanation: String,
    val location: SafetyLocation? = null,
    val evidence: String? = null,
    val link: LinkSafety? = null
)

/**
 * The result of one static scan. It is data only: no code from the PDF was
 * run, no link was opened, and no network request was made.
 */
data class SafetyReport(
    val risk: RiskLevel,
    val findings: List<SafetyFinding>,
    val scannedObjects: Int = 0,
    val pageCount: Int = 0,
    val encrypted: Boolean = false,
    val error: String? = null
) {
    val hasFindings: Boolean get() = findings.isNotEmpty()

    /** The banner is only worth interrupting the reader for medium/high findings. */
    val hasNoteworthyFindings: Boolean
        get() = findings.any { it.severity != RiskLevel.LOW }

    fun summary(): String {
        if (error != null) return "The safety check could not finish: $error"
        if (findings.isEmpty()) return "No known risky features found."
        val high = findings.count { it.severity == RiskLevel.HIGH }
        val medium = findings.count { it.severity == RiskLevel.MEDIUM }
        val low = findings.count { it.severity == RiskLevel.LOW }
        val parts = ArrayList<String>()
        if (high > 0) parts += "$high high"
        if (medium > 0) parts += "$medium medium"
        if (low > 0) parts += "$low low"
        return "${findings.size} finding${if (findings.size == 1) "" else "s"}: " +
            parts.joinToString(", ")
    }

    companion object {
        /**
         * Shown on every report. The wording is intentionally weaker than
         * "safe": this is a heuristic inspection, not an antivirus engine.
         */
        const val DISCLAIMER: String =
            "This is a heuristic check, not antivirus software. It cannot prove " +
                "that a document is safe, and it never runs JavaScript, actions or links."

        fun error(message: String): SafetyReport = SafetyReport(
            risk = RiskLevel.LOW,
            findings = emptyList(),
            error = message
        )
    }
}
