package com.pdfpaper.engine.text

import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream

/**
 * Font objects for text extraction (ISO 32000-1, §9.6 simple fonts, §9.7
 * composite fonts). No glyph rendering here — the [PdfFont] contract gives
 * per-code Unicode text plus advance widths (in 1/1000 em, §9.7.4) which the
 * text extractor uses to position runs.
 *
 * Tolerances (never throw for unknown fonts):
 *  - missing /Widths → 0 advance (standard-14 AFM tables are a T14 candidate),
 *  - missing /Encoding → StandardEncoding (Type1-ish) or WinAnsi (TrueType),
 *  - unsupported /Encoding on a Type0 font → per-CID replacement mapping,
 *  - missing /ToUnicode → Identity-H/V codes read as Unicode (best effort).
 */
sealed interface PdfFont {
    /** /BaseFont name, e.g. "Helvetica" or "ABCDEF+Calibri"; null when absent. */
    val name: String?

    /** True when something was unrecoverable (never fatal, extraction continues). */
    val degraded: Boolean

    /**
     * Embedded font program bytes (/FontFile, /FontFile2, /FontFile3, §9.8)
     * already decoded, or null when the font is not embedded. Rendering uses
     * these when the platform can load the format (T9), otherwise a system
     * font stands in.
     */
    val embeddedProgram: ByteArray? get() = null

    /** What [embeddedProgram] holds: "truetype", "opentype", "cff" or "type1". */
    val embeddedFormat: String? get() = null

    /**
     * Encodes [text] into this font's code bytes (§9.6.6 / §9.7.5), or null
     * when the font has no code for a character. Used by text-run editing (T16):
     * a null result means the replacement must be drawn with a fallback font.
     */
    fun encode(text: String): ByteArray? = null
}

/** Simple one-byte-code font (§9.6): Type1, TrueType, Type3, MMType1, ... */
class PdfSimpleFont internal constructor(
    override val name: String?,
    private val toUnicode: Map<Int, String>?,
    private val differences: Map<Int, String>,
    private val base: Encodings.Base?,
    private val widths1000: Map<Int, Long>,
    override val degraded: Boolean,
    override val embeddedProgram: ByteArray? = null,
    override val embeddedFormat: String? = null
) : PdfFont {

    /** Unicode for a single-byte code; never null, unknown codes yield "". */
    fun decode(code: Int): String {
        val masked = code and 0xFF
        toUnicode?.get(masked)?.let { return it }
        differences[masked]?.let { return it }
        base?.let { return Encodings.decode(it, masked) }
        return Encodings.winAnsi(masked)
    }

    /** Advance width in 1/1000 em for a single-byte code. */
    fun width1000(code: Int): Long = widths1000[code and 0xFF] ?: 0L

    /**
     * Codes this font can actually address. A non-embedded font is assumed to
     * supply the whole encoding (the viewer substitutes a full font, §9.6.2.2);
     * an embedded one is limited to the codes it declares — its /Widths range
     * for a subset, or its /Differences and /ToUnicode otherwise (§9.6.6).
     */
    private val encodableCodes: IntArray by lazy {
        when {
            embeddedProgram == null -> IntArray(256) { it }
            widths1000.isNotEmpty() -> widths1000.keys.toIntArray()
            else -> (differences.keys + (toUnicode?.keys ?: emptySet())).toIntArray()
        }
    }

    /** Reverse of [decode] over [encodableCodes]: Unicode char → first code. */
    private val reverseByChar: Map<Char, Int> by lazy {
        buildMap {
            for (code in encodableCodes) {
                val text = decode(code)
                if (text.length == 1) putIfAbsent(text[0], code and 0xFF)
            }
        }
    }

    override fun encode(text: String): ByteArray? {
        if (text.isEmpty()) return ByteArray(0)
        val out = ByteArray(text.length)
        for (i in text.indices) {
            val code = reverseByChar[text[i]] ?: return null
            out[i] = code.toByte()
        }
        return out
    }
}

