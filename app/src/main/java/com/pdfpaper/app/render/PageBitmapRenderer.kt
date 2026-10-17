package com.pdfpaper.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.pdfpaper.engine.graphics.buildDisplayList
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.render.PageRenderPlan
import com.pdfpaper.engine.render.renderDisplayList
import java.io.File

/** One rendered page: the bitmap plus the plan that produced it. */
class RenderedPage(
    val plan: PageRenderPlan,
    val bitmap: Bitmap,
    /** Non-fatal engine warnings (unsupported operators, degraded fonts, ...). */
    val warnings: List<String>
) {
    val byteSize: Long get() = bitmap.allocationByteCount.toLong()
}

/**
 * Renders a page to a bitmap (T9): builds the display list, sets up the
 * crop-box → pixel transform from [PageGeometry] and replays the commands
 * through [AndroidRenderer]. Rendering is CPU-bound and synchronous; callers
 * run it off the main thread.
 */
object PageBitmapRenderer {

    fun render(
        page: PdfPage,
        scale: Double,
        maxPixels: Int = PageGeometry.DEFAULT_MAX_PIXELS,
        decoder: ExternalImageDecoder = AndroidImageDecoder,
        /** Directory for embedded font cache files; null disables them. */
        fontDir: File? = null,
        /** Extra clockwise rotation on top of the page's /Rotate (T10 thumbnails). */
        extraRotation: Int = 0
    ): RenderedPage {
        val base = PageGeometry.boxOf(page)
        val box = if (extraRotation % 360 == 0) {
            base
        } else {
            PageBox(base.left, base.bottom, base.right, base.top, normalizeRotation(base.rotate + extraRotation))
        }
        val plan = PageGeometry.plan(box, scale, maxPixels)
        val bitmap = Bitmap.createBitmap(plan.pixelWidth, plan.pixelHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // PDF pages have no background of their own; viewers show them on white (§11.4.7 note).
        canvas.drawColor(Color.WHITE)
        val displayList = page.buildDisplayList(decoder)
        canvas.save()
        canvas.concat(plan.matrix.intoAndroid())
        renderDisplayList(displayList, AndroidRenderer(canvas, fontDir))
        canvas.restore()
        return RenderedPage(plan, bitmap, displayList.warnings)
    }

    private fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360
}
