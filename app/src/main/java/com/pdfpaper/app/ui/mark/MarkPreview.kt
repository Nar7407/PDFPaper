package com.pdfpaper.app.ui.mark

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.mark.MarkAlign
import kotlin.math.min

/**
 * The live preview of a marked page (T44).
 *
 * The first page of the document is drawn as it is; the marks are drawn on top
 * of it, in the same places and with the same sizes the engine will use. It
 * is a guide, not a renderer: the text is drawn with the platform font and
 * the picture is scaled to its box.
 */
@Composable
fun MarkPreview(
    bitmap: Bitmap?,
    box: MarkBox,
    modifier: Modifier = Modifier,
    marks: DrawScope.(MarkPreviewMap) -> Unit
) {
    Box(modifier = modifier.background(Color.White)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pageWidth = box.displayWidth.toFloat()
            val pageHeight = box.displayHeight.toFloat()
            if (pageWidth <= 0f || pageHeight <= 0f) return@Canvas
            val scale = min(size.width / pageWidth, size.height / pageHeight)
            val width = pageWidth * scale
            val height = pageHeight * scale
            val offsetX = (size.width - width) / 2f
            val offsetY = (size.height - height) / 2f
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                bitmap?.let {
                    native.drawBitmap(
                        it,
                        null,
                        RectF(offsetX, offsetY, offsetX + width, offsetY + height),
                        null
                    )
                }
                native.drawRect(
                    offsetX,
                    offsetY,
                    offsetX + width,
                    offsetY + height,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 1f
                        color = android.graphics.Color.argb(60, 0, 0, 0)
                    }
                )
            }
            marks(MarkPreviewMap(box, scale, offsetX, offsetY))
        }
    }
}

/**
 * Maps the points of the page onto the pixels of the preview.
 *
 * The page may be turned (/Rotate), so a point in PDF user space is first
 * turned into a point of the shown page and then scaled.
 */
class MarkPreviewMap(
    private val box: MarkBox,
    /** How many preview pixels one point of the page is. */
    val pixelsPerPoint: Float,
    private val offsetX: Float,
    private val offsetY: Float
) {

    /** A point in PDF user space as a pixel on the preview. */
    fun point(x: Double, y: Double): Offset {
        val (dx, dy) = box.toDevice(x, y)
        return Offset(
            offsetX + dx.toFloat() * pixelsPerPoint,
            offsetY + dy.toFloat() * pixelsPerPoint
        )
    }

    /** The rotation of the page, added to the rotation of a mark. */
    val pageRotation: Float get() = box.normalizedRotate().toFloat()
}

/** The engine's colour as a Compose colour. */
fun PdfColor.toComposeColor(): Color =
    Color(red.toFloat(), green.toFloat(), blue.toFloat(), alpha.toFloat())

/**
 * Draws one line of mark text on the preview: the baseline sits on the
 * anchor, the line is aligned to it and turned by [rotationDegrees].
 */
fun DrawScope.drawMarkText(
    map: MarkPreviewMap,
    text: String,
    anchor: MarkAnchor,
    sizeInPoints: Double,
    color: Color,
    alpha: Float = 1f,
    rotationDegrees: Double = 0.0
) {
    if (text.isBlank()) return
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        this.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        textSize = (sizeInPoints * map.pixelsPerPoint).toFloat()
        textAlign = when (anchor.align) {
            MarkAlign.LEFT -> Paint.Align.LEFT
            MarkAlign.CENTER -> Paint.Align.CENTER
            MarkAlign.RIGHT -> Paint.Align.RIGHT
        }
    }
    val point = map.point(anchor.x, anchor.y)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        native.save()
        native.rotate(map.pageRotation + rotationDegrees.toFloat(), point.x, point.y)
        native.drawText(text, point.x, point.y, paint)
        native.restore()
    }
}

/**
 * Draws the picture of a watermark on the preview. The picture is turned
 * around its bottom-left corner, the way the engine places it.
 */
fun DrawScope.drawMarkImage(
    map: MarkPreviewMap,
    bitmap: Bitmap,
    userX: Double,
    userY: Double,
    widthInPoints: Double,
    heightInPoints: Double,
    alpha: Float = 1f,
    rotationDegrees: Double = 0.0
) {
    val origin = map.point(userX, userY)
    val width = (widthInPoints * map.pixelsPerPoint).toFloat()
    val height = (heightInPoints * map.pixelsPerPoint).toFloat()
    if (width <= 0f || height <= 0f) return
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        this.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
    }
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        native.save()
        native.rotate(map.pageRotation + rotationDegrees.toFloat(), origin.x, origin.y)
        native.drawBitmap(
            bitmap,
            null,
            RectF(origin.x, origin.y - height, origin.x + width, origin.y),
            paint
        )
        native.restore()
    }
}
