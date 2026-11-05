package com.pdfpaper.engine.model

/**
 * Result of scanning a token that may be a PDF number (§7.3.3):
 * `[+-]?(\d+\.\d*|\.\d+|\d+)([eE][+-]?\d+)?`.
 *
 * This is a hand-written replacement for a per-token `Regex.matches` call —
 * object and content tokenizers classify every number-like token, so the regex
 * engine (and its `Matcher` allocation) dominated large-document profiling.
 */
internal sealed interface NumberValue

internal data class NumberLong(val value: Long) : NumberValue

internal data class NumberDouble(val value: Double) : NumberValue

/** Returns the numeric value when [text] is exactly a PDF number, else null. */
internal fun scanNumber(text: String): NumberValue? {
    val n = text.length
    if (n == 0) return null
    var i = 0
    if (text[0] == '+' || text[0] == '-') i++
    val intStart = i
    while (i < n && text[i] in '0'..'9') i++
    val intDigits = i - intStart
    var isReal = false
    if (i < n && text[i] == '.') {
        isReal = true
        i++
        val fracStart = i
        while (i < n && text[i] in '0'..'9') i++
        if (intDigits == 0 && i == fracStart) return null
    } else if (intDigits == 0) {
        return null
    }
    if (i < n && (text[i] == 'e' || text[i] == 'E')) {
        isReal = true
        i++
        if (i < n && (text[i] == '+' || text[i] == '-')) i++
        val expStart = i
        while (i < n && text[i] in '0'..'9') i++
        if (i == expStart) return null
    }
    if (i != n) return null
    if (isReal) return NumberDouble(text.toDouble())
    return text.toLongOrNull()?.let { NumberLong(it) } ?: NumberDouble(text.toDouble())
}
