package com.pdfpaper.engine.fit

import com.pdfpaper.engine.export.PageRasterizer
import com.pdfpaper.engine.geom.PdfMatrix
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

/** What produced a page's content bounds (T57). */
enum class ContentSource {
    /** Visible text runs only. */
    TEXT,

    /** Raster images only. */
    IMAGE,

    /** Painted vector geometry only. */
    VECTOR,

    /** A scanned page whose content came from the low-resolution render. */
    RASTER,

    /** Nothing painted inside the page (or only a full-page background). */
    BLANK
}

/**
 * How aggressively content is detected. All lengths are PDF points (1 pt =
 * 1/72 in) and all fractions are of the page's displayed size.
 *
 * [headerBand]/[footerBand] are the strips at the visual top and bottom that
 * [ignoreHeaderFooter] drops: running heads, page numbers and the rules under
 * them otherwise keep the margins as wide as the original page.
 *
 * [backgroundCoverage] is the share of the page a filled rectangle or image
 * must cover to count as a page background. A page-sized rectangle is
 * decorative and ignored; a page-sized image is real content, so that page is
 * kept whole rather than cropped.
 */
class ContentFitOptions(
    val padding: Double = DEFAULT_PADDING,
    val ignoreHeaderFooter: Boolean = true,
    val headerBand: Double = 0.10,
    val footerBand: Double = 0.10,
    val backgroundCoverage: Double = 0.97,
    /** Pages further than this factor from the median are fitted per page. */
    val outlierRatio: Double = 1.35,
    /**
     * True for the document-wide box; false gives every page its own content
     * box (the viewer's "each page" mode).
     */
    val sharedSize: Boolean = true,
    /** Odd and even pages get separate boxes above this relative difference. */
    val parityThreshold: Double = 0.08,
    /** Blank-pixel tolerance of the scanned-page detection. */
    val tolerance: Int = 10,
    /** Resolution of the scanned-page detection render. */
    val rasterDpi: Int = 72
) {
    init {
        require(padding.isFinite() && padding >= 0.0) { "The padding must be finite and not negative" }
        require(headerBand in 0.0..0.4 && footerBand in 0.0..0.4) {
            "The header and footer bands must be between 0 and 40 % of the page"
        }
        require(backgroundCoverage in 0.5..1.0) {
            "The background coverage must be between 50 and 100 %"
        }
        require(outlierRatio >= 1.0) { "The outlier ratio must be at least 1" }
        require(parityThreshold >= 0.0) { "The parity threshold must not be negative" }
        require(tolerance in 0..255) { "The tolerance must be between 0 and 255" }
        require(rasterDpi in 36..300) { "The detection resolution must be between 36 and 300 dpi" }
    }

    companion object {
        /** A few points so a glyph's ink never touches the screen edge. */
        const val DEFAULT_PADDING: Double = 4.0
    }
}

/** A displayed box size in points, measured with the page rotation applied. */
data class FitSize(val width: Double, val height: Double)

/**
 * One page's detected content, in unrotated user space (y up). [text],
 * [images] and [vectors] are the per-kind unions after the background,
 * header/footer and invisible-text filters; [raster] is filled for a scanned
 * page whose content was found in a low-resolution render.
 */
class PageContent(
    val pageIndex: Int,
    /** The page's current CropBox. */
    val page: PdfRect,
    val rotate: Int,
    val text: PdfRect?,
    val images: PdfRect?,
    val vectors: PdfRect?,
    val raster: PdfRect?,
    /** A page-sized image: the page shows a picture edge to edge, keep it whole. */
    val fullBleed: Boolean
) {
    /**
     * The union that matters for fitting. When the scan detector ran, the
     * page-sized image is replaced by what it actually found inside it.
     */
    val bounds: PdfRect?
        get() = if (raster != null) {
            unionRects(text, raster)
        } else {
            unionRects(text, unionRects(images, vectors))
        }

    val source: ContentSource
        get() = when {
            raster != null -> ContentSource.RASTER
            text != null -> ContentSource.TEXT
            images != null -> ContentSource.IMAGE
            vectors != null -> ContentSource.VECTOR
            else -> ContentSource.BLANK
        }

    /** The bounds' width on screen (the page rotation swaps the axes). */
    val displayWidth: Double
        get() = bounds?.let { if (rotate % 180 == 0) it.width else it.height } ?: 0.0

    /** The bounds' height on screen. */
    val displayHeight: Double
        get() = bounds?.let { if (rotate % 180 == 0) it.height else it.width } ?: 0.0
}