/** Composite Type0 font (§9.7.5) with CID encoding (Identity-H/V here). */
class PdfType0Font internal constructor(
    override val name: String?,
    /** Predefined CMap name, e.g. "Identity-H". Null when malformed. */
    val encodingName: String?,
    /** Codes are two-byte big-endian CIDs. */
    private val toUnicode: Map<Int, String>?,
    private val widths1000: Map<Int, Long>,
    private val defaultWidth1000: Long,
    /** True when the predefined CMap is not one we implement. */
    val unsupportedEncoding: Boolean,
    override val degraded: Boolean,
    override val embeddedProgram: ByteArray? = null,
    override val embeddedFormat: String? = null
) : PdfFont {

    /** Splits a string operand into CIDs (two bytes each, big-endian, §9.7.5.2). */
    fun cids(bytes: ByteArray): IntArray =
        IntArray(bytes.size / 2) { i ->
            ((bytes[2 * i].toInt() and 0xFF) shl 8) or (bytes[2 * i + 1].toInt() and 0xFF)
        }

    /** Unicode for a CID. Identity encoding without /ToUnicode reads the CID
     *  itself as a code point (best-effort, common for generated files). */
    fun decode(cid: Int): String = when {
        toUnicode?.get(cid)?.isNotEmpty() == true -> toUnicode.getValue(cid)
        toUnicode?.get(cid) == "" -> "" // mapped to nothing deliberately
       unsupportedEncoding -> "\uFFFD"
        else -> {
            // No /ToUnicode: with Identity-H/V the CID order conventionally
            // matches Unicode BMP; other cases degrade to the replacement char.
            if (toUnicode == null && cid in 0..0xFFFF) cid.toChar().toString() else "\uFFFD"
        }
    }

    /** Advance width in 1/1000 em for a CID (/W array, §9.7.4.1). */
    fun width1000(cid: Int): Long = widths1000[cid] ?: defaultWidth1000

    /** Reverse of [decode] over /ToUnicode: Unicode char → CID. */
    private val reverseByChar: Map<Char, Int> by lazy {
        val map = toUnicode ?: return@lazy emptyMap()
        buildMap {
            for ((cid, text) in map) if (text.length == 1) putIfAbsent(text[0], cid)
        }
    }

    override fun encode(text: String): ByteArray? {
        // A composite font is only rewritten in place when /ToUnicode maps the
        // text back to CIDs and the embedded program supplies the glyphs (T16).
        if (unsupportedEncoding || toUnicode == null || embeddedProgram == null) return null
        if (text.isEmpty()) return ByteArray(0)
        val out = ByteArray(text.length * 2)
        for (i in text.indices) {
            val cid = reverseByChar[text[i]] ?: return null
            if (widths1000.isNotEmpty() && cid !in widths1000) return null
            out[2 * i] = (cid shr 8).toByte()
            out[2 * i + 1] = cid.toByte()
        }
        return out
    }
}

/** Never-crashing placeholder used when a font operand is missing or unusable. */
internal val FALLBACK_FONT: PdfSimpleFont = PdfSimpleFont(
    name = null,
    toUnicode = null,
    differences = emptyMap(),
    base = Encodings.Base.WIN_ANSI,
    widths1000 = emptyMap(),
    degraded = true
)

/** Resolves a font dictionary (or a ref to one) into an extraction [PdfFont]. */
object FontResolver {

    private val SUPPORTED_CID_CMAPS = setOf("Identity-H", "Identity-V")

    /** Names of the predefined CMaps we deliberately do not ship (encoding name kept raw). */
    fun supportsCMap(name: String?): Boolean = name in SUPPORTED_CID_CMAPS

    fun resolve(raw: PdfObject?, doc: PdfDocument): PdfFont? {
        val dict = when (raw) {
            null -> return null
            is PdfRef -> doc.resolve(raw) as? PdfDict
            is PdfDict -> raw
            else -> null
        } ?: return null

        val baseFontName = (dict["BaseFont"] as? PdfName)?.let {
            unescapeSubsetPrefix(it.name)
        }
        val toUnicode = loadToUnicode(dict["ToUnicode"], doc)

        val subtype = (dict["Subtype"] as? PdfName)?.name
        return when (subtype) {
            "Type0" -> type0Font(dict, baseFontName, toUnicode, doc)
            "Type1", "Type3", "TrueType", "MMType1", "CIDFontType0", "CIDFontType2",
            null -> simpleFont(dict, baseFontName, toUnicode, subtype, loadEmbedded(dict, doc))
            else -> simpleFont(dict, baseFontName, toUnicode, subtype, loadEmbedded(dict, doc))
        }
    }

