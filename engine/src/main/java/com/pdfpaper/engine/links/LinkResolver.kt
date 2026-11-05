package com.pdfpaper.engine.links

import com.pdfpaper.engine.annot.AnnotationType
import com.pdfpaper.engine.annot.annotations
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.PageTreeResult
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pageTree
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.text.PageLayout
import com.pdfpaper.engine.text.TextLine
import com.pdfpaper.engine.text.layout
import kotlin.math.max
import kotlin.math.min

/**
 * Resolves internal links and in-text references of a PDF (T36, extended by
 * T61). It reads /Link annotations with /Dest or /GoTo actions, explicit and
 * named destinations (catalog /Dests and the /Names /Dests tree), then finds
 * citations, figure/table captions, section headings and page references in
 * the page text. Every target gets a small preview region, a title and the text
 * the app's Copy text action needs.
 *
 * All work is lazy and bounded: page text is only laid out when a reference is
 * actually looked for, and malformed objects simply produce no target (never
 * an exception).
 */
class LinkResolver(private val document: PdfDocument) {

    private val pages: List<PdfPage> = when (val result = pageTree(document)) {
        is PageTreeResult.Ok -> result.pages
        else -> emptyList()
    }

    private val catalog: PdfDict? = document.resolveRoot() as? PdfDict
    private val pageBoxes: MutableMap<Int, PageBox> = HashMap()
    private val layouts: MutableMap<Int, PageLayout?> = HashMap()

    private val destinations: DestinationResolver by lazy { DestinationResolver(document, pages) }
    private val outlineTitles: Map<Int, String> by lazy { readOutlineTitles() }
    private val textIndex: TextIndex by lazy { buildTextIndex() }

    /** Resolves the whole document. Call on a background thread. */
    fun resolve(): LinkIndex {
        val byPage = LinkedHashMap<Int, MutableList<DocumentLink>>(pages.size)
        val allTargets = ArrayList<LinkTarget>()
        var references = 0
        for (page in pages) {
            val links = ArrayList<DocumentLink>()
            val annotationLinks = annotationLinks(page)
            links += annotationLinks
            if (references < MAX_REFERENCE_HITS) {
                val annotationRects = annotationLinks.map { it.rect }
                val refs = referenceLinks(page, annotationRects, MAX_REFERENCE_HITS - references)
                links += refs
                references += refs.size
            }
            byPage[page.index] = links
            for (link in links) allTargets += link.targets
        }
        return LinkIndex(byPage, allTargets.distinctBy { targetKey(it) })
    }

    /** Links on one page (used by tests and incremental callers). */
    fun linksOnPage(page: PdfPage): List<DocumentLink> =
        annotationLinks(page) + referenceLinks(page, emptyList(), MAX_REFERENCE_HITS)

    // ------------------------------------------------------------ link annotations

