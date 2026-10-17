package com.pdfpaper.engine.xref

import com.pdfpaper.engine.io.RandomAccessSource
import java.io.Closeable
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Reference to an indirect object, "<objNum> <gen> R" (ISO 32000-1, §7.3.10).
 */
data class IndirectRef(val objNum: Int, val gen: Int)

/** How an object is stored (§7.5.4, §7.5.8). */
enum class EntryKind {
    FREE,
    UNCOMPRESSED,
    COMPRESSED
}

/**
 * One cross-reference entry.
 *
 * For [COMPRESSED], [offset] is unused; [streamObjNum]/[streamIndex] locate the
 * containing object stream (resolved in T4, §7.5.7).
 */
data class XRefEntry(
    val objNum: Int,
    val gen: Int,
    val offset: Long,
    val kind: EntryKind,
    val streamObjNum: Int = -1,
    val streamIndex: Int = -1
)

/**
 * File trailer (§7.5.5). [raw] holds every trailer-dictionary entry as raw text
 * keyed without the leading slash (e.g. "ID", "Info"); [size], [root] and
 * [prev] are the typed views used by the parser.
 */
data class Trailer(
    val size: Int,
    val root: IndirectRef?,
    val prev: Long?,
    val raw: Map<String, String>
)

/**
 * Low-level PDF file handle: header version, trailer and object lookup by number.
 *
 * Open with [open], which never throws for malformed input: problems are
 * reported as a failed [Result]. The [RandomAccessSource] stays open for lazy
 * [readObject] lookups; close this file (which closes the source) when done.
 * On open failure the source is closed automatically.
 */
class PdfFile private constructor(
    val headerVersion: String,
    val trailer: Trailer,
    val entries: Map<Int, XRefEntry>,
    val rebuiltFromScan: Boolean,
    private val source: RandomAccessSource
) : Closeable {

    /** Number of entries in the merged cross-reference table. */
    val objectCount: Int get() = entries.size

    fun entry(objNum: Int): XRefEntry? = entries[objNum]

    /**
     * Raw bytes of indirect object [objNum], from "<num> <gen> obj" up to (not
     * including) "endobj". Returns null for free, compressed (until T4),
     * missing or unreadable objects; never throws for malformed data.
     */
    fun readObject(objNum: Int): ByteArray? {
        val ref = entries[objNum] ?: return null
        if (ref.kind != EntryKind.UNCOMPRESSED) return null
        return try {
            readObjectBytes(ref)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun readObjectBytes(ref: XRefEntry): ByteArray? {
        if (!matchesObjHeader(source.read(ref.offset, HEAD_CHECK), ref.objNum, ref.gen)) {
            return null
        }
        var cursor = ref.offset
        var carry = ByteArray(0)
        val total = source.size
        while (cursor < total) {
            val chunk = source.read(cursor, OBJECT_CHUNK)
            if (chunk.isEmpty()) break
            val buf = if (carry.isEmpty()) chunk else carry + chunk
            val idx = indexOfEndObj(buf)
            if (idx >= 0) {
                val length = (cursor - carry.size + idx - ref.offset).toInt()
                if (length < 0) return null
                return source.read(ref.offset, length)
            }
            carry = buf.copyOfRange(maxOf(0, buf.size - ENDOBJ_KEEP), buf.size)
            cursor += chunk.size
            if (chunk.size < OBJECT_CHUNK) break
        }
        return null
    }

    override fun close() = source.close()

    companion object {
        private const val HEAD_CHECK = 64
        private const val OBJECT_CHUNK = 65536
        private const val ENDOBJ_KEEP = 16
        private const val MAX_CHAIN = 32

        fun open(source: RandomAccessSource): Result<PdfFile> {
            val result = runCatching { openInternal(source) }
            if (result.isFailure) {
                try {
                    source.close()
                } catch (_: Exception) {
                    // Ignore close errors after a failed open.
                }
            }
            return result
        }

        private fun openInternal(source: RandomAccessSource): PdfFile {
            if (source.size <= 0) throw IOException("Empty input")
            // §7.5.2 File Header.
            val header = XRefParser.findHeaderVersion(source)
                ?: throw IOException("Missing %PDF- header")
            // §7.5.5 File Trailer via last startxref (§7.5.6 incremental updates
            // append newer sections, so the last one wins).
            val start = XRefParser.findStartXref(source)
            if (start != null) {
                parseChain(source, start)?.let { (entries, trailer) ->
                    return PdfFile(header, trailer, entries, false, source)
                }
            }
            // Missing or broken xref: rebuild by scanning for "N G obj".
            val rebuilt = XRefParser.rebuildScan(source)
            if (rebuilt.isEmpty()) throw IOException("No cross-reference table and no objects found")
            val maxObj = rebuilt.keys.max()
            val trailer = Trailer(maxObj + 1, findCatalogRef(source, rebuilt), null, emptyMap())
            return PdfFile(header, trailer, rebuilt, true, source)
        }

        /** Cap on bytes read per object when hunting for the catalog after a rebuild. */
        private const val CATALOG_WINDOW = 8192
        private val CATALOG_PATTERN = Regex("(?i)/Type\\s*/Catalog")

        /**
         * /Root recovery for rebuilt files (§7.5.5): the scan produces no
         * trailer, so search the recovered objects for the document catalog
         * dictionary ("/Type /Catalog", §7.7.2; first match wins). Null when
         * none exists — page tree navigation then reports zero pages.
         */
        private fun findCatalogRef(
            source: RandomAccessSource,
            entries: Map<Int, XRefEntry>
        ): IndirectRef? {
            for (num in entries.keys.sorted()) {
                val entry = entries.getValue(num)
                if (entry.kind != EntryKind.UNCOMPRESSED) continue
                val len = minOf(source.size - entry.offset, CATALOG_WINDOW.toLong()).toInt()
                if (len <= 0) continue
                val head = try {
                    source.read(entry.offset, len)
                } catch (_: Exception) {
                    continue
                }
                if (CATALOG_PATTERN.containsMatchIn(head.toString(StandardCharsets.ISO_8859_1))) {
                    return IndirectRef(num, entry.gen)
                }
            }
            return null
        }

        /** Walks the /Prev chain (§7.5.6); newer entries win. Null = latest section broken. */
        private fun parseChain(
            source: RandomAccessSource,
            start: Long
        ): Pair<Map<Int, XRefEntry>, Trailer>? {
            var offset: Long? = start
            var depth = 0
            val merged = LinkedHashMap<Int, XRefEntry>()
            val trailers = mutableListOf<Trailer>()
            while (offset != null && depth < MAX_CHAIN) {
                val section = XRefParser.parseSectionAt(source, offset)
                if (section == null || !XRefParser.validateEntries(source, section.entries)) {
                    // Latest section broken -> full rebuild; older link broken ->
                    // keep the newer entries parsed so far.
                    if (depth == 0) return null
                    break
                }
                for ((num, entry) in section.entries) {
                    merged.putIfAbsent(num, entry)
                }
                trailers += section.trailer
                val prev = section.trailer.prev
                offset = if (prev != null && prev >= 0 && prev != offset) prev else null
                depth++
            }
            if (trailers.isEmpty()) return null
            val latest = trailers.first()
            val maxObj = merged.keys.maxOrNull() ?: -1
            val size = if (latest.size > 0) latest.size else maxObj + 1
            val root = trailers.mapNotNull { it.root }.firstOrNull()
            return merged to Trailer(size, root, latest.prev, latest.raw)
        }
    }
}