/**
 * The fit boxes for one document: one box per page, no file change (T57).
 *
 * [boxes] are the boxes a viewer should show instead of the full page. A page
 * missing from [boxes] is blank or unusable; callers fall back to the page
 * itself. [oddSize]/[evenSize] are the common displayed sizes the boxes were
 * built for (equal when odd and even pages did not need separate treatment),
 * and [fallbackPages] are the pages that could not share the common size
 * (a full-page figure, a landscape page, a page too small for it).
 */
class ContentFitResult(
    val pages: List<PageContent>,
    val boxes: Map<Int, PdfRect>,
    val oddSize: FitSize?,
    val evenSize: FitSize?,
    val fallbackPages: List<Int>,
    val scannedPages: List<Int>,
    val warnings: List<String>
) {
    /** True when every page that has content shares one displayed size. */
    val uniform: Boolean get() = fallbackPages.isEmpty() && oddSize != null &&
        (evenSize == null || evenSize == oddSize)

    /** The box for [index], or the page itself when nothing was detected. */
    fun boxFor(index: Int): PdfRect? = boxes[index] ?: pages.getOrNull(index)?.page
}

/**
 * Content bounds and a document-level fit box (task T57, ISO 32000-1 §8.5,
 * §8.9.5, §9.4.3, §14.11.2).
 *
 * The fit is view-only: it proposes a box per page so a viewer can show the
 * text edge to edge on a narrow screen, and writes nothing back to the file.
 * "Make this permanent" is the T46 crop, which writes the same boxes as
 * `/CropBox`.
 *
 * Detection reads the display list, so it sees exactly what a renderer would
 * paint: visible text runs (render mode 3 is ignored), image XObjects mapped
 * through their CTM and painted paths (curves bounded by their control points,
 * which can only over-estimate and therefore never cut content). A filled
 * rectangle or picture that covers the page is treated as background.
 *
 * A page whose only content is a page-sized image is a scan: when a
 * [PageRasterizer] is supplied it is rendered at a low resolution and the
 * printed area inside the scan is found with the T46 white-margin detector.
 *
 * The document-level box uses the median per page parity so the zoom does not
 * jump: the same displayed size is used for every page of a group, grown to
 * cover the widest page in the group so nothing is ever clipped. Pages that
 * differ from the median by more than [ContentFitOptions.outlierRatio] (a
 * full-page figure, a landscape page) keep their own box.
 */
object ContentFit {

    /** Smallest usable box; a sliver is treated as no detection. */
    const val MIN_BOX_POINTS: Double = 8.0

