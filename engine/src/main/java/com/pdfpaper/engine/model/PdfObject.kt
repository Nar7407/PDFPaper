package com.pdfpaper.engine.model

import java.nio.charset.StandardCharsets

/**
 * PDF data objects (ISO 32000-1, §7.3).
 *
 * Text-carrying [PdfString] keeps raw bytes: literal strings may hold binary
 * data, and decoding (PDFDoc/UTF-16) is refined in T7 (§9). Use [PdfString.text]
 * for a best-effort decode.
 */
sealed interface PdfObject

data object PdfNull : PdfObject

data class PdfBoolean(val value: Boolean) : PdfObject

data class PdfInteger(val value: Long) : PdfObject

data class PdfReal(val value: Double) : PdfObject

/** Name object (§7.3.5); [name] is decoded (`#` escapes applied). */
data class PdfName(val name: String) : PdfObject

/** Literal or hexadecimal string (§7.3.4); content compared by bytes. */
class PdfString(val bytes: ByteArray) : PdfObject {
    constructor(text: String) : this(text.toByteArray(StandardCharsets.ISO_8859_1))

    /** Best-effort decode: UTF-16BE after a BOM, else byte-preserving Latin-1. */
    fun text(): String {
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        return String(bytes, StandardCharsets.ISO_8859_1)
    }

    override fun equals(other: Any?): Boolean =
        other is PdfString && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "PdfString(${text()})"
}

/** Array object (§7.3.7). */
class PdfArray(val items: List<PdfObject>) : PdfObject {
    override fun equals(other: Any?): Boolean =
        other is PdfArray && items == other.items

    override fun hashCode(): Int = items.hashCode()

    override fun toString(): String = "PdfArray($items)"
}

/** Dictionary object (§7.3.7), keyed by decoded name. */
class PdfDict(val entries: Map<PdfName, PdfObject>) : PdfObject {
    operator fun get(name: String): PdfObject? = entries[PdfName(name)]

    override fun equals(other: Any?): Boolean =
        other is PdfDict && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "PdfDict($entries)"
}

/**
 * Stream object (§7.3.8): [dict] plus raw (still encoded) [data].
 * Filter decoding arrives in T5 (§7.4).
 */
class PdfStream(val dict: PdfDict, val data: ByteArray) : PdfObject {
    override fun equals(other: Any?): Boolean =
        other is PdfStream && dict == other.dict && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * dict.hashCode() + data.contentHashCode()

    override fun toString(): String = "PdfStream(dict=$dict, ${data.size} bytes)"
}

/** Indirect reference "<objNum> <gen> R" (§7.3.10). */
data class PdfRef(val objNum: Int, val gen: Int) : PdfObject {
    companion object {
        fun parse(raw: String): PdfRef? {
            val parts = raw.trim().split(Regex("\\s+"))
            if (parts.size != 3 || parts[2] != "R") return null
            val num = parts[0].toIntOrNull() ?: return null
            val gen = parts[1].toIntOrNull() ?: return null
            return PdfRef(num, gen)
        }
    }
}
