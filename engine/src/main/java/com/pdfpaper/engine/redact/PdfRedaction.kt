package com.pdfpaper.engine.redact

import com.pdfpaper.engine.annot.PdfAnnotation
import com.pdfpaper.engine.annot.annotations
import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.form.PdfField
import com.pdfpaper.engine.form.formFields
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.buildDisplayList
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.PdfTextRun
import com.pdfpaper.engine.text.layout
import com.pdfpaper.engine.text.TextRect
import com.pdfpaper.engine.write.PdfDocumentAssembler
import com.pdfpaper.engine.write.PdfPageSelection
import com.pdfpaper.engine.write.PdfWriter
import java.nio.charset.StandardCharsets

/**
 * Privacy-critical redaction (T28).
 *
 * The API is deliberately small: [apply] creates a complete new file (never an
 * incremental update), [deletePages] removes pages and their exclusive object
 * graphs, [find] builds marks from text searches, and [verify] re-extracts,
 * re-renders and byte-scans the result.
 */
object PdfRedaction {

    fun apply(
        document: PdfDocument,
        marks: List<RedactionMark>,
        decoder: ExternalImageDecoder? = DEFAULT_IMAGE_DECODER
    ): RedactionResult {
        val pages = document.pages()
        if (marks.isEmpty()) {
            val selections = pages.indices.map { PdfPageSelection(document, it) }
            return build(document, selections, emptyMap(), emptySet(), emptySet(), null, decoder)
        }
        for (mark in marks) {
            require(mark.pageIndex in pages.indices) {
                "Redaction mark page ${mark.pageIndex} is out of range (0..${pages.lastIndex})"
            }
        }
        val markedPages = marks.groupBy { it.pageIndex }
        val perPageAreas = markedPages.mapValues { (index, pageMarks) ->
            if (pageMarks.any { it.type == RedactionMarkType.PAGE }) {
                listOf(pageBox(pages[index]))
            } else {
                pageMarks.flatMap { it.rects }
            }
        }
        val context = RedactionContext()
        val droppedAnnots = HashSet<Int>()
        val droppedFields = HashSet<Int>()

        collectAnnotationDrops(pages, markedPages, perPageAreas, droppedAnnots)
        collectFieldDrops(document, markedPages, perPageAreas, droppedAnnots, droppedFields)
        closeAnnotationDrops(pages, droppedAnnots)

        val outlineFilter: (PdfDict) -> Boolean = { source ->
            outlineTouches(document, pages, source, perPageAreas)
        }
        val redactionPages = markedPages.keys.associateWith { index ->
            { page: PdfPage, entries: LinkedHashMap<PdfName, PdfObject>, copier: com.pdfpaper.engine.write.PdfObjectCopier, builder: com.pdfpaper.engine.write.PdfDocumentBuilder ->
                PageRedactionApplier(
                    builder, copier, page, markedPages.getValue(index), decoder, context
                ).apply(entries)
            }
        }
        val selections = pages.indices.map { PdfPageSelection(document, it) }
        return build(
            document,
            selections,
            redactionPages,
            droppedAnnots,
            droppedFields,
            outlineFilter,
            decoder,
            context
        )
    }

    /** Deletes [pageIndices] and every object reachable only from them. */
    fun deletePages(
        document: PdfDocument,
        pageIndices: Set<Int>
    ): RedactionResult {
        val pages = document.pages()
        require(pageIndices.all { it in pages.indices }) {
            "Page deletion index out of range (0..${pages.lastIndex})"
        }
        val deleted = pageIndices.toSet()
        val selections = pages.indices.filterNot { it in deleted }.map { PdfPageSelection(document, it) }
        val droppedFields = fieldsOnPages(document, deleted)
        val context = RedactionContext()
        return build(
            document,
            selections,
            emptyMap(),
            emptySet(),
            droppedFields,
            null,
            DEFAULT_IMAGE_DECODER,
            context,
            pagesDeleted = deleted.size
        )
    }

    fun deletePages(document: PdfDocument, range: IntRange): RedactionResult =
        deletePages(document, range.toSet())