    /** Renders a document-wide fit. Never throws on malformed input. */
    fun compute(
        document: PdfDocument,
        options: ContentFitOptions = ContentFitOptions(),
        decoder: ExternalImageDecoder? = null,
        rasterizer: PageRasterizer? = null
    ): Result<ContentFitResult> = runCatching {
        val allPages = document.pages()
        require(allPages.isNotEmpty()) { "The document has no pages" }
        val warnings = ArrayList<String>()
        val scanned = ArrayList<Int>()
        val contents = allPages.mapIndexed { index, page ->
            analyse(page, index, options, decoder, rasterizer, warnings, scanned)
        }

        val blank = contents.filter { it.bounds == null || it.fullBleed }.map { it.pageIndex }
        for (index in blank) {
            val content = contents[index]
            warnings += if (content.fullBleed) {
                "page ${index + 1}: a page-sized picture, shown whole"
            } else {
                "page ${index + 1}: no content found, shown whole"
            }
        }

        val candidates = contents
            .filter { it.bounds != null && !it.fullBleed }
            .mapNotNull { content ->
                val bounds = content.bounds ?: return@mapNotNull null
                val padded = pad(bounds, content.page, options.padding)
                Candidate(content.pageIndex, content, padded, content.rotate)
            }

        val fallback = LinkedHashSet<Int>(blank)
        val boxes = LinkedHashMap<Int, PdfRect>()
        for (index in blank) boxes[index] = contents[index].page

        if (!options.sharedSize) {
            for (candidate in candidates) boxes[candidate.index] = candidate.padded
            return@runCatching ContentFitResult(
                pages = contents,
                boxes = boxes,
                oddSize = null,
                evenSize = null,
                fallbackPages = fallback.sorted(),
                scannedPages = scanned.sorted(),
                warnings = warnings
            )
        }

        val odd = candidates.filter { it.index % 2 == 0 }
        val even = candidates.filter { it.index % 2 == 1 }
        val separate = odd.isNotEmpty() && even.isNotEmpty() &&
            differs(odd, even, options.parityThreshold)
        val groups = if (separate) listOf(odd, even) else listOf(candidates)

        var oddSize: FitSize? = null
        var evenSize: FitSize? = null
        for (group in groups) {
            if (group.isEmpty()) continue
            val fit = commonSize(group, options.outlierRatio)
            for (candidate in group) {
                val own = candidate.padded
                // A page in the group's common size is framed around its own
                // content; an outlier keeps its own box.
                val framed = if (candidate.index in fit.inliers) {
                    frame(candidate.content.page, own, fit.size, candidate.rotate)
                } else {
                    null
                }
                if (framed == null) {
                    boxes[candidate.index] = own
                    fallback += candidate.index
                    warnings += "page ${candidate.index + 1}: its content differs from the rest, " +
                        "so it keeps its own box"
                } else {
                    boxes[candidate.index] = framed
                }
            }
            val displayed = FitSize(fit.size.first, fit.size.second)
            if (separate && group === odd) oddSize = displayed else if (separate) {
                evenSize = displayed
            } else {
                oddSize = displayed
            }
        }
        if (oddSize == null && evenSize != null) {
            oddSize = evenSize
            evenSize = null
        }

        for ((index, box) in boxes) {
            if (box.width < MIN_BOX_POINTS || box.height < MIN_BOX_POINTS) {
                boxes[index] = contents[index].page
                fallback += index
                warnings += "page ${index + 1}: the detected box is too small, shown whole"
            }
        }

        ContentFitResult(
            pages = contents,
            boxes = boxes,
            oddSize = oddSize,
            evenSize = evenSize,
            fallbackPages = fallback.sorted(),
            scannedPages = scanned.sorted(),
            warnings = warnings
        )
    }

    /** Content of one page; the page box is taken from its current CropBox. */
    fun analyse(
        page: PdfPage,
        pageIndex: Int = page.index,
        options: ContentFitOptions = ContentFitOptions(),
        decoder: ExternalImageDecoder? = null,
        rasterizer: PageRasterizer? = null
    ): PageContent = analyse(
        page,
        pageIndex,
        options,
        decoder,
        rasterizer,
        ArrayList(),
        ArrayList()
    )

    /**
     * Bounds of a display list in page user space. [page] is the current
     * CropBox and [rotate] the page's /Rotate.
     */
    fun fromDisplayList(
        list: DisplayList,
        page: PdfRect,
        rotate: Int,
        options: ContentFitOptions = ContentFitOptions()
    ): PageContent {
        val display = DisplayMapper(page)
        var text: PdfRect? = null
        var images: PdfRect? = null
        var vectors: PdfRect? = null
        var fullBleed = false

        for (command in list.commands) {
            when (command) {
                is DrawTextRun -> {
                    val run = command.run
                    // Render mode 3 paints nothing (§9.3.6): an OCR layer must
                    // not define the visible content.
                    if (run.renderMode == 3 || run.text.isBlank()) continue
                    val rect = PdfRect(run.x0, run.y0, run.x1, run.y1)
                    if (!rect.isFinite() || rect.height <= 0.0) continue
                    if (options.ignoreHeaderFooter && display.inBand(rect, options)) continue
                    text = unionRects(text, rect)
                }

                is DrawRasterImage -> {
                    val rect = transformedUnitSquare(command.matrix) ?: continue
                    if (!rect.isFinite()) continue
                    if (covers(rect, page) >= options.backgroundCoverage) {
                        // A page-sized picture is content, and the whole page
                        // is what a reader must see. A scan detector may still
                        // find white paper inside it.
                        fullBleed = true
                        images = unionRects(images, rect)
                        continue
                    }
                    if (options.ignoreHeaderFooter && display.inBand(rect, options)) continue
                    images = unionRects(images, rect)
                }

                is FillPath -> {
                    val rect = pathRect(command.path, command.matrix, 0.0) ?: continue
                    if (covers(rect, page) >= options.backgroundCoverage) continue
                    if (options.ignoreHeaderFooter && display.inBand(rect, options)) continue
                    vectors = unionRects(vectors, rect)
                }

                is StrokePath -> {
                    val half = command.lineStyle.width * command.matrix.meanScale() / 2.0
                    val rect = pathRect(command.path, command.matrix, half) ?: continue
                    if (covers(rect, page) >= options.backgroundCoverage) continue
                    if (options.ignoreHeaderFooter && display.inBand(rect, options)) continue
                    vectors = unionRects(vectors, rect)
                }

                is FillStrokePath -> {
                    val half = command.lineStyle.width * command.matrix.meanScale() / 2.0
                    val rect = pathRect(command.path, command.matrix, half) ?: continue
                    if (covers(rect, page) >= options.backgroundCoverage) continue
                    if (options.ignoreHeaderFooter && display.inBand(rect, options)) continue
                    vectors = unionRects(vectors, rect)
                }

                // Clips paint nothing on their own; save/restore/blend modes
                // carry no geometry.
                is ClipPath -> {}
                else -> {}
            }
        }

        return PageContent(0, page, rotate, text, images, vectors, null, fullBleed)
    }

