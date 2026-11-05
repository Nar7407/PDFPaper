package com.pdfpaper.engine.annot

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * PDF date strings (§7.9.4): `D:YYYYMMDDHHmmSSOHH'mm'`. Every field after the
 * year is optional, the `D:` prefix may be missing, and the offset may be `Z`,
 * `+HH`, `+HH'mm'` or `-HH'mm'`. The engine writes UTC (`...Z`) in [now].
 *
 * A date without an offset carries no zone information; [millis] reads it as
 * UTC because the engine only needs a stable total order (comment threads are
 * listed by page and time).
 */
internal object PdfDate {

    private val WRITE = DateTimeFormatter.ofPattern("'D:'yyyyMMddHHmmss'Z'")

    /** The current time as a PDF date string in UTC. */
    fun now(): String = LocalDateTime.now(ZoneOffset.UTC).format(WRITE)

    /** Epoch milliseconds for [text], or null when it is missing or malformed. */
    fun millis(text: String?): Long? {
        var value = text?.trim().orEmpty()
        if (value.startsWith("D:")) value = value.substring(2)
        if (value.isEmpty()) return null
        return try {
            val reader = DateReader(value)
            val year = reader.digits(4) ?: return null
            val month = reader.optional(2) ?: 1
            val day = reader.optional(2) ?: 1
            val hour = reader.optional(2) ?: 0
            val minute = reader.optional(2) ?: 0
            val second = reader.optional(2) ?: 0
            reader.skipFraction()
            val offset = reader.offset() ?: return null
            if (!reader.atEnd()) return null
            LocalDateTime.of(year, month, day, hour, minute, second).toInstant(offset).toEpochMilli()
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Thrown for an ill-formed field; [millis] turns it into a null result. */
    private object Malformed : RuntimeException() {
        private fun readResolve(): Any = Malformed
    }

    private class DateReader(private val text: String) {
        private var index = 0

        /** Exactly [count] digits; null when the text ends first, throws on non-digits. */
        fun digits(count: Int): Int? {
            if (index + count > text.length) return null
            var value = 0
            repeat(count) {
                val char = text[index]
                if (char !in '0'..'9') throw Malformed
                value = value * 10 + (char - '0')
                index++
            }
            return value
        }

        /** [count] digits when the text has them, null when the text has ended. */
        fun optional(count: Int): Int? = digits(count)

        /** Skips a fractional second (`.5`) when one is present. */
        fun skipFraction() {
            if (index < text.length && text[index] == '.') {
                index++
                while (index < text.length && text[index] in '0'..'9') index++
            }
        }

        /** Zone offset at the current position; UTC when the text has ended. */
        fun offset(): ZoneOffset? {
            if (index >= text.length) return ZoneOffset.UTC
            return when (text[index]) {
                'Z' -> {
                    index++
                    ZoneOffset.UTC
                }
                '+', '-' -> {
                    val negative = text[index] == '-'
                    index++
                    val hours = digits(2) ?: throw Malformed
                    if (index < text.length && text[index] == '\'') index++
                    val minutes =
                        if (index < text.length && text[index] in '0'..'9') digits(2) ?: throw Malformed else 0
                    if (index < text.length && text[index] == '\'') index++
                    val sign = if (negative) -1 else 1
                    ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes)
                }
                else -> null
            }
        }

        fun atEnd(): Boolean = index >= text.length
    }
}
