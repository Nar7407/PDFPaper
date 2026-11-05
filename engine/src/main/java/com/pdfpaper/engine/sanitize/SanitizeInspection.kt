package com.pdfpaper.engine.sanitize

import com.pdfpaper.engine.form.PdfWidget
import com.pdfpaper.engine.form.formFields
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages

internal const val MAX_SANITIZE_DEPTH = 64

internal fun PdfDocument.resolveObject(obj: PdfObject?): PdfObject? = when (obj) {
    null -> null
    is PdfRef -> resolve(obj)
    else -> obj
}

internal fun PdfDocument.resolveDict(obj: PdfObject?): PdfDict? =
    resolveObject(obj) as? PdfDict

internal fun PdfDocument.resolveArray(obj: PdfObject?): PdfArray? =
    resolveObject(obj) as? PdfArray

internal class PageAnnotation(
    val pageIndex: Int,
    val ref: PdfRef?,
    val dict: PdfDict
) {
    val subtype: String? get() = (dict["Subtype"] as? PdfName)?.name
    val isWidget: Boolean get() = subtype == "Widget"
    val isLink: Boolean get() = subtype == "Link"
}

internal fun PdfDocument.pageAnnotations(): List<PageAnnotation> {
    val out = ArrayList<PageAnnotation>()
    for (page in pages()) {
        val array = resolveArray(page.dict["Annots"]) ?: continue
        for (item in array.items) {
            val dict = resolveDict(item) ?: continue
            out += PageAnnotation(page.index, item as? PdfRef, dict)
        }
    }
    return out
}

/** Widgets grouped by the zero-based page that owns their /Rect. */
internal fun PdfDocument.widgetsByPage(): Map<Int, List<PdfWidget>> {
    val annotPage = HashMap<Int, Int>()
    for (annotation in pageAnnotations()) {
        val num = annotation.ref?.objNum ?: continue
        annotPage[num] = annotation.pageIndex
    }
    val out = HashMap<Int, MutableList<PdfWidget>>()
    for (field in formFields()) {
        for (widget in field.widgets) {
            val pageIndex = widget.pageIndex ?: widget.ref?.objNum?.let { annotPage[it] } ?: continue
            out.getOrPut(pageIndex) { ArrayList() } += widget
        }
    }
    return out
}

internal fun PdfDocument.formFieldCount(): Int = formFields().size

internal fun countMetadata(document: PdfDocument): Int {
    var count = 0
    if (document.file.trailer.raw["Info"] != null) count++
    val catalog = document.resolveRoot() as? PdfDict
    if (catalog?.get("Metadata") != null) count++
    return count
}

internal fun countNamedDestinations(document: PdfDocument): Int {
    val catalog = document.resolveRoot() as? PdfDict ?: return 0
    var count = 0
    (document.resolveObject(catalog["Dests"]) as? PdfDict)?.let { count += it.entries.size }
    count += countNameTreeLeaves(document, "Dests")
    return count
}

internal fun countEmbeddedFiles(document: PdfDocument): Int {
    var count = countNameTreeLeaves(document, "EmbeddedFiles")
    val catalog = document.resolveRoot() as? PdfDict
    count += countAssociatedFiles(document, catalog?.get("AF"))
    for (page in document.pages()) count += countAssociatedFiles(document, page.dict["AF"])
    for (annotation in document.pageAnnotations()) count += countAssociatedFiles(document, annotation.dict["AF"])
    return count
}

private fun countAssociatedFiles(document: PdfDocument, obj: PdfObject?): Int {
    val array = document.resolveArray(obj) ?: return 0
    return array.items.size
}

/** Counts leaf value pairs in a name tree (§7.9.6). */
internal fun countNameTreeLeaves(document: PdfDocument, key: String): Int {
    val catalog = document.resolveRoot() as? PdfDict ?: return 0
    val names = document.resolveDict(catalog["Names"]) ?: return 0
    val root = names[key] ?: return 0
    return countNameTreeNode(document, root, HashSet(), 0)
}

