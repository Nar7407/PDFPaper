package com.pdfpaper.engine.model

import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.io.RandomAccessSource
import com.pdfpaper.engine.security.PdfPermissions
import com.pdfpaper.engine.security.StandardSecurityHandler
import com.pdfpaper.engine.xref.EntryKind
import com.pdfpaper.engine.xref.PdfFile
import java.io.Closeable

/**
 * Document-level object access over a [PdfFile].
 *
 * Indirect objects are parsed lazily on first use and cached; reference chains
 * (including cycles) always terminate: cycles resolve to null. Object streams
 * (§7.5.7) are unpacked on demand, decoding their filter chain (§7.4); a stream
 * that cannot be decoded resolves to null.
 *
 * When the file is encrypted (§7.6), [open] installs a
 * [StandardSecurityHandler] and every string and stream is decrypted as its
 * object is first loaded, so the rest of the engine only ever sees plaintext.
 */
class PdfDocument private constructor(val file: PdfFile) : Closeable {

    private val cache = HashMap<Int, PdfObject?>()
    private val resolving = HashSet<Int>()

    /**
     * Object streams (§7.5.7) decoded once and indexed by object number, so a
     * document with thousands of compressed objects never re-inflates the
     * containing stream per object. The value for a stream that failed to load
     * is the empty sentinel, which stops retries on corrupt input.
     */
    private val objectStreams = HashMap<Int, ObjectStreamData>()
    private val objectStreamsLoading = HashSet<Int>()

    /** Non-null when the document is encrypted; installed by [open]. */
    internal var security: StandardSecurityHandler? = null
        private set

    /** True when the document is encrypted (§7.6). */
    val isEncrypted: Boolean get() = security != null

    /** `/P` permissions, or null when the document is not encrypted. */
    val permissions: PdfPermissions? get() = security?.permissions

    /** Raw stored (and decrypted) object for [objNum] (may itself be a [PdfRef]); null when missing. */
    fun deref(objNum: Int): PdfObject? {
        if (cache.containsKey(objNum)) return cache[objNum]
        if (!resolving.add(objNum)) {
            cache[objNum] = null
            return null
        }
        try {
            val entry = file.entry(objNum) ?: return storeNull(objNum)
            val obj = when (entry.kind) {
                EntryKind.FREE -> null
                EntryKind.UNCOMPRESSED -> file.readObject(objNum)?.let { bytes ->
                    parseIndirectObject(bytes).getOrNull()?.obj
                }
                EntryKind.COMPRESSED -> readFromObjectStream(entry.streamObjNum, entry.streamIndex, objNum)
            }
            // Objects stored in an object stream are already plaintext (the
            // stream was decrypted as a whole); only uncompressed objects get
            // their strings and stream data decrypted here.
            val decrypted = if (entry.kind == EntryKind.UNCOMPRESSED) {
                decryptIfNeeded(obj, objNum, entry.gen)
            } else {
                obj
            }
            cache[objNum] = decrypted
            return decrypted
        } finally {
            resolving.remove(objNum)
        }
    }

    private fun decryptIfNeeded(obj: PdfObject?, objNum: Int, gen: Int): PdfObject? {
        val handler = security ?: return obj
        if (obj == null || objNum == handler.encryptObjNum) return obj
        return handler.decrypt(obj, objNum, gen)
    }

    /** Follows [ref] (and any chained refs) to a concrete object; null on cycles or misses. */
    fun resolve(ref: PdfRef): PdfObject? {
        val visited = HashSet<Int>()
        visited += ref.objNum
        var current: PdfObject? = deref(ref.objNum) ?: return null
        while (current is PdfRef) {
            if (!visited.add(current.objNum)) return null
            current = deref(current.objNum) ?: return null
        }
        return current
    }

    /** Catalog object from the trailer /Root (§7.5.5), or null. Rebuilt files
     *  get a recovered /Root via [PdfFile] (catalog scan). */
    fun resolveRoot(): PdfObject? {
        val root = file.trailer.root ?: return null
        return resolve(PdfRef(root.objNum, root.gen))
    }