    private fun build(
        document: PdfDocument,
        selections: List<PdfPageSelection>,
        redactionPages: Map<Int, (PdfPage, LinkedHashMap<PdfName, PdfObject>, com.pdfpaper.engine.write.PdfObjectCopier, com.pdfpaper.engine.write.PdfDocumentBuilder) -> Unit>,
        droppedAnnots: Set<Int>,
        droppedFields: Set<Int>,
        outlineFilter: ((PdfDict) -> Boolean)?,
        decoder: ExternalImageDecoder?,
        context: RedactionContext = RedactionContext(),
        pagesDeleted: Int = 0
    ): RedactionResult {
        val warnings = LinkedHashSet<String>()
        val assembler = PdfDocumentAssembler(
            selections = selections,
            primary = document,
            version = PdfWriter.DEFAULT_VERSION,
            droppedAnnots = droppedAnnots,
            droppedFields = droppedFields,
            outlineFilter = outlineFilter,
            redactionPages = redactionPages,
            garbageCollect = true
        )
        val bytes = assembler.assemble()
        warnings += context.warnings
        warnings += assembler.textReports().map { it.toString() }
        return RedactionResult(
            bytes = bytes,
            warnings = warnings.toList(),
            unredacted = context.unredacted.toList(),
            marksApplied = context.marksApplied,
            pagesDeleted = pagesDeleted,
            pageCount = selections.size
        )
    }

    // ---------------------------------------------------------------- find

    fun find(
        document: PdfDocument,
        query: String,
        regex: Boolean = false,
        preset: RedactionPreset? = null,
        pageIndices: Set<Int>? = null,
        maxMatches: Int = 10_000
    ): List<RedactionFindMatch> {
        if (query.isEmpty() && preset == null) return emptyList()
        val pattern = preset?.regex ?: query
        val compiled = if (regex || preset != null) {
            Regex(pattern, setOf(RegexOption.IGNORE_CASE))
        } else {
            null
        }
        val out = ArrayList<RedactionFindMatch>()
        for (page in document.pages()) {
            if (pageIndices != null && page.index !in pageIndices) continue
            if (out.size >= maxMatches) break
            val layout = page.layout()
            if (compiled == null) {
                for (match in layout.search(query, maxMatches - out.size)) {
                    out += RedactionFindMatch(
                        pageIndex = page.index,
                        text = layout.text.substring(match.start, match.end),
                        start = match.start,
                        end = match.end,
                        rects = match.rects.map { it.toPdfRect() }
                    )
                }
            } else {
                for (line in layout.lines) {
                    for (match in compiled.findAll(line.text)) {
                        if (match.value.isEmpty()) continue
                        val start = line.start + match.range.first
                        val end = line.start + match.range.last + 1
                        out += RedactionFindMatch(
                            pageIndex = page.index,
                            text = match.value,
                            start = start,
                            end = end,
                            rects = layout.rectsFor(start, end).map { it.toPdfRect() }
                        )
                        if (out.size >= maxMatches) break
                    }
                    if (out.size >= maxMatches) break
                }
            }
            if (out.size < maxMatches) {
                out += findInForms(page, compiled, if (compiled == null) query else null)
            }
        }
        return out.take(maxMatches)
    }

    // --------------------------------------------------------------- verify

    fun verify(
        document: PdfDocument,
        expected: Collection<String>,
        decoder: ExternalImageDecoder? = DEFAULT_IMAGE_DECODER,
        unredacted: List<String> = emptyList()
    ): RedactionVerification {
        val needles = expected.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val leaks = ArrayList<RedactionLeak>()
        val pages = document.pages()
        for (page in pages) {
            val extracted = allText(page)
            for (needle in needles) {
                var from = 0
                while (true) {
                    val at = extracted.indexOf(needle, from, ignoreCase = true)
                    if (at < 0) break
                    leaks += RedactionLeak(
                        needle, page.index, -1, RedactionLeakKind.TEXT,
                        "text extraction still contains '${abbreviate(needle)}'"
                    )
                    from = at + needle.length
                }
            }
            val display = runCatching { page.buildDisplayList(decoder) }.getOrNull()
            if (display != null) {
                val rendered = display.textRuns.joinToString("") { it.text }
                for (needle in needles) {
                    if (rendered.contains(needle, ignoreCase = true)) {
                        leaks += RedactionLeak(
                            needle, page.index, -1, RedactionLeakKind.RENDERED_TEXT,
                            "re-rendered text commands still contain '${abbreviate(needle)}'"
                        )
                    }
                }
            }
        }

        val rawLeaks = scanDecodedStreams(document, needles)
        leaks += rawLeaks
        val remaining = LinkedHashMap<String, Int>()
        for (needle in needles) remaining[needle] = leaks.count { it.expected == needle }
        return RedactionVerification(
            expected = needles,
            remaining = remaining,
            leaks = leaks,
            unredacted = unredacted,
            checkedPages = pages.size
        )
    }