    /**
     * White-margin detection for a scanned page: renders at [ContentFitOptions.rasterDpi]
     * and maps the printed area back to user space. Null when the page is
     * blank or cannot be rendered.
     */
    fun scannedBounds(
        page: PdfPage,
        rasterizer: PageRasterizer,
        options: ContentFitOptions = ContentFitOptions()
    ): PdfRect? {
        val box = PageGeometry.boxOf(page)
        val plan = PageGeometry.plan(box, options.rasterDpi / 72.0)
        val raster = runCatching { rasterizer.rasterize(page, plan) }.getOrNull() ?: return null
        val borders = scannedBorders(raster, options.tolerance) ?: return null
        val pageRect = PdfRect(box.left, box.bottom, box.right, box.top)
        val detected = com.pdfpaper.engine.crop.CropDetection.toUserRect(borders, plan)
            ?: return null
        return intersect(detected, pageRect)
    }

    // ------------------------------------------------------------- internals

    /**
     * The printed area of a scan. Unlike a white-margin scan on a digital
     * page, the paper of a scan is often slightly grey or yellow, so the
     * background colour is measured on the page border first and any pixel
     * close to it is paper. Pure pixel maths; the raster comes from the host.
     */
    private fun scannedBorders(
        raster: com.pdfpaper.engine.export.PageRaster,
        tolerance: Int
    ): com.pdfpaper.engine.crop.CropDetection.PixelBorders? {
        val width = raster.width
        val height = raster.height
        if (width <= 0 || height <= 0) return null
        val data = raster.rgba
        if (data.size < width * height * 4) return null
        val paper = borderColour(data, width, height)
        val limit = tolerance.coerceIn(0, 255)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            var hasContent = false
            val row = y * width * 4
            for (x in 0 until width) {
                val i = row + x * 4
                val dr = abs((data[i].toInt() and 0xFF) - paper[0])
                val dg = abs((data[i + 1].toInt() and 0xFF) - paper[1])
                val db = abs((data[i + 2].toInt() and 0xFF) - paper[2])
                if (max(dr, max(dg, db)) > limit) {
                    hasContent = true
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
            if (hasContent) {
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return null
        return com.pdfpaper.engine.crop.CropDetection.PixelBorders(minX, minY, maxX + 1, maxY + 1)
    }

    /** Median RGB of a 1-pixel frame around the raster: the scan's paper. */
    private fun borderColour(data: ByteArray, width: Int, height: Int): IntArray {
        val reds = ArrayList<Int>(2 * (width + height))
        val greens = ArrayList<Int>(2 * (width + height))
        val blues = ArrayList<Int>(2 * (width + height))
        fun sample(x: Int, y: Int) {
            val i = (y * width + x) * 4
            reds += data[i].toInt() and 0xFF
            greens += data[i + 1].toInt() and 0xFF
            blues += data[i + 2].toInt() and 0xFF
        }
        for (x in 0 until width) {
            sample(x, 0)
            sample(x, height - 1)
        }
        for (y in 1 until height - 1) {
            sample(0, y)
            sample(width - 1, y)
        }
        if (reds.isEmpty()) return intArrayOf(255, 255, 255)
        reds.sort()
        greens.sort()
        blues.sort()
        val middle = reds.size / 2
        return intArrayOf(reds[middle], greens[middle], blues[middle])
    }

    private class Candidate(
        val index: Int,
        val content: PageContent,
        val padded: PdfRect,
        val rotate: Int
    ) {
        val displayWidth: Double
            get() = if (rotate % 180 == 0) padded.width else padded.height
        val displayHeight: Double
            get() = if (rotate % 180 == 0) padded.height else padded.width
    }

    private fun analyse(
        page: PdfPage,
        pageIndex: Int,
        options: ContentFitOptions,
        decoder: ExternalImageDecoder?,
        rasterizer: PageRasterizer?,
        warnings: MutableList<String>,
        scanned: MutableList<Int>
    ): PageContent {
        val box = PageGeometry.boxOf(page)
        val pageRect = PdfRect(box.left, box.bottom, box.right, box.top)
        val list = runCatching { page.buildDisplayList(decoder) }.getOrNull()
        val content = if (list == null) {
            warnings += "page ${pageIndex + 1}: the content could not be read, shown whole"
            PageContent(pageIndex, pageRect, box.rotate, null, null, null, null, false)
        } else {
            val analysed = fromDisplayList(list, pageRect, box.rotate, options)
            PageContent(
                pageIndex = pageIndex,
                page = pageRect,
                rotate = box.rotate,
                text = analysed.text,
                images = analysed.images,
                vectors = analysed.vectors,
                raster = null,
                fullBleed = analysed.fullBleed
            )
        }

        // A scanned page is one page-sized picture. The picture's own bounds
        // are the whole page, so the printed area is found in pixels instead.
        val image = content.images
        val isScan = content.text == null && image != null && covers(image, pageRect) >= 0.85
        if (!isScan || rasterizer == null) return content
        val detected = scannedBounds(page, rasterizer, options) ?: return content
        scanned += pageIndex
        return PageContent(
            pageIndex = pageIndex,
            page = pageRect,
            rotate = box.rotate,
            text = content.text,
            images = null,
            vectors = content.vectors,
            raster = detected,
            fullBleed = false
        )
    }

    private class GroupFit(val size: Pair<Double, Double>, val inliers: Set<Int>)

    /**
     * The displayed size every inlier of [group] shares: the median decides
     * which pages are outliers, and the largest inlier decides the size, so a
     * box can never clip a page's own content.
     */
    private fun commonSize(group: List<Candidate>, outlierRatio: Double): GroupFit {
        val widths = group.map { it.displayWidth }.sorted()
        val heights = group.map { it.displayHeight }.sorted()
        val medianWidth = percentile(widths, 0.5)
        val medianHeight = percentile(heights, 0.5)
        val inliers = group.filter {
            it.displayWidth <= medianWidth * outlierRatio &&
                it.displayHeight <= medianHeight * outlierRatio &&
                it.displayWidth * outlierRatio >= medianWidth &&
                it.displayHeight * outlierRatio >= medianHeight
        }
        val usable = inliers.ifEmpty { group }
        return GroupFit(
            usable.maxOf { it.displayWidth } to usable.maxOf { it.displayHeight },
            usable.map { it.index }.toSet()
        )
    }

    /** True when the odd and even groups need separate common boxes. */
    private fun differs(odd: List<Candidate>, even: List<Candidate>, threshold: Double): Boolean {
        val oddWidth = percentile(odd.map { it.displayWidth }.sorted(), 0.5)
        val evenWidth = percentile(even.map { it.displayWidth }.sorted(), 0.5)
        val oddHeight = percentile(odd.map { it.displayHeight }.sorted(), 0.5)
        val evenHeight = percentile(even.map { it.displayHeight }.sorted(), 0.5)
        return relativeDifference(oddWidth, evenWidth) > threshold ||
            relativeDifference(oddHeight, evenHeight) > threshold
    }

    private fun relativeDifference(a: Double, b: Double): Double {
        val scale = max(abs(a), abs(b))
        return if (scale <= 1e-9) 0.0 else abs(a - b) / scale
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val position = (fraction.coerceIn(0.0, 1.0) * (sorted.size - 1))
        val low = position.toInt()
        val high = min(low + 1, sorted.size - 1)
        val weight = position - low
        return sorted[low] * (1.0 - weight) + sorted[high] * weight
    }

    /**
     * A box of [size] (displayed points) around [inner], inside [outer].
     * Null when the page cannot hold that size.
     */
    private fun frame(outer: PdfRect, inner: PdfRect, size: Pair<Double, Double>, rotate: Int): PdfRect? {
        val width = if (rotate % 180 == 0) size.first else size.second
        val height = if (rotate % 180 == 0) size.second else size.first
        if (width > outer.width + 1e-6 || height > outer.height + 1e-6) return null
        if (width < MIN_BOX_POINTS || height < MIN_BOX_POINTS) return null
        val x = (inner.centerX - width / 2.0).coerceIn(outer.left, outer.right - width)
        val y = (inner.centerY - height / 2.0).coerceIn(outer.bottom, outer.top - height)
        return PdfRect(x, y, x + width, y + height)
    }

    private fun pad(bounds: PdfRect, page: PdfRect, padding: Double): PdfRect {
        if (padding <= 0.0) return intersect(bounds, page) ?: bounds
        val padded = PdfRect(
            bounds.left - padding,
            bounds.bottom - padding,
            bounds.right + padding,
            bounds.top + padding
        )
        return intersect(padded, page) ?: bounds
    }

    /** Share of [page]'s area covered by [rect] (0..1). */
    private fun covers(rect: PdfRect, page: PdfRect): Double {
        val area = page.width * page.height
        if (area <= 0.0) return 0.0
        val visible = intersect(rect, page) ?: return 0.0
        return (visible.width * visible.height / area).coerceIn(0.0, 1.0)
    }

    private fun intersect(a: PdfRect, b: PdfRect): PdfRect? {
        val left = max(a.left, b.left)
        val bottom = max(a.bottom, b.bottom)
        val right = min(a.right, b.right)
        val top = min(a.top, b.top)
        if (right <= left || top <= bottom) return null
        return PdfRect(left, bottom, right, top)
    }

    /** Bounds of [path] after [matrix], grown by [halfWidth] on every side. */
    private fun pathRect(path: PdfPath, matrix: PdfMatrix, halfWidth: Double): PdfRect? {
        if (path.isEmpty) return null
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        fun include(x: Double, y: Double) {
            val p = matrix.apply(x, y)
            if (!p[0].isFinite() || !p[1].isFinite()) return
            if (p[0] < minX) minX = p[0]
            if (p[0] > maxX) maxX = p[0]
            if (p[1] < minY) minY = p[1]
            if (p[1] > maxY) maxY = p[1]
        }
        for (segment in path.segments) {
            when (segment) {
                is MoveTo -> include(segment.x, segment.y)
                is LineTo -> include(segment.x, segment.y)
                is CurveTo -> {
                    include(segment.x1, segment.y1)
                    include(segment.x2, segment.y2)
                    include(segment.x3, segment.y3)
                }
                ClosePath -> {}
                else -> {}
            }
        }
        if (maxX < minX || maxY < minY) return null
        return PdfRect(
            minX - halfWidth,
            minY - halfWidth,
            maxX + halfWidth,
            maxY + halfWidth
        )
    }

    /** Bounds of an image's unit square (§8.9.5.2) in page space. */
    private fun transformedUnitSquare(matrix: PdfMatrix): PdfRect? {
        val corners = doubleArrayOf(
            matrix.apply(0.0, 0.0)[0], matrix.apply(0.0, 0.0)[1],
            matrix.apply(1.0, 0.0)[0], matrix.apply(1.0, 0.0)[1],
            matrix.apply(1.0, 1.0)[0], matrix.apply(1.0, 1.0)[1],
            matrix.apply(0.0, 1.0)[0], matrix.apply(0.0, 1.0)[1]
        )
        if (corners.any { !it.isFinite() }) return null
        val xs = doubleArrayOf(corners[0], corners[2], corners[4], corners[6])
        val ys = doubleArrayOf(corners[1], corners[3], corners[5], corners[7])
        return PdfRect(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /**
     * The header/footer bands, measured in the page's own (unrotated) user
     * space: a running head is at the top of the page as authored, whatever
     * /Rotate does to the screen orientation.
     */
    private class DisplayMapper(private val page: PdfRect) {
        private val height = page.height

        fun inBand(rect: PdfRect, options: ContentFitOptions): Boolean {
            val topLimit = page.top - height * options.headerBand
            val bottomLimit = page.bottom + height * options.footerBand
            return rect.bottom >= topLimit - 1e-6 || rect.top <= bottomLimit + 1e-6
        }
    }
}

private fun PdfRect.isFinite(): Boolean =
    left.isFinite() && bottom.isFinite() && right.isFinite() && top.isFinite()

/** Smallest rectangle containing both; a missing side is the other one. */
private fun unionRects(a: PdfRect?, b: PdfRect?): PdfRect? = when {
    a == null -> b
    b == null -> a
    else -> a.union(b)
}
