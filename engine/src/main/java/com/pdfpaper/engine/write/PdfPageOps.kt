package com.pdfpaper.engine.write

import com.pdfpaper.engine.annot.AnnotationSpec
import com.pdfpaper.engine.annot.PdfComments
import com.pdfpaper.engine.annot.ReplySpec
import com.pdfpaper.engine.annot.StatusSpec
import com.pdfpaper.engine.annot.comments
import com.pdfpaper.engine.form.FormFiller
import com.pdfpaper.engine.geom.PdfRect
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

    /**
     * Writes a new document with [annotations] added on top of the existing ones
     * (ISO 32000-1, §12.5). The selection keeps every page in order; annotations
     * are appended to their page's /Annots with fresh appearance streams.
     */
    fun applyAnnotations(
        document: PdfDocument,
        annotations: List<AnnotationSpec>,
        version: String = PdfWriter.DEFAULT_VERSION
    ): Result<ByteArray> = edit(document, annotations, emptyMap(), version = version)

    /**
     * Fills AcroForm fields, by fully-qualified name, and writes appearance
     * streams (§12.7.4.4, §12.5.5).
     */
    fun fillForm(
        document: PdfDocument,
        values: Map<String, String>,
        version: String = PdfWriter.DEFAULT_VERSION
    ): Result<ByteArray> = edit(document, emptyList(), values, version = version)

    /**
     * One save that both adds annotations and fills form fields (T12), and can
     * drop annotations (T22). Any list/map may be empty. [droppedAnnots] holds
     * source object numbers of annotations to remove together with everything
     * that refers to them (popups, replies, state changes); the output is a
     * fresh classic-xref file that keeps links, bookmarks and the rest of the
     * AcroForm intact.
     */
    fun edit(
        document: PdfDocument,
        annotations: List<AnnotationSpec> = emptyList(),
        formValues: Map<String, String> = emptyMap(),
        droppedAnnots: Set<Int> = emptySet(),
        version: String = PdfWriter.DEFAULT_VERSION
    ): Result<ByteArray> = runCatching {
        val selections = document.pages().indices.map { PdfPageSelection(document, it) }
        PdfDocumentAssembler(selections, document, version, annotations, formValues, emptyList(), droppedAnnots)
            .assemble()
    }

    /**
     * Incremental variant of [edit] (T55, §7.5.6): appends new annotation and
     * form objects plus the rewritten page dictionaries to [originalBytes].
     * Existing pages, resources, links, outlines and earlier revisions stay
     * byte-for-byte untouched, so annotations, comments, stamps and form values
     * can be saved without rewriting the document (and without invalidating a
     * signature that covers the earlier bytes).
     *
     * Callers must pass the exact bytes the document was opened from. Files
     * recovered by a rebuild scan have no startxref and fail with a clear
     * message; use [edit] for those.
     */
    fun editIncremental(
        document: PdfDocument,
        originalBytes: ByteArray,
        annotations: List<AnnotationSpec> = emptyList(),
        formValues: Map<String, String> = emptyMap(),
        droppedAnnots: Set<Int> = emptySet(),
        version: String = PdfWriter.DEFAULT_VERSION
    ): Result<ByteArray> = runCatching {
        require(!document.isEncrypted) { "An encrypted document cannot be saved" }
        document.file.startXrefOffset
            ?: error("This file has no startxref; it cannot be saved incrementally")
        val pages = document.pages()
        val dropped = if (droppedAnnots.isEmpty()) {
            emptySet()
        } else {
            PdfComments.deletionClosureFor(document, droppedAnnots.toList()).getOrThrow()
        }
        validateTargets(document, annotations)

        val base = document.file
        val startAt = maxOf(base.trailer.size, (base.entries.keys.maxOrNull() ?: 0) + 1)
        val builder = PdfDocumentBuilder(version, document, startAt)
        val root = base.trailer.root
            ?: throw IllegalStateException("No document catalog; cannot save incrementally")
        builder.root = PdfRef(root.objNum, root.gen)
        builder.info = infoRef(document)
        builder.id = idArray(document)

        val writer = com.pdfpaper.engine.annot.AnnotationWriter(builder)
        val pagesWithDropped = pages.indices.filter { index ->
            val page = pages[index]
            pageAnnots(document, page.dict["Annots"]).any { item ->
                (item as? PdfRef)?.objNum in dropped
            }
        }.toSet()
        val pageIndices = (annotations.map { it.pageIndex } + pagesWithDropped).toSortedSet()

        for (pageIndex in pageIndices) {
            val page = pages.getOrNull(pageIndex)
                ?: throw IllegalArgumentException("Page $pageIndex is out of range")
            val pageRef = PdfRef(page.objNum, base.entry(page.objNum)?.gen ?: 0)
            val existing = pageAnnots(document, page.dict["Annots"])
            val kept = existing.filter { item ->
                val ref = item as? PdfRef
                ref == null || ref.objNum !in dropped
            }
            val added = ArrayList<PdfRef>()
            for (spec in annotations.filter { it.pageIndex == pageIndex }) {
                added += writer.build(spec, pageRef, PdfRect.of(page.cropBox)) { num ->
                    sourceAnnotRef(document, num, dropped)
                }
            }
            if (added.isEmpty() && kept.size == existing.size) continue
            val entries = LinkedHashMap(page.dict.entries)
            val all = kept + added
            if (all.isEmpty()) entries.remove(PdfName("Annots"))
            else entries[PdfName("Annots")] = PdfArray(all)
            builder.put(page.objNum, PdfDict(entries))
        }

        if (formValues.isNotEmpty()) FormFiller(builder).fill(formValues)
        require(builder.entries().isNotEmpty()) { "Nothing changed; no objects to save" }
        builder.buildIncremental(originalBytes)
    }

    private fun pageAnnots(document: PdfDocument, raw: com.pdfpaper.engine.model.PdfObject?): List<com.pdfpaper.engine.model.PdfObject> {
        if (raw == null) return emptyList()
        val resolved = when (raw) {
            is PdfRef -> document.resolve(raw)
            else -> raw
        } ?: return emptyList()
        return (resolved as? PdfArray)?.items.orEmpty()
    }

    private fun sourceAnnotRef(
        document: PdfDocument,
        objNum: Int,
        dropped: Set<Int>
    ): PdfRef? {
        if (objNum in dropped) return null
        val entry = document.file.entry(objNum) ?: return null
        if (entry.kind == com.pdfpaper.engine.xref.EntryKind.FREE) return null
        return PdfRef(objNum, entry.gen)
    }

    private fun validateTargets(document: PdfDocument, annotations: List<AnnotationSpec>) {
        val targets = annotations.filter { it is ReplySpec || it is StatusSpec }
        if (targets.isEmpty()) return
        val ids = HashMap<Int, Int>()
        fun collect(comments: List<com.pdfpaper.engine.annot.PdfComment>) {
            for (comment in comments) {
                ids[comment.id] = comment.pageIndex
                collect(comment.replies)
            }
        }
        collect(document.comments())
        for (spec in targets) {
            val target = when (spec) {
                is ReplySpec -> spec.targetObjNum
                is StatusSpec -> spec.targetObjNum
                else -> continue
            }
            val page = ids[target] ?: throw IllegalArgumentException("No comment with id $target")
            require(page == spec.pageIndex) {
                "Comment $target is on page ${page + 1}, not ${spec.pageIndex + 1}"
            }
        }
    }

    /**
     * Incremental variant of [applyAnnotations] (T55).
     */
    fun applyAnnotationsIncremental(
        document: PdfDocument,
        originalBytes: ByteArray,
        annotations: List<AnnotationSpec>,
        version: String = PdfWriter.DEFAULT_VERSION
    ): Result<ByteArray> = editIncremental(document, originalBytes, annotations, version = version)


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