    private fun scanDecodedStreams(document: PdfDocument, needles: List<String>): List<RedactionLeak> {
        if (needles.isEmpty()) return emptyList()
        val out = ArrayList<RedactionLeak>()
        val seen = HashSet<String>()
        for (objNum in document.file.entries.keys) {
            val stream = document.resolve(PdfRef(objNum, 0)) as? PdfStream ?: continue
            val bytes = runCatching { stream.decoded { ref -> document.resolve(ref) }.getOrNull()?.bytes }
                .getOrNull() ?: continue
            if (bytes.size > MAX_RAW_SCAN_BYTES) continue
            for (needle in needles) {
                val foundUtf8 = contains(bytes, needle.toByteArray(StandardCharsets.UTF_8))
                val foundLatin1 = contains(bytes, needle.toByteArray(StandardCharsets.ISO_8859_1))
                val foundUtf16 = contains(bytes, needle.toByteArray(StandardCharsets.UTF_16BE))
                if ((foundUtf8 || foundLatin1 || foundUtf16) && seen.add("$objNum:${needle.hashCode()}")) {
                    out += RedactionLeak(
                        needle, -1, objNum, RedactionLeakKind.RAW_STREAM,
                        "decoded stream $objNum still contains '${abbreviate(needle)}'"
                    )
                }
            }
        }
        return out
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    // ---------------------------------------------------------- drop planning

    private fun collectAnnotationDrops(
        pages: List<PdfPage>,
        markedPages: Map<Int, List<RedactionMark>>,
        perPageAreas: Map<Int, List<PdfRect>>,
        dropped: MutableSet<Int>
    ) {
        for ((pageIndex, pageMarks) in markedPages) {
            val page = pages[pageIndex]
            val pageWide = pageMarks.any { it.type == RedactionMarkType.PAGE }
            val areas = perPageAreas[pageIndex].orEmpty()
            for (annot in page.annotations()) {
                if (pageWide || annotationTouches(annot, areas)) {
                    annot.ref?.let { dropped += it.objNum }
                }
            }
        }
    }

    private fun annotationTouches(annot: PdfAnnotation, areas: List<PdfRect>): Boolean {
        val bounds = ArrayList<PdfRect>()
        annot.rect?.let { bounds += it }
        bounds += annot.quadBounds()
        for (stroke in annot.inkLists) {
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            var i = 0
            while (i + 1 < stroke.size) {
                val x = stroke[i]
                val y = stroke[i + 1]
                if (x.isFinite() && y.isFinite()) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
                i += 2
            }
            if (minX <= maxX) bounds += PdfRect(minX, minY, maxX, maxY)
        }
        if (bounds.isEmpty()) return areas.isNotEmpty()
        return bounds.any { rect -> areas.any { intersects(rect, it) } }
    }

    private fun collectFieldDrops(
        document: PdfDocument,
        markedPages: Map<Int, List<RedactionMark>>,
        perPageAreas: Map<Int, List<PdfRect>>,
        droppedAnnots: MutableSet<Int>,
        droppedFields: MutableSet<Int>
    ) {
        for (field in document.formFields()) {
            val touches = field.widgets.any { widget ->
                val index = widget.pageIndex ?: return@any false
                val pageMarks = markedPages[index] ?: return@any false
                val pageWide = pageMarks.any { it.type == RedactionMarkType.PAGE }
                val rect = widget.rect ?: return@any pageWide
                pageWide || perPageAreas[index].orEmpty().any { intersects(rect, it) }
            }
            if (!touches) continue
            field.ref?.let { droppedFields += it.objNum; droppedAnnots += it.objNum }
            for (widget in field.widgets) {
                widget.ref?.let { droppedAnnots += it.objNum }
            }
        }
    }

    private fun fieldsOnPages(document: PdfDocument, deleted: Set<Int>): Set<Int> {
        val out = HashSet<Int>()
        for (field in document.formFields()) {
            if (field.widgets.any { it.pageIndex in deleted }) {
                field.ref?.let { out += it.objNum }
                for (widget in field.widgets) widget.ref?.let { out += it.objNum }
            }
        }
        return out
    }

    private fun closeAnnotationDrops(pages: List<PdfPage>, dropped: MutableSet<Int>) {
        var changed = true
        var passes = 0
        while (changed && passes++ < 8) {
            changed = false
            for (page in pages) {
                for (annot in page.annotations()) {
                    val ref = annot.ref?.objNum ?: continue
                    if (ref in dropped) continue
                    val parent = annot.parent?.objNum
                    val reply = annot.inReplyTo?.objNum
                    val popup = annot.popup?.objNum
                    if ((parent != null && parent in dropped) ||
                        (reply != null && reply in dropped) ||
                        (popup != null && popup in dropped)
                    ) {
                        dropped.add(ref)
                        changed = true
                    }
                }
            }
        }
    }

    // --------------------------------------------------------------- outlines

    private fun outlineTouches(
        document: PdfDocument,
        pages: List<PdfPage>,
        source: PdfDict,
        perPageAreas: Map<Int, List<PdfRect>>
    ): Boolean {
        if (perPageAreas.isEmpty()) return false
        val dest = source["Dest"] ?: run {
            val action = resolveDict(document, source["A"]) ?: return@run null
            when ((action["S"] as? PdfName)?.name) {
                null, "GoTo" -> action["D"]
                else -> null
            }
        } ?: return false
        return destinationTouches(document, pages, dest, perPageAreas, 0)
    }

    private fun destinationTouches(
        document: PdfDocument,
        pages: List<PdfPage>,
        raw: PdfObject?,
        perPageAreas: Map<Int, List<PdfRect>>,
        depth: Int
    ): Boolean {
        if (depth > 8) return false
        val obj = when (raw) {
            is PdfRef -> document.resolve(raw)
            is PdfString -> namedDestination(document, raw.text())
            is PdfName -> namedDestination(document, raw.name)
            else -> raw
        } ?: return false
        if (obj is PdfDict) return destinationTouches(document, pages, obj["D"], perPageAreas, depth + 1)
        val array = obj as? PdfArray ?: return false
        val pageRef = array.items.firstOrNull() as? PdfRef ?: return false
        val pageIndex = pages.indexOfFirst { it.objNum == pageRef.objNum }
        if (pageIndex < 0) return false
        val areas = perPageAreas[pageIndex] ?: return false
        val pageWide = areas.any { it == pageBox(pages[pageIndex]) }
        val mode = (array.items.getOrNull(1) as? PdfName)?.name ?: "Fit"
        fun num(i: Int): Double? = when (val value = array.items.getOrNull(i)) {
            is PdfInteger -> value.value.toDouble()
            is PdfReal -> value.value
            else -> null
        }
        if (pageWide) return true
        return when (mode) {
            "XYZ" -> {
                val x = num(2)
                val y = num(3)
                if (x == null && y == null) false
                else areas.any { rect ->
                    (x == null || x in rect.left..rect.right) &&
                        (y == null || y in rect.bottom..rect.top)
                }
            }
            "FitH", "FitBH" -> num(2)?.let { y -> areas.any { y in it.bottom..it.top } } ?: false
            "FitV", "FitBV" -> num(2)?.let { x -> areas.any { x in it.left..it.right } } ?: false
            "FitR" -> {
                val left = num(2) ?: return false
                val bottom = num(3) ?: return false
                val right = num(4) ?: return false
                val top = num(5) ?: return false
                val rect = PdfRect(left, bottom, right, top)
                areas.any { intersects(rect, it) }
            }
            else -> false
        }
    }

    private fun namedDestination(document: PdfDocument, name: String): PdfObject? {
        val catalog = document.resolveRoot() as? PdfDict ?: return null
        (catalog["Dests"] as? PdfDict)?.get(name)?.let { return it }
        val names = resolveDict(document, catalog["Names"]) ?: return null
        val dests = resolveDict(document, names["Dests"]) ?: return null
        return findName(dests, name, 0)
    }

    private fun findName(node: PdfDict, name: String, depth: Int): PdfObject? {
        if (depth > 16) return null
        (node["Kids"] as? PdfArray)?.items?.forEach { kid ->
            val dict = kid as? PdfDict ?: return@forEach
            findName(dict, name, depth + 1)?.let { return it }
        }
        val names = node["Names"] as? PdfArray ?: return null
        var i = 0
        while (i + 1 < names.items.size) {
            val key = names.items[i] as? PdfString ?: return null
            if (key.text() == name) return names.items[i + 1]
            i += 2
        }
        return null
    }

    // ----------------------------------------------------------------- helpers

    private fun pageBox(page: PdfPage): PdfRect =
        PdfRect.of(page.cropBox) ?: PdfRect(0.0, 0.0, 612.0, 792.0)

    private fun intersects(a: PdfRect, b: PdfRect): Boolean =
        a.right > b.left && a.left < b.right && a.top > b.bottom && a.bottom < b.top

    private fun resolveDict(document: PdfDocument, obj: PdfObject?): PdfDict? = when (obj) {
        is PdfDict -> obj
        is PdfRef -> document.resolve(obj) as? PdfDict
        else -> null
    }

    private fun TextRect.toPdfRect(): PdfRect = PdfRect(x0, y0, x1, y1)

    private fun abbreviate(text: String): String =
        if (text.length <= 80) text else text.take(77) + "..."

    private const val MAX_RAW_SCAN_BYTES = 64 * 1024 * 1024
}