    /**
     * Embedded font program from the font descriptor (§9.8): /FontFile2
     * (TrueType), /FontFile3 (Type1C/CIDFontType0C/OpenType) or /FontFile
     * (Type1). Returns the decoded bytes plus the format tag, or null.
     */
    private fun loadEmbedded(owner: PdfDict, doc: PdfDocument): Pair<ByteArray, String>? {
        val descriptor = asDict(owner["FontDescriptor"], doc) ?: return null
        asStream(descriptor["FontFile2"], doc)?.let { stream ->
            return streamBytes(stream, doc)?.let { bytes -> bytes to "truetype" }
        }
        asStream(descriptor["FontFile3"], doc)?.let { stream ->
            val subtype = (stream.dict["Subtype"] as? PdfName)?.name
            val format = if (subtype == "OpenType") "opentype" else "cff" // Type1C / CIDFontType0C
            return streamBytes(stream, doc)?.let { bytes -> bytes to format }
        }
        asStream(descriptor["FontFile"], doc)?.let { stream ->
            return streamBytes(stream, doc)?.let { bytes -> bytes to "type1" }
        }
        return null
    }

    private fun asDict(obj: PdfObject?, doc: PdfDocument): PdfDict? = when (obj) {
        is PdfDict -> obj
        is PdfRef -> doc.resolve(obj) as? PdfDict
        else -> null
    }

    private fun asStream(obj: PdfObject?, doc: PdfDocument): PdfStream? = when (obj) {
        is PdfStream -> obj
        is PdfRef -> doc.resolve(obj) as? PdfStream
        else -> null
    }

    private fun streamBytes(stream: PdfStream, doc: PdfDocument): ByteArray? =
        stream.decoded { doc.resolve(it) }.getOrNull()?.bytes

    /** "ABCDEF+Calibri" / "AAAAAA+Arial-BoldMT" → "Calibri" / "Arial-BoldMT"? */
    private fun unescapeSubsetPrefix(name: String): String {
        val plus = name.indexOf('+')
        return if (plus in 1..6) name.substring(plus + 1) else name
    }

    private fun loadToUnicode(raw: PdfObject?, doc: PdfDocument): Map<Int, String>? {
        val stream = when (raw) {
            null -> return null
            is PdfRef -> doc.resolve(raw) as? PdfStream
            is PdfStream -> raw
            else -> null
        } ?: return null
        val decoded = stream.decoded { doc.resolve(it) }.getOrNull() ?: return null
        val map = CMapParser.parse(decoded.bytes)
        return if (map.isEmpty()) null else map
    }

    private fun simpleFont(
        dict: PdfDict,
        baseFontName: String?,
        toUnicode: Map<Int, String>?,
        subtype: String?,
        embedded: Pair<ByteArray, String>?
    ): PdfSimpleFont {
        var degraded = false

        // --- Encoding (§9.6.6) ------------------------------------------
        var base: Encodings.Base? = when (subtype) {
            "TrueType" -> Encodings.Base.WIN_ANSI // §9.6.6.2 default for TrueType
            else -> Encodings.Base.STANDARD // §9.6.6.1 default for Type1/Type3
        }
        // An absent /Encoding key: keep the default above. Any /Encoding
        // (name or dict) replaces it; unknown names fall back to Standard.
        var differences = emptyMap<Int, String>()
        when (val enc = dict["Encoding"]) {
            null -> {}
            is PdfName -> {
                base = Encodings.byName(name = enc.name)
                if (enc.name !in setOf("StandardEncoding", "WinAnsiEncoding", "MacRomanEncoding", "PDFDocEncoding")) {
                    degraded = true // e.g. SymbolEncoding: approximated
                }
            }
            is PdfDict -> {
                val baseEnc = (enc["BaseEncoding"] as? PdfName)?.name
                if (baseEnc != null) {
                    base = Encodings.byName(baseEnc)
                    if (baseEnc !in setOf("StandardEncoding", "WinAnsiEncoding", "MacRomanEncoding", "PDFDocEncoding")) {
                        degraded = true
                    }
                }
                differences = parseDifferences(enc["Differences"])
            }
            else -> degraded = true
        }

        // --- Widths (§9.6.2.2): /Widths + /FirstChar ---------------------
        val widths = HashMap<Int, Long>()
        val widthsArr = dict["Widths"] as? PdfArray
        if (widthsArr != null) {
            val firstChar = ((dict["FirstChar"] as? PdfInteger)?.value ?: 0L)
            widthsArr.items.forEachIndexed { i, w ->
                val value = toDouble(w) ?: return@forEachIndexed
                widths[(firstChar + i).toInt() and 0xFF] = value.toLong()
            }
        } else {
            degraded = true // no metrics; advances fall back to 0
        }

        return PdfSimpleFont(
            name = baseFontName,
            toUnicode = toUnicode,
            differences = differences,
            base = base,
            widths1000 = widths,
            degraded = degraded,
            embeddedProgram = embedded?.first,
            embeddedFormat = embedded?.second
        )
    }

