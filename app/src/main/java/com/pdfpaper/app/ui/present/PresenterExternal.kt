package com.pdfpaper.app.ui.present

import android.animation.ValueAnimator
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Display
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.pdfpaper.app.present.PenStroke
import com.pdfpaper.app.present.PresentGeometry
import com.pdfpaper.app.present.PresentTransition
import kotlin.math.roundToInt

/**
 * Finds a non-default display the system already exposes (USB-C, HDMI or a
 * system overlay) for the presenter view (T53). PDFPaper never opens, connects
 * to or casts to a display itself: it only draws on a connection the user has
 * already made in Android, so no network code exists on this path.
 */
@Composable
fun rememberPresentationDisplay(): Display? {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(DisplayManager::class.java)
    }
    var display by remember(manager) {
        mutableStateOf(manager?.displays?.firstOrNull(::isPresentationDisplay))
    }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose { }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                display = manager.displays.firstOrNull(::isPresentationDisplay)
            }

            override fun onDisplayRemoved(displayId: Int) {
                display = manager.displays.firstOrNull(::isPresentationDisplay)
            }

            override fun onDisplayChanged(displayId: Int) {
                display = manager.displays.firstOrNull(::isPresentationDisplay)
            }
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        display = manager.displays.firstOrNull(::isPresentationDisplay)
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    return display
}

fun isPresentationDisplay(display: Display): Boolean =
    display.displayId != Display.DEFAULT_DISPLAY &&
        display.isValid &&
        display.state != Display.STATE_OFF

/** Physical pixel width of the slide surface on [display]. */
fun presentationDisplayWidth(display: Display?): Int {
    if (display == null) return 0
    return runCatching { display.mode.physicalWidth }
        .getOrDefault(0)
        .coerceAtLeast(0)
}

/**
 * The external slide surface (T53). Slides are drawn letterboxed on black;
 * page changes fade or slide, and the temporary pen and laser are mirrored
 * here exactly as they are on the phone.
 */
class PresenterPresentation(
    context: Context,
    display: Display
) : Presentation(context, display) {

    val slideView = PresenterSlideView(context)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        setContentView(slideView)
    }

    fun showFrame(
        bitmap: Bitmap?,
        page: Int,
        direction: Int,
        transition: PresentTransition,
        strokes: List<PenStroke>,
        laser: Pair<Float, Float>?,
        black: Boolean
    ) {
        slideView.update(bitmap, page, direction, transition, strokes, laser, black)
    }
}

/** The custom View used inside the external [Presentation]. */
class PresenterSlideView(context: Context) : View(context) {

    private var current: Bitmap? = null
    private var outgoing: Bitmap? = null
    private var currentPage: Int = -1
    private var direction: Int = 1
    private var transition: PresentTransition = PresentTransition.NONE
    private var strokes: List<PenStroke> = emptyList()
    private var laser: Pair<Float, Float>? = null
    private var black: Boolean = false
    private var progress: Float = 1f
    private var animator: ValueAnimator? = null

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 40, 30)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 55, 45)
        style = Paint.Style.FILL
    }
    private val laserGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 55, 45)
        style = Paint.Style.FILL
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            18f,
            resources.displayMetrics
        )
        textAlign = Paint.Align.CENTER
    }

    init {
        setBackgroundColor(Color.BLACK)
    }

    fun update(
        bitmap: Bitmap?,
        page: Int,
        direction: Int,
        transition: PresentTransition,
        strokes: List<PenStroke>,
        laser: Pair<Float, Float>?,
        black: Boolean
    ) {
        val wasBlack = this.black
        this.black = black
        this.strokes = strokes
        this.laser = laser
        this.direction = if (direction >= 0) 1 else -1
        this.transition = transition
        if (black) {
            animator?.cancel()
            animator = null
            outgoing = null
            invalidate()
            return
        }
        if (bitmap == null) {
            invalidate()
            return
        }
        if (page != currentPage && current != null && !wasBlack && transition != PresentTransition.NONE) {
            outgoing = current
            current = bitmap
            currentPage = page
            animator?.cancel()
            progress = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = TRANSITION_MILLIS
                interpolator = LinearInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            animator?.cancel()
            animator = null
            outgoing = null
            current = bitmap
            currentPage = page
            progress = 1f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        if (black) return
        val page = current
        if (page == null) {
            canvas.drawText(
                "Waiting for the slide",
                width / 2f,
                height / 2f,
                hintPaint
            )
            return
        }
        val p = progress.coerceIn(0f, 1f)
        if (outgoing != null && p < 1f) {
            when (transition) {
                PresentTransition.FADE -> {
                    drawPage(canvas, outgoing!!, 0f, 1f - p, drawInk = false)
                }
                PresentTransition.SLIDE -> {
                    drawPage(canvas, outgoing!!, -direction * p * width, 1f, drawInk = false)
                }
                PresentTransition.NONE -> Unit
            }
        }
        when (transition) {
            PresentTransition.FADE -> drawPage(canvas, page, 0f, p, drawInk = true)
            PresentTransition.SLIDE -> {
                drawPage(canvas, page, direction * (1f - p) * width, 1f, drawInk = true)
            }
            PresentTransition.NONE -> drawPage(canvas, page, 0f, 1f, drawInk = true)
        }
    }

    private fun drawPage(
        canvas: Canvas,
        bitmap: Bitmap,
        offsetX: Float,
        alpha: Float,
        drawInk: Boolean
    ) {
        val fit = PresentGeometry.fit(
            width.toFloat(),
            height.toFloat(),
            bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()
        )
        if (fit.width <= 0f || fit.height <= 0f) return
        val dst = RectF(
            fit.left + offsetX,
            fit.top,
            fit.right + offsetX,
            fit.bottom
        )
        val oldAlpha = bitmapPaint.alpha
        bitmapPaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
        bitmapPaint.alpha = oldAlpha
        if (!drawInk) return
        val size = minOf(dst.width(), dst.height())
        strokePaint.strokeWidth = (size * 0.006f).coerceIn(3f, 16f)
        strokes.forEach { stroke ->
            if (stroke.points.size < 4) return@forEach
            var i = 0
            var lastX = 0f
            var lastY = 0f
            while (i + 1 < stroke.points.size) {
                val x = dst.left + stroke.points[i].coerceIn(0f, 1f) * dst.width()
                val y = dst.top + stroke.points[i + 1].coerceIn(0f, 1f) * dst.height()
                if (i > 0) canvas.drawLine(lastX, lastY, x, y, strokePaint)
                lastX = x
                lastY = y
                i += 2
            }
            if (stroke.points.size == 2) {
                canvas.drawCircle(lastX, lastY, strokePaint.strokeWidth / 2f, strokePaint)
            }
        }
        laser?.let { (fx, fy) ->
            val x = dst.left + fx.coerceIn(0f, 1f) * dst.width()
            val y = dst.top + fy.coerceIn(0f, 1f) * dst.height()
            val radius = (size * 0.010f).coerceIn(7f, 26f)
            canvas.drawCircle(x, y, radius * 2.6f, laserGlowPaint)
            canvas.drawCircle(x, y, radius, laserPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TRANSITION_MILLIS = 220L
    }
}
