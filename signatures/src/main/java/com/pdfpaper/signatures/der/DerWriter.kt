package com.pdfpaper.signatures.der

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Minimal DER writer for the CMS structures we create (RFC 5652, RFC 5280).
 *
 * Only definite-length, low-tag-number values are produced. SET OF values are
 * sorted by their encoded bytes, as DER requires, because the signed attributes
 * must hash exactly as a verifier sees them.
 */
internal object DerWriter {

    fun sequence(vararg parts: ByteArray): ByteArray = sequence(parts.asList())

    fun sequence(parts: Collection<ByteArray>): ByteArray = tlv(TAG_SEQUENCE, concat(parts))

    fun set(vararg parts: ByteArray): ByteArray = set(parts.asList())

    fun set(parts: Collection<ByteArray>): ByteArray = tlv(TAG_SET, concat(sorted(parts)))

    fun implicitSet(tag: Int, parts: Collection<ByteArray>): ByteArray =
        tlv(tag, concat(sorted(parts)))

    fun explicit(tag: Int, value: ByteArray): ByteArray = tlv(tag, value)

    fun integer(value: Long): ByteArray = integer(BigInteger.valueOf(value))

    fun integer(value: BigInteger): ByteArray = tlv(TAG_INTEGER, value.toByteArray())

    fun oid(value: String): ByteArray = tlv(TAG_OID, encodeOid(value))

    fun nullValue(): ByteArray = byteArrayOf(TAG_NULL.toByte(), 0)

    fun octetString(value: ByteArray): ByteArray = tlv(TAG_OCTET_STRING, value)

    fun bitString(value: ByteArray): ByteArray {
        val content = ByteArray(value.size + 1)
        content[0] = 0 // no unused bits
        value.copyInto(content, 1)
        return tlv(TAG_BIT_STRING, content)
    }

    fun utf8String(value: String): ByteArray =
        tlv(TAG_UTF8_STRING, value.toByteArray(StandardCharsets.UTF_8))

    fun utcTime(value: Instant): ByteArray {
        val utc = value.atZone(ZoneOffset.UTC)
        val tag = if (utc.year in 1950..2049) TAG_UTC_TIME else TAG_GENERALIZED_TIME
        val text = if (tag == TAG_UTC_TIME) {
            UTC_FORMAT.format(utc)
        } else {
            GENERALIZED_FORMAT.format(utc)
        }
        return tlv(tag, text.toByteArray(StandardCharsets.US_ASCII))
    }

    fun raw(tag: Int, content: ByteArray): ByteArray = tlv(tag, content)

    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        writeLength(out, content.size)
        out.write(content)
        return out.toByteArray()
    }

    fun concat(parts: Iterable<ByteArray>): ByteArray {
        var size = 0
        for (part in parts) size += part.size
        val out = ByteArray(size)
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    fun sorted(parts: Collection<ByteArray>): List<ByteArray> =
        parts.sortedWith { a, b -> compareUnsigned(a, b) }

    /** Lexicographic byte comparison for DER SET OF ordering. */
    fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val common = minOf(a.size, b.size)
        for (i in 0 until common) {
            val difference = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (difference != 0) return difference
        }
        return a.size - b.size
    }

    private fun writeLength(out: ByteArrayOutputStream, length: Int) {
        if (length < 0x80) {
            out.write(length)
            return
        }
        var value = length
        var bytes = 0
        while (value > 0) {
            bytes++
            value = value ushr 8
        }
        out.write(0x80 or bytes)
        for (shift in (bytes - 1) * 8 downTo 0 step 8) {
            out.write((length ushr shift) and 0xFF)
        }
    }

    /** Encodes a dotted OID (ITU-T X.690 §8.19). */
    private fun encodeOid(value: String): ByteArray {
        val parts = value.split('.').map { part ->
            part.toLongOrNull() ?: throw IllegalArgumentException("invalid OID: $value")
        }
        require(parts.size >= 2) { "an OID needs at least two arcs: $value" }
        require(parts[0] in 0L..2L) { "invalid OID first arc: $value" }
        require((parts[0] < 2L && parts[1] in 0L..39L) || (parts[0] == 2L && parts[1] >= 0L)) {
            "invalid OID second arc: $value"
        }
        val out = ByteArrayOutputStream()
        writeBase128(out, parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) writeBase128(out, parts[i])
        return out.toByteArray()
    }

    private fun writeBase128(out: ByteArrayOutputStream, value: Long) {
        require(value >= 0) { "negative OID arc" }
        if (value == 0L) {
            out.write(0)
            return
        }
        var remaining = value
        val bytes = ArrayList<Int>()
        while (remaining > 0) {
            bytes += (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
        }
        for (i in bytes.indices.reversed()) {
            val last = i == 0
            out.write(bytes[i] or if (last) 0 else 0x80)
        }
    }

    private const val TAG_INTEGER = 0x02
    private const val TAG_BIT_STRING = 0x03
    private const val TAG_OCTET_STRING = 0x04
    private const val TAG_NULL = 0x05
    private const val TAG_OID = 0x06
    private const val TAG_UTF8_STRING = 0x0C
    private const val TAG_UTC_TIME = 0x17
    private const val TAG_GENERALIZED_TIME = 0x18
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_SET = 0x31

    private val UTC_FORMAT = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")
        .withZone(ZoneOffset.UTC)
    private val GENERALIZED_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")
        .withZone(ZoneOffset.UTC)
}
