package com.pdfpaper.engine.render

import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.graphics.ClipPath
import com.pdfpaper.engine.graphics.DisplayList
import com.pdfpaper.engine.graphics.DrawCommand
import com.pdfpaper.engine.graphics.DrawRasterImage
import com.pdfpaper.engine.graphics.DrawTextRun
import com.pdfpaper.engine.graphics.FillPath
import com.pdfpaper.engine.graphics.FillStrokePath
import com.pdfpaper.engine.graphics.LineStyle
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.graphics.PdfPath
import com.pdfpaper.engine.graphics.RestoreGraphicsState
import com.pdfpaper.engine.graphics.SaveGraphicsState
import com.pdfpaper.engine.graphics.SetBlendMode
import com.pdfpaper.engine.graphics.StrokePath
import com.pdfpaper.engine.image.RasterImage
import com.pdfpaper.engine.text.PdfTextRun

/**
 * Platform drawing surface for one page (ISO 32000-1, §8 painting model).
 * The engine owns the interpretation and hands each primitive together with
 * the CTM that was in effect when it was issued (§8.3.3); implementations
 * map PDF user space to device pixels themselves.
 *
 * Contract:
 *  - [saveState]/[restoreState] bracket `q`/`Q`; a clip set after a save
 *    persists until the matching restore (the engine never re-issues it).
 *  - Geometry is in the user space of [matrix]; parameters ([strokePath]
 *    width, dash lengths, image unit square) are in the same space, so a
 *    renderer that concatenates [matrix] gets correct scaling for free.
 *  - Paints are device-RGB with constant alpha already resolved (§8.6, §11.6.6).
 *
 * :app implements this with `android.graphics.Canvas`; tests use a recorder.
 */
interface Renderer {

    /** `q` (§8.4.4). */
    fun saveState()

    /** `Q` (§8.4.4). */
    fun restoreState()

    /**
     * Constant blend mode from ExtGState /BM (§11.6.3, §11.4.5): "Multiply",
     * "Screen", "Darken", "Lighten", "Overlay", ... or null for Normal.
     * Default is a no-op so renderers can ignore compositing.
     */
    fun setBlendMode(mode: String?) {}

    /** `W`/`W*`: intersect the clip with [path] (§8.5.4). */
    fun clip(path: PdfPath, matrix: PdfMatrix, evenOdd: Boolean)

    /** `f`/`F`/`f*` (§8.5.3.1). [alpha] is the constant fill alpha. */
    fun fillPath(path: PdfPath, matrix: PdfMatrix, color: PdfColor, alpha: Double, evenOdd: Boolean)

    /** `S`/`s` (§8.5.3.1); [lineStyle] is in user space. */
    fun strokePath(path: PdfPath, matrix: PdfMatrix, color: PdfColor, alpha: Double, lineStyle: LineStyle)

    /**
     * Image XObject in the unit square of image space (§8.9.5.2): (0,0) is the
     * top-left sample row and y grows down, so bitmap row 0 is drawn at y=0.
     * Stencil masks ([RasterImage.stencil]) are painted in [fillColor];
     * ordinary images ignore it.
     */
    fun drawImage(image: RasterImage, matrix: PdfMatrix, fillColor: PdfColor, alpha: Double)

    /**
     * One text run (§9.4.3). [PdfTextRun.originX]/[originY] and
     * [PdfTextRun.endX]/[endY] give the baseline in page space; the run is in
     * user-space units of [matrix], so glyph size scales with the CTM.
     */
    fun drawText(run: PdfTextRun, matrix: PdfMatrix)
}

/**
 * Replays [list] into [renderer] in content order. This is the whole
 * engine-side renderer: all platform work happens behind [Renderer].
 */
fun renderDisplayList(list: DisplayList, renderer: Renderer) {
    for (command in list.commands) replay(command, renderer)
}

/** Convenience form: `displayList.render(renderer)`. */
fun DisplayList.render(renderer: Renderer) = renderDisplayList(this, renderer)

private fun replay(command: DrawCommand, renderer: Renderer) {
    when (command) {
        is SaveGraphicsState -> {
            renderer.saveState()
            // A mode captured before q applies until the matching Q restores it.
            if (command.blendMode != null) renderer.setBlendMode(command.blendMode)
        }
        is RestoreGraphicsState -> renderer.restoreState()
        is SetBlendMode -> renderer.setBlendMode(command.mode)
        is ClipPath -> if (!command.path.isEmpty) {
            renderer.clip(command.path, command.matrix, command.evenOdd)
        }
        is FillPath -> if (!command.path.isEmpty) {
            renderer.fillPath(command.path, command.matrix, command.color, command.alpha, command.evenOdd)
        }
        is StrokePath -> if (!command.path.isEmpty) {
            renderer.strokePath(command.path, command.matrix, command.color, command.alpha, command.lineStyle)
        }
        is FillStrokePath -> if (!command.path.isEmpty) {
            // Fill first, then stroke (§8.5.3.1), each with its own alpha.
            renderer.fillPath(command.path, command.matrix, command.fillColor, command.fillAlpha, command.evenOdd)
            renderer.strokePath(command.path, command.matrix, command.strokeColor, command.strokeAlpha, command.lineStyle)
        }
        is DrawRasterImage -> renderer.drawImage(
            command.image, command.matrix, command.fillColor, command.fillAlpha
        )
        is DrawTextRun -> {
            val run = command.run
            // Mode 3 is invisible and mode 7 only feeds the clip path; neither
            // paints glyphs (§9.3.6).
            if (run.text.isNotEmpty() && run.renderMode != 3 && run.renderMode != 7) {
                renderer.drawText(run, command.matrix)
            }
        }
    }
}
