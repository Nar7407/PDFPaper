package com.pdfpaper.engine.write

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfNull
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream

/**
 * Copies an object graph from a source [PdfDocument] into a [target] builder,
 * giving every referenced indirect object a fresh number (§7.3.10 references).
 *
 * Two properties make the copy safe to use for page operations:
 *
 * - **Sharing is preserved.** Every source object number is copied at most
 *   once ([memo]), so a resource or annotation referenced from several places
 *   stays a single target object. This also breaks cycles: the target number is
 *   reserved and memoised *before* the body is copied, so an object that
 *   refers back to itself resolves to its own new reference.
 * - **Known remaps win.** [mapKnown] lets the caller decide up front what a
 *   given source object number should become in the target — typically a
 *   source page mapped to its imported page, or `null` for a page that the
 *   operation dropped. A copy that runs into such a reference returns the
 *   mapped reference (or drops the value), never the original subtree.
 *
 * A reference whose value cannot be resolved copies as a dropped value (`null`)
 * rather than dragging in a broken subtree; callers delete the owning entry.
 */
class PdfObjectCopier(
    private val source: PdfDocument,
    private val target: PdfDocumentBuilder
) {

    /** source object number -> target reference (not yet necessarily stored). */
    private val memo = HashMap<Int, PdfRef>()

    /** Caller-supplied remaps: value null means "this object is dropped". */
    private val known = HashMap<Int, PdfRef?>()

    fun mapKnown(sourceObjNum: Int, targetRef: PdfRef?) {
        known[sourceObjNum] = targetRef
    }

    /** Raw source object for [objNum] (may be a reference); null when missing. */
    fun deref(objNum: Int): PdfObject? = source.deref(objNum)

    /** Follows a reference/value chain to a concrete object; null on miss/cycle. */
    fun resolve(obj: PdfObject): PdfObject? =
        if (obj is PdfRef) source.resolve(obj) else obj

    /**
     * Copies indirect object [objNum] into the target; null when it maps to a
     * dropped object, is a dangling reference, or cannot be resolved.
     * [skipKeys] is applied to the copied body's dictionary (see [copyValue]).
     */
    fun copyIndirect(objNum: Int, skipKeys: Set<String> = emptySet()): PdfRef? {
        if (known.containsKey(objNum)) return known[objNum]
        memo[objNum]?.let { return it }
        val raw = source.deref(objNum) ?: return null
        val obj = if (raw is PdfRef) (source.resolve(raw) ?: return null) else raw
        val num = target.allocate()
        val ref = PdfRef(num, 0)
        memo[objNum] = ref
        target.put(num, copyValue(obj, skipKeys) ?: PdfNull)
        return ref
    }

    /** Target reference [objNum] was already copied to, if any. */
    fun copiedRef(objNum: Int): PdfRef? = memo[objNum]

    /** Caller-supplied remap for [objNum]; null when unknown or dropped. */
    fun mappedRef(objNum: Int): PdfRef? = known[objNum]

    /**
     * Reserves a target reference for [objNum] without copying its body yet
     * (existing copies are returned as-is). Used by callers that need a forward
     * reference to an object they will fill in later.
     */
    fun reserveCopy(objNum: Int): PdfRef {
        memo[objNum]?.let { return it }
        val ref = PdfRef(target.allocate(), 0)
        memo[objNum] = ref
        return ref
    }

    /**
     * Copies a direct object. A [PdfDict] skips the names in [skipKeys] — used
     * to cut structural links (a page's `/Parent`, an outline item's chain
     * pointers) that are rebuilt by the caller. [skipKeys] applies only to the
     * dictionary it is passed with, not to nested dictionaries.
     */
    fun copyValue(obj: PdfObject, skipKeys: Set<String> = emptySet()): PdfObject? = when (obj) {
        is PdfRef -> copyIndirect(obj.objNum)
        is PdfArray -> PdfArray(obj.items.mapNotNull { copyValue(it) })
        is PdfDict -> copyDict(obj, skipKeys)
        is PdfStream -> PdfStream(copyDict(obj.dict) ?: PdfDict(emptyMap()), obj.data)
        else -> obj
    }

    /** Copies a dictionary, dropping [skipKeys] and any value that cannot be copied. */
    fun copyDict(dict: PdfDict, skipKeys: Set<String> = emptySet()): PdfDict? {
        val out = LinkedHashMap<PdfName, PdfObject>()
        for ((key, value) in dict.entries) {
            if (key.name in skipKeys) continue
            val copied = copyValue(value) ?: continue
            out[key] = copied
        }
        return PdfDict(out)
    }
}
