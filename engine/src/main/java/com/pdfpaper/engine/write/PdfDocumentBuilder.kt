package com.pdfpaper.engine.write

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
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
class PdfDocumentBuilder(val version: String = PdfWriter.DEFAULT_VERSION) {

    private val objects = LinkedHashMap<Int, PdfObject>()
    private var next = 1

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

    fun get(num: Int): PdfObject? = objects[num]

    fun entries(): Map<Int, PdfObject> = objects

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
