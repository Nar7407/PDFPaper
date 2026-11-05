package com.pdfpaper.engine.write

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream

/**
 * In-memory target of a write operation: allocates object numbers and holds the
 * objects that will make up the new file. Object 0 is reserved (the free head
 * of the cross-reference table, §7.5.4).
 *
 * Object numbers are handed out monotonically, so a caller that needs a forward
 * reference (a page's `/Parent`, the catalog's `/Pages`) can [allocate] the
 * number first, use the returned [PdfRef] while copying, and [put] the body
 * afterwards. [build] serialises everything with [PdfWriter].
 */
class PdfDocumentBuilder(
    val version: String = PdfWriter.DEFAULT_VERSION,
    /**
     * An existing document whose objects can be read through [get] (T55). Only
     * objects written with [put]/[add] are part of [entries]; [buildIncremental]
     * appends exactly those, so a seeded builder never copies the whole file.
     */
    val source: PdfDocument? = null,
    startObjectNumber: Int = 1
) {

    private val objects = LinkedHashMap<Int, PdfObject>()
    private var next = startObjectNumber.coerceAtLeast(1)

    /** Trailer /Root; must be set before [build]. */
    var root: PdfRef? = null

    /** Trailer /Info; optional. */
    var info: PdfRef? = null

    /** Trailer /ID (two byte strings); optional. */
    var id: PdfArray? = null

    /** Highest object number in use plus one (§7.5.5 /Size). */
    val size: Int get() = next

    /** Reserves the next object number without storing a body yet. */
    fun allocate(): Int = next++

    /** Stores [obj] as indirect object [num] (allocating a gap if needed). */
    fun put(num: Int, obj: PdfObject) {
        require(num >= 1) { "Object numbers must be >= 1" }
        objects[num] = obj
        if (num >= next) next = num + 1
    }

    /** Stores [obj] under a fresh number and returns its reference. */
    fun add(obj: PdfObject): PdfRef {
        val num = allocate()
        put(num, obj)
        return PdfRef(num, 0)
    }

    fun addStream(dict: PdfDict, data: ByteArray): PdfRef = add(PdfStream(dict, data))

    /**
     * The object stored by this builder, or (for a seeded builder) the object
     * read from the source document. New object numbers never collide with the
     * source, so a miss is simply absent.
     */
    fun get(num: Int): PdfObject? = objects[num] ?: source?.deref(num)

    /** Objects this builder wrote; the incremental update is exactly this map. */
    fun entries(): Map<Int, PdfObject> = objects

    /** True when [num] was written in this builder (and must be appended). */
    fun isDirty(num: Int): Boolean = objects.containsKey(num)

    /**
     * The dirty objects as incremental-update records. Existing object numbers
     * keep their source generation; new ones use generation 0.
     */
    fun updates(): List<IndirectObject> = objects.map { (num, obj) ->
        IndirectObject(num, source?.file?.entry(num)?.gen ?: 0, obj)
    }

    /**
     * Drops every body that cannot be reached from the trailer roots. Object
     * numbers are not compacted (the cross-reference table simply has free
     * entries), so references allocated earlier stay valid.
     *
     * This is used by privacy-sensitive rewrites: annotations, pages and
     * resources that were removed from the visible graph must not survive as
     * unreachable payload in the file.
     */
    fun pruneUnreachable(): Int {
        val rootRef = root ?: return 0
        val before = objects.size
        val reachable = HashSet<Int>()
        val stack = ArrayDeque<Int>()

        fun mark(ref: PdfRef) {
            if (reachable.add(ref.objNum)) stack.addLast(ref.objNum)
        }

        fun visit(obj: PdfObject?) {
            when (obj) {
                null -> {}
                is PdfRef -> mark(obj)
                is PdfArray -> for (item in obj.items) visit(item)
                is PdfDict -> for (value in obj.entries.values) visit(value)
                is PdfStream -> for (value in obj.dict.entries.values) visit(value)
                else -> {}
            }
        }

        mark(rootRef)
        info?.let { mark(it) }
        id?.let { visit(it) }
        while (stack.isNotEmpty()) visit(objects[stack.removeLast()])
        objects.keys.retainAll(reachable)
        return before - objects.size
    }

    /**
     * Serialises only the dirty objects as an incremental update (§7.5.6) on
     * top of [originalBytes]. The source document must have a usable startxref;
     * files recovered by a rebuild scan cannot be chained safely.
     */
    fun buildIncremental(originalBytes: ByteArray): ByteArray {
        val seeded = source ?: error("An incremental build needs a source document")
        val rootRef = root ?: error("Cannot build a PDF without a /Root")
        val prevStart = seeded.file.startXrefOffset
            ?: error("This file has no startxref; it cannot be saved incrementally")
        require(objects.isNotEmpty()) { "An incremental save needs at least one changed object" }
        val highest = maxOf(
            seeded.file.trailer.size,
            next,
            (objects.keys.maxOrNull() ?: 0) + 1
        )
        return PdfWriter.append(
            originalBytes,
            prevStart,
            updates(),
            rootRef,
            info,
            id,
            highest
        )
    }

    /** Serialises the document; throws if /Root was never set. */
    fun build(): ByteArray {
        val rootRef = root ?: error("Cannot build a PDF without a /Root")
        return PdfWriter.write(
            objects.map { (num, obj) -> IndirectObject(num, 0, obj) },
            rootRef,
            info,
            id,
            version
        )
    }
}