    /** /Differences array: [code name name code name ...] → code → Unicode. */
    private fun parseDifferences(raw: PdfObject?): Map<Int, String> {
        val arr = (raw as? PdfArray) ?: return emptyMap()
        val out = HashMap<Int, String>()
        var code = -1
        for (item in arr.items) {
            when (item) {
                is PdfInteger -> code = item.value.toInt()
                is PdfReal -> code = item.value.toInt()
                is PdfName -> {
                    if (code in 0..255) {
                        val uni = Encodings.glyphToUnicode(item.name)
                        out[code] = uni ?: "" // unknown glyph names decode to ""
                    }
                    code++
                }
                else -> {} // tolerate junk entries
            }
        }
        return out
    }

    private fun type0Font(
        dict: PdfDict,
        baseFontName: String?,
        toUnicode: Map<Int, String>?,
        doc: PdfDocument
    ): PdfType0Font {
        val encodingName = (dict["Encoding"] as? PdfName)?.name
        val unsupported = encodingName != null && !supportsCMap(encodingName)

        // /DescendantFonts [ CIDFont ] (§9.7.5.1): widths live on the CIDFont.
        val descendant: PdfDict? = (dict["DescendantFonts"] as? PdfArray)?.items
            ?.firstOrNull()?.let { item ->
                when (item) {
                    is PdfDict -> item
                    is PdfRef -> doc.resolve(item) as? PdfDict
                    else -> null
                }
            }

        val widths = HashMap<Int, Long>()
        if (descendant != null) {
            val wArr = descendant["W"] as? PdfArray
            if (wArr != null) parseW(wArr, widths)
        }
        val dw = descendant?.let { toDouble(it["DW"])?.toLong() } ?: 1000L // §9.7.4.1 default 1000

        val embedded = descendant?.let { loadEmbedded(it, doc) }
        return PdfType0Font(
            name = baseFontName,
            encodingName = encodingName,
            toUnicode = toUnicode,
            widths1000 = widths,
            defaultWidth1000 = if (dw in 0..1_000_000) dw else 1000L,
            unsupportedEncoding = unsupported,
            degraded = descendant == null,
            embeddedProgram = embedded?.first,
            embeddedFormat = embedded?.second
        )
    }

    /** Table 244 W-array tuple forms: scalar-with-list and range-with-width. */
    private fun parseW(arr: PdfArray, out: MutableMap<Int, Long>) {
        val items = arr.items
        var i = 0
        var guard = 0
        while (i < items.size && guard++ < 100_000) {
            val start = toDouble(items[i])?.toInt()
            if (start == null) { i++; continue }
            when (val next = items.getOrNull(i + 1)) {
                is PdfArray -> {
                    var cid = start
                    for (w in next.items) {
                        toDouble(w)?.let { if (cid in 0..0xFFFF) out[cid] = it.toLong() }
                        cid++
                    }
                    i += 2
                }
                else -> {
                    val end = toDouble(next)?.toInt()
                    val w = toDouble(items.getOrNull(i + 2))
                    if (end != null && w != null && start in 0..0xFFFF && start <= end && end - start < 65_536) {
                        for (cid in start..end) out[cid] = w.toLong()
                    }
                    i += 3
                }
            }
        }
    }

    private fun toDouble(obj: PdfObject?): Double? = when (obj) {
        is PdfInteger -> obj.value.toDouble()
        is PdfReal -> obj.value
        else -> null
    }
}
