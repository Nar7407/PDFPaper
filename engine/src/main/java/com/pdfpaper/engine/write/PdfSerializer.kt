package com.pdfpaper.engine.write

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfBoolean
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInlineImage
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfNull
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.model.PdfString
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.charset.StandardCharsets

/**
 * Serialises the object model back to PDF syntax (ISO 32000-1, §7.3).
 *
 * Byte-preserving where it matters: a [PdfStream]'s `/Length` is rewritten to
 * the exact stored byte count and its raw (still encoded) bytes are written
 * unchanged — filters are never decoded and re-encoded, so no quality or size
 * is lost. Strings stay literal unless escaping would be ambiguous; names get
 * `#XX` escapes only for bytes that are not regular characters (§7.3.5).
 *
 * Numbers never use exponent notation and integral doubles lose the trailing
 * ".0"; non-finite values are written as 0 so a bad upstream value cannot
 * produce a file other viewers choke on.
 */
object PdfSerializer {

    /** Serialises one object to its direct byte form. */
    fun toBytes(obj: PdfObject): ByteArray {
        val out = ByteArrayOutputStream()
        writeObject(obj, out)
        return out.toByteArray()
    }

    /** Serialises one object to [out] (used to build indirect object bodies). */
    fun writeObject(obj: PdfObject, out: ByteArrayOutputStream) {
        when (obj) {
            is PdfNull -> ascii("null", out)
            is PdfBoolean -> ascii(if (obj.value) "true" else "false", out)
            is PdfInteger -> ascii(obj.value.toString(), out)
            is PdfReal -> ascii(formatReal(obj.value), out)
            is PdfName -> writeName(obj.name, out)
            is PdfString -> writeString(obj.bytes, out)
            is PdfArray -> writeArray(obj, out)
            is PdfDict -> writeDict(obj.entries, out)
            is PdfStream -> writeStream(obj, out)
            is PdfRef -> ascii("${obj.objNum} ${obj.gen} R", out)
            is PdfInlineImage -> throw IllegalArgumentException(
                "Inline images live inside content streams, not in the object graph"
            )
        }
    }

    // -- Containers -----------------------------------------------------------

    private fun writeArray(array: PdfArray, out: ByteArrayOutputStream) {
        ascii("[", out)
        for ((i, item) in array.items.withIndex()) {
            if (i > 0) ascii(" ", out)
            writeObject(item, out)
        }
        ascii("]", out)
    }

    private fun writeDict(entries: Map<PdfName, PdfObject>, out: ByteArrayOutputStream) {
        // "<<" then " /Key value" pairs then " >>"; the leading spaces guarantee
        // adjacent tokens never merge (§7.2.2).
        ascii("<<", out)
        for ((key, value) in entries) {
            ascii(" ", out)
            writeName(key.name, out)
            ascii(" ", out)
            writeObject(value, out)
        }
        ascii(" >>", out)
    }

    private fun writeStream(stream: PdfStream, out: ByteArrayOutputStream) {
        // §7.3.8.2: /Length must be the exact number of bytes between the
        // "stream" keyword's EOL and the "endstream" keyword. Rewrite it rather
        // than trusting a possibly wrong or indirect value in the source dict.
        val entries = LinkedHashMap<PdfName, PdfObject>(stream.dict.entries)
        entries[PdfName("Length")] = PdfInteger(stream.data.size.toLong())
        writeDict(entries, out)
        ascii("\nstream\n", out)
        out.write(stream.data)
        ascii("\nendstream", out)
    }

    // -- Names and strings ----------------------------------------------------

    /**
     * Writes `/Name` (§7.3.5). Regular characters (0x21..0x7E except the
     * delimiters and `#`) are written literally; anything else becomes `#XX`.
     */
    fun writeName(name: String, out: ByteArrayOutputStream) {
        ascii("/", out)
        val bytes = name.toByteArray(StandardCharsets.ISO_8859_1)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x21..0x7E && c !in DELIMITERS) {
                out.write(c)
            } else {
                ascii("#%02X".format(c), out)
            }
        }
    }

    private val DELIMITERS = charArrayOf('(', ')', '<', '>', '[', ']', '{', '}', '/', '%', '#').map { it.code }.toSet()

    /**
     * Writes a string. Printable bytes go out as a literal string (§7.3.4.2)
     * with the required escapes; any byte outside 0x20..0x7E makes the whole
     * string hexadecimal (`<...>`, §7.3.4.3) so no control byte lands in the
     * file. Both forms round-trip to the exact same bytes.
     */
    fun writeString(bytes: ByteArray, out: ByteArrayOutputStream) {
        val literal = bytes.all { val c = it.toInt() and 0xFF; c in 0x20..0x7E }
        if (!literal) {
            out.write('<'.code)
            for (b in bytes) ascii("%02X".format(b.toInt() and 0xFF), out)
            out.write('>'.code)
            return
        }
        out.write('('.code)
        for (b in bytes) {
            when (b.toInt() and 0xFF) {
                0x5C -> ascii("\\\\", out) // backslash
                0x28 -> ascii("\\(", out) // (
                0x29 -> ascii("\\)", out) // )
                else -> out.write(b.toInt() and 0xFF)
            }
        }
        out.write(')'.code)
    }

    // -- Numbers --------------------------------------------------------------

    /**
     * Compact real formatting: no exponent, at most six decimals, trailing
     * zeros stripped; integral values lose the fraction. 0 for NaN/infinity.
     */
    fun formatReal(value: Double): String {
        if (!value.isFinite()) return "0"
        val rounded = Math.round(value * 1_000_000.0) / 1_000_000.0
        if (rounded == 0.0) return "0"
        if (rounded == Math.floor(rounded) && Math.abs(rounded) < 1e15) {
            return rounded.toLong().toString()
        }
        return BigDecimal.valueOf(rounded).stripTrailingZeros().toPlainString()
    }

    private fun ascii(s: String, out: ByteArrayOutputStream) {
        out.write(s.toByteArray(StandardCharsets.US_ASCII))
    }
}
