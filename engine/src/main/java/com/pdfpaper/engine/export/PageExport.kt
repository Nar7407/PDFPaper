package com.pdfpaper.engine.export

import com.pdfpaper.engine.image.ImageEncoder
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.render.PageRenderPlan
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The still-image formats a page can be exported as. */
enum class ImageFormat(val label: String, val extension: String, val mimeType: String) {
    PNG("PNG", "png", "image/png"),
    JPEG("JPEG", "jpg", "image/jpeg"),
    WEBP("WebP", "webp", "image/webp")
}

/** One rasterised page handed to the caller (RGBA, row 0 first). */
class PageRaster(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
    val warnings: List<String> = emptyList()
)

/**
 * Host hook that turns a page into pixels. `:app` backs it with
 * `PageBitmapRenderer` (Canvas) and the JVM tooling with an `java.awt`
 * renderer, so the engine keeps no platform code while still owning the DPI,
 * page-range and pixel-cap rules.
 */
interface PageRasterizer {
    fun rasterize(page: PdfPage, plan: PageRenderPlan): PageRaster?
}

/** What to export; [documentName] becomes the file-name prefix. */
class PageExportOptions(
    val documentName: String,
    val dpi: Int = 150,
    val format: ImageFormat = ImageFormat.PNG,
    val pages: PageRange = PageRange.All,
    /** JPEG/WebP quality, 1..100. */
    val quality: Int = 90,
    /** Refuse a page above this many pixels; never silently downscale. */
    val maxPixels: Long = DEFAULT_MAX_PIXELS
) {
    companion object {
        /**
         * 64 MP ≈ 256 MB of RGBA for one page. Above that the export refuses
         * with a message rather than letting a phone run out of memory.
         */
        const val DEFAULT_MAX_PIXELS: Long = 64_000_000L

        /** Supported DPI choices shown by the app and accepted by the CLI. */
        val COMMON_DPI = intArrayOf(72, 150, 300)
    }
}

/** One exported page: metadata plus the encoded file bytes. */
class ExportedPage(
    /** Zero-based page index in the document. */
    val pageIndex: Int,
    /** One-based page number as the user sees it. */
    val pageNumber: Int,
    val fileName: String,
    val format: ImageFormat,
    val bytes: ByteArray,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val warnings: List<String>
)

/** The outcome of a completed run; pages are reported as they are written. */
class PageExportSummary(
    val exported: Int,
    val total: Int,
    val cancelled: Boolean,
    val warnings: List<String>
)

/**
 * Exports pages as PNG, JPEG or WebP (ISO 32000-1 §14.11.2 for the page box
 * and rotation; §8 for the painting model the renderer replays).
 *
 * The pixel size of a page is `rotated page points / 72 × dpi`, taken from the
 * effective crop box, so a rotated page exports upright and a page in a
 * non-point user unit comes out at its printed size. PNG is written by
 * [PngWriter]; JPEG and WebP go through the host [ImageEncoder].
 *
 * Pages are rasterised, encoded and handed to [sink] one at a time, so a
 * 300-page export holds at most one page in memory. Nothing is buffered
 * beyond the current page.
 */
object PdfPageExport {

    /** DPI bounds accepted by the engine (a guard, not a quality opinion). */
    const val MIN_DPI = 1
    const val MAX_DPI = 1200

