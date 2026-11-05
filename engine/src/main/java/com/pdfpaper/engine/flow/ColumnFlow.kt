package com.pdfpaper.engine.flow

import com.pdfpaper.engine.export.PageRasterizer
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.ClipPath
import com.pdfpaper.engine.graphics.ClosePath
import com.pdfpaper.engine.graphics.CurveTo
import com.pdfpaper.engine.graphics.DisplayList
import com.pdfpaper.engine.graphics.DrawRasterImage
import com.pdfpaper.engine.graphics.DrawTextRun
import com.pdfpaper.engine.graphics.FillPath
import com.pdfpaper.engine.graphics.FillStrokePath
import com.pdfpaper.engine.graphics.LineTo
import com.pdfpaper.engine.graphics.MoveTo
import com.pdfpaper.engine.graphics.PdfPath
import com.pdfpaper.engine.graphics.StrokePath
import com.pdfpaper.engine.graphics.buildDisplayList
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.PageGeometry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Column auto-flow (T59, ISO 32000-1 §8.3.3, §8.9.5, §9.4.3).
 *
 * The page keeps its original look: this does not reflow text (T33) and it does
 * not crop margins (T57). It only decides **which rectangle of the page to show
 * next**, so a reader on a phone can pan through a two-column paper one column
 * at a time, edge to edge, without pinching.
 *
 * Detection reads the display list — exactly what a renderer paints — and runs
 * a recursive X-Y cut:
 *  1. Elements are visible text runs, image XObjects mapped through their CTM
 *     and painted paths bounded by their control points (§8.5.3).
 *  2. Elements entirely inside the top/bottom bands are running heads, page
 *     numbers or footnotes and become [FlowSegmentKind.HEADER]/[FlowSegmentKind.FOOTER].
 *  3. A vertical whitespace gutter wider than [ColumnFlowOptions.gutterRatio] of
 *     the content width, with real elements on both sides, splits the page into
 *     columns. It is found from a coverage profile over x-slabs — a slab is free
 *     when its ink is a small share of the busiest slab's, not of the page
 *     height, because a text column is mostly leading whitespace — and its
 *     position sits between the two columns' facing edges, so a ragged right
 *     margin cannot pull it inside a column. A corridor tolerates the little ink
 *     a masthead or a rule puts in it (a small share of the busiest slab's), and
 *     the profile is also taken without the short wide bands (a title, an
 *     abstract, a figure) and the text inside them, so a band lying across a
 *     corridor cannot hide the columns beneath it; of the two passes the one
 *     with more corridors wins. Nothing else splits a page, so a wide word gap
 *     or a pair of boxes is not mistaken for a column layout.
 *  4. A wide element — at least [ColumnFlowOptions.spanningRatio] of the content
 *     width — spans the columns when it crosses two gutters, or one when it is
 *     itself wide (a title, an abstract, a wide figure or table). A wide
 *     element that crosses none is a wide *column* beside a narrow sidebar, and
 *     a narrow element that merely reaches past a gutter belongs to the column
 *     it overlaps most. The columns are cut above and below a band so reading
 *     order is "left above → right above → the figure → left below → right
 *     below". Touching bands are merged, so a three-line abstract is one segment
 *     and a picture with the art drawn inside it is one picture.
 *
 * A page with one column produces one segment: the T57 content bounds. A page
 * whose structure is uncertain stays one segment on purpose ([FlowPage.confidence]
 * below [ColumnFlowOptions.minConfidence] and [FlowPage.fallback] set) because a
 * wrong cut is worse than a slightly wide one.
 *
 * A scanned page has no visible text: the OCR text layer (T25, render mode 3)
 * is used for structure when present; otherwise a low-resolution render finds
 * the printed area ([PageRasterizer]) and the page becomes one segment.
 *
 * Segments are linked into one document-wide chain: [FlowSegment.continuesAt]
 * points to the next segment, across columns and pages, so a caller can simply
 * advance. Nothing is ever written back to the file.
 */
enum class FlowSegmentKind {
    /** A column (or a single-column block) of running text. */
    TEXT,

    /** A picture, a spanning illustration or a diagram. */
    FIGURE,

    /** A spanning table: painted rules with at least two columns of cells. */
    TABLE,

    /** A running head at the top of the page; not part of the flow. */
    HEADER,

    /** A page number or footnote at the foot of the page; not part of the flow. */
    FOOTER
}

/** A reference to one segment by page and reading-order index. */
data class SegmentRef(val pageIndex: Int, val order: Int) {
    override fun toString(): String = "p${pageIndex + 1}#$order"
}

/**
 * One rectangle to show, in page user space (y up, unrotated). [order] is the
 * reading position inside its page and [column] the column it belongs to
 * (0..n, or -1 for a spanning element).
 */
data class FlowSegment(
    val pageIndex: Int,
    val kind: FlowSegmentKind,
    val bounds: PdfRect,
    val column: Int,
    val order: Int,
    val continuesFrom: SegmentRef? = null,
    val continuesAt: SegmentRef? = null
) {
    val ref: SegmentRef get() = SegmentRef(pageIndex, order)

    /** True when [kind] is part of the reading flow (not furniture). */
    val flows: Boolean
        get() = kind == FlowSegmentKind.TEXT ||
            kind == FlowSegmentKind.FIGURE ||
            kind == FlowSegmentKind.TABLE
}

/** One page's detected flow. */
class FlowPage(
    val pageIndex: Int,
    /** The page's current CropBox, unrotated user space. */
    val page: PdfRect,
    val rotate: Int,
    /** Reading order; includes HEADER/FOOTER so a caller can report them. */
    val segments: List<FlowSegment>,
    /** 0..1; how sure the detector is of the column structure. */
    val confidence: Double,
    /** True when the page was deliberately left as one segment. */
    val fallback: Boolean,
    /** True when the page is a scan measured from pixels or its OCR layer. */
    val scanned: Boolean,
    val warnings: List<String>
) {
    /** Only the segments a reader should visit. */
    val flowSegments: List<FlowSegment> get() = segments.filter { it.flows }

    fun segmentAt(order: Int): FlowSegment? = segments.firstOrNull { it.order == order }

    companion object {
        /** A page with nothing painted: one segment covering the page. */
        fun blank(pageIndex: Int, page: PdfRect, rotate: Int): FlowPage = FlowPage(
            pageIndex = pageIndex,
            page = page,
            rotate = rotate,
            segments = listOf(
                FlowSegment(pageIndex, FlowSegmentKind.TEXT, page, 0, 0)
            ),
            confidence = 1.0,
            fallback = true,
            scanned = false,
            warnings = emptyList()
        )
    }
}

/** The whole document's flow. */
class ColumnFlowResult(
    val pages: List<FlowPage>,
    val warnings: List<String>
) {
    val segmentCount: Int get() = pages.sumOf { it.flowSegments.size }

    fun page(index: Int): FlowPage? = pages.getOrNull(index)

    fun segments(index: Int): List<FlowSegment> = pages.getOrNull(index)?.flowSegments.orEmpty()

    /** The first flow segment of the document, or null when it is empty. */
    val first: FlowSegment?
        get() = pages.firstNotNullOfOrNull { it.flowSegments.firstOrNull() }

    fun segment(ref: SegmentRef): FlowSegment? =
        pages.getOrNull(ref.pageIndex)?.segmentAt(ref.order)

    /**
     * The segment [steps] positions from [from] in reading order, clamped to the
     * document. Used by edge taps and the volume keys.
     */
    fun advance(from: SegmentRef?, steps: Int): FlowSegment? {
        val all = pages.flatMap { it.flowSegments }
        if (all.isEmpty()) return null
        val current = from?.let { ref -> all.indexOfFirst { it.ref == ref } } ?: -1
        val target = (current + steps).coerceIn(0, all.size - 1)
        return all[target]
    }
}

/**
 * Detection settings. Lengths are PDF points, fractions are of the page's
 * content width/height after the header/footer bands are removed.
 */
class ColumnFlowOptions(
    /** A gutter must be at least this share of the content width. */
    val gutterRatio: Double = 0.02,
    /** The smallest gutter in points, so a narrow page is not over-cut. */
    val minGutterPoints: Double = 8.0,
    val headerBand: Double = 0.08,
    val footerBand: Double = 0.08,
    /**
     * An element at least this wide (share of content width) is a band
     * candidate before the gutter search runs: a title, an abstract line, a
     * full-width figure.
     */
    val spanningRatio: Double = 0.55,
    /**
     * A wide element no taller than this share of the content height is a band
     * for the second gutter pass: a title, an abstract, a figure. Width alone
     * would take a wide main column beside a narrow sidebar out of the search,
     * so the height is checked as well; a wide element that is also tall is a
     * column and keeps its ink. Whether an element really spans the page is
     * decided once the gutters are known, by how many it crosses.
     */
    val bandRatio: Double = 0.35,
    /** A segment narrower/shorter than this is dropped. */
    val minSegmentPoints: Double = 6.0,
    /** Below this the page is left as one segment. */
    val minConfidence: Double = 0.45,
    /** A picture covering this share of the page is a scan, not a figure. */
    val backgroundCoverage: Double = 0.97,
    val rasterDpi: Int = 72,
    val tolerance: Int = 10
) {
    init {
        require(gutterRatio in 0.002..0.3) { "The gutter ratio must be between 0.2 % and 30 %" }
        require(minGutterPoints >= 0.0) { "The minimum gutter must not be negative" }
        require(headerBand in 0.0..0.4 && footerBand in 0.0..0.4) {
            "The header and footer bands must be between 0 and 40 % of the page"
        }
        require(spanningRatio in 0.3..1.0) { "The spanning ratio must be between 30 % and 100 %" }
        require(bandRatio in 0.05..1.0) { "The band ratio must be between 5 % and 100 %" }
        require(minSegmentPoints >= 0.0) { "The minimum segment must not be negative" }
        require(minConfidence in 0.0..1.0) { "The confidence threshold must be between 0 and 1" }
        require(backgroundCoverage in 0.5..1.0) { "The background coverage must be 50–100 %" }
        require(rasterDpi in 36..300) { "The detection resolution must be between 36 and 300 dpi" }
        require(tolerance in 0..255) { "The tolerance must be between 0 and 255" }
    }
}

/** Runs the detector over a document. Never throws on malformed input. */
object ColumnFlow {

    fun compute(
        document: PdfDocument,
        options: ColumnFlowOptions = ColumnFlowOptions(),
        decoder: ExternalImageDecoder? = null,
        rasterizer: PageRasterizer? = null
    ): Result<ColumnFlowResult> = runCatching {
        val allPages = document.pages()
        require(allPages.isNotEmpty()) { "The document has no pages" }
        val warnings = ArrayList<String>()
        val raw = allPages.mapIndexed { index, page ->
            analyse(page, index, options, decoder, rasterizer, warnings)
        }
        ColumnFlowResult(
            pages = link(raw),
            warnings = warnings
        )
    }

    /** One page. Public so a caller can re-analyse after a manual correction. */
    fun analyse(
        page: PdfPage,
        pageIndex: Int = page.index,
        options: ColumnFlowOptions = ColumnFlowOptions(),
        decoder: ExternalImageDecoder? = null,
        rasterizer: PageRasterizer? = null,
        warnings: MutableList<String> = ArrayList()
    ): FlowPage {
        val box = PageGeometry.boxOf(page)
        val pageRect = PdfRect(box.left, box.bottom, box.right, box.top)
        val list = runCatching { page.buildDisplayList(decoder) }.getOrNull()
            ?: return FlowPage.blank(pageIndex, pageRect, box.rotate).also {
                warnings += "page ${pageIndex + 1}: the content could not be read"
            }
        return fromDisplayList(list, pageIndex, pageRect, box.rotate, options, page, rasterizer)
    }

    /**
     * The detector proper, split out so it is testable without a document: a
     * display list, the page box and the rotation are all it needs.
     */
    fun fromDisplayList(
        list: DisplayList,
        pageIndex: Int,
        page: PdfRect,
        rotate: Int,
        options: ColumnFlowOptions = ColumnFlowOptions(),
        pageObject: PdfPage? = null,
        rasterizer: PageRasterizer? = null
    ): FlowPage {
        val warnings = ArrayList<String>()
        val visible = ArrayList<Element>()
        val invisibleText = ArrayList<Element>()
        val rules = ArrayList<Rule>()
        var pageSizedImage: PdfRect? = null

        for (command in list.commands) {
            when (command) {
                is DrawTextRun -> {
                    val run = command.run
                    if (run.text.isBlank()) continue
                    val rect = PdfRect(run.x0, run.y0, run.x1, run.y1)
                    if (!rect.isFinite() || rect.height <= 0.0) continue
                    // Render mode 3 paints nothing (§9.3.6): an OCR layer. It is
                    // kept separately, because a scan can still be structured by
                    // the text a recogniser placed on it (T25).
                    if (run.renderMode == 3) {
                        invisibleText += Element(rect, ElementKind.TEXT, 0.0)
                    } else {
                        visible += Element(rect, ElementKind.TEXT, run.fontSize)
                    }
                }

                is DrawRasterImage -> {
                    val rect = unitSquareBounds(command.matrix) ?: continue
                    if (!rect.isFinite()) continue
                    if (covers(rect, page) >= options.backgroundCoverage) {
                        pageSizedImage = rect
                        continue
                    }
                    visible += Element(rect, ElementKind.IMAGE, 0.0)
                }

                is FillPath -> {
                    val rect = pathBounds(command.path, command.matrix, 0.0) ?: continue
                    if (covers(rect, page) >= options.backgroundCoverage) continue
                    visible += Element(rect, ElementKind.VECTOR, 0.0)
                    ruleOf(rect)?.let { rules += it }
                }

                is StrokePath -> {
                    val half = command.lineStyle.width * command.matrix.meanScale() / 2.0
                    val rect = pathBounds(command.path, command.matrix, half) ?: continue
                    if (covers(rect, page) >= options.backgroundCoverage) continue
                    visible += Element(rect, ElementKind.VECTOR, 0.0)
                    ruleOf(rect)?.let { rules += it }
                }

                is FillStrokePath -> {
                    val half = command.lineStyle.width * command.matrix.meanScale() / 2.0
                    val rect = pathBounds(command.path, command.matrix, half) ?: continue
                    if (covers(rect, page) >= options.backgroundCoverage) continue
                    visible += Element(rect, ElementKind.VECTOR, 0.0)
                    ruleOf(rect)?.let { rules += it }
                }

                // A clip or a state command carries no ink of its own.
                is ClipPath, is com.pdfpaper.engine.graphics.SaveGraphicsState,
                is com.pdfpaper.engine.graphics.RestoreGraphicsState,
                is com.pdfpaper.engine.graphics.SetBlendMode -> Unit
            }
        }

        // A scan: a page-sized picture and no visible text. Its OCR layer, when
        // one exists, is the best structure available; otherwise measure the
        // ink in a low-resolution render.
        var scanned = false
        var elements = visible
        if (visible.none { it.kind == ElementKind.TEXT } && pageSizedImage != null) {
            scanned = true
            if (invisibleText.isNotEmpty()) {
                elements = invisibleText
            } else if (pageObject != null && rasterizer != null) {
                val printed = runCatching { printedBounds(pageObject, rasterizer, options) }.getOrNull()
                if (printed != null) {
                    return FlowPage(
                        pageIndex = pageIndex,
                        page = page,
                        rotate = rotate,
                        segments = listOf(
                            FlowSegment(pageIndex, FlowSegmentKind.FIGURE, printed, 0, 0)
                        ),
                        confidence = 0.9,
                        fallback = true,
                        scanned = true,
                        warnings = listOf("page ${pageIndex + 1}: scanned page, measured from pixels")
                    )
                }
            }
        }

        if (elements.isEmpty()) {
            return FlowPage(
                pageIndex = pageIndex,
                page = page,
                rotate = rotate,
                segments = listOf(FlowSegment(pageIndex, FlowSegmentKind.TEXT, page, 0, 0)),
                confidence = 1.0,
                fallback = true,
                scanned = scanned,
                warnings = listOf("page ${pageIndex + 1}: no content found, shown whole")
            )
        }

        return layout(pageIndex, page, rotate, elements, rules, options, scanned, warnings)
    }

    // ------------------------------------------------------------------ layout

    private fun layout(
        pageIndex: Int,
        page: PdfRect,
        rotate: Int,
        all: List<Element>,
        rules: List<Rule>,
        options: ColumnFlowOptions,
        scanned: Boolean,
        warnings: MutableList<String>
    ): FlowPage {
        // 1. Running heads, page numbers and footnotes. An element inside the
        //    band is furniture only when it is not a display size: a title sits
        //    in the same strip and is content. A glyph box that straddles the
        //    band edge counts as inside — a page number set one line above the
        //    margin is still a page number, and a box measured from its baseline
        //    reaches half a line higher than the band.
        val headerBottom = page.top - page.height * options.headerBand
        val footerTop = page.bottom + page.height * options.footerBand
        val bodyFontSize = medianFontSize(all)
        val furniture = ArrayList<Element>()
        val content = ArrayList<Element>()
        for (element in all) {
            val slack = element.rect.height * 0.5
            val inHeader = element.rect.bottom >= headerBottom
            val inFooter = element.rect.top <= footerTop + slack
            val small = element.fontSize <= 1.25 * bodyFontSize
            if ((inHeader || inFooter) && small) furniture += element else content += element
        }
        if (content.isEmpty()) {
            return FlowPage(
                pageIndex = pageIndex,
                page = page,
                rotate = rotate,
                segments = listOf(FlowSegment(pageIndex, FlowSegmentKind.TEXT, page, 0, 0)),
                confidence = 1.0,
                fallback = true,
                scanned = scanned,
                warnings = warnings + "page ${pageIndex + 1}: only page furniture, shown whole"
            )
        }

        val contentBounds = union(content.map { it.rect }) ?: page

        // 2. A grid of painted rules is a table, not a picture. Its members are
        //    taken out of the column search and shown whole, because a table cut
        //    down the middle of a cell is unreadable.
        val tableRects = findTableRegions(rules, content, contentBounds, page, options)
        val free = content.filter { element ->
            tableRects.none { rect ->
                element.rect.centerX in rect.left..rect.right &&
                    element.rect.centerY in rect.bottom..rect.top
            }
        }

        // 3. Wide elements — at least spanningRatio of the content width — are
        //    band *candidates*: a title, an abstract, a full-width figure. They
        //    are not taken out of the search yet, because a wide main column
        //    beside a narrow sidebar is just as wide and must stay a column;
        //    whether an element really spans the page is decided once the
        //    gutters are known (step 5).
        val wide = free.filter { element ->
            contentBounds.width > 0.0 &&
                element.rect.width / contentBounds.width >= options.spanningRatio
        }.toSet()

        // 3b. An element drawn inside a wide element belongs to it: the wording
        //     on an advert, the art inside a figure. Whether it is absorbed
        //     depends on the container ending up as a band, which is known after
        //     step 5, so the pair is only recorded here.
        val insideOf = ArrayList<Pair<Element, Element>>()
        for (element in free) {
            if (element in wide) continue
            val container = wide.firstOrNull { box ->
                element.rect.centerX in box.rect.left..box.rect.right &&
                    element.rect.centerY in box.rect.bottom..box.rect.top
            } ?: continue
            insideOf += element to container
        }

        // 4. Gutters: vertical white corridors with ink on both sides. A wide
        //    band lying across a corridor blocks it — a full-width figure fills
        //    the gutter over its whole height — so the search is also run
        //    without the short wide bands (a title, an abstract, a figure) and
        //    the result with more corridors wins. It can only add corridors,
        //    never lose one: removing ink cannot make a free slab busy, and a
        //    corridor still needs ink on both sides from what remains, so a
        //    corridor that only the retry finds is a real column boundary.
        //    A wide *column* (taller than bandRatio) keeps its ink, and a page
        //    whose columns are both wide never needs the retry at all.
        val bandHeight = contentBounds.height * options.bandRatio
        val shortBands = wide.filter { it.rect.height <= bandHeight }.toSet()
        var gutters = findGutters(free, contentBounds, page, options)
        if (shortBands.isNotEmpty()) {
            // The wording inside a band belongs to the band: an advert's text
            // starts inside the corridor the advert box spans, so it must not
            // block it either.
            val bandMembers =
                shortBands + insideOf.filter { it.second in shortBands }.map { it.first }
            val withoutBands =
                findGutters(free.filter { it !in bandMembers }, contentBounds, page, options)
            if (withoutBands.size > gutters.size) gutters = withoutBands
        }

        if (gutters.isEmpty()) {
            // One column: the content bounds are the segment (the T57 rule).
            val bounds = union(content.map { it.rect }) ?: page
            val confidence = (0.75 + 0.2 * textShareOf(content)).coerceIn(0.0, 1.0)
            return FlowPage(
                pageIndex = pageIndex,
                page = page,
                rotate = rotate,
                segments = listOf(
                    FlowSegment(pageIndex, FlowSegmentKind.TEXT, bounds, 0, 0)
                ) + furnitureSegments(pageIndex, furniture, headerBottom, 1),
                confidence = confidence,
                fallback = false,
                scanned = scanned,
                warnings = warnings
            )
        }

        val columns = buildColumns(contentBounds, gutters)

        // 5. Every element is either a band or part of a column. A band crosses
        //    two or more gutters (it really does run across the page) or is wide
        //    and crosses at least one. A narrow element that merely reaches past
        //    a gutter — a ragged margin, a long word — and a wide element that
        //    crosses none (a main column beside a narrow sidebar) belong to the
        //    column they overlap most.
        val spanning = ArrayList<Element>()
        for (rect in tableRects) spanning += Element(rect, ElementKind.TABLE, 0.0)
        val bands = HashSet<Element>()
        fun crossingsOf(element: Element): Int = gutters.count { gutter ->
            element.rect.left < gutter - 1e-6 && element.rect.right > gutter + 1e-6
        }
        for (element in free) {
            val crossing = crossingsOf(element)
            if (crossing >= 2 || (crossing > 0 && element in wide)) {
                spanning += element
                bands += element
            }
        }

        val perColumn = ArrayList<MutableList<Element>>(columns.size)
        repeat(columns.size) { perColumn += ArrayList<Element>() }
        for (element in free) {
            if (element in bands) continue
            val index = widestOverlap(element, columns)
            if (index >= 0) perColumn[index] += element else spanning += element
        }

        // An element inside a band is part of that band, never a column of its
        // own; an element inside a wide element that turned out to be a column
        // stays where it was assigned.
        for ((inner, container) in insideOf) {
            if (container !in bands || inner in bands) continue
            for (column in perColumn) column.remove(inner)
            spanning += inner
            bands += inner
        }

        // 5b. Neighbouring spanning elements are one block: the lines of a
        //     full-width abstract are one paragraph, and a picture plus the art
        //     drawn inside it is one picture. A block takes the strongest kind it
        //     contains (table, then figure, then text), so text that lies inside
        //     a figure or a table is part of that picture rather than a second
        //     reading of it and is not emitted again.
        val blocks = mergeBlocks(spanning)

        // 6. Segments, in reading order: bands from top to bottom and, inside a
        //    band, every column from left to right. A spanning block cuts the
        //    bands, so it is read after everything above it and before
        //    everything below it. That is the recursive X-Y cut.
        val segments = ArrayList<FlowSegment>()
        val ordered = blocks.sortedWith(
            compareByDescending<Block> { it.rect.top }.thenBy { it.rect.left }
        )
        val emitted = BooleanArray(ordered.size)

        fun emitText(
            columnIndex: Int,
            members: List<Element>,
            bandBottom: Double,
            bandTop: Double,
            columnWidth: Double
        ) {
            if (members.isEmpty()) return
            val figures = members.filter {
                it.kind == ElementKind.IMAGE &&
                    it.rect.width >= columnWidth * 0.6 &&
                    it.rect.height >= options.minSegmentPoints
            }
            fun emit(list: List<Element>) {
                val bounds = union(list.map { it.rect }) ?: return
                if (bounds.width < options.minSegmentPoints ||
                    bounds.height < options.minSegmentPoints
                ) {
                    return
                }
                segments += FlowSegment(
                    pageIndex = pageIndex,
                    kind = FlowSegmentKind.TEXT,
                    bounds = bounds,
                    column = columnIndex,
                    order = 0
                )
            }
            if (figures.isEmpty()) {
                emit(members)
                return
            }
            // A picture nearly as wide as its own column is a block of its own:
            // text above and below it reads in order and the picture is shown
            // whole, fitted to the width. This is the horizontal cut.
            var cursor = bandTop
            for (figure in figures.sortedByDescending { it.rect.top }) {
                emit(
                    members.filter {
                        it !in figures && it.rect.centerY in figure.rect.top..cursor
                    }
                )
                segments += FlowSegment(
                    pageIndex = pageIndex,
                    kind = FlowSegmentKind.FIGURE,
                    bounds = figure.rect,
                    column = columnIndex,
                    order = 0
                )
                cursor = min(cursor, figure.rect.bottom)
            }
            emit(
                members.filter {
                    it !in figures && it.rect.centerY in bandBottom..cursor
                }
            )
        }

        fun columnSegments(columnIndex: Int, bandBottom: Double, bandTop: Double) {
            val (left, right) = columns[columnIndex]
            val members = perColumn[columnIndex].filter { it.rect.centerY in bandBottom..bandTop }
            emitText(columnIndex, members, bandBottom, bandTop, right - left)
        }

        fun spanSegment(index: Int) {
            if (emitted[index]) return
            emitted[index] = true
            val block = ordered[index]
            val bounds = block.rect
            if (bounds.width < options.minSegmentPoints ||
                bounds.height < options.minSegmentPoints
            ) {
                return
            }
            val kind = when (block.kind) {
                ElementKind.TEXT -> FlowSegmentKind.TEXT
                ElementKind.IMAGE -> FlowSegmentKind.FIGURE
                ElementKind.TABLE -> FlowSegmentKind.TABLE
                ElementKind.VECTOR -> FlowSegmentKind.FIGURE
            }
            segments += FlowSegment(pageIndex, kind, bounds, -1, 0)
        }

        val sidebarColumns = sidebarColumns(columns, perColumn)
        val columnOrder = columns.indices.sortedWith(
            compareBy<Int> { if (it in sidebarColumns) 1 else 0 }.thenBy { it }
        )

        var cursorTop = contentBounds.top
        for (index in ordered.indices) {
            val cut = ordered[index].rect
            // A cut at or above the cursor has already been passed: the first
            // cut usually starts exactly at the top of the content.
            if (cut.bottom >= cursorTop - 1e-6) continue
            if (cursorTop - cut.top > options.minSegmentPoints) {
                for (columnIndex in columnOrder) {
                    columnSegments(columnIndex, cut.top, cursorTop)
                }
            }
            spanSegment(index)
            cursorTop = min(cursorTop, cut.bottom)
        }
        if (cursorTop - contentBounds.bottom > options.minSegmentPoints) {
            for (columnIndex in columnOrder) {
                columnSegments(columnIndex, contentBounds.bottom, cursorTop)
            }
        }
        // A block that shared a rectangle or overlapped no band still gets its
        // place, so nothing is silently lost.
        for (index in ordered.indices) spanSegment(index)

        val numbered = segments.mapIndexed { index, segment -> segment.copy(order = index) }

        val confidence = confidenceOf(
            gutters = gutters,
            columns = columns,
            perColumn = perColumn,
            sidebarColumns = sidebarColumns,
            contentBounds = contentBounds
        )
        val fallback = confidence < options.minConfidence || numbered.isEmpty()
        if (fallback) {
            warnings += "page ${pageIndex + 1}: the column layout was uncertain, shown as one block"
            return FlowPage(
                pageIndex = pageIndex,
                page = page,
                rotate = rotate,
                segments = listOf(FlowSegment(pageIndex, FlowSegmentKind.TEXT, contentBounds, 0, 0)),
                confidence = confidence,
                fallback = true,
                scanned = scanned,
                warnings = warnings
            )
        }

        val furnitureSegments = furnitureSegments(
            pageIndex,
            furniture,
            headerBottom,
            numbered.size
        )

        return FlowPage(
            pageIndex = pageIndex,
            page = page,
            rotate = rotate,
            segments = numbered + furnitureSegments,
            confidence = confidence,
            fallback = false,
            scanned = scanned,
            warnings = warnings
        )
    }

    /** Page floor and numbers, ordered after the reading segments. */
    private fun furnitureSegments(
        pageIndex: Int,
        furniture: List<Element>,
        headerBottom: Double,
        from: Int
    ): List<FlowSegment> = furniture
        .sortedWith(compareByDescending<Element> { it.rect.top }.thenBy { it.rect.left })
        .mapIndexed { index, element ->
            FlowSegment(
                pageIndex = pageIndex,
                kind = if (element.rect.bottom >= headerBottom) {
                    FlowSegmentKind.HEADER
                } else {
                    FlowSegmentKind.FOOTER
                },
                bounds = element.rect,
                column = -1,
                order = from + index
            )
        }

    /**
     * Painted rules that form a grid: at least two horizontal rules with two
     * vertical rules crossing them, and real text between them. The union is one
     * table region. A ruled table is the only reliable evidence — guessing one
     * from aligned text would take a table detector, not a layout detector.
     */
    private fun findTableRegions(
        rules: List<Rule>,
        content: List<Element>,
        bounds: PdfRect,
        page: PdfRect,
        options: ColumnFlowOptions
    ): List<PdfRect> {
        if (rules.size < 4 || bounds.width <= 0.0) return emptyList()
        val horizontals = rules
            .filter { it.horizontal && it.end - it.start >= bounds.width * 0.45 }
            .sortedByDescending { it.position }
        if (horizontals.size < 2) return emptyList()

        val maxGap = max(options.minSegmentPoints * 4, page.height * 0.12)
        val groups = ArrayList<MutableList<Rule>>()
        var current = mutableListOf(horizontals.first())
        for (rule in horizontals.drop(1)) {
            if (current.last().position - rule.position <= maxGap) {
                current += rule
            } else {
                groups += current
                current = mutableListOf(rule)
            }
        }
        groups += current

        val tables = ArrayList<PdfRect>()
        for (group in groups) {
            if (group.size < 2) continue
            val top = group.first().position
            val bottom = group.last().position
            if (top - bottom < options.minSegmentPoints * 3) continue
            val left = group.minOf { it.start }
            val right = group.maxOf { it.end }
            val verticals = rules.count { rule ->
                !rule.horizontal &&
                    rule.position in left..right &&
                    rule.start <= bottom + 1.0 &&
                    rule.end >= top - 1.0
            }
            if (verticals < 2) continue
            val inside = content.count {
                it.rect.centerX in left..right && it.rect.centerY in bottom..top
            }
            if (inside < 2) continue
            val rect = PdfRect(left, bottom, right, top)
            if (tables.none { it.left == rect.left && it.bottom == rect.bottom }) tables += rect
        }
        return tables
    }

    /** A thin, long painted path: a table rule or a divider, not a picture. */
    private fun ruleOf(rect: PdfRect): Rule? {
        if (rect.width <= 0.0 || rect.height <= 0.0) return null
        val horizontal = rect.height <= max(2.0, rect.width * 0.02) && rect.width >= 20.0
        if (horizontal) {
            return Rule(true, (rect.top + rect.bottom) / 2.0, rect.left, rect.right)
        }
        val vertical = rect.width <= max(2.0, rect.height * 0.02) && rect.height >= 20.0
        if (vertical) {
            return Rule(false, (rect.left + rect.right) / 2.0, rect.bottom, rect.top)
        }
        return null
    }

    /**
     * A narrow, short column beside a wide, tall one is a sidebar (a margin
     * note, a caption column), not a second column of the main text: it is read
     * after the main block beside it, not before it.
     */
    private fun sidebarColumns(
        columns: List<Pair<Double, Double>>,
        perColumn: List<MutableList<Element>>
    ): Set<Int> {
        val used = perColumn.indices.filter { perColumn[it].isNotEmpty() }
        if (used.size < 2) return emptySet()
        val widest = used.maxOf { columns[it].second - columns[it].first }
        val tallest = used.maxOf { index ->
            val own = perColumn[index]
            own.maxOf { it.rect.top } - own.minOf { it.rect.bottom }
        }
        return used.filter { index ->
            val own = perColumn[index]
            val width = columns[index].second - columns[index].first
            val height = own.maxOf { it.rect.top } - own.minOf { it.rect.bottom }
            width < widest * 0.55 && height < tallest * 0.75
        }.toSet()
    }

    /**
     * Vertical corridors of whitespace, found from a coverage profile rather
     * than from a left-to-right sweep. Each x-slab between two element edges
     * carries some ink; a slab is free when it carries almost none of the
     * page's ink. The comparison is with the **busiest** slab, not with the
     * page height, because a text column is mostly leading whitespace: measured
     * against the page a column would look empty, and the page would be cut
     * inside it.
     *
     * The corridor's position is taken between the two columns' facing edges,
     * not between the ends of the whitespace run: a ragged right margin widens
     * the run into the column beside it, and the run's midpoint would fall
     * inside that column and split it.
     *
     * A corridor qualifies when it is wide enough, both sides carry ink, and the
     * two sides overlap for a real share of the **page** height — real columns
     * run alongside each other, while a word gap or a pair of boxes does not.
     */
    private fun findGutters(
        content: List<Element>,
        bounds: PdfRect,
        page: PdfRect,
        options: ColumnFlowOptions
    ): List<Double> {
        if (content.isEmpty() || bounds.width <= 0.0 || bounds.height <= 0.0) return emptyList()
        val minGutter = max(options.minGutterPoints, bounds.width * options.gutterRatio)
        val minOverlap = page.height * 0.15
        val edges = content
            .flatMap { listOf(it.rect.left, it.rect.right) }
            .map { it.coerceIn(bounds.left, bounds.right) }
            .distinct()
            .sorted()
        if (edges.size < 3) return emptyList()

        val ink = DoubleArray(edges.size - 1)
        for (index in ink.indices) {
            val x0 = edges[index]
            val x1 = edges[index + 1]
            if (x1 - x0 <= 1e-6) continue
            val intervals = content
                .filter { it.rect.left <= x0 + 1e-6 && it.rect.right >= x1 - 1e-6 }
                .map { it.rect.bottom to it.rect.top }
                .sortedBy { it.first }
            var covered = 0.0
            var start = Double.NaN
            var end = 0.0
            for ((bottom, top) in intervals) {
                if (start.isNaN()) {
                    start = bottom
                    end = top
                } else if (bottom <= end) {
                    end = max(end, top)
                } else {
                    covered += end - start
                    start = bottom
                    end = top
                }
            }
            if (!start.isNaN()) covered += end - start
            ink[index] = covered
        }
        val freeInk = max(1.0, ink.max() * INK_SHARE)

        val gutters = ArrayList<Double>()
        var runStart = -1
        for (index in 0..ink.size) {
            val open = index < ink.size && ink[index] <= freeInk
            if (open && runStart < 0) runStart = index
            if (!open && runStart >= 0) {
                val left = edges[runStart]
                val right = edges[index]
                runStart = -1
                if (right - left < minGutter) continue
                val middle = gutterPosition(content, left, right)
                val leftSide = content.filter { it.rect.centerX < middle }
                val rightSide = content.filter { it.rect.centerX > middle }
                if (leftSide.isEmpty() || rightSide.isEmpty()) continue
                if (verticalOverlap(leftSide, rightSide) < minOverlap) continue
                if (gutters.none { abs(it - middle) < minGutter }) gutters += middle
            }
        }
        return gutters.sorted()
    }

    /**
     * Where the corridor really is: half way between the right edge of the
     * column on its left and the left edge of the column on its right.
     */
    private fun gutterPosition(content: List<Element>, left: Double, right: Double): Double {
        val leftEdge = content.filter { it.rect.centerX < left }.maxOfOrNull { it.rect.right }
        val rightEdge = content.filter { it.rect.centerX > right }.minOfOrNull { it.rect.left }
        if (leftEdge != null && rightEdge != null && leftEdge < rightEdge) {
            return (leftEdge + rightEdge) / 2.0
        }
        return (left + right) / 2.0
    }

    /** The middle of the visible text sizes, the "body size" of the page. */
    private fun medianFontSize(elements: List<Element>): Double {
        val sizes = elements.filter { it.kind == ElementKind.TEXT }
            .map { it.fontSize }
            .filter { it > 0.0 }
            .sorted()
        if (sizes.isEmpty()) return 0.0
        return sizes[sizes.size / 2]
    }

    /** How much of the content is text (used by the confidence score). */
    private fun textShareOf(elements: List<Element>): Double {
        if (elements.isEmpty()) return 0.0
        return elements.count { it.kind == ElementKind.TEXT }.toDouble() / elements.size
    }

    /**
     * How much two groups of elements share the same vertical extent. Real
     * columns run alongside each other; a footnote beside a figure does not.
     */
    private fun verticalOverlap(left: List<Element>, right: List<Element>): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val leftTop = left.maxOf { it.rect.top }
        val leftBottom = left.minOf { it.rect.bottom }
        val rightTop = right.maxOf { it.rect.top }
        val rightBottom = right.minOf { it.rect.bottom }
        return max(0.0, min(leftTop, rightTop) - max(leftBottom, rightBottom))
    }

    /** Column slabs between the gutters, clamped to the content bounds. */
    private fun buildColumns(bounds: PdfRect, gutters: List<Double>): List<Pair<Double, Double>> {
        val columns = ArrayList<Pair<Double, Double>>()
        var left = bounds.left
        for (gutter in gutters) {
            if (gutter - left <= 0.0) continue
            columns += left to gutter
            left = gutter
        }
        columns += left to bounds.right
        return columns
    }

    /**
     * The column an element belongs to: the one it overlaps horizontally the
     * most. An element that straddles a gutter but is not wide enough to be a
     * band still belongs to a column — usually the one its centre is in — and
     * this keeps a ragged margin or a long word from becoming a band that cuts
     * the whole page in two.
     */
    private fun widestOverlap(element: Element, columns: List<Pair<Double, Double>>): Int {
        var best = -1
        var bestOverlap = 0.0
        for (index in columns.indices) {
            val (left, right) = columns[index]
            val overlap = min(element.rect.right, right) - max(element.rect.left, left)
            if (overlap > bestOverlap + 1e-6) {
                best = index
                bestOverlap = overlap
            }
        }
        return best
    }

    /**
     * Confidence in the cut. A single column is confident when text dominates.
     * A multi-column page is confident when every column carries real ink and
     * the columns run alongside each other; a page that looks like one long
     * column next to a stub is deliberately scored low so the caller falls back
     * to a single block (a wrong cut is worse than a slightly wide one).
     */
    private fun confidenceOf(
        gutters: List<Double>,
        columns: List<Pair<Double, Double>>,
        perColumn: List<MutableList<Element>>,
        sidebarColumns: Set<Int>,
        contentBounds: PdfRect
    ): Double {
        val textCount = perColumn.sumOf { column -> column.count { it.kind == ElementKind.TEXT } }
        val total = perColumn.sumOf { it.size }
        if (total == 0) return 0.0
        val textShare = textCount.toDouble() / total

        if (gutters.isEmpty()) {
            // One column: confident when there is real text. A page of pure
            // vector art is still usable, just less certain.
            return (0.75 + 0.2 * textShare).coerceIn(0.0, 1.0)
        }

        val used = perColumn.filter { it.isNotEmpty() }
        if (used.size < 2) return 0.2

        // One main column beside a sidebar is a trustworthy layout; the sidebar
        // is short by definition, so the balance rule must not reject it.
        val mainIndices = perColumn.indices.filter {
            perColumn[it].isNotEmpty() && it !in sidebarColumns
        }
        if (mainIndices.size <= 1 && sidebarColumns.isNotEmpty()) {
            return (0.62 + 0.15 * textShare).coerceIn(0.0, 1.0)
        }

        val measured = if (mainIndices.size >= 2) {
            mainIndices.map { perColumn[it] }
        } else {
            used
        }
        val heights = measured.map { column ->
            column.maxOf { it.rect.top } - column.minOf { it.rect.bottom }
        }
        val shortest = heights.min()
        val tallest = heights.max()
        val contentHeight = if (contentBounds.height > 0.0) contentBounds.height else tallest
        val share = if (contentHeight <= 0.0) 0.0 else shortest / contentHeight
        // A column that only runs a third of the page beside a full-height one
        // is almost certainly not a real column layout.
        if (share < 0.35) return 0.30

        val balance = if (tallest <= 0.0) 0.0 else shortest / tallest
        val widths = if (mainIndices.size >= 2) {
            mainIndices.map { columns[it].second - columns[it].first }
        } else {
            columns.map { it.second - it.first }
        }
        val widest = widths.max()
        val narrowest = widths.min()
        val widthBalance = if (widest <= 0.0) 0.0 else narrowest / widest

        val score = 0.45 * balance + 0.25 * widthBalance + 0.30 * share
        return (score * (0.85 + 0.15 * textShare)).coerceIn(0.0, 1.0)
    }

    // ------------------------------------------------------------------ linking

    /**
     * Joins every page's segments into one reading chain. The order inside a
     * page is already the reading order, so the chain is simply document order;
     * a column that ends at the bottom of one page therefore continues at the
     * first segment of the next.
     */
    fun link(pages: List<FlowPage>): List<FlowPage> {
        val flat = pages.flatMap { page -> page.flowSegments.map { page.pageIndex to it } }
        val index = HashMap<SegmentRef, Int>()
        flat.forEachIndexed { position, (_, segment) -> index[segment.ref] = position }

        return pages.map { page ->
            val segments = page.segments.map { segment ->
                if (!segment.flows) {
                    segment
                } else {
                    val position = index[segment.ref] ?: -1
                    val previous = if (position > 0) flat[position - 1].second.ref else null
                    val next = if (position >= 0 && position < flat.size - 1) {
                        flat[position + 1].second.ref
                    } else {
                        null
                    }
                    segment.copy(continuesFrom = previous, continuesAt = next)
                }
            }
            FlowPage(
                pageIndex = page.pageIndex,
                page = page.page,
                rotate = page.rotate,
                segments = segments,
                confidence = page.confidence,
                fallback = page.fallback,
                scanned = page.scanned,
                warnings = page.warnings
            )
        }
    }

    // ------------------------------------------------------ manual corrections

    /**
     * Replaces a page's reading segments with [boxes], the result of a manual
     * correction (a merge, a split or a dragged edge). Page furniture is kept,
     * because a correction is about the reading order, not about hiding a
     * running head. Call [link] again afterwards so the chain follows.
     */
    fun withSegments(page: FlowPage, boxes: List<PdfRect>): FlowPage {
        val furniture = page.segments.filter { !it.flows }
        val ordered = boxes
            .filter { it.width > 0.0 && it.height > 0.0 }
            .sortedWith(compareByDescending<PdfRect> { it.top }.thenBy { it.left })
        val segments = ordered.mapIndexed { index, rect ->
            FlowSegment(page.pageIndex, FlowSegmentKind.TEXT, rect, 0, index)
        } + furniture.mapIndexed { index, segment ->
            segment.copy(order = ordered.size + index)
        }
        return FlowPage(
            pageIndex = page.pageIndex,
            page = page.page,
            rotate = page.rotate,
            segments = segments,
            confidence = page.confidence,
            fallback = false,
            scanned = page.scanned,
            warnings = page.warnings
        )
    }

    /** The union of [refs]: one box for a manual merge. Null when none exist. */
    fun mergeBoxes(page: FlowPage, refs: List<SegmentRef>): PdfRect? = PdfRect.union(
        page.segments.filter { it.ref in refs && it.flows }.map { it.bounds }
    )

    /** Two boxes cut out of [segment] at [y] (page space): a manual split. */
    fun splitBox(segment: FlowSegment, y: Double): List<PdfRect> {
        val bounds = segment.bounds
        if (y <= bounds.bottom || y >= bounds.top) return listOf(bounds)
        return listOf(
            PdfRect(bounds.left, bounds.bottom, bounds.right, y),
            PdfRect(bounds.left, y, bounds.right, bounds.top)
        )
    }

    // ------------------------------------------------------------- scanned pages

    /**
     * The printed area of a scan, found in a low-resolution render with the
     * T46 white-margin rule: a pixel is ink when its darkest channel is far
     * enough from white. Mapped back through the render plan, so a rotated or
     * cropped page is handled.
     */
    private fun printedBounds(
        page: PdfPage,
        rasterizer: PageRasterizer,
        options: ColumnFlowOptions
    ): PdfRect? {
        val box = PageGeometry.boxOf(page)
        val plan = PageGeometry.plan(box, options.rasterDpi.toDouble() / 72.0)
        val raster = rasterizer.rasterize(page, plan) ?: return null
        val width = raster.width
        val height = raster.height
        if (width <= 0 || height <= 0) return null
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = (y * width + x) * 4
                if (index + 2 >= raster.rgba.size) continue
                val red = raster.rgba[index].toInt() and 0xFF
                val green = raster.rgba[index + 1].toInt() and 0xFF
                val blue = raster.rgba[index + 2].toInt() and 0xFF
                val darkest = minOf(red, green, blue)
                if (255 - darkest > options.tolerance) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0 || maxY < 0) return null
        val inverse = plan.matrix.inverse() ?: return null
        val corners = listOf(
            minX.toDouble() to minY.toDouble(),
            (maxX + 1).toDouble() to minY.toDouble(),
            minX.toDouble() to (maxY + 1).toDouble(),
            (maxX + 1).toDouble() to (maxY + 1).toDouble()
        ).map { (px, py) ->
            val point = inverse.apply(px, py)
            PdfRect(point[0], point[1], point[0], point[1])
        }
        return PdfRect.union(corners)
    }

    // ---------------------------------------------------------------- geometry

    private enum class ElementKind { TEXT, IMAGE, VECTOR, TABLE }

    /**
     * A slab is free when its ink is at most this share of the busiest slab's
     * ink. Measured against the page height instead, every column interior would
     * look free (text is mostly leading whitespace) and the page would be cut
     * inside a column.
     */
    private const val INK_SHARE = 0.12

    private class Element(val rect: PdfRect, val kind: ElementKind, val fontSize: Double)

    /** A spanning block: one rectangle to show whole, with its strongest kind. */
    private class Block(
        val rect: PdfRect,
        val kind: ElementKind,
        val members: List<Element>
    )

    /**
     * Groups spanning elements into blocks. Two elements belong together when
     * their rectangles intersect — a picture and the art drawn inside it, an
     * advert box and its wording — or when they are the same kind, overlap
     * horizontally and sit only a hair apart (the lines of one abstract). A
     * block takes the strongest kind it contains, so a picture with its caption
     * drawn over it stays one picture instead of becoming text again.
     */
    private fun mergeBlocks(elements: List<Element>): List<Block> {
        if (elements.isEmpty()) return emptyList()
        val groups = ArrayList<MutableList<Element>>()
        val sorted = elements.sortedWith(
            compareByDescending<Element> { it.rect.top }.thenBy { it.rect.left }
        )
        for (element in sorted) {
            var target: MutableList<Element>? = null
            for (group in groups) {
                val bounds = union(group.map { it.rect }) ?: continue
                if (joins(bounds, blockKind(group), element)) {
                    target = group
                    break
                }
            }
            if (target != null) target += element else groups += mutableListOf(element)
        }
        return groups.mapNotNull { group ->
            val rect = union(group.map { it.rect }) ?: return@mapNotNull null
            Block(rect, blockKind(group), group.toList())
        }
    }

    /** Whether [element] belongs to the block [block] of kind [kind]. */
    private fun joins(block: PdfRect, kind: ElementKind, element: Element): Boolean {
        val overlap = min(block.right, element.rect.right) - max(block.left, element.rect.left)
        if (overlap <= 1e-6) return false
        val inside = element.rect.bottom < block.top - 1e-6 && element.rect.top > block.bottom + 1e-6
        if (inside) return true
        if (familyOf(element.kind) != familyOf(kind)) return false
        val gap = if (element.rect.top >= block.top) {
            element.rect.bottom - block.top
        } else {
            block.bottom - element.rect.top
        }
        return gap <= max(2.0, 0.5 * min(element.rect.height, block.height))
    }

    /** Text, a table and a picture are three families for block merging. */
    private fun familyOf(kind: ElementKind): Int = when (kind) {
        ElementKind.TEXT -> 0
        ElementKind.TABLE -> 1
        ElementKind.IMAGE, ElementKind.VECTOR -> 2
    }

    /** A block's kind is the strongest it contains: table, figure, then text. */
    private fun blockKind(elements: List<Element>): ElementKind = when {
        elements.any { it.kind == ElementKind.TABLE } -> ElementKind.TABLE
        elements.any { it.kind == ElementKind.IMAGE || it.kind == ElementKind.VECTOR } ->
            ElementKind.IMAGE
        else -> ElementKind.TEXT
    }

    /** A painted rule: a horizontal or vertical thin line (§8.5.3). */
    private class Rule(
        val horizontal: Boolean,
        val position: Double,
        val start: Double,
        val end: Double
    )

    private fun union(rects: List<PdfRect>): PdfRect? = PdfRect.union(rects)

    /** The unit square of image space mapped through [matrix] (§8.9.5.2). */
    private fun unitSquareBounds(matrix: com.pdfpaper.engine.geom.PdfMatrix): PdfRect? {
        val points = listOf(
            0.0 to 0.0,
            1.0 to 0.0,
            0.0 to 1.0,
            1.0 to 1.0
        ).map { (x, y) -> matrix.apply(x, y) }
        if (points.any { !it[0].isFinite() || !it[1].isFinite() }) return null
        return PdfRect(
            points.minOf { it[0] },
            points.minOf { it[1] },
            points.maxOf { it[0] },
            points.maxOf { it[1] }
        )
    }

    /**
     * Bounds of a painted path through [matrix], grown by [halfStroke] on every
     * side. Curves are bounded by their control points, which can only
     * over-estimate and therefore never cuts content.
     */
    private fun pathBounds(
        path: PdfPath,
        matrix: com.pdfpaper.engine.geom.PdfMatrix,
        halfStroke: Double
    ): PdfRect? {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var any = false
        fun add(x: Double, y: Double) {
            val point = matrix.apply(x, y)
            val px = point[0]
            val py = point[1]
            if (!px.isFinite() || !py.isFinite()) return
            any = true
            if (px < minX) minX = px
            if (py < minY) minY = py
            if (px > maxX) maxX = px
            if (py > maxY) maxY = py
        }
        for (segment in path.segments) {
            when (segment) {
                is MoveTo -> add(segment.x, segment.y)
                is LineTo -> add(segment.x, segment.y)
                is CurveTo -> {
                    add(segment.x1, segment.y1)
                    add(segment.x2, segment.y2)
                    add(segment.x3, segment.y3)
                }
                is ClosePath -> Unit
            }
        }
        if (!any) return null
        return PdfRect(
            minX - halfStroke,
            minY - halfStroke,
            maxX + halfStroke,
            maxY + halfStroke
        )
    }

    /** The share of [page] that [rect] covers, 0..1. */
    private fun covers(rect: PdfRect, page: PdfRect): Double {
        val area = page.width * page.height
        if (area <= 0.0) return 0.0
        val width = min(rect.right, page.right) - max(rect.left, page.left)
        val height = min(rect.top, page.top) - max(rect.bottom, page.bottom)
        if (width <= 0.0 || height <= 0.0) return 0.0
        return (width * height / area).coerceIn(0.0, 1.0)
    }

    private fun PdfRect.isFinite(): Boolean =
        left.isFinite() && bottom.isFinite() && right.isFinite() && top.isFinite()
}
