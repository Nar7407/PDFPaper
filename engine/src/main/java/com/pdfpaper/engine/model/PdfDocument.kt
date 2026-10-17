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

    private fun storeNull(objNum: Int): PdfObject? {
        cache[objNum] = null
        return null
    }

    private fun readFromObjectStream(streamObjNum: Int, index: Int, wantObjNum: Int): PdfObject? {
        val stream = deref(streamObjNum) as? PdfStream ?: return null
        // Object streams are normally Flate-compressed (§7.5.7). Decode through
        // the filter chain; a failed decode (unknown filter, corrupt data) just
        // yields null so the caller treats the object as missing.
        val data = stream.decoded { ref -> resolve(ref) }.getOrNull()?.bytes ?: return null
        val count = (stream.dict["N"] as? PdfInteger)?.value?.toIntOrNull() ?: return null
        val first = (stream.dict["First"] as? PdfInteger)?.value?.toIntOrNull() ?: return null
        if (count <= 0 || first < 0 || first > data.size) return null
        if (index < 0 || index >= count) return null
        val header = data.copyOfRange(0, first).toString(Charsets.ISO_8859_1)
        val numbers = header.trim().split(Regex("\\s+")).map { it.toIntOrNull() ?: return null }
        if (numbers.size != count * 2) return null
        val pairs = numbers.chunked(2).map { it[0] to it[1] }
        if (pairs[index].first != wantObjNum) return null
        val start = first + pairs[index].second
        val end = if (index + 1 < count) first + pairs[index + 1].second else data.size
        if (start < first || end < start || end > data.size) return null
        return parseDirectObject(data.copyOfRange(start, end)).getOrNull()
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

private fun Long.toIntOrNull(): Int? =
    if (this < Int.MIN_VALUE || this > Int.MAX_VALUE) null else toInt()
