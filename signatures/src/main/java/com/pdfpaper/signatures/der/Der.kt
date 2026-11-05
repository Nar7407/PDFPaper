package com.pdfpaper.signatures.der

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** A malformed or unsupported DER structure. Never escapes the public API. */
internal class DerException(message: String) : Exception(message)

/**
 * Minimal DER reader for the subset used by CMS/PKCS#7 and X.509
 * (definite lengths, low tag numbers, primitive values).
 *
 * The reader never copies unless asked: [Tlv] addresses the original byte array,
 * so a signed attribute set can be re-tagged byte-for-byte (RFC 5652 §5.4).
 */
internal class DerReader(
    private val data: ByteArray,
    private var position: Int = 0,
    private val limit: Int = data.size
) {

    class Tlv(
        val tag: Int,
        val start: Int,
        val contentStart: Int,
        val contentEnd: Int
    ) {
        val end: Int get() = contentEnd
        val contentLength: Int get() = contentEnd - contentStart
    }

    fun hasMore(): Boolean = position < limit

    fun read(): Tlv {
        if (position >= limit) throw DerException("unexpected end of DER data")
        val start = position
        var tag = data[position++].toInt() and 0xFF
        if (tag and 0x1F == 0x1F) {
            var count = 0
            while (true) {
                if (position >= limit) throw DerException("truncated tag number")
                val b = data[position++].toInt() and 0xFF
                count++
                if (count > 4) throw DerException("tag number too large")
                if (b and 0x80 == 0) break
            }
        }

        if (position >= limit) throw DerException("truncated length")
        val first = data[position++].toInt() and 0xFF
        val length: Int
        when {
            first == 0x80 -> throw DerException("indefinite-length encoding is not supported")
            first and 0x80 == 0 -> length = first
            else -> {
                val count = first and 0x7F
                if (count == 0 || count > 4) throw DerException("unsupported DER length")
                var value = 0L
                repeat(count) {
                    if (position >= limit) throw DerException("truncated length")
                    value = (value shl 8) or (data[position++].toLong() and 0xFF)
                }
                if (value > Int.MAX_VALUE) throw DerException("DER value too large")
                length = value.toInt()
            }
        }
        if (length < 0 || length > limit - position) throw DerException("truncated DER value")
        val contentStart = position
        position += length
        return Tlv(tag, start, contentStart, position)
    }

    fun readTag(expected: Int): Tlv {
        val tlv = read()
        if (tlv.tag != expected) {
            throw DerException(
                "expected tag 0x${expected.toString(16)} but found 0x${tlv.tag.toString(16)}"
            )
        }
        return tlv
    }

    fun readSequence(): DerReader = sub(readTag(0x30))

    fun readSet(): DerReader = sub(readTag(0x31))

    fun sub(tlv: Tlv): DerReader = DerReader(data, tlv.contentStart, tlv.contentEnd)

    fun bytes(tlv: Tlv): ByteArray = data.copyOfRange(tlv.contentStart, tlv.contentEnd)

    /** The whole TLV, tag and length included. */
    fun raw(tlv: Tlv): ByteArray = data.copyOfRange(tlv.start, tlv.end)

    fun readOid(tlv: Tlv = readTag(0x06)): String = decodeOid(bytes(tlv))

    fun readInteger(tlv: Tlv = readTag(0x02)): BigInteger = BigInteger(bytes(tlv))

    fun readOctets(tlv: Tlv = readTag(0x04)): ByteArray = bytes(tlv)

    fun readBoolean(tlv: Tlv = readTag(0x01)): Boolean = bytes(tlv).any { it.toInt() != 0 }

    /** Content of a BIT STRING, rejecting padded (non-byte-aligned) values. */
    fun readBitString(tlv: Tlv = readTag(0x03)): ByteArray {
        val bytes = bytes(tlv)
        if (bytes.isEmpty()) throw DerException("empty BIT STRING")
        if (bytes[0].toInt() != 0) throw DerException("padded BIT STRING is not supported")
        return bytes.copyOfRange(1, bytes.size)
    }

    /**
     * UTCTime (RFC 5280 §4.1.2.5.1) or GeneralizedTime (…5.2) as an [Instant].
     * Returns null for a malformed value instead of failing the whole parse.
     */
    fun time(tlv: Tlv): Instant? {
        val text = String(bytes(tlv), StandardCharsets.US_ASCII).trim()
        return try {
            when (tlv.tag) {
                0x17 -> parseUtcTime(text)
                0x18 -> parseGeneralizedTime(text)
                else -> null
            }
        } catch (_: DateTimeParseException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    companion object {

        fun decodeOid(bytes: ByteArray): String {
            if (bytes.isEmpty()) throw DerException("empty OID")
            val arcs = ArrayList<Long>()
            var value = 0L
            var pending = false
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (value > (Long.MAX_VALUE shr 7)) throw DerException("OID arc too large")
                value = (value shl 7) or (v and 0x7F).toLong()
                pending = true
                if (v and 0x80 == 0) {
                    arcs += value
                    value = 0
                    pending = false
                }
            }
            if (pending || arcs.isEmpty()) throw DerException("truncated OID")
            val first = arcs[0]
            val (root, second) = when {
                first < 40 -> 0L to first
                first < 80 -> 1L to first - 40
                else -> 2L to first - 80
            }
            return buildString {
                append(root).append('.').append(second)
                for (i in 1 until arcs.size) append('.').append(arcs[i])
            }
        }

        private val UTC = Regex("""(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?(Z|[+-]\d{4})""")
        private val GENERALIZED =
            Regex("""(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?(?:[.,](\d+))?(Z|[+-]\d{4})?""")

        private fun parseUtcTime(text: String): Instant {
            val m = UTC.matchEntire(text) ?: throw DateTimeParseException("bad UTCTime", text, 0)
            val yy = m.groupValues[1].toInt()
            val year = if (yy >= 50) 1900 + yy else 2000 + yy
            return instant(
                year, m.groupValues[2], m.groupValues[3], m.groupValues[4],
                m.groupValues[5], m.groupValues[6], null, m.groupValues[7]
            )
        }

        private fun parseGeneralizedTime(text: String): Instant {
            val m = GENERALIZED.matchEntire(text) ?: throw DateTimeParseException("bad GeneralizedTime", text, 0)
            return instant(
                m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3], m.groupValues[4],
                m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8]
            )
        }

        private fun instant(
            year: Int,
            month: String,
            day: String,
            hour: String,
            minute: String,
            second: String,
            fraction: String?,
            zone: String
        ): Instant {
            val base = LocalDateTime.of(
                year,
                month.toInt(),
                day.toInt(),
                hour.toInt(),
                minute.toInt(),
                if (second.isEmpty()) 0 else second.toInt()
            )
            val withSeconds = if (fraction.isNullOrEmpty()) {
                base
            } else {
                val nanos = ((fraction + "000000000").substring(0, 9)).toInt()
                base.withNano(nanos)
            }
            val offset = when {
                zone.isEmpty() || zone == "Z" -> ZoneOffset.UTC
                else -> {
                    val sign = if (zone[0] == '-') -1 else 1
                    val hours = zone.substring(1, 3).toInt()
                    val minutes = zone.substring(3, 5).toInt()
                    ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes)
                }
            }
            return withSeconds.toInstant(offset)
        }
    }
}
