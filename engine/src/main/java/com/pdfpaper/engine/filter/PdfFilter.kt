package com.pdfpaper.engine.filter

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfNull
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfParseException
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream

/**
 * Decoded stream payload: [bytes] plus non-fatal [warnings].
 *
 * Decoding is tolerant by design (ISO 32000-1 leaves error handling to
 * readers): truncated or corrupt input yields whatever decoded so far plus a
 * warning describing the problem, instead of throwing.
 */
class DecodedBytes(val bytes: ByteArray, val warnings: List<String>) {
    override fun equals(other: Any?): Boolean =
        other is DecodedBytes && bytes.contentEquals(other.bytes) && warnings == other.warnings

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + warnings.hashCode()

    override fun toString(): String = "DecodedBytes(${bytes.size} bytes, warnings=$warnings)"
}

/** One stream filter (ISO 32000-1, §7.4). Implementations decode eagerly. */
sealed interface PdfFilter {
    val names: Set<String>

    /** Decodes [data] with optional decode parameters; corrupt input yields partial bytes + warning. */
    fun decode(data: ByteArray, params: PdfDict?): DecodedBytes
}

private val FILTERS: List<PdfFilter> by lazy {
    listOf(AsciiHexFilter, Ascii85Filter, LzwFilter, FlateFilter, RunLengthFilter)
}

private val BY_NAME: Map<String, PdfFilter> by lazy {
    FILTERS.flatMap { filter -> filter.names.map { it to filter } }.toMap()
}

/**
 * Decodes a stream through its /Filter chain (§7.4.2), applying /DecodeParms
 * (or /DP) per filter. A single dict applies to the first filter; an array
 * aligns with the chain (missing entries and unresolvable references mean
 * "no parameters"). Unknown filters fail the whole decode.
 *
 * Streams stay encoded until this is called (lazy), and each stage only holds
 * its input plus output (bounded; see [MAX_OUTPUT]).
 */
fun PdfStream.decoded(resolve: (PdfRef) -> PdfObject? = { null }): Result<DecodedBytes> = runCatching {
    val filterObj = dict["Filter"] ?: return@runCatching DecodedBytes(data, emptyList())
    val names = when (filterObj) {
        is PdfName -> listOf(filterObj.name)
        is PdfArray -> filterObj.items.map {
            (it as? PdfName)?.name ?: throw PdfParseException("/Filter chain entries must be names")
        }
        else -> throw PdfParseException("/Filter must be a name or an array of names")
    }
    if (names.isEmpty()) return@runCatching DecodedBytes(data, emptyList())
    val paramObj = dict["DecodeParms"] ?: dict["DP"]
    val params: List<PdfDict?> = when (paramObj) {
        null, is PdfNull -> List(names.size) { null }
        is PdfDict -> listOf(paramObj) + List(maxOf(0, names.size - 1)) { null }
        is PdfRef -> listOf(resolveDict(paramObj, resolve)) + List(maxOf(0, names.size - 1)) { null }
        is PdfArray -> names.indices.map { i ->
            when (val entry = paramObj.items.getOrNull(i)) {
                null, is PdfNull -> null
                is PdfDict -> entry
                is PdfRef -> resolveDict(entry, resolve)
                else -> throw PdfParseException("DecodeParms entries must be dictionaries")
            }
        }
        else -> throw PdfParseException("/DecodeParms must be a dictionary or an array")
    }
    var bytes = data
    val warnings = ArrayList<String>()
    for ((index, name) in names.withIndex()) {
        val filter = BY_NAME[name] ?: throw PdfParseException("Unsupported filter: $name")
        val decoded = filter.decode(bytes, params[index])
        bytes = decoded.bytes
        decoded.warnings.mapTo(warnings) { "$name: $it" }
    }
    DecodedBytes(bytes, warnings)
}

private fun resolveDict(ref: PdfRef, resolve: (PdfRef) -> PdfObject?): PdfDict? =
    resolve(ref) as? PdfDict

/** Integer decode parameter with a default (§7.4.2, Tables 8-9). */
internal fun intParam(params: PdfDict?, key: String, default: Int): Int =
    (params?.get(key) as? PdfInteger)?.value?.toIntOrNull() ?: default

private fun Long.toIntOrNull(): Int? =
    if (this < Int.MIN_VALUE || this > Int.MAX_VALUE) null else toInt()

/** Safety bound for the unbounded decoders (flate/LZW); exceeding stops with a warning. */
internal const val MAX_OUTPUT = 256 * 1024 * 1024