    /** Pixel size a page would get at [dpi], before the pixel cap is applied. */
    fun pixelSize(box: com.pdfpaper.engine.render.PageBox, dpi: Int): Pair<Int, Int> {
        val scale = dpi.coerceIn(MIN_DPI, MAX_DPI) / 72.0
        val width = (box.rotatedWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (box.rotatedHeight * scale).roundToInt().coerceAtLeast(1)
        return width to height
    }

    /** The render plan for a page at [dpi] (no downscaling: the cap refuses instead). */
    fun plan(page: PdfPage, dpi: Int): PageRenderPlan {
        val box = PageGeometry.boxOf(page)
        val scale = dpi.coerceIn(MIN_DPI, MAX_DPI) / 72.0
        return PageGeometry.plan(box, scale, maxPixels = 0)
    }

    /** The highest DPI that keeps a page under [maxPixels]. */
    fun dpiWithinCap(box: com.pdfpaper.engine.render.PageBox, maxPixels: Long): Int {
        val area = box.rotatedWidth * box.rotatedHeight
        if (area <= 0.0) return MAX_DPI
        val dpi = floor(72.0 * sqrt(maxPixels.toDouble() / area)).toInt()
        return dpi.coerceIn(MIN_DPI, MAX_DPI)
    }

    /**
     * Runs the export. [sink] receives each page in page order and may write
     * it straight to disk; [isCancelled] is checked before every page so a
     * cancelled run stops promptly and reports what it managed to write.
     */
    fun export(
        document: PdfDocument,
        options: PageExportOptions,
        rasterizer: PageRasterizer,
        encoder: ImageEncoder?,
        sink: (ExportedPage) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): Result<PageExportSummary> {
        if (options.dpi < MIN_DPI || options.dpi > MAX_DPI) {
            return Result.failure(
                IllegalArgumentException("DPI must be between $MIN_DPI and $MAX_DPI (got ${options.dpi}).")
            )
        }
        val allPages = document.pages()
        if (allPages.isEmpty()) return Result.failure(IllegalStateException("This PDF has no pages."))
        val selected = allPages.filter { options.pages.includes(it.index) }
        if (selected.isEmpty()) {
            return Result.failure(
                IllegalArgumentException("No pages matched \"${options.pages.describe(allPages.size)}\" in a ${allPages.size}-page PDF.")
            )
        }
        if (options.format != ImageFormat.PNG && encoder == null) {
            return Result.failure(
                IllegalStateException("${options.format.label} export needs a host image encoder.")
            )
        }
        val warnings = ArrayList<String>()
        val prefix = sanitizeName(options.documentName)
        var exported = 0
        for (page in selected) {
            if (isCancelled()) {
                return Result.success(PageExportSummary(exported, selected.size, cancelled = true, warnings = warnings))
            }
            val box = PageGeometry.boxOf(page)
            val (width, height) = pixelSize(box, options.dpi)
            val pixels = width.toLong() * height.toLong()
            if (pixels > options.maxPixels) {
                val suggestion = dpiWithinCap(box, options.maxPixels)
                return Result.failure(
                    IllegalArgumentException(
                        "Page ${page.index + 1} at ${options.dpi} dpi would be $width × $height " +
                            "(${megapixels(pixels)} MP), over the ${megapixels(options.maxPixels)} MP limit. " +
                            "Try a lower DPI (${suggestion} dpi would fit)."
                    )
                )
            }
            val plan = PageGeometry.plan(box, options.dpi / 72.0, maxPixels = 0)
            val raster = rasterizer.rasterize(page, plan)
                ?: return Result.failure(IllegalStateException("Page ${page.index + 1} could not be rendered."))
            if (raster.width != plan.pixelWidth || raster.height != plan.pixelHeight) {
                warnings += "Page ${page.index + 1} came back ${raster.width}×${raster.height}, expected " +
                    "${plan.pixelWidth}×${plan.pixelHeight}"
            }
            raster.warnings.mapTo(warnings) { "Page ${page.index + 1}: $it" }
            val bytes = when (options.format) {
                ImageFormat.PNG -> PngWriter.encode(raster.width, raster.height, raster.rgba)
                ImageFormat.JPEG -> encoder!!.encodeJpeg(
                    raster.width, raster.height, raster.rgba, options.quality, false
                )?.bytes ?: ByteArray(0)
                ImageFormat.WEBP -> encoder!!.encodeWebp(
                    raster.width, raster.height, raster.rgba, options.quality
                )?.bytes ?: ByteArray(0)
            }
            if (bytes.isEmpty()) {
                return Result.failure(
                    IllegalStateException("${options.format.label} encoding is not available for page ${page.index + 1}.")
                )
            }
            sink(
                ExportedPage(
                    pageIndex = page.index,
                    pageNumber = page.index + 1,
                    fileName = pageFileName(prefix, page.index + 1, options.format),
                    format = options.format,
                    bytes = bytes,
                    pixelWidth = raster.width,
                    pixelHeight = raster.height,
                    warnings = raster.warnings
                )
            )
            exported++
        }
        return Result.success(PageExportSummary(exported, selected.size, cancelled = false, warnings = warnings))
    }

    /** `<document>-p003.png` — the page number is padded to at least three digits. */
    fun pageFileName(documentName: String, pageNumber: Int, format: ImageFormat): String =
        "${sanitizeName(documentName)}-p${pageNumber.toString().padStart(3, '0')}.${format.extension}"

    /** Strips directory parts and characters a file system may refuse. */
    fun sanitizeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val withoutExtension = if (base.contains('.')) base.substringBeforeLast('.') else base
        val cleaned = withoutExtension
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else if (it == ' ') '-' else '_' }
            .joinToString("")
            .trim('-', '_', '.')
            .replace(Regex("-{2,}"), "-")
        return cleaned.ifEmpty { "document" }
    }

    private fun megapixels(pixels: Long): String =
        String.format(java.util.Locale.ROOT, "%.1f", pixels / 1_000_000.0)
}