    /**
     * The trailer dictionary as objects (T66): `/Info`, `/Root`, `/Prev` and
     * every other entry of the file trailer, parsed once. Callers that report
     * on a written file (the split report) need `/Info`; the reader itself uses
     * [file] directly.
     */
    fun trailer(): PdfDict {
        val entries = LinkedHashMap<PdfName, PdfObject>()
        for ((key, raw) in file.trailer.raw) {
            val name = PdfName(key)
            val parsed = PdfRef.parse(raw)
                ?: raw.trim().toLongOrNull()?.let { PdfInteger(it) }
                ?: continue
            entries[name] = parsed
        }
        return PdfDict(entries)
    }

    private fun storeNull(objNum: Int): PdfObject? {
        cache[objNum] = null
        return null
    }

    private fun readFromObjectStream(streamObjNum: Int, index: Int, wantObjNum: Int): PdfObject? {
        val data = objectStreams.getOrPut(streamObjNum) {
            loadObjectStream(streamObjNum) ?: EMPTY_OBJECT_STREAM
        }
        // The xref's (stream, index) must agree with the stream's own header;
        // the whole stream was parsed at once, so every member is now cached.
        if (index < 0 || index >= data.objNums.size || data.objNums[index] != wantObjNum) return null
        return cache[wantObjNum]
    }

    /**
     * Decodes and parses an object stream once (§7.5.7), caching every contained
     * object. Object streams are normally Flate-compressed; a failed decode
     * (unknown filter, corrupt data) yields null so callers treat the objects as
     * missing. Re-entrancy is guarded because the stream object itself is loaded
     * through [deref].
     */
    private fun loadObjectStream(streamObjNum: Int): ObjectStreamData? {
        if (!objectStreamsLoading.add(streamObjNum)) return null
        try {
            val stream = deref(streamObjNum) as? PdfStream ?: return null
            val data = stream.decoded { ref -> resolve(ref) }.getOrNull()?.bytes ?: return null
            val count = (stream.dict["N"] as? PdfInteger)?.value?.toIntOrNull() ?: return null
            val first = (stream.dict["First"] as? PdfInteger)?.value?.toIntOrNull() ?: return null
            if (count <= 0 || first < 0 || first > data.size) return null
            val header = data.copyOfRange(0, first).toString(Charsets.ISO_8859_1)
            val numbers = header.trim().split(WHITESPACE).map { it.toIntOrNull() ?: return null }
            if (numbers.size != count * 2) return null
            val objNums = IntArray(count)
            for (i in 0 until count) {
                val objNum = numbers[i * 2]
                objNums[i] = objNum
                val start = first + numbers[i * 2 + 1]
                val end = if (i + 1 < count) first + numbers[i * 2 + 3] else data.size
                // One malformed member must not deny the others: skip it (it
                // resolves to null) and keep loading the rest.
                if (start < first || end < start || end > data.size) continue
                val obj = parseDirectObject(data.copyOfRange(start, end)).getOrNull() ?: continue
                cache[objNum] = obj
            }
            return ObjectStreamData(objNums)
        } finally {
            objectStreamsLoading.remove(streamObjNum)
        }
    }

    override fun close() = file.close()

    companion object {
        /**
         * Opens [source]. When the file is encrypted (§7.6), [password]
         * (default empty) is used to authenticate; a wrong password fails with
         * [com.pdfpaper.engine.security.PdfPasswordException] so callers can
         * prompt instead of crashing.
         */
        fun open(source: RandomAccessSource, password: String = ""): Result<PdfDocument> =
            runCatching {
                val file = PdfFile.open(source).getOrThrow()
                val document = PdfDocument(file)
                try {
                    document.security = StandardSecurityHandler.create(file, document, password)
                } catch (t: Throwable) {
                    document.close()
                    throw t
                }
                document
            }
    }
}

/** Object numbers of one object stream, in xref-index order (§7.5.7). */
private class ObjectStreamData(val objNums: IntArray)

private val EMPTY_OBJECT_STREAM = ObjectStreamData(IntArray(0))

private val WHITESPACE = Regex("\\s+")

private fun Long.toIntOrNull(): Int? =
    if (this < Int.MIN_VALUE || this > Int.MAX_VALUE) null else toInt()
