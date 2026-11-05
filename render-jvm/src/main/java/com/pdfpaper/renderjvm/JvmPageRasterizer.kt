package com.pdfpaper.renderjvm

import com.pdfpaper.engine.export.PageRaster
import com.pdfpaper.engine.export.PageRasterizer
import com.pdfpaper.engine.graphics.buildDisplayList
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.render.PageRenderPlan
import com.pdfpaper.engine.render.renderDisplayList
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * [PageRasterizer] for the JVM: renders a page into an ARGB image with
 * [AwtRenderer] and returns 32-bit RGBA.
 *
 * Font sizes use the integer point size AWT supports, so very small type is
 * rendered at 1 pt; everything else is exact. Pages have no background of
 * their own, so the raster starts white (§11.4.7).
 */
class JvmPageRasterizer(
    private val decoder: com.pdfpaper.engine.image.ExternalImageDecoder = JvmImageDecoder
) : PageRasterizer {

    override fun rasterize(page: PdfPage, plan: PageRenderPlan): PageRaster? {
        if (plan.pixelWidth <= 0 || plan.pixelHeight <= 0) return null
        return try {
            val image = BufferedImage(plan.pixelWidth, plan.pixelHeight, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            var warnings: List<String> = emptyList()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = Color.WHITE
                g.fillRect(0, 0, plan.pixelWidth, plan.pixelHeight)
                val displayList = page.buildDisplayList(decoder)
                warnings = displayList.warnings
                renderDisplayList(displayList, AwtRenderer(g, plan.matrix))
            } finally {
                g.dispose()
            }
            val argb = IntArray(plan.pixelWidth * plan.pixelHeight)
            image.getRGB(0, 0, plan.pixelWidth, plan.pixelHeight, argb, 0, plan.pixelWidth)
            val rgba = ByteArray(argb.size * 4)
            for (i in argb.indices) {
                val pixel = argb[i]
                rgba[i * 4] = (pixel ushr 16 and 0xFF).toByte()
                rgba[i * 4 + 1] = (pixel ushr 8 and 0xFF).toByte()
                rgba[i * 4 + 2] = (pixel and 0xFF).toByte()
                rgba[i * 4 + 3] = 255.toByte()
            }
            PageRaster(plan.pixelWidth, plan.pixelHeight, rgba, warnings)
        } catch (t: Throwable) {
            null
        }
    }
}
