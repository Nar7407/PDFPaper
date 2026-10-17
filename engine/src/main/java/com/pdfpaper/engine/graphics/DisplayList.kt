package com.pdfpaper.engine.graphics

import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.image.RasterImage
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.text.PdfTextRun

/**
 * One draw command. Every command carries the CTM in effect when it was
 * issued (§8.3.3) and geometry/text coordinates in the user space of that
 * moment, so a renderer can apply the matrix itself and get exact stroke
 * scaling, clipping and image placement. Commands appear in content-stream
 * order.
 */
sealed class DrawCommand(val matrix: PdfMatrix)

/** `q` (§8.4.4): saves state; renderers push their clip/transform stack. */
class SaveGraphicsState(matrix: PdfMatrix, val blendMode: String? = null) : DrawCommand(matrix)

/** `Q` (§8.4.4). */
class RestoreGraphicsState(matrix: PdfMatrix) : DrawCommand(matrix)

/** `W`/`W*` (§8.5.4): intersect the clip with [path]. */
class ClipPath(
    val path: PdfPath,
    matrix: PdfMatrix,
    val evenOdd: Boolean
) : DrawCommand(matrix)

/** `f`/`F`/`f*` (§8.5.3.1). [alpha] is the constant fill alpha. */
class FillPath(
    val path: PdfPath,
    matrix: PdfMatrix,
    val color: PdfColor,
    val alpha: Double,
    val evenOdd: Boolean
) : DrawCommand(matrix)

/** `S`/`s` (§8.5.3.1). */
class StrokePath(
    val path: PdfPath,
    matrix: PdfMatrix,
    val color: PdfColor,
    val alpha: Double,
    val lineStyle: LineStyle
) : DrawCommand(matrix)

/** `B`/`B*`/`b`/`b*` (§8.5.3.1). */
class FillStrokePath(
    val path: PdfPath,
    matrix: PdfMatrix,
    val fillColor: PdfColor,
    val fillAlpha: Double,
    val strokeColor: PdfColor,
    val strokeAlpha: Double,
    val evenOdd: Boolean,
    val lineStyle: LineStyle
) : DrawCommand(matrix)

/** One extracted text run (§9.4.3); see [PdfTextRun] for the page-space box. */
class DrawTextRun(val run: PdfTextRun, matrix: PdfMatrix) : DrawCommand(matrix)

/**
 * An image drawn into the unit square of image space mapped by [matrix]
 * (§8.9.5.2). [fillColor] tints stencil masks ([RasterImage.stencil]).
 */
class DrawRasterImage(
    val image: RasterImage,
    matrix: PdfMatrix,
    val fillColor: PdfColor,
    val fillAlpha: Double
) : DrawCommand(matrix)

/**
 * A page's display list: draw commands in stream order plus non-fatal warnings
 * (§7.8, §8, §9). No pixels — renderers (T9) consume [commands].
 */
class DisplayList(val commands: List<DrawCommand>, val warnings: List<String>) {

    /** Text runs in reading order (content order). */
    val textRuns: List<PdfTextRun> get() = commands.filterIsInstance<DrawTextRun>().map { it.run }

    /** Raster images in content order. */
    val images: List<DrawRasterImage> get() = commands.filterIsInstance<DrawRasterImage>()

    /** Painted path commands (fills, strokes and their combinations). */
    val paths: List<DrawCommand>
        get() = commands.filter { it is FillPath || it is StrokePath || it is FillStrokePath }
}

/**
 * Builds the display list of this page (§8 graphics + §9 text). Form XObjects
 * are expanded in place with their own /Resources and /Matrix; images decode
 * through [decoder] when the codec needs the platform (DCT/JPX).
 */
fun PdfPage.buildDisplayList(decoder: ExternalImageDecoder? = null): DisplayList =
    DisplayListBuilder(this, decoder).build()
