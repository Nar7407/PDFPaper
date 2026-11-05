package com.pdfpaper.app.ui.viewer.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.engine.annot.InkTool
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.render.PageRenderPlan
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * T54 focused drawing surface. It is deliberately separate from the scrolling
 * viewer: one page is fitted to the screen and the whole surface is the page,
 * so a stylus never competes with a scroll gesture. On Android 10+ the active
 * segment is painted into a front-buffered `SurfaceView` through
 * `CanvasFrontBufferedRenderer`; the reader's normal Compose page canvas is the
 * fallback on older versions (and remains available for ordinary annotation).
 */
@Composable
fun InkFocusOverlay(
    pageIndex: Int,
    pageCount: Int,
    bitmap: Bitmap?,
    plan: PageRenderPlan?,
    aspect: Float,
    state: InkUiState,
    onNeedPage: (Int, Int) -> Unit,
    onStart: (Int, Float, Float, Float, Float, Boolean) -> Unit,
    onExtend: (Float, Float, Float, Float, Boolean) -> Unit,
    onEnd: (Boolean) -> Unit,
    onMode: (InkToolMode) -> Unit,
    onColor: (PdfColor) -> Unit,
    onWidth: (Double) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClose: () -> Unit
) {
    LaunchedEffect(pageIndex, bitmap) {
        if (bitmap == null) onNeedPage(pageIndex, 1600)
    }
    Surface(color = Color(0xFF14161A)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF20242A))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ink canvas · page ${pageIndex + 1} of $pageCount",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (state.stylusOnly) "Stylus only" else "Stylus or finger",
                    color = Color(0xFFB8C0CC),
                    style = MaterialTheme.typography.labelSmall
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close ink canvas", tint = Color.White)
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth().testTag(UiTags.INK_FOCUS_SURFACE)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            InkFocusView(context).also { view ->
                                view.onInkStart = onStart
                                view.onInkExtend = onExtend
                                view.onInkEnd = onEnd
                            }
                        },
                        update = { view ->
                            view.onInkStart = onStart
                            view.onInkExtend = onExtend
                            view.onInkEnd = onEnd
                            view.update(
                                FocusScene(
                                    pageIndex = pageIndex,
                                    bitmap = bitmap,
                                    plan = plan,
                                    aspect = aspect,
                                    strokes = state.strokesOn(pageIndex),
                                    selected = state.selected,
                                    mode = state.mode,
                                    color = state.color,
                                    width = state.width,
                                    stylusOnly = state.stylusOnly
                                )
                            )
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "The focused ink canvas needs Android 10 or newer. " +
                                "Draw on the page instead; pressure, tilt and the eraser still work.",
                            color = Color(0xFFB8C0CC)
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF20242A))
                    .padding(horizontal = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TextButton(
                        onClick = { onMode(InkToolMode.PEN) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (state.mode == InkToolMode.PEN) {
                                Color(0xFF8ED8C7)
                            } else {
                                Color.White
                            }
                        )
                    ) { Text("Pen") }
                    TextButton(
                        onClick = { onMode(InkToolMode.HIGHLIGHTER) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (state.mode == InkToolMode.HIGHLIGHTER) {
                                Color(0xFFFFD36E)
                            } else {
                                Color.White
                            }
                        )
                    ) { Text("Highlighter") }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(onClick = onUndo, enabled = state.canUndo) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Undo,
                                contentDescription = "Undo ink",
                                tint = if (state.canUndo) Color.White else Color(0xFF5D6672)
                            )
                        }
                        IconButton(onClick = onRedo, enabled = state.canRedo) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Redo,
                                contentDescription = "Redo ink",
                                tint = if (state.canRedo) Color.White else Color(0xFF5D6672)
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (color in InkPalette.colors) {
                        val selected = color.red == state.color.red &&
                            color.green == state.color.green &&
                            color.blue == state.color.blue
                        Box(
                            modifier = Modifier
                                .padding(2.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(
                                    Color(
                                        color.red.toFloat(),
                                        color.green.toFloat(),
                                        color.blue.toFloat()
                                    )
                                )
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) Color.White else Color(0xFF6E7784),
                                    shape = CircleShape
                                )
                                .clickable { onColor(color) }
                        )
                    }
                    if (state.mode == InkToolMode.PEN) {
                        for (width in InkPalette.widths) {
                            TextButton(onClick = { onWidth(width) }) {
                                Text(
                                    text = width.roundToInt().toString(),
                                    color = if (state.width == width) {
                                        Color(0xFF8ED8C7)
                                    } else {
                                        Color.White
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal data class FocusScene(
    val pageIndex: Int = 0,
    val bitmap: Bitmap? = null,
    val plan: PageRenderPlan? = null,
    val aspect: Float = 612f / 792f,
    val strokes: List<InkStroke> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val mode: InkToolMode = InkToolMode.PEN,
    val color: PdfColor = InkUiState.INK_BLACK,
    val width: Double = 2.0,
    val stylusOnly: Boolean = false
)

private data class FocusSegment(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val width: Float,
    val color: Int,
    val alpha: Int,
    val multiply: Boolean
)

/**
 * Low-latency drawing view. The multi-buffered layer is the page plus every
 * committed stroke; the front-buffered layer is only the newest short segment.
 * `CanvasFrontBufferedRenderer` reports every front segment back to the
 * multi-buffer callback on commit, so a new stroke is folded into the stable
 * layer without re-rendering the page.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class InkFocusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    var onInkStart: ((Int, Float, Float, Float, Float, Boolean) -> Unit)? = null
    var onInkExtend: ((Float, Float, Float, Float, Boolean) -> Unit)? = null
    var onInkEnd: ((Boolean) -> Unit)? = null

    @Volatile
    private var scene = FocusScene()
    private var renderer: androidx.graphics.lowlatency.CanvasFrontBufferedRenderer<FocusSegment>? = null
    private val localStrokes = ArrayList<InkStroke>()
    private val gestureSamples = ArrayList<InkSample>()
    private var lastViewX = 0f
    private var lastViewY = 0f
    private var gestureActive = false
    private var erasingGesture = false
    private var lastPenTime = 0L
    private var gestureTool = InkTool.PEN
    private var gestureWidth = 2.0
    private var gestureColor = InkUiState.INK_BLACK

    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = AndroidColor.rgb(0, 137, 123)
    }
    private val callback = object :
        androidx.graphics.lowlatency.CanvasFrontBufferedRenderer.Callback<FocusSegment> {
        @Suppress("UNUSED_PARAMETER")
        override fun onDrawFrontBufferedLayer(
            canvas: Canvas,
            bufferWidth: Int,
            bufferHeight: Int,
            param: FocusSegment
        ) {
            drawFront(canvas, param)
        }

        @Suppress("UNUSED_PARAMETER")
        override fun onDrawMultiBufferedLayer(
            canvas: Canvas,
            bufferWidth: Int,
            bufferHeight: Int,
            params: Collection<FocusSegment>
        ) {
            drawScene(canvas, bufferWidth, bufferHeight)
        }
    }

    init {
        holder.addCallback(this)
        setWillNotDraw(true)
    }

    internal fun update(next: FocusScene) {
        val old = scene
        if (old == next) return
        scene = next
        if (old.strokes != next.strokes || old.pageIndex != next.pageIndex) {
            localStrokes.clear()
        }
        renderer?.renderMultiBufferedLayer(emptyList())
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderer = androidx.graphics.lowlatency.CanvasFrontBufferedRenderer(
            this,
            callback,
            HardwareBuffer.RGBA_8888
        )
        renderer?.renderMultiBufferedLayer(emptyList())
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderer?.renderMultiBufferedLayer(emptyList())
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderer?.release(true)
        renderer = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val current = scene
        if (current.mode != InkToolMode.PEN && current.mode != InkToolMode.HIGHLIGHTER) {
            return false
        }
        val rect = pageRect(current, width, height)
        val context = StylusInputPolicy.StylusContext(
            tool = StylusInputPolicy.StylusContext.TOOL_DRAW,
            penActive = gestureActive,
            lastPenTime = lastPenTime,
            stylusOnly = current.stylusOnly
        )
        val result = StylusInputPolicy.filter(
            event.getToolType(0), event.buttonState, event.eventTime, context)
        if (result.scrollZoom) {
            return false
        }
        if (result.consume) {
            return true
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!rect.contains(event.x, event.y)) return false
                gestureActive = true
                lastPenTime = event.eventTime
                val penTool =
                    event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                        event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                erasingGesture = penTool &&
                    (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
                    event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                gestureSamples.clear()
                localStrokes.removeAll { it.id < 0 }
                gestureTool = if (current.mode == InkToolMode.HIGHLIGHTER) {
                    InkTool.HIGHLIGHTER
                } else {
                    InkTool.PEN
                }
                gestureWidth = if (gestureTool == InkTool.HIGHLIGHTER) {
                    InkPalette.HIGHLIGHTER_WIDTH
                } else {
                    current.width
                }
                gestureColor = current.color
                lastViewX = event.x
                lastViewY = event.y
                addPoint(event, rect, current)
                onInkStart?.invoke(
                    current.pageIndex,
                    fractionX(event.x, rect),
                    fractionY(event.y, rect),
                    pressure(event),
                    tilt(event),
                    erasingGesture
                )
                parent?.requestDisallowInterceptTouchEvent(true)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gestureActive) return false
                for (index in 0 until event.historySize) {
                    extend(event.getHistoricalX(0, index), event.getHistoricalY(0, index),
                        event.getHistoricalPressure(0, index),
                        event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, 0, index),
                        rect, current)
                }
                extend(event.x, event.y, event.pressure,
                    event.getAxisValue(MotionEvent.AXIS_TILT), rect, current)
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!gestureActive) return false
                extend(event.x, event.y, event.pressure,
                    event.getAxisValue(MotionEvent.AXIS_TILT), rect, current)
                // A deliberate pause at the end of a line/circle/box snaps the
                // stroke to a clean shape, matching the scrolling canvas.
                finishGesture(event.eventTime - event.downTime >= 550L)
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!gestureActive) return false
                gestureActive = false
                gestureSamples.clear()
                localStrokes.removeAll { it.id < 0 }
                renderer?.cancel()
                onInkEnd?.invoke(false)
                true
            }
            else -> false
        }
    }

    private fun addPoint(event: MotionEvent, rect: RectF, current: FocusScene) {
        val plan = current.plan ?: return
        val sample = InkTransform.sampleAt(
            plan,
            fractionX(event.x, rect),
            fractionY(event.y, rect),
            pressure(event),
            tilt(event)
        ) ?: return
        gestureSamples += sample
    }

    private fun extend(
        x: Float,
        y: Float,
        rawPressure: Float,
        rawTilt: Float,
        rect: RectF,
        current: FocusScene
    ) {
        if (!gestureActive) return
        val plan = current.plan ?: return
        val sample = InkTransform.sampleAt(
            plan,
            fractionX(x, rect),
            fractionY(y, rect),
            normalizedPressure(rawPressure),
            tiltDegrees(rawTilt)
        ) ?: return
        gestureSamples += sample
        val fx = fractionX(x, rect)
        val fy = fractionY(y, rect)
        onInkExtend?.invoke(
            fx,
            fy,
            normalizedPressure(rawPressure),
            tiltDegrees(rawTilt),
            erasingGesture
        )
        if (!erasingGesture && hypot(x - lastViewX, y - lastViewY) >= 1.5f) {
            val widthPx = viewWidth(plan, rect, normalizedPressure(rawPressure))
            val segment = FocusSegment(
                x0 = lastViewX,
                y0 = lastViewY,
                x1 = x,
                y1 = y,
                width = widthPx,
                color = androidColor(gestureColor),
                alpha = if (gestureTool == InkTool.HIGHLIGHTER) {
                    (InkPalette.HIGHLIGHTER_OPACITY * 255).roundToInt()
                } else {
                    255
                },
                multiply = gestureTool == InkTool.HIGHLIGHTER
            )
            renderer?.renderFrontBufferedLayer(segment)
        }
        lastViewX = x
        lastViewY = y
    }

    private fun finishGesture(held: Boolean) {
        if (!gestureActive) return
        gestureActive = false
        if (!erasingGesture && gestureSamples.size >= 1) {
            localStrokes.removeAll { it.id < 0 }
            localStrokes += InkStroke(
                id = -1,
                pageIndex = scene.pageIndex,
                samples = gestureSamples.toList(),
                color = gestureColor,
                width = gestureWidth,
                opacity = if (gestureTool == InkTool.HIGHLIGHTER) {
                    InkPalette.HIGHLIGHTER_OPACITY
                } else {
                    1.0
                },
                tool = gestureTool
            )
        }
        gestureSamples.clear()
        onInkEnd?.invoke(held)
        renderer?.commit()
    }

    private fun drawScene(canvas: Canvas, bufferWidth: Int, bufferHeight: Int) {
        val current = scene
        canvas.drawColor(AndroidColor.rgb(20, 22, 26))
        val rect = pageRect(current, bufferWidth, bufferHeight)
        if (current.bitmap != null) {
            pagePaint.alpha = 255
            canvas.drawBitmap(current.bitmap, null, rect, pagePaint)
        } else {
            pagePaint.color = AndroidColor.WHITE
            pagePaint.alpha = 255
            canvas.drawRect(rect, pagePaint)
        }
        val plan = current.plan ?: return
        for (stroke in current.strokes) drawStroke(canvas, plan, rect, stroke)
        for (stroke in localStrokes) drawStroke(canvas, plan, rect, stroke)
        for (stroke in current.strokes) {
            if (stroke.id !in current.selected) continue
            val box = InkGeometry.bounds(stroke.samples, stroke.width) ?: continue
            val a = viewPoint(plan, rect, box.left, box.bottom)
            val b = viewPoint(plan, rect, box.right, box.top)
            canvas.drawRect(
                min(a.first, b.first),
                min(a.second, b.second),
                kotlin.math.max(a.first, b.first),
                kotlin.math.max(a.second, b.second),
                boxPaint
            )
        }
    }

    private fun drawFront(canvas: Canvas, segment: FocusSegment) {
        inkPaint.color = segment.color
        inkPaint.alpha = segment.alpha
        inkPaint.strokeWidth = segment.width.coerceAtLeast(1f)
        if (segment.multiply && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            inkPaint.blendMode = android.graphics.BlendMode.MULTIPLY
        } else {
            inkPaint.blendMode = android.graphics.BlendMode.SRC_OVER
        }
        canvas.drawLine(segment.x0, segment.y0, segment.x1, segment.y1, inkPaint)
    }

    private fun drawStroke(canvas: Canvas, plan: PageRenderPlan, rect: RectF, stroke: InkStroke) {
        if (stroke.samples.isEmpty()) return
        inkPaint.color = androidColor(stroke.color)
        inkPaint.alpha = (stroke.opacity * 255).roundToInt().coerceIn(0, 255)
        inkPaint.blendMode = if (stroke.highlighter) {
            android.graphics.BlendMode.MULTIPLY
        } else {
            android.graphics.BlendMode.SRC_OVER
        }
        val fullWidth = viewWidthFor(stroke, plan, rect)
        if (stroke.samples.size == 1) {
            val point = viewPoint(plan, rect, stroke.samples[0].x, stroke.samples[0].y)
            inkPaint.style = Paint.Style.FILL
            canvas.drawCircle(point.first, point.second, fullWidth / 2f, inkPaint)
            inkPaint.style = Paint.Style.STROKE
            return
        }
        for (i in 0 until stroke.samples.size - 1) {
            val a = stroke.samples[i]
            val b = stroke.samples[i + 1]
            val pointA = viewPoint(plan, rect, a.x, a.y)
            val pointB = viewPoint(plan, rect, b.x, b.y)
            val pressure = (a.pressure + b.pressure) / 2.0
            inkPaint.strokeWidth = if (stroke.highlighter) {
                fullWidth
            } else {
                (fullWidth * (0.35 + 0.65 * pressure)).toFloat()
            }.coerceAtLeast(1f)
            canvas.drawLine(pointA.first, pointA.second, pointB.first, pointB.second, inkPaint)
        }
    }

    private fun viewWidth(plan: PageRenderPlan, rect: RectF, pressure: Float): Float {
        val nominal = if (gestureTool == InkTool.HIGHLIGHTER) {
            InkPalette.HIGHLIGHTER_WIDTH
        } else {
            // The live preview follows the same 35 %..100 % pressure curve as
            // the saved appearance, so the line does not change width on commit.
            gestureWidth * (0.35 + 0.65 * pressure.coerceIn(0f, 1f))
        }
        return (nominal * plan.scale * (rect.width() / plan.pixelWidth.coerceAtLeast(1)))
            .toFloat().coerceAtLeast(1f)
    }

    private fun viewWidthFor(stroke: InkStroke, plan: PageRenderPlan, rect: RectF): Float =
        (stroke.width * plan.scale * (rect.width() / plan.pixelWidth.coerceAtLeast(1)))
            .toFloat().coerceAtLeast(1f)

    private fun viewPoint(plan: PageRenderPlan, rect: RectF, x: Double, y: Double): Pair<Float, Float> {
        val point = plan.matrix.apply(x, y)
        val sx = rect.width() / plan.pixelWidth.coerceAtLeast(1)
        val sy = rect.height() / plan.pixelHeight.coerceAtLeast(1)
        return rect.left + (point[0] * sx).toFloat() to
            rect.top + (point[1] * sy).toFloat()
    }

    private fun pageRect(current: FocusScene, viewWidth: Int, viewHeight: Int): RectF {
        val aspect = current.bitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }
            ?: current.plan?.let {
                it.pixelWidth.toFloat() / it.pixelHeight.coerceAtLeast(1)
            }
            ?: current.aspect.coerceAtLeast(0.1f)
        val availableWidth = viewWidth.coerceAtLeast(1).toFloat()
        val availableHeight = viewHeight.coerceAtLeast(1).toFloat()
        val width = min(availableWidth, availableHeight * aspect)
        val height = width / aspect.coerceAtLeast(0.01f)
        val left = (availableWidth - width) / 2f
        val top = (availableHeight - height) / 2f
        return RectF(left, top, left + width, top + height)
    }

    private fun fractionX(x: Float, rect: RectF): Float =
        ((x - rect.left) / rect.width().coerceAtLeast(1f)).coerceIn(0f, 1f)

    private fun fractionY(y: Float, rect: RectF): Float =
        ((y - rect.top) / rect.height().coerceAtLeast(1f)).coerceIn(0f, 1f)

    private fun pressure(event: MotionEvent): Float =
        normalizedPressure(event.pressure)

    private fun normalizedPressure(raw: Float): Float =
        if (!raw.isFinite() || raw <= 0.001f) 1f else raw.coerceIn(0f, 1f)

    private fun tilt(event: MotionEvent): Float =
        tiltDegrees(event.getAxisValue(MotionEvent.AXIS_TILT))

    private fun tiltDegrees(radians: Float): Float {
        if (!radians.isFinite()) return 0f
        return Math.toDegrees(radians.toDouble()).toFloat().coerceIn(0f, 90f)
    }

    private fun androidColor(color: PdfColor): Int = AndroidColor.rgb(
        (color.red.coerceIn(0.0, 1.0) * 255).roundToInt(),
        (color.green.coerceIn(0.0, 1.0) * 255).roundToInt(),
        (color.blue.coerceIn(0.0, 1.0) * 255).roundToInt()
    )
}
