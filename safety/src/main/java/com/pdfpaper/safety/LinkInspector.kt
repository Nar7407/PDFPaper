package com.pdfpaper.safety

import java.util.Locale

/**
 * Static inspection of a web address. It never resolves a name and never makes
 * a request; everything here is string and Unicode analysis.
 */
internal object LinkInspector {

    private val authorityPattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*://([^/?#]+)")
    private val schemePattern = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val domainToken = Regex(
        "(?i)\\b(?:https?://)?((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63})\\b"
    )
    private val ipv4 = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")

    private val shorteners = setOf(
        "bit.ly", "buff.ly", "cutt.ly", "goo.gl", "is.gd", "lnkd.in", "ow.ly",
        "rb.gy", "rebrand.ly", "shorturl.at", "t.co", "tiny.cc", "tinyurl.com",
        "trib.al", "t.ly", "v.gd"
    )

    /** Hosts that are only a placeholder in old or broken documents. */
    private val placeholders = setOf("example.com", "example.org", "localhost")

    fun inspect(uri: String, visibleText: String? = null): LinkSafety {
        val address = uri.trim()
        val scheme = schemePattern.find(address)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        val host = hostOf(address, scheme)
        val warnings = ArrayList<String>()
        var risk = RiskLevel.LOW

        fun warn(message: String, level: RiskLevel) {
            warnings += message
            if (level.rank > risk.rank) risk = level
        }

        if (address.any { it in BIDI_CONTROLS }) {
            warn(
                "The address contains invisible direction-changing characters. " +
                    "They can make the displayed name differ from the real one.",
                RiskLevel.HIGH
            )
        }
        when (scheme) {
            "javascript" -> warn(
                "This is a JavaScript link. PDFPaper will not run it.",
                RiskLevel.HIGH
            )
            "data" -> warn(
                "The link embeds its content directly (a data: address) instead of naming a site.",
                RiskLevel.HIGH
            )
            "file" -> warn(
                "The link points to a local file rather than a website.",
                RiskLevel.MEDIUM
            )
        }

        if (scheme == "http" || scheme == "https") {
            val authority = authorityPattern.find(address)?.groupValues?.get(1).orEmpty()
            if (authority.contains('@')) {
                warn(
                    "The address hides part of itself before an @ sign. " +
                        "Only the part after the @ is the real host.",
                    RiskLevel.HIGH
                )
            }
            if (host == null) {
                warn("The address has no readable domain name.", RiskLevel.MEDIUM)
            }
        } else if (scheme == "mailto") {
            if (host == null) {
                warn("The mail address has no readable domain.", RiskLevel.MEDIUM)
            }
        } else if (scheme == null && host == null && address.isNotBlank()) {
            warn("The link has no readable domain name.", RiskLevel.MEDIUM)
        }

        if (host != null) {
            val normalized = normalizeHost(host)
            when {
                isIpAddress(normalized) -> warn(
                    "The link goes to a numeric IP address instead of a named domain. " +
                        "The name can change owner or location.",
                    RiskLevel.MEDIUM
                )
                normalized in shorteners -> warn(
                    "This is a link shortener. The address shown here is not the final site; " +
                        "PDFPaper does not contact the service to expand it.",
                    RiskLevel.MEDIUM
                )
            }
            if (normalized.contains("xn--", ignoreCase = true)) {
                warn(
                    "The domain uses punycode (xn--). It can visually imitate another name.",
                    RiskLevel.HIGH
                )
            } else if (hasMixedScripts(normalized)) {
                warn(
                    "The domain mixes Latin letters with another script. " +
                        "This is a common homograph trick.",
                    RiskLevel.HIGH
                )
            } else if (normalized.any { it.code > 0x7F }) {
                warn(
                    "The domain contains non-ASCII characters. Check the spelling carefully.",
                    RiskLevel.MEDIUM
                )
            }
        }

        val visibleDomains = domainsIn(visibleText)
        if (host != null && visibleDomains.isNotEmpty()) {
            val real = normalizeHost(host)
            if (real !in placeholders && visibleDomains.none { it == real }) {
                warn(
                    "The visible text names ${visibleDomains.joinToString(", ")}, " +
                        "but the link really goes to ${displayDomain(host)}.",
                    RiskLevel.HIGH
                )
            }
        }

        return LinkSafety(
            uri = address,
            scheme = scheme,
            host = host,
            displayDomain = displayDomain(host),
            visibleText = visibleText,
            warnings = warnings.distinct(),
            risk = risk
        )
    }

    private fun hostOf(address: String, scheme: String?): String? {
        if (scheme == "mailto") {
            val mailbox = address.substringAfter(':', "").substringBefore('?')
            return mailbox.substringAfterLast('@', "").trim().ifBlank { null }
        }
        if (scheme != null) {
            val authority = authorityPattern.find(address)?.groupValues?.get(1) ?: return null
            return hostFromAuthority(authority)
        }
        // A bare "www.example.com" or "example.com/path" is still useful to show.
        return domainToken.find(address)?.groupValues?.get(1)?.trim()
    }

    private fun hostFromAuthority(authority: String): String? {
        var value = authority.substringBefore('/').substringBefore('?').substringBefore('#')
        value = value.substringAfterLast('@')
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            return if (end > 1) value.substring(0, end + 1) else null
        }
        value = value.substringBefore(':')
        return value.trim().ifBlank { null }
    }

    private fun displayDomain(host: String?): String? {
        val value = host?.trim()?.trimEnd('.') ?: return null
        if (value.isBlank()) return null
        return value
    }

    private fun normalizeHost(host: String): String =
        host.trim().trimEnd('.').lowercase(Locale.ROOT).removePrefix("www.")

    private fun isIpAddress(host: String): Boolean {
        if (host.startsWith("[") && host.endsWith("]")) return host.contains(':')
        if (!ipv4.matches(host)) return false
        return host.split('.').all { part ->
            part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun domainsIn(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return domainToken.findAll(text)
            .map { normalizeHost(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun hasMixedScripts(host: String): Boolean {
        var latin = false
        var other = false
        for (character in host) {
            when (character.code) {
                in 0x0041..0x005A, in 0x0061..0x007A -> latin = true
                in 0x0370..0x03FF, in 0x0400..0x04FF, in 0x0530..0x058F,
                in 0x0590..0x05FF, in 0x0600..0x06FF -> other = true
            }
        }
        return latin && other
    }

    private val BIDI_CONTROLS: Set<Char> = setOf(
        '\u200E', '\u200F',
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069'
    )
}