private fun countNameTreeNode(
    document: PdfDocument,
    nodeObj: PdfObject,
    seen: MutableSet<Int>,
    depth: Int
): Int {
    if (depth > MAX_SANITIZE_DEPTH) return 0
    val ref = nodeObj as? PdfRef
    if (ref != null && !seen.add(ref.objNum)) return 0
    val node = document.resolveDict(nodeObj) ?: return 0
    val kids = document.resolveArray(node["Kids"])
    if (kids != null) return kids.items.sumOf { countNameTreeNode(document, it, seen, depth + 1) }
    val names = document.resolveArray(node["Names"]) ?: return 0
    return names.items.size / 2
}

internal fun countBookmarks(document: PdfDocument): Int {
    val catalog = document.resolveRoot() as? PdfDict ?: return 0
    val root = document.resolveDict(catalog["Outlines"]) ?: return 0
    return countOutlineSiblings(document, root["First"], 0, HashSet())
}

private fun countOutlineSiblings(
    document: PdfDocument,
    first: PdfObject?,
    depth: Int,
    seen: MutableSet<Int>
): Int {
    if (depth > MAX_SANITIZE_DEPTH) return 0
    var count = 0
    var current = first
    while (current != null) {
        val ref = current as? PdfRef
        if (ref != null && !seen.add(ref.objNum)) break
        val item = document.resolveDict(current) ?: break
        count++
        count += countOutlineSiblings(document, item["First"], depth + 1, seen)
        current = item["Next"]
    }
    return count
}

internal fun countThumbnails(document: PdfDocument): Int =
    document.pages().count { it.dict["Thumb"] != null }

internal fun countActions(document: PdfDocument): Int {
    var count = 0
    val seen = HashSet<Int>()

    fun action(value: PdfObject?): Int {
        val ref = value as? PdfRef
        if (ref != null && !seen.add(ref.objNum)) return 0
        return if (document.resolveDict(value) != null) 1 else 0
    }

    fun actionSlots(dict: PdfDict?): Int {
        if (dict == null) return 0
        var n = 0
        if (dict["A"] != null) n += action(dict["A"])
        if (dict["AA"] != null) n += action(dict["AA"])
        return n
    }

    val catalog = document.resolveRoot() as? PdfDict
    if (catalog != null) {
        if (catalog["OpenAction"] != null) count += action(catalog["OpenAction"])
        if (catalog["AA"] != null) count += action(catalog["AA"])
    }
    for (page in document.pages()) {
        if (page.dict["AA"] != null) count += action(page.dict["AA"])
    }
    for (annotation in document.pageAnnotations()) count += actionSlots(annotation.dict)
    count += countNameTreeLeaves(document, "JavaScript")
    if (catalog != null) {
        val outlines = document.resolveDict(catalog["Outlines"])
        count += countOutlineActions(document, outlines?.get("First"), 0, HashSet())
    }
    for (field in document.formFields()) {
        count += actionSlots(field.dict)
        for (widget in field.widgets) count += actionSlots(widget.dict)
    }
    return count
}

private fun countOutlineActions(
    document: PdfDocument,
    first: PdfObject?,
    depth: Int,
    seen: MutableSet<Int>
): Int {
    if (depth > MAX_SANITIZE_DEPTH) return 0
    var count = 0
    var current = first
    while (current != null) {
        val ref = current as? PdfRef
        if (ref != null && !seen.add(ref.objNum)) break
        val item = document.resolveDict(current) ?: break
        if (item["A"] != null) count++
        if (item["AA"] != null) count++
        count += countOutlineActions(document, item["First"], depth + 1, seen)
        current = item["Next"]
    }
    return count
}

/** Action-like keys, used when deciding whether an annotation must be dropped. */
internal fun hasActions(dict: PdfDict): Boolean = dict["A"] != null || dict["AA"] != null

internal fun hasEmbeddedFile(dict: PdfDict): Boolean = dict["AF"] != null

internal fun widgetIsVisible(widget: PdfWidget): Boolean {
    val flags = (widget.dict["F"] as? PdfInteger)?.value?.toInt() ?: 0
    // Invisible (bit 1), Hidden (bit 2) and NoView (bit 6) all keep the widget
    // out of normal on-screen output.
    return flags and 1 == 0 && flags and 2 == 0 && flags and 32 == 0
}

internal fun textString(value: PdfObject?): String? = when (value) {
    is PdfString -> value.text()
    is PdfName -> value.name
    else -> null
}

/** A stream that can be used as a page or form content stream. */
internal fun PdfDocument.streamOf(obj: PdfObject?): PdfStream? =
    resolveObject(obj) as? PdfStream
