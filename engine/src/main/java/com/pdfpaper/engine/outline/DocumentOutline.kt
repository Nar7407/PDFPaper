package com.pdfpaper.engine.outline

import com.pdfpaper.engine.links.DestinationResolver
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.PageTreeResult
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pageTree

/**
 * One outline item (ISO 32000-1 §12.3.3) that points at a page of this
 * document. [top] is the destination's top edge in PDF user space when the fit
 * is `/XYZ`; otherwise it is null and the caller starts at the page's top.
 * [depth] is 0 for a top-level item, 1 for its child, and so on.
 */
data class OutlineEntry(
    val title: String,
    val pageIndex: Int,
    val top: Double?,
    val depth: Int
)

/**
 * One outline item as found by [DocumentOutline.diagnose] (T66). [pageIndex]
 * is null when the entry's destination cannot be resolved to a page of this
 * document, which [DocumentOutline.read] silently skips.
 */
data class OutlineDiagnostic(
    val title: String,
    val pageIndex: Int?,
    val depth: Int
)

/**
 * Reads the document outline for the reader's chapter list (T34). The outline
 * is authoritative when it exists; an entry whose destination cannot be
 * resolved to a page of this document is skipped, so a file saved by another
 * tool can never make "next chapter" fail.
 */
object DocumentOutline {

    /** Upper bound on visited items; a cyclic or absurd outline stops here. */
    private const val MAX_ITEMS = 5000

    fun read(document: PdfDocument, maxDepth: Int = 12): List<OutlineEntry> {
        val pages = when (val result = pageTree(document)) {
            is PageTreeResult.Ok -> result.pages
            else -> emptyList()
        }
        if (pages.isEmpty()) return emptyList()
        val catalog = document.resolveRoot() as? PdfDict ?: return emptyList()
        val outlines = resolve(document, catalog["Outlines"]) as? PdfDict ?: return emptyList()
        val resolver = DestinationResolver(document, pages)
        val out = ArrayList<OutlineEntry>()
        val visited = HashSet<Int>()
        var budget = MAX_ITEMS

        // Depth-first in document order: the item, its children, then its next
        // sibling. Iterative so a deep or broken tree cannot overflow the stack.
        fun visit(first: PdfObject?, depth: Int) {
            var current = first
            while (current != null && budget-- > 0) {
                val ref = current as? PdfRef
                if (ref != null && !visited.add(ref.objNum)) return
                val item = resolve(document, current) as? PdfDict ?: return
                val title = titleOf(document, item)?.take(160).orEmpty()
                val action = resolve(document, item["A"]) as? PdfDict
                val destination = resolver.resolve(item["Dest"] ?: action?.get("D"))
                if (title.isNotBlank() && destination != null && destination.pageIndex in pages.indices) {
                    out += OutlineEntry(
                        title = title,
                        pageIndex = destination.pageIndex,
                        top = destination.top,
                        depth = depth
                    )
                }
                if (depth + 1 < maxDepth) visit(item["First"], depth + 1)
                current = item["Next"]
            }
        }

        visit(outlines["First"], 0)
        return out
    }

    /**
     * Every outline item, in outline order, including the ones [read] would
     * skip because their destination is missing (T66). The splitter needs to
     * say *why* a chapter was left out; the reader's resolved list stays as
     * it is.
     */
    fun diagnose(document: PdfDocument, maxDepth: Int = 12): List<OutlineDiagnostic> {
        val pages = documentPages(document)
        if (pages.isEmpty()) return emptyList()
        val catalog = document.resolveRoot() as? PdfDict ?: return emptyList()
        val outlines = resolve(document, catalog["Outlines"]) as? PdfDict ?: return emptyList()
        val resolver = DestinationResolver(document, pages)
        val out = ArrayList<OutlineDiagnostic>()
        val visited = HashSet<Int>()
        var budget = MAX_ITEMS

        fun visit(first: PdfObject?, depth: Int) {
            var current = first
            while (current != null && budget-- > 0) {
                val ref = current as? PdfRef
                if (ref != null && !visited.add(ref.objNum)) return
                val item = resolve(document, current) as? PdfDict ?: return
                val title = titleOf(document, item)?.take(160).orEmpty()
                val action = resolve(document, item["A"]) as? PdfDict
                val destination = resolver.resolve(item["Dest"] ?: action?.get("D"))
                out += OutlineDiagnostic(
                    title = title,
                    pageIndex = destination?.pageIndex?.takeIf { it in pages.indices },
                    depth = depth
                )
                if (depth + 1 < maxDepth) visit(item["First"], depth + 1)
                current = item["Next"]
            }
        }

        visit(outlines["First"], 0)
        return out
    }

    private fun titleOf(document: PdfDocument, item: PdfDict): String? =
        when (val value = resolve(document, item["Title"])) {
            is PdfString -> value.text().trim()
            is PdfName -> value.name.trim()
            else -> null
        }?.takeIf { it.isNotBlank() }

    private fun resolve(document: PdfDocument, obj: PdfObject?): PdfObject? = when (obj) {
        null -> null
        is PdfRef -> document.resolve(obj)
        else -> obj
    }
}

/** The pages of [document] in order, or an empty list when the tree is broken. */
internal fun documentPages(document: PdfDocument): List<PdfPage> =
    when (val result = pageTree(document)) {
        is PageTreeResult.Ok -> result.pages
        else -> emptyList()
    }
