package com.pdfpaper.engine.outline

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfBoolean
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfNull
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.model.parseDirectObject
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.write.PdfDocumentBuilder
import com.pdfpaper.engine.write.PdfWriter
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.max

/**
 * T56: writes the app's bookmarks into the document outline (§12.3.3) as an
 * incremental update, so a reader's own bookmarks panel shows them.
 *
 * Items this app added earlier carry `/PdfPaperBookmark true`. A save rebuilds
 * the top-level list from the document's own items plus the current bookmarks,
 * and rewrites the `/Prev`/`/Next` chain: that is how a bookmark deleted in the
 * hub also disappears from the outline. Items written by the document itself
 * are kept in their previous order and never modified beyond their chain links.
 * An item that is no longer linked simply becomes unreachable.
 */
object PdfOutlines {

    private val MARKER = PdfName("PdfPaperBookmark")

    data class Entry(val title: String, val pageIndex: Int)

    fun applyIncremental(
        document: PdfDocument,
        originalBytes: ByteArray,
        entries: List<Entry>,
        version: String = PdfWriter.DEFAULT_VERSION
    ): Result<ByteArray> = runCatching {
        require(!document.isEncrypted) { "An encrypted document cannot be saved" }
        document.file.startXrefOffset
            ?: error("This file has no startxref; it cannot be saved incrementally")

        val base = document.file
        val pages = document.pages()
        val pageRefs = pages.map { page ->
            PdfRef(page.objNum, base.entry(page.objNum)?.gen ?: 0)
        }
        entries.forEach { entry ->
            require(entry.pageIndex in pageRefs.indices) {
                "Bookmark page ${entry.pageIndex} is out of range"
            }
        }

        val catalogRef = (base.trailer.root as? com.pdfpaper.engine.xref.IndirectRef)
            ?.let { PdfRef(it.objNum, it.gen) }
            ?: throw IllegalStateException("No document catalog; cannot save incrementally")
        val catalog = document.resolve(catalogRef) as? PdfDict
            ?: throw IllegalStateException("The document catalog is not a dictionary")
        val existingOutline = catalog["Outlines"] as? PdfRef

        val existingItems = ArrayList<Pair<PdfRef, PdfDict>>()
        var hadMarked = false
        if (existingOutline != null) {
            val rootDict = document.resolve(existingOutline) as? PdfDict
                ?: throw IllegalStateException("The document outline is not a dictionary")
            var next = rootDict["First"] as? PdfRef
            var guard = 0
            while (next != null && guard++ < 5000) {
                val dict = document.resolve(next) as? PdfDict ?: break
                if (dict["PdfPaperBookmark"] is PdfBoolean) hadMarked = true
                else existingItems += next to dict
                next = dict["Next"] as? PdfRef
            }
        }
        // Nothing to add and nothing of ours to remove: the caller should not
        // have asked, so say so rather than writing an empty revision.
        require(entries.isNotEmpty() || hadMarked) { "There are no bookmarks to write" }

        val startAt = maxOf(base.trailer.size, (base.entries.keys.maxOrNull() ?: 0) + 1)
        val builder = PdfDocumentBuilder(version, document, startAt)
        builder.root = PdfRef(catalogRef.objNum, catalogRef.gen)
        builder.info = base.trailer.raw["Info"]?.let { PdfRef.parse(it) }
        builder.id = base.trailer.raw["ID"]?.let { raw ->
            parseDirectObject(raw.toByteArray(StandardCharsets.ISO_8859_1)).getOrNull() as? PdfArray
        }

        val outlineRef: PdfRef
        if (existingOutline != null) {
            outlineRef = existingOutline
        } else {
            outlineRef = PdfRef(builder.allocate(), 0)
            val newCatalog = LinkedHashMap(catalog.entries)
            newCatalog[PdfName("Outlines")] = outlineRef
            builder.put(catalogRef.objNum, PdfDict(newCatalog))
        }

        // The final flat chain: the document's own items, then the bookmarks.
        data class ChainItem(val ref: PdfRef, val dict: Map<PdfName, PdfObject>)
        val chain = ArrayList<ChainItem>(existingItems.size + entries.size)
        existingItems.forEach { (ref, dict) -> chain += ChainItem(ref, dict.entries) }
        entries.forEach { entry ->
            val ref = PdfRef(builder.allocate(), 0)
            val item = LinkedHashMap<PdfName, PdfObject>()
            item[PdfName("Title")] = textString(entry.title)
            item[PdfName("Parent")] = outlineRef
            item[PdfName("Dest")] = PdfArray(
                listOf(pageRefs[entry.pageIndex], PdfName("XYZ"), PdfNull, PdfNull, PdfNull)
            )
            item[MARKER] = PdfBoolean(true)
            chain += ChainItem(ref, item)
        }

        chain.forEachIndexed { index, item ->
            val updated = LinkedHashMap(item.dict)
            updated[PdfName("Parent")] = outlineRef
            if (index > 0) updated[PdfName("Prev")] = chain[index - 1].ref
            else updated.remove(PdfName("Prev"))
            if (index < chain.lastIndex) updated[PdfName("Next")] = chain[index + 1].ref
            else updated.remove(PdfName("Next"))
            builder.put(item.ref.objNum, PdfDict(updated))
        }

        val rootDict = LinkedHashMap<PdfName, PdfObject>()
        rootDict[PdfName("Type")] = PdfName("Outlines")
        if (chain.isEmpty()) {
            rootDict[PdfName("Count")] = PdfInteger(0)
        } else {
            rootDict[PdfName("First")] = chain.first().ref
            rootDict[PdfName("Last")] = chain.last().ref
            rootDict[PdfName("Count")] = PdfInteger(chain.size.toLong())
        }
        builder.put(outlineRef.objNum, PdfDict(rootDict))

        builder.buildIncremental(originalBytes)
    }

    private fun textString(text: String): PdfString {
        val latin = text.toByteArray(StandardCharsets.ISO_8859_1)
        if (String(latin, StandardCharsets.ISO_8859_1) == text) return PdfString(latin)
        val out = ByteArrayOutputStream()
        out.write(0xFE)
        out.write(0xFF)
        out.write(text.toByteArray(StandardCharsets.UTF_16BE))
        return PdfString(out.toByteArray())
    }
}
