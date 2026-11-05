package com.pdfpaper.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.pdfpaper.engine.eink.EinkPipeline
import com.pdfpaper.engine.eink.EinkSettings
import com.pdfpaper.engine.graphics.buildDisplayList
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.render.ColorTransform
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.render.PageRenderPlan
import com.pdfpaper.engine.render.PageTheme
import com.pdfpaper.engine.render.PageThemeMode
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
        extraRotation: Int = 0,
        /** Page theme (T32/T32b); OFF paints the document as authored. */
        theme: PageTheme = PageTheme.OFF,
        /** T62 colour look (filter and sliders) on top of the theme. */
        transform: ColorTransform = ColorTransform.NONE,
        /** T63 E-Ink / reflex display profile, applied after the page is painted. */
        eink: EinkSettings = EinkSettings.OFF
    ): RenderedPage {
        val base = PageGeometry.boxOf(page)
        val box = if (extraRotation % 360 == 0) {
            base
        } else {
            PageBox(base.left, base.bottom, base.right, base.top, normalizeRotation(base.rotate + extraRotation))
        }
        val plan = PageGeometry.plan(box, scale, maxPixels)
        return render(page, plan, decoder, fontDir, theme, transform, eink)
    }

    /**
     * Renders one page inside an explicit [box] (T57 fit content). Only the
     * box is rasterised, so a fitted page is not painted and then cropped:
     * the visible region is the whole bitmap.
     */
    fun render(
        page: PdfPage,
        box: PageBox,
        scale: Double,
        maxPixels: Int = PageGeometry.DEFAULT_MAX_PIXELS,
        decoder: ExternalImageDecoder = AndroidImageDecoder,
        fontDir: File? = null,
        theme: PageTheme = PageTheme.OFF,
        transform: ColorTransform = ColorTransform.NONE,
        eink: EinkSettings = EinkSettings.OFF
    ): RenderedPage =
        render(page, PageGeometry.plan(box, scale, maxPixels), decoder, fontDir, theme, transform, eink)

    /**
     * Renders one page with an explicit [plan] (T45 page export, which owns
     * the DPI and pixel-cap rules). Same painting path as the viewer.
     */
    fun render(
        page: PdfPage,
        plan: PageRenderPlan,
        decoder: ExternalImageDecoder = AndroidImageDecoder,
        fontDir: File? = null,
        theme: PageTheme = PageTheme.OFF,
        transform: ColorTransform = ColorTransform.NONE,
        eink: EinkSettings = EinkSettings.OFF
    ): RenderedPage {
        val settings = eink.normalized()
        val bitmap = Bitmap.createBitmap(plan.pixelWidth, plan.pixelHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // T62: the look decides the paper the theme paints on. A dark or
        // true-black filter keeps its T32/T32b paper; a sepia or inverted page
        // starts from white and is graded like every other primitive.
        // T63: an E-Ink profile is a light-page mode. A dark theme would turn
        // the 1-bit reduction into white text on black, which is not what a
        // reflective panel is for, so the profile forces the light page.
        val themed = transform.pageTheme(theme)
        val painted = if (settings.active) themed.copy(mode = PageThemeMode.OFF) else themed
        // PDF pages have no background of their own; viewers show them on white (§11.4.7 note).
        canvas.drawColor(transform.grade(painted.background).toArgb(1.0))
        val displayList = page.buildDisplayList(decoder)
        canvas.save()
        canvas.concat(plan.matrix.intoAndroid())
        renderDisplayList(
            displayList,
            AndroidRenderer(
                canvas = canvas,
                fontDir = fontDir,
                theme = painted,
                transform = transform,
                eink = settings,
                baseScale = plan.matrix.meanScale()
            )
        )
        canvas.restore()
        if (settings.postProcessed) {
            val pixels = IntArray(plan.pixelWidth * plan.pixelHeight)
            bitmap.getPixels(pixels, 0, plan.pixelWidth, 0, 0, plan.pixelWidth, plan.pixelHeight)
            EinkPipeline.apply(pixels, settings)
            bitmap.setPixels(pixels, 0, plan.pixelWidth, 0, 0, plan.pixelWidth, plan.pixelHeight)
        }
        return RenderedPage(plan, bitmap, displayList.warnings)
    }

    private fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360
}