    private fun annotationLinks(page: PdfPage): List<DocumentLink> {
        val out = ArrayList<DocumentLink>()
        val annotations = runCatching { page.annotations() }.getOrDefault(emptyList())
        for (annotation in annotations) {
            if (annotation.type != AnnotationType.LINK) continue
            val rect = annotation.rect
                ?: PdfRect.union(annotation.quadBounds())
                ?: continue
            if (rect.isEmpty) continue
            val dict = annotation.dict
            val action = resolveObject(dict["A"]) as? PdfDict
            val actionType = (action?.get("S") as? PdfName)?.name
            val destinationObject = action?.get("D") ?: dict["Dest"]
            when (actionType) {
                "URI" -> {
                    val uri = textOf(action?.get("URI"))
                    if (uri.isNullOrBlank()) {
                        out += DocumentLink(
                            page.index, rect, LinkKind.URI, "[web link]",
                            note = "This web link has no address."
                        )
                    } else {
                        out += DocumentLink(page.index, rect, LinkKind.URI, uri, uri = uri)
                    }
                }
                "GoToR", "Launch" -> {
                    val file = fileName(action?.get("F"))
                        ?: fileName(dict["F"])
                        ?: "another file"
                    out += DocumentLink(
                        page.index, rect, LinkKind.FILE, file, file = file,
                        note = "This link opens another file: $file."
                    )
                }
                "GoTo", null -> {
                    val destination = resolveDestination(destinationObject)
                    if (destination == null) {
                        out += DocumentLink(
                            page.index, rect, LinkKind.LINK, "Link",
                            note = "This link has no readable destination."
                        )
                    } else {
                        val target = makeDestinationTarget(
                            destination,
                            destinationTitle(destinationObject) ?: outlineTitles[destination.pageIndex]
                        )
                        out += if (target == null) {
                            DocumentLink(
                                page.index, rect, LinkKind.LINK, "Link",
                                note = "The destination page is not in this document."
                            )
                        } else {
                            DocumentLink(
                                page.index, rect, LinkKind.LINK,
                                target.title.ifBlank { "Page ${target.pageIndex + 1}" },
                                targets = listOf(target)
                            )
                        }
                    }
                }
                else -> {
                    out += DocumentLink(
                        page.index, rect, LinkKind.LINK, "Link",
                        note = "This link uses an action PDFPaper does not handle yet."
                    )
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------ destinations

    /** Destination grammar is shared with the outline reader (T34). */
    private fun resolveDestination(obj: PdfObject?): LinkDestination? = destinations.resolve(obj)

    private fun readOutlineTitles(): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        val outlines = resolveObject(catalog?.get("Outlines")) as? PdfDict ?: return out
        val visited = HashSet<Int>()
        val stack = ArrayDeque<PdfObject>()
        outlines["First"]?.let { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val obj = stack.removeLast()
            val ref = obj as? PdfRef
            if (ref != null && !visited.add(ref.objNum)) continue
            val item = resolveObject(obj) as? PdfDict ?: continue
            val title = textOf(item["Title"])?.trim().orEmpty()
            val action = resolveObject(item["A"]) as? PdfDict
            val destination = resolveDestination(item["Dest"] ?: action?.get("D"))
            if (title.isNotBlank() && destination != null) {
                out.putIfAbsent(destination.pageIndex, title.take(160))
            }
            item["First"]?.let { stack.addLast(it) }
            item["Next"]?.let { stack.addLast(it) }
        }
        return out
    }

    private fun destinationTitle(obj: PdfObject?): String? = when (val value = resolveObject(obj)) {
        is PdfName -> value.name.replace('_', ' ').takeIf { it.isNotBlank() }
        is PdfString -> value.text().replace('_', ' ').takeIf { it.isNotBlank() }
        is PdfDict -> textOf(value["Title"])?.takeIf { it.isNotBlank() }
        else -> null
    }

    private fun makeDestinationTarget(destination: LinkDestination, preferredTitle: String?): LinkTarget? {
        val page = pages.getOrNull(destination.pageIndex) ?: return null
        val box = boxOf(page)
        val preview = destination.rect?.let { clampToPage(page, box, it) }
            ?: pageRegion(page, box, destination.top)
        val title = preferredTitle?.takeIf { it.isNotBlank() }
            ?: "Page ${destination.pageIndex + 1}"
        return targetFor(
            pageIndex = destination.pageIndex,
            preview = preview,
            title = title,
            label = if (destination.fit == LinkFit.FIT || destination.fit == LinkFit.UNKNOWN) {
                "Page"
            } else {
                "Link target"
            },
            destination = destination,
            copyText = textInRect(page, preview)
        ).toTarget()
    }

    // ------------------------------------------------------------ references

    private fun referenceLinks(page: PdfPage, annotationRects: List<PdfRect>, limit: Int): List<DocumentLink> {
        if (limit <= 0) return emptyList()
        val layout = layoutFor(page) ?: return emptyList()
        if (layout.isEmpty) return emptyList()
        val index = textIndex
        val out = ArrayList<DocumentLink>()
        for (match in findReferences(layout.text)) {
            if (out.size >= limit) break
            val rects = layout.rectsFor(match.start, match.end).map {
                PdfRect(it.x0, it.y0, it.x1, it.y1)
            }
            val exact = PdfRect.union(rects)
            val rect = if (exact != null && exact.width >= 1.0) exact else referenceRect(layout, match)
            if (rect.isEmpty) continue
            if (annotationRects.any { overlaps(it, rect) }) continue
            if (match.kind == LinkKind.PAGE) {
                val number = match.number ?: continue
                val pageIndex = number - 1
                if (pageIndex !in pages.indices) {
                    out += DocumentLink(
                        page.index, rect, match.kind, match.text,
                        note = "Page $number is outside this document."
                    )
                    continue
                }
                val targetPage = pages[pageIndex]
                val box = boxOf(targetPage)
                val preview = PdfRect(
                    box.left + box.width * 0.05,
                    box.top - box.height * 0.55,
                    box.right - box.width * 0.05,
                    box.top - box.height * 0.02
                )
                val target = targetFor(
                    pageIndex, preview, "Page $number", "Page",
                    LinkDestination(pageIndex, LinkFit.FIT),
                    textInRect(targetPage, preview)
                )
                out += DocumentLink(page.index, rect, match.kind, match.text, listOf(target.toTarget()))
                continue
            }

            val targets = when (match.kind) {
                LinkKind.CITATION -> if (match.author != null) {
                    listOfNotNull(index.authorYear(match.author, match.identifier ?: ""))
                } else {
                    match.numbers.mapNotNull { index.bibliography[it] }
                }
                LinkKind.FIGURE -> listOfNotNull(index.captions[LinkKind.FIGURE to match.identifier])
                LinkKind.TABLE -> listOfNotNull(index.captions[LinkKind.TABLE to match.identifier])
                LinkKind.EQUATION -> listOfNotNull(index.captions[LinkKind.EQUATION to match.identifier])
                LinkKind.SECTION -> {
                    val heading = index.headings[match.identifier]
                    if (heading != null) {
                        listOf(heading)
                    } else {
                        val tocPage = index.sectionPages[match.identifier]
                        if (tocPage != null && tocPage in pages.indices) {
                            val targetPage = pages[tocPage]
                            val box = boxOf(targetPage)
                            val preview = PdfRect(
                                box.left + box.width * 0.06,
                                box.top - box.height * 0.3,
                                box.right - box.width * 0.06,
                                box.top
                            )
                            listOf(
                                targetFor(
                                    tocPage, preview,
                                    "Section ${match.identifier}",
                                    "Section",
                                    LinkDestination(tocPage, LinkFit.FIT_H, top = box.top),
                                    textInRect(targetPage, preview)
                                )
                            )
                        } else {
                            emptyList()
                        }
                    }
                }
                else -> emptyList()
            }
            val resolved = targets.map { it.toTarget() }
            val note = if (resolved.isEmpty()) {
                notFoundMessage(match)
            } else {
                val missing = when (match.kind) {
                    LinkKind.CITATION -> if (match.author == null) {
                        match.numbers.filter { index.bibliography[it] == null }
                    } else {
                        emptyList()
                    }
                    else -> emptyList()
                }
                if (missing.isEmpty()) null else "Not found: " + missing.joinToString(", ") { "[$it]" }
            }
            out += DocumentLink(page.index, rect, match.kind, match.text, resolved, note = note)
        }
        return out
    }

    private fun notFoundMessage(match: ReferenceMatch): String = when (match.kind) {
        LinkKind.CITATION -> "Reference ${match.text} was not found in this document."
        LinkKind.FIGURE -> "${match.text} was not found in this document."
        LinkKind.TABLE -> "${match.text} was not found in this document."
        LinkKind.SECTION -> "${match.text} was not found in this document."
        LinkKind.EQUATION -> "${match.text} was not found in this document."
        else -> "${match.text} has no target in this document."
    }

    private fun findReferences(text: String): List<ReferenceMatch> {
        if (text.isBlank()) return emptyList()
        val matches = ArrayList<ReferenceMatch>()
        for (pattern in REFERENCE_PATTERNS) {
            for (match in pattern.regex.findAll(text)) {
                val groups = match.groupValues
                val kind = pattern.kind
                when (kind) {
                    LinkKind.CITATION -> {
                        val raw = groups.getOrNull(1) ?: continue
                        val year = groups.getOrNull(2)
                        if (year != null) {
                            matches += ReferenceMatch(
                                kind, match.range.first, match.range.last + 1,
                                match.value.trim(), author = raw.trim(), identifier = year
                            )
                        } else {
                            val numbers = parseCitationNumbers(raw)
                            if (numbers.isEmpty()) continue
                            matches += ReferenceMatch(
                                kind, match.range.first, match.range.last + 1,
                                match.value.trim(), numbers = numbers
                            )
                        }
                    }
                    LinkKind.SECTION -> {
                        val id = groups.getOrNull(1) ?: continue
                        matches += ReferenceMatch(
                            kind, match.range.first, match.range.last + 1,
                            match.value.trim(), identifier = id
                        )
                    }
                    LinkKind.PAGE -> {
                        val number = groups.getOrNull(1)?.toIntOrNull() ?: continue
                        matches += ReferenceMatch(
                            kind, match.range.first, match.range.last + 1,
                            match.value.trim(), number = number
                        )
                    }
                    LinkKind.FIGURE, LinkKind.TABLE, LinkKind.EQUATION -> {
                        val id = groups.getOrNull(1) ?: continue
                        matches += ReferenceMatch(
                            kind, match.range.first, match.range.last + 1,
                            match.value.trim(), identifier = id
                        )
                    }
                    else -> {
                        val author = groups.getOrNull(1)?.trim() ?: continue
                        val year = groups.getOrNull(2) ?: continue
                        matches += ReferenceMatch(
                            kind, match.range.first, match.range.last + 1,
                            match.value.trim(), author = author, identifier = year
                        )
                    }
                }
            }
        }
        matches.sortBy { it.start }
        val out = ArrayList<ReferenceMatch>(matches.size)
        var lastEnd = -1
        for (match in matches) {
            if (match.start < lastEnd) continue
            out += match
            lastEnd = match.end
        }
        return out
    }

    private fun parseCitationNumbers(raw: String): List<Int> {
        val out = LinkedHashSet<Int>()
        for (part in raw.split(',', ';')) {
            val value = part.trim()
            if (value.isEmpty()) continue
            val range = value.split('-', '–', '—')
            if (range.size == 2) {
                val from = range[0].trim().toIntOrNull() ?: continue
                val to = range[1].trim().toIntOrNull() ?: continue
                if (from <= to && to - from <= 50) {
                    for (n in from..to) out += n
                }
            } else {
                value.toIntOrNull()?.let { out += it }
            }
        }
        return out.toList()
    }

    // ------------------------------------------------------------ text index

    private fun layoutFor(page: PdfPage): PageLayout? =
        layouts.getOrPut(page.index) { runCatching { page.layout() }.getOrNull() }

    private fun buildTextIndex(): TextIndex {
        val index = TextIndex()
        if (pages.isEmpty()) return index
        val limit = min(pages.size, MAX_INDEXED_PAGES)
        for (page in pages.subList(0, limit)) {
            val layout = layoutFor(page) ?: continue
            val lines = layout.lines
            for (i in lines.indices) {
                val text = lines[i].text.trim()
                if (text.isEmpty()) continue
                val bib = BIBLIOGRAPHY_START.find(text)
                if (bib != null) {
                    val number = bib.groupValues[1].toIntOrNull()
                    // A table cell or a link annotation label can be just
                    // "[3]". Only a line with an actual entry after the label
                    // is a bibliography entry, otherwise the first "[3]" in a
                    // results table would shadow the real reference.
                    val entryText = text.substring(bib.range.last + 1).trim()
                    if (number != null && entryText.length >= 16 &&
                        index.bibliography[number] == null
                    ) {
                        val block = bibliographyBlock(lines, i)
                        index.bibliography[number] = targetFor(
                            page.index, blockRect(page, block), block.first().text.trim(),
                            "Bibliography entry", LinkDestination(page.index, LinkFit.XYZ, top = block.first().bounds.y1),
                            block.joinToString("\n") { it.text.trim() }
                        )
                    }
                }
                val caption = CAPTION_START.find(text)
                if (caption != null && isCaptionLine(text, caption.range.last + 1)) {
                    val kind = if (caption.groupValues[1].startsWith("T", ignoreCase = true)) {
                        LinkKind.TABLE
                    } else if (caption.groupValues[1].startsWith("E", ignoreCase = true)) {
                        LinkKind.EQUATION
                    } else {
                        LinkKind.FIGURE
                    }
                    val id = caption.groupValues[2]
                    val block = captionBlock(lines, i, kind)
                    val key = kind to id
                    if (index.captions[key] == null) {
                        index.captions[key] = targetFor(
                            page.index, blockRect(page, block), text.take(160),
                            kind.label(), LinkDestination(page.index, LinkFit.XYZ, top = lines[i].bounds.y1),
                            block.joinToString("\n") { it.text.trim() }
                        )
                    }
                }
                val heading = HEADING_START.find(text)
                if (heading != null) {
                    val id = heading.groupValues[1]
                    if (index.headings[id] == null) {
                        val block = headingBlock(lines, i)
                        index.headings[id] = targetFor(
                            page.index, blockRect(page, block), text.take(160),
                            "Section", LinkDestination(page.index, LinkFit.XYZ, top = lines[i].bounds.y1),
                            block.joinToString("\n") { it.text.trim() }
                        )
                    }
                }
                val toc = TOC_LINE.find(text)
                if (toc != null) {
                    val id = toc.groupValues[1]
                    val pageNumber = toc.groupValues[2].toIntOrNull()
                    if (pageNumber != null) index.sectionPages.putIfAbsent(id, pageNumber - 1)
                }
            }
        }
        return index
    }

    private fun bibliographyBlock(lines: List<TextLine>, start: Int): List<TextLine> {
        val out = ArrayList<TextLine>()
        out += lines[start]
        var previous = lines[start]
        var i = start + 1
        while (i < lines.size && out.size < 8 && out.size < MAX_BLOCK_LINES) {
            val line = lines[i]
            if (BIBLIOGRAPHY_START.containsMatchIn(line.text.trim())) break
            if (verticalGap(previous, line) > max(14.0, previous.bounds.height * 1.8)) break
            out += line
            previous = line
            i++
        }
        return out
    }

    private fun isCaptionLine(text: String, idEnd: Int): Boolean {
        val rest = text.substring(idEnd.coerceAtMost(text.length)).trimStart()
        if (rest.isEmpty()) return true
        val first = rest.first()
        if (first == ':' || first == '.' || first == '-' || first == '–' || first == '—') return true
        // "Figure 2.1 and Table 4 are ..." is a reference in running text, not
        // a caption. Real captions usually continue with a capital, a digit or
        // punctuation; lowercase conjunctions are the common false positive.
        if (!first.isUpperCase() && !first.isDigit()) return false
        return true
    }

    private fun captionBlock(lines: List<TextLine>, caption: Int, kind: LinkKind): List<TextLine> {        val out = ArrayList<TextLine>()
        out += lines[caption]
        if (kind == LinkKind.FIGURE) {
            val before = ArrayList<TextLine>()
            var i = caption - 1
            var lower = lines[caption]
            var height = 0.0
            while (i >= 0 && before.size < 12 && height < 280.0) {
                val line = lines[i]
                if (verticalGap(line, lower) > max(16.0, line.bounds.height * 2.2)) break
                before += line
                height += line.bounds.height + 4
                lower = line
                i--
            }
            before.asReversed().forEach { out.add(0, it) }
        } else {
            var i = caption + 1
            var previous = lines[caption]
            var height = 0.0
            while (i < lines.size && out.size < MAX_BLOCK_LINES && height < 280.0) {
                val line = lines[i]
                if (verticalGap(previous, line) > max(16.0, previous.bounds.height * 2.2)) break
                if (CAPTION_START.containsMatchIn(line.text.trim())) break
                out += line
                height += line.bounds.height + 4
                previous = line
                i++
            }
        }
        return out
    }

    private fun headingBlock(lines: List<TextLine>, start: Int): List<TextLine> {
        val out = ArrayList<TextLine>()
        out += lines[start]
        var previous = lines[start]
        var i = start + 1
        while (i < lines.size && out.size < 6) {
            val line = lines[i]
            val text = line.text.trim()
            if (HEADING_START.containsMatchIn(text) || CAPTION_START.containsMatchIn(text)) break
            if (verticalGap(previous, line) > max(18.0, previous.bounds.height * 2.4)) break
            out += line
            previous = line
            i++
        }
        return out
    }

    private fun blockRect(page: PdfPage, lines: List<TextLine>): PdfRect {
        val rects = lines.map { lineTextRect(it) }
        val union = PdfRect.union(rects) ?: return pageRegion(page, boxOf(page), null)
        return clampToPage(page, boxOf(page), union)
    }

    private fun targetFor(
        pageIndex: Int,
        preview: PdfRect,
        title: String,
        label: String,
        destination: LinkDestination,
        copyText: String
    ): TextTarget {
        val page = pages.getOrNull(pageIndex)
        val clean = if (page != null) clampToPage(page, boxOf(page), preview) else preview
        val fraction = page?.let { pageFractionTop(it, clean) } ?: 0.0
        return TextTarget(
            LinkTarget(
                pageIndex = pageIndex,
                destination = destination,
                title = title.ifBlank { "Page ${pageIndex + 1}" }.take(240),
                previewRect = clean,
                pageFractionTop = fraction,
                label = label,
                copyText = copyText.take(MAX_COPY_CHARS)
            )
        )
    }

    private fun pageFractionTop(page: PdfPage, rect: PdfRect): Double {
        val box = boxOf(page)
        val plan = PageGeometry.plan(box, 1.0, 4_000_000)
        val y = plan.matrix.apply(rect.centerX, min(rect.top, box.top))[1]
        val height = plan.pixelHeight.coerceAtLeast(1)
        return (y / height).coerceIn(0.0, 1.0)
    }

    private fun pageRegion(page: PdfPage, box: PageBox, top: Double?): PdfRect {
        val height = max(72.0, box.height * 0.38)
        val topY = (top ?: box.top).coerceIn(box.bottom, box.top)
        val bottom = max(box.bottom, topY - height)
        return PdfRect(
            box.left + box.width * 0.06,
            bottom,
            box.right - box.width * 0.06,
            topY
        )
    }

    private fun textInRect(page: PdfPage, rect: PdfRect): String {
        val layout = layoutFor(page) ?: return ""
        val out = StringBuilder()
        for (line in layout.lines) {
            val b = lineTextRect(line)
            if (b.right < rect.left || b.left > rect.right || b.top < rect.bottom || b.bottom > rect.top) continue
            if (out.isNotEmpty()) out.append('\n')
            out.append(line.text.trim())
            if (out.length >= MAX_COPY_CHARS) break
        }
        return out.toString().take(MAX_COPY_CHARS)
    }

    /**
     * A line's page rectangle. Some PDFs use a symbolic font without widths;
     * their runs have a zero-width box but the text is still readable, so fall
     * back to a conservative estimate from the character count and font size.
     */
    private fun lineTextRect(line: TextLine): PdfRect {
        val bounds = line.bounds
        val width = bounds.x1 - bounds.x0
        if (width >= 1.0) {
            return PdfRect(bounds.x0, bounds.y0, bounds.x1, bounds.y1)
        }
        val size = line.runs.maxOfOrNull { it.fontSize } ?: 12.0
        val estimated = line.text.length * max(2.0, size * 0.5)
        return PdfRect(bounds.x0, bounds.y0, bounds.x0 + estimated, bounds.y1)
    }

    private fun referenceRect(layout: PageLayout, match: ReferenceMatch): PdfRect {
        val parts = ArrayList<PdfRect>()
        for (line in layout.lines) {
            val lineStart = line.start
            val lineEnd = lineStart + line.text.length
            if (match.end <= lineStart || match.start >= lineEnd) continue
            val full = lineTextRect(line)
            val from = (max(match.start, lineStart) - lineStart).coerceAtLeast(0)
            val to = (min(match.end, lineEnd) - lineStart).coerceAtMost(line.text.length)
            val length = line.text.length.coerceAtLeast(1)
            val x0 = full.left + full.width * from / length
            val x1 = full.left + full.width * to / length
            parts += PdfRect(x0, full.bottom, max(x0 + 1.0, x1), full.top)
        }
        return PdfRect.union(parts) ?: PdfRect(0.0, 0.0, 1.0, 1.0)
    }

    // ------------------------------------------------------------ geometry helpers

    private fun boxOf(page: PdfPage): PageBox =
        pageBoxes.getOrPut(page.index) { PageGeometry.boxOf(page) }

    private fun clampToPage(page: PdfPage, box: PageBox, rect: PdfRect): PdfRect {
        val left = max(box.left, rect.left)
        val bottom = max(box.bottom, rect.bottom)
        val right = min(box.right, rect.right)
        val top = min(box.top, rect.top)
        if (right - left < 1.0 || top - bottom < 1.0) {
            return PdfRect(
                box.left + box.width * 0.06,
                box.top - box.height * 0.38,
                box.right - box.width * 0.06,
                box.top
            )
        }
        return PdfRect(left, bottom, right, top)
    }

    private fun overlaps(a: PdfRect, b: PdfRect): Boolean =
        a.left < b.right && a.right > b.left && a.bottom < b.top && a.top > b.bottom

    private fun verticalGap(upper: TextLine, lower: TextLine): Double =
        max(0.0, upper.bounds.y0 - lower.bounds.y1)

    private fun targetKey(target: LinkTarget): String =
        "${target.pageIndex}:${target.previewRect}:${target.title}"

    // ------------------------------------------------------------ object helpers

    private fun resolveObject(obj: PdfObject?): PdfObject? = when (obj) {
        null -> null
        is PdfRef -> document.resolve(obj)
        else -> obj
    }

    private fun number(obj: PdfObject?): Double? = when (obj) {
        is PdfInteger -> obj.value.toDouble()
        is PdfReal -> obj.value
        else -> null
    }

    private fun textOf(obj: PdfObject?): String? = when (val value = resolveObject(obj)) {
        is PdfString -> value.text()
        is PdfName -> value.name
        else -> null
    }

    private fun fileName(obj: PdfObject?): String? {
        val value = resolveObject(obj) ?: return null
        return when (value) {
            is PdfString -> value.text()
            is PdfName -> value.name
            is PdfDict -> textOf(value["UF"]) ?: textOf(value["F"])
            else -> null
        }?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------ data types

    private class TextTarget(val target: LinkTarget) {
        fun toTarget(): LinkTarget = target
    }

    private class TextIndex {
        val bibliography = HashMap<Int, TextTarget>()
        val captions = HashMap<Pair<LinkKind, String>, TextTarget>()
        val headings = HashMap<String, TextTarget>()
        val sectionPages = HashMap<String, Int>()

        fun authorYear(author: String, year: String): TextTarget? {
            val surname = author.substringBefore(' ').trim().lowercase()
            if (surname.isEmpty() || year.isEmpty()) return null
            return bibliography.values.firstOrNull {
                val text = it.target.copyText.lowercase()
                text.contains(surname) && text.contains(year.lowercase())
            }
        }
    }

    private data class ReferenceMatch(
        val kind: LinkKind,
        val start: Int,
        val end: Int,
        val text: String,
        val numbers: List<Int> = emptyList(),
        val identifier: String? = null,
        val author: String? = null,
        val number: Int? = null
    )

    private class ReferencePattern(val kind: LinkKind, val regex: Regex)

    private companion object {
        const val MAX_REFERENCE_HITS = 1000
        const val MAX_INDEXED_PAGES = 1500
        const val MAX_BLOCK_LINES = 14
        const val MAX_COPY_CHARS = 4000

        val BIBLIOGRAPHY_START = Regex("""^\[(\d{1,3})]""")
        val CAPTION_START = Regex(
            """^(Figure|Fig\.|Table|Equation|Eq\.)\s*\(?(\d+(?:\.\d+)*)\)?""",
            RegexOption.IGNORE_CASE
        )
        val HEADING_START = Regex("""^(\d+(?:\.\d+)+)\.?\s+\S""")
        val TOC_LINE = Regex("""^(\d+(?:\.\d+)+)\.?\s+.*?(?:\.{2,}|\s)\s*(\d{1,4})\s*$""")

        val REFERENCE_PATTERNS = listOf(
            ReferencePattern(
                LinkKind.CITATION,
                Regex("""\[(\d{1,3}(?:\s*[-,–]\s*\d{1,3})*)]""")
            ),
            ReferencePattern(
                LinkKind.FIGURE,
                Regex("""\b(?:Figure|Fig\.)\s*(\d+(?:\.\d+)*)""", RegexOption.IGNORE_CASE)
            ),
            ReferencePattern(
                LinkKind.TABLE,
                Regex("""\bTable\s*(\d+(?:\.\d+)*)""", RegexOption.IGNORE_CASE)
            ),
            ReferencePattern(
                LinkKind.SECTION,
                Regex("""\b(?:Section|Sec\.|§)\s*(\d+(?:\.\d+)*)""", RegexOption.IGNORE_CASE)
            ),
            ReferencePattern(
                LinkKind.PAGE,
                Regex("""\b(?:Page|p\.)\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
            ),
            ReferencePattern(
                LinkKind.EQUATION,
                Regex("""\b(?:Equation|Eq\.)\s*\(?(\d+(?:\.\d+)*)\)?""", RegexOption.IGNORE_CASE)
            ),
            ReferencePattern(
                LinkKind.CITATION,
                Regex(
                    """\(([A-Z][\p{L}'’\-]+(?:\s+(?:et\s+al\.?|and\s+[A-Z][\p{L}'’\-]+))?\s*,\s*((?:19|20)\d{2}[a-z]?))\)""",
                    RegexOption.IGNORE_CASE
                )
            )
        )
    }
}
