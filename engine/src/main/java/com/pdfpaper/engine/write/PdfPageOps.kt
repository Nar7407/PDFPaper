package com.pdfpaper.engine.write

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.parseDirectObject
import com.pdfpaper.engine.page.pages
import java.nio.charset.StandardCharsets

/**
 * Page-level operations that write a new PDF: rotate, delete, reorder, insert,
 * extract, merge and split (ISO 32000-1, §7.7 page tree, §7.5 file structure).
 *
 * Every operation is a thin wrapper over [assemble], which imports an ordered
 * list of pages into a fresh document and preserves links, bookmarks and form
 * fields that still point at a kept page (see [PdfDocumentAssembler]). All
 * functions return a [Result] and never throw on malformed input.
 */
object PdfPageOps {

    /**
     * Writes a new document containing [selections] in order. [primary] supplies
     * the document /Info, /ID, catalog extras (outlines, named destinations,
     * AcroForm) and is the document whose pages are kept as reference targets.
     */
    fun assemble(
        selections: List<PdfPageSelection>,
        primary: PdfDocument = selections.firstOrNull()?.document
            ?: throw IllegalArgumentException("No pages selected"),
        version: String = PdfWriter.DEFAULT_VERSION
    ): Result<ByteArray> = runCatching {
        PdfDocumentAssembler(selections, primary, version).assemble()
    }

    /** Adds [degrees] clockwise rotation to each page in [pages] (§14.11.2). */
    fun rotate(document: PdfDocument, pages: List<Int>, degrees: Int): Result<ByteArray> {
        val rotated = pages.toSet()
        val selections = document.pages().indices.map { index ->
            PdfPageSelection(document, index, if (index in rotated) degrees else 0)
        }
        return assemble(selections, document)
    }

    /** Removes [pages] from the document. */
    fun delete(document: PdfDocument, pages: List<Int>): Result<ByteArray> = runCatching {
        val count = document.pages().size
        val remove = pages.toSet()
        val selections = (0 until count)
            .filter { it !in remove }
            .map { PdfPageSelection(document, it) }
        PdfDocumentAssembler(selections, document, PdfWriter.DEFAULT_VERSION).assemble()
    }

    /** Rebuilds the document from [order], a permutation/subset of page indices. */
    fun reorder(document: PdfDocument, order: List<Int>): Result<ByteArray> = assemble(
        order.map { PdfPageSelection(document, it) },
        document
    )

    /** Keeps only [pages], in the given order. */
    fun extract(document: PdfDocument, pages: List<Int>): Result<ByteArray> =
        assemble(pages.map { PdfPageSelection(document, it) }, document)

    /** Keeps the half-open page range [from] until [until]. */
    fun extract(document: PdfDocument, from: Int, until: Int): Result<ByteArray> {
        val count = document.pages().size
        val start = from.coerceIn(0, count)
        val end = until.coerceIn(start, count)
        return assemble((start until end).map { PdfPageSelection(document, it) }, document)
    }

    /** Inserts [sourcePages] of [source] before page [at] of [document]. */
    fun insert(
        document: PdfDocument,
        source: PdfDocument,
        at: Int,
        sourcePages: List<Int> = source.pages().indices.toList()
    ): Result<ByteArray> = runCatching {
        val count = document.pages().size
        val position = at.coerceIn(0, count)
        val selections = ArrayList<PdfPageSelection>()
        for (i in 0 until position) selections += PdfPageSelection(document, i)
        for (p in sourcePages) selections += PdfPageSelection(source, p)
        for (i in position until count) selections += PdfPageSelection(document, i)
        PdfDocumentAssembler(selections, document, PdfWriter.DEFAULT_VERSION).assemble()
    }

    /** Concatenates every page of [documents] in order. */
    fun merge(documents: List<PdfDocument>): Result<ByteArray> = runCatching {
        require(documents.isNotEmpty()) { "Nothing to merge" }
        val selections = ArrayList<PdfPageSelection>()
        for (doc in documents) {
            for (index in doc.pages().indices) selections += PdfPageSelection(doc, index)
        }
        PdfDocumentAssembler(selections, documents.first(), PdfWriter.DEFAULT_VERSION).assemble()
    }

    /** Splits the document into parts of at most [everyN] pages each. */
    fun split(document: PdfDocument, everyN: Int): Result<List<ByteArray>> = runCatching {
        require(everyN > 0) { "everyN must be positive" }
        val count = document.pages().size
        val parts = ArrayList<List<Int>>()
        var start = 0
        while (start < count) {
            val end = minOf(start + everyN, count)
            parts += (start until end).toList()
            start = end
        }
        parts.map { part ->
            PdfDocumentAssembler(part.map { PdfPageSelection(document, it) }, document, PdfWriter.DEFAULT_VERSION)
                .assemble()
        }
    }

    /** Splits the document by inclusive [ranges] (each becomes one output file). */
    fun split(document: PdfDocument, ranges: List<IntRange>): Result<List<ByteArray>> = runCatching {
        val count = document.pages().size
        ranges.map { range ->
            val start = range.first.coerceIn(0, count)
            val end = (range.last + 1).coerceIn(start, count)
            val selections = (start until end).map { PdfPageSelection(document, it) }
            PdfDocumentAssembler(selections, document, PdfWriter.DEFAULT_VERSION).assemble()
        }
    }

    /**
     * Rotation-only incremental save (§7.5.6): copies [originalBytes] unchanged
     * and appends just the rotated page dictionaries plus a chained
     * cross-reference section. Requires a document opened from a file that has a
     * usable startxref.
     */
    fun rotateIncremental(
        document: PdfDocument,
        originalBytes: ByteArray,
        pages: List<Int>,
        degrees: Int
    ): Result<ByteArray> = runCatching {
        val start = document.file.startXrefOffset
            ?: throw IllegalStateException("This file has no startxref; cannot save incrementally")
        val pageList = document.pages()
        val updates = pages.map { index ->
            val page = pageList.getOrNull(index)
                ?: throw IllegalArgumentException("Page $index is out of range")
            require(page.objNum >= 1) { "Page $index has no object number" }
            val gen = document.file.entry(page.objNum)?.gen?.takeIf { it >= 0 } ?: 0
            val entries = LinkedHashMap(page.dict.entries)
            entries[PdfName("Rotate")] =
                PdfInteger((((page.rotate + degrees) % 360) + 360) % 360.toLong())
            IndirectObject(page.objNum, gen, PdfDict(entries))
        }
        val root = document.file.trailer.root
            ?: throw IllegalStateException("No document catalog; cannot save incrementally")
        val maxObj = maxOf(
            document.file.entries.keys.maxOrNull() ?: 0,
            updates.maxOf { it.num }
        )
        val size = maxOf(document.file.trailer.size, maxObj + 1)
        PdfWriter.append(
            originalBytes,
            start,
            updates,
            PdfRef(root.objNum, root.gen),
            infoRef(document),
            idArray(document),
            size
        )
    }

    private fun infoRef(document: PdfDocument): PdfRef? =
        document.file.trailer.raw["Info"]?.let { PdfRef.parse(it) }

    private fun idArray(document: PdfDocument): PdfArray? {
        val raw = document.file.trailer.raw["ID"] ?: return null
        val parsed = parseDirectObject(raw.toByteArray(StandardCharsets.ISO_8859_1)).getOrNull()
        return parsed as? PdfArray
    }
}
