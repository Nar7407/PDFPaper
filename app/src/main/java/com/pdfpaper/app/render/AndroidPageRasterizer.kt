package com.pdfpaper.app.render

import com.pdfpaper.engine.annot.AnnotationFilter
import com.pdfpaper.engine.export.PageRaster
import com.pdfpaper.engine.export.PageRasterizer
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.render.PageRenderPlan
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [PageRasterizer] backed by [PageBitmapRenderer]: the exported JPEG/PNG/WebP
 * is painted by exactly the same code as the viewer, so a 150 dpi export looks
 * like the page on screen.
 *
 * The bitmap is copied straight into a 32-bit buffer (no ARGB int array), and
 * both buffers are released as soon as the page is encoded. The caller keeps
 * one page in memory at a time.
 */
object AndroidPageRasterizer : PageRasterizer {

    override fun rasterize(page: PdfPage, plan: PageRenderPlan): PageRaster? {
        if (plan.pixelWidth <= 0 || plan.pixelHeight <= 0) return null
        return try {
            val rendered = PageBitmapRenderer.render(page, plan)
            val bitmap = rendered.bitmap
            try {
                val buffer = ByteBuffer.allocate(plan.pixelWidth * plan.pixelHeight * 4)
                    .order(ByteOrder.nativeOrder())
                bitmap.copyPixelsToBuffer(buffer)
                PageRaster(plan.pixelWidth, plan.pixelHeight, buffer.array(), rendered.warnings)
            } finally {
                bitmap.recycle()
            }
        } catch (throwable: Throwable) {
            // Out of memory on a very large page is reported as "could not be
            // rendered" rather than taking the app down.
            null
        }
    }

    /** Font cache directory the viewer uses, so exports do not re-read fonts. */
    fun fontDir(filesDir: File?): File? = filesDir?.let { File(it, "fonts") }

    /**
     * T68b configured page rasterizer: produces a [PageRasterizer] configured with
     * the given [filter].
     */
    operator fun invoke(filter: AnnotationFilter = AnnotationFilter.SHOW_ALL): PageRasterizer =
        TODO("T68b")
}
