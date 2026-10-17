package com.pdfpaper.engine.page

import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream

/**
 * Document catalog and page tree (ISO 32000-1, §7.7).
 *
 * Traversal is tolerant: missing /Count, broken nodes, and reference cycles
 * yield whatever pages could be reached (leaf order preserved), never an
 * exception. Page bounds stop runaway trees.
 */
internal const val MAX_PAGES = 100_000
internal const val MAX_TREE_DEPTH = 64

/** Decoded page content (§7.8.1): concatenated streams plus non-fatal warnings. */
class PageContents(val bytes: ByteArray, val warnings: List<String>)

/**
 * One page (§7.7.3.3) with attributes resolved through the /Parent chain
 * (§7.7.3.4, Table 30): a value lives on the object itself or on the nearest
 * ancestor that defines it.
 */
class PdfPage internal constructor(
    val index: Int,
    val objNum: Int,
    val dict: PdfDict,
    private val doc: PdfDocument
) {

    /** MediaBox; inherited when the page omits it. Null only if no ancestor has one. */
    val mediaBox: PdfArray? = inherited("MediaBox") as? PdfArray

    /** CropBox (§14.11.2); defaults to MediaBox when absent. */
    val cropBox: PdfArray? = (inherited("CropBox") as? PdfArray) ?: mediaBox

    /**
     * /Rotate in degrees clockwise (§14.11.2), normalized to 0..359; 0 by
     * default and for non-integer/dangling values.
     */
    val rotate: Int =
        ((inherited("Rotate") as? PdfInteger)?.value?.toIntOrNull() ?: 0).let { ((it % 360) + 360) % 360 }

    /** Resource dictionary (§7.8.3); inherited from the nearest ancestor. */
    val resources: PdfDict? = inherited("Resources") as? PdfDict

    /**
     * Content (§7.8.1): a single /Contents stream or an array of streams,
     * resolved, decoded through their filters, and concatenated in order.
     * Streams are joined with "\n" — legal whitespace that prevents tokens
     * merging at split boundaries. Success(null) = page has no content.
     */
    fun contents(): Result<PageContents?> = runCatching {
        val raw = dict["Contents"] ?: return@runCatching null
        val streams = when (val obj = resolveValue(raw)) {
            null -> return@runCatching null
            is PdfStream -> listOf(obj)
            is PdfArray -> obj.items.mapNotNull { resolveValue(it) as? PdfStream }
            else -> return@runCatching null
        }
        if (streams.isEmpty()) return@runCatching null
        val out = java.io.ByteArrayOutputStream()
        val warnings = ArrayList<String>()
        for (stream in streams) {
            stream.decoded { ref -> doc.resolve(ref) }.fold(
                onSuccess = {
                    if (out.size() > 0) out.write('\n'.code)
                    out.write(it.bytes)
                    if (it.warnings.isNotEmpty()) warnings += it.warnings
                },
                onFailure = { warnings += "content stream failed: ${it.message}" }
            )
        }
        PageContents(out.toByteArray(), warnings)
    }

    /** Follows the /Parent chain upward until [key] is found (cycle-safe). */
    private fun inherited(key: String): PdfObject? {
        var node: PdfDict? = dict
        val visited = HashSet<Int>()
        while (node != null) {
            node.entries[PdfName(key)]?.let { return it }
            val parentRef = node["Parent"] as? PdfRef ?: return null
            if (!visited.add(parentRef.objNum)) return null // /Parent cycle
            node = doc.resolve(parentRef) as? PdfDict
        }
        return null
    }

    private fun resolveValue(value: PdfObject): PdfObject? = when (value) {
        is PdfRef -> doc.resolve(value)
        else -> value
    }
}

/**
 * Page tree (§7.7.3.2): flattens nested /Kids into pages in document order.
 * A missing or unusable /Root /Pages yields zero pages.
 */
sealed class PageTreeResult {
    class Ok(val pages: List<PdfPage>) : PageTreeResult()
    object NoRoot : PageTreeResult()
    object Broken : PageTreeResult()
}

private enum class NodeKind { PAGE, NODE }

private fun kindOf(node: PdfDict): NodeKind {
    when ((node["Type"] as? PdfName)?.name) {
        "Page" -> return NodeKind.PAGE
        "Pages" -> return NodeKind.NODE
    }
    // Missing /Type (tolerance): dictionaries with /Kids are nodes.
    return if (node["Kids"] != null) NodeKind.NODE else NodeKind.PAGE
}

/**
 * Walks the page tree of [doc]. Returns [PageTreeResult.Ok] on any usable
 * tree (even zero pages), [PageTreeResult.NoRoot] when the file has no
 * catalog/pages pointer at all, and [PageTreeResult.Broken] when the /Pages
 * node exists but cannot be traversed.
 */
fun pageTree(doc: PdfDocument): PageTreeResult {
    val catalog = doc.resolveRoot() as? PdfDict ?: return PageTreeResult.NoRoot
    val pagesObj = catalog["Pages"] ?: return PageTreeResult.NoRoot
    val rootNum = (pagesObj as? PdfRef)?.objNum
    val root = (if (pagesObj is PdfRef) doc.resolve(pagesObj) else pagesObj) as? PdfDict
        ?: return PageTreeResult.Broken

    val pages = ArrayList<PdfPage>(64)
    val visited = HashSet<Int>()
    rootNum?.let { visited += it }
    walk(root, rootNum, visited, doc, pages, 0)
    return PageTreeResult.Ok(pages)
}

private fun walk(
    node: PdfDict,
    nodeNum: Int?,
    visited: HashSet<Int>,
    doc: PdfDocument,
    out: ArrayList<PdfPage>,
    depth: Int
) {
    if (depth > MAX_TREE_DEPTH || out.size >= MAX_PAGES) return
    when (kindOf(node)) {
        NodeKind.PAGE -> out += PdfPage(out.size, nodeNum ?: -1, node, doc)
        NodeKind.NODE -> for (item in kids(node)) {
            val childDict: PdfDict
            val childNum: Int?
            when (val raw = item) {
                is PdfRef -> {
                    if (!visited.add(raw.objNum)) continue // shared/cyclic node: skip
                    childNum = raw.objNum
                    childDict = doc.resolve(raw) as? PdfDict ?: continue
                }
                is PdfDict -> {
                    childNum = null
                    childDict = raw
                }
                else -> continue
            }
            walk(childDict, childNum, visited, doc, out, depth + 1)
        }
    }
}

/** /Kids entries as-is (refs or direct dicts); anything else is skipped. */
private fun kids(node: PdfDict): List<PdfObject> {
    return (node["Kids"] as? PdfArray)?.items ?: emptyList()
}

// Document-level convenience API (kept out of PdfDocument to avoid a
// model -> page dependency).

/** Flattened pages in document order; empty when the file has no usable tree. */
fun PdfDocument.pages(): List<PdfPage> =
    (pageTree(this) as? PageTreeResult.Ok)?.pages ?: emptyList()

/** Zero-based page access (§7.7.3); null beyond the tree. */
fun PdfDocument.page(index: Int): PdfPage? = pages().getOrNull(index)

/** Page count via tree traversal — correct even when /Count is missing or wrong. */
fun PdfDocument.pageCount(): Int = pages().size

private fun Long.toIntOrNull(): Int? =
    if (this < Int.MIN_VALUE || this > Int.MAX_VALUE) null else toInt()
