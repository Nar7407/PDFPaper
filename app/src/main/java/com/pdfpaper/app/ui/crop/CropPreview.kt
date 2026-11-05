package com.pdfpaper.app.ui.crop

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.render.PageRenderPlan
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The crop editor preview (T46): the rendered page with the new box outlined,
 * the removed area dimmed, a rule-of-thirds grid and four draggable corners.
 *
 * Handles are identified in PDF user space and drawn through the render plan,
 * so a rotated page needs no special case: the corner the user grabs is always
 * the corner the engine will move.
 */
@Composable
fun CropPreview(
    bitmap: Bitmap,
    plan: PageRenderPlan,
    pageBox: PdfRect,
    cropBox: PdfRect,
    showGrid: Boolean,
    onCropChange: (PdfRect) -> Unit,
    modifier: Modifier = Modifier
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val boxState = rememberUpdatedState(cropBox)
    val pageState = rememberUpdatedState(pageBox)
    val changeState = rememberUpdatedState(onCropChange)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val fit = fitBitmap(
        bitmap.width,
        bitmap.height,
        canvasSize.width.toDouble(),
        canvasSize.height.toDouble()
    )
    val border = MaterialTheme.colorScheme.primary
    val handleFill = Color.White

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(plan, canvasSize, bitmap) {
                var active: CropHandle? = null
                detectDragGestures(
                    onDragStart = { position ->
                        val box = boxState.value
                        val currentFit = fitBitmap(
                            bitmap.width,
                            bitmap.height,
                            size.width.toDouble(),
                            size.height.toDouble()
                        )
                        active = CropHandle.entries
                            .map { handle ->
                                val point = canvasCorner(box, handle, plan.matrix, currentFit)
                                handle to hypot(point.first - position.x, point.second - position.y)
                            }
                            .filter { it.second <= HANDLE_HIT_RADIUS_PX }
                            .minByOrNull { it.second }
                            ?.first
                    },
                    onDrag = { change, _ ->
                        val handle = active ?: return@detectDragGestures
                        change.consume()
                        val currentFit = fitBitmap(
                            bitmap.width,
                            bitmap.height,
                            size.width.toDouble(),
                            size.height.toDouble()
                        )
                        val point = canvasToUser(
                            change.position.x.toDouble(),
                            change.position.y.toDouble(),
                            currentFit,
                            plan.matrix
                        ) ?: return@detectDragGestures
                        val next = dragCrop(
                            box = boxState.value,
                            handle = handle,
                            x = point.first,
                            y = point.second,
                            bounds = pageState.value
                        )
                        changeState.value(next)
                    },
                    onDragEnd = { active = null },
                    onDragCancel = { active = null }
                )
            }
    ) {
        drawRect(Color.White)
        drawImage(
            image = image,
            dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
            dstSize = IntSize(
                (bitmap.width * fit.scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * fit.scale).roundToInt().coerceAtLeast(1)
            )
        )

        val page = fit.toCanvas(deviceRect(pageBox, plan.matrix))
        val crop = fit.toCanvas(deviceRect(cropBox, plan.matrix))
        val dim = Color(0x66000000)
        if (crop.top > page.top) {
            drawRect(dim, Offset(page.left.toFloat(), page.top.toFloat()),
                Size(page.width.toFloat(), (crop.top - page.top).toFloat()))
        }
        if (crop.bottom < page.bottom) {
            drawRect(dim, Offset(page.left.toFloat(), crop.bottom.toFloat()),
                Size(page.width.toFloat(), (page.bottom - crop.bottom).toFloat()))
        }
        if (crop.left > page.left) {
            drawRect(dim, Offset(page.left.toFloat(), crop.top.toFloat()),
                Size((crop.left - page.left).toFloat(), crop.height.toFloat()))
        }
        if (crop.right < page.right) {
            drawRect(dim, Offset(crop.right.toFloat(), crop.top.toFloat()),
                Size((page.right - crop.right).toFloat(), crop.height.toFloat()))
        }

        if (showGrid && crop.width > 1.0 && crop.height > 1.0) {
            val grid = Color(0x99FFFFFF)
            val thin = Stroke(width = 1.dp.toPx())
            for (i in 1..2) {
                val x = crop.left + crop.width * i / 3.0
                drawLine(grid, Offset(x.toFloat(), crop.top.toFloat()),
                    Offset(x.toFloat(), crop.bottom.toFloat()), thin.width)
                val y = crop.top + crop.height * i / 3.0
                drawLine(grid, Offset(crop.left.toFloat(), y.toFloat()),
                    Offset(crop.right.toFloat(), y.toFloat()), thin.width)
            }
        }

        drawRect(
            color = border,
            topLeft = Offset(crop.left.toFloat(), crop.top.toFloat()),
            size = Size(crop.width.toFloat().coerceAtLeast(1f), crop.height.toFloat().coerceAtLeast(1f)),
            style = Stroke(width = 2.dp.toPx())
        )
        for (handle in CropHandle.entries) {
            val point = canvasCorner(cropBox, handle, plan.matrix, fit)
            val center = Offset(point.first.toFloat(), point.second.toFloat())
            drawCircle(handleFill, radius = 7.dp.toPx(), center = center)
            drawCircle(border, radius = 7.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
        }
    }
}

/**
 * "Before" and "After" thumbnails. The after picture is cut out of the same
 * rendered bitmap with the plan matrix, so it shows exactly the visible result
 * (contents, rotation and all) without a second render.
 */
@Composable
fun CropBeforeAfter(
    bitmap: Bitmap,
    plan: PageRenderPlan,
    cropBox: PdfRect,
    modifier: Modifier = Modifier
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val all = PixelRect(0, 0, bitmap.width, bitmap.height)
    val cropped = sourcePixels(cropBox, plan.matrix, bitmap.width, bitmap.height)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CropThumb(image, all, "Before", Modifier.weight(1f))
        CropThumb(image, cropped, "After", Modifier.weight(1f))
    }
}

@Composable
private fun CropThumb(
    image: androidx.compose.ui.graphics.ImageBitmap,
    source: PixelRect,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(124.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(4.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val fit = fitBitmap(source.width, source.height, size.width.toDouble(), size.height.toDouble())
                drawImage(
                    image = image,
                    srcOffset = IntOffset(source.left, source.top),
                    srcSize = IntSize(source.width, source.height),
                    dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
                    dstSize = IntSize(
                        (source.width * fit.scale).roundToInt().coerceAtLeast(1),
                        (source.height * fit.scale).roundToInt().coerceAtLeast(1)
                    )
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp)
        )
    }
}

private const val HANDLE_HIT_RADIUS_PX = 48f
