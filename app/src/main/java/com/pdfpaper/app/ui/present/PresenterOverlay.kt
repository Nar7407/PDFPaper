package com.pdfpaper.app.ui.present

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.present.PenStroke
import com.pdfpaper.app.present.PresentCommand
import com.pdfpaper.app.present.PresentGeometry
import com.pdfpaper.app.present.PresentInk
import com.pdfpaper.app.present.PresentKeyMapper
import com.pdfpaper.app.present.PresentKeyRouter
import com.pdfpaper.app.present.PresentTimerReading
import com.pdfpaper.app.present.PresentTransition
import com.pdfpaper.app.present.PresenterTimer
import com.pdfpaper.app.present.pageAspect
import com.pdfpaper.app.render.RenderedPage
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.motion.LocalReduceMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val SlideBlack = Color.Black
private val ConsoleBackground = Color(0xFF111317)
private val ConsoleCard = Color(0xFF1D2026)
private val InkRed = Color(0xFFDC281E)
private val LaserRed = Color(0xFFFF372D)
private val LaserGlow = Color(0x46FF372D)

private data class PresentFrame(
    val pageIndex: Int,
    val rendered: RenderedPage
) {
    val bitmap get() = rendered.bitmap
    val aspect: Float get() = pageAspect(rendered.plan.pixelWidth, rendered.plan.pixelHeight)
}

/**
 * Full-screen presentation mode (T53).
 *
 * This is an overlay inside the viewer, not a new route, so the document,
 * password state and renderer stay exactly as they are. It keeps the screen on,
 * hides the system bars, renders one page at a time and mirrors the slide to a
 * connected display. Temporary pen strokes live only here; "Save as
 * annotations" converts them to real `/Ink` annotations through the normal
 * viewer save flow.
 */
@Composable
fun PresenterOverlay(
    path: String,
    pageCount: Int,
    startPage: Int,
    displayName: String?,
    renderPage: suspend (Int, Int) -> RenderedPage?,
    onExit: () -> Unit,
    onSaveInk: (List<com.pdfpaper.engine.annot.InkSpec>) -> Unit,
    viewModel: PresentViewModel = viewModel()
) {
    if (pageCount <= 0) {
        LaunchedEffect(Unit) { onExit() }
        return
    }

    val state by viewModel.state.collectAsState()
    val timer by viewModel.timer.collectAsState()
    // T63: presentation is the largest full-screen transition in the app, so
    // reduce motion wins over the per-session slide/fade choice here too.
    val reduceMotion = LocalReduceMotion.current
    val slideTransition = if (reduceMotion) PresentTransition.NONE else state.transition

    LaunchedEffect(Unit) {
        if (!viewModel.state.value.active) {
            viewModel.start(path, pageCount, startPage)
        }
    }

    PresentWindowEffect()
    BackHandler(enabled = true) {
        when {
            state.black -> viewModel.setBlack(false)
            state.showJumpGrid -> viewModel.showJumpGrid(false)
            state.showOptions -> viewModel.showOptions(false)
            !state.showControls && state.resumePage == null -> viewModel.setControls(true)
            else -> {
                viewModel.exit()
                onExit()
            }
        }
    }

    val external = rememberPresentationDisplay()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val phoneWidthPx = remember(configuration.screenWidthDp, density) {
        with(density) { configuration.screenWidthDp.dp.roundToPx() }
    }
    val externalWidthPx = remember(external) { presentationDisplayWidth(external) }
    val targetWidth = remember(phoneWidthPx, externalWidthPx) {
        maxOf(phoneWidthPx, minOf(externalWidthPx, EXTERNAL_RENDER_WIDTH))
            .coerceIn(MIN_RENDER_WIDTH, EXTERNAL_RENDER_WIDTH)
    }

    var frame by remember { mutableStateOf<PresentFrame?>(null) }
    var nextFrame by remember { mutableStateOf<PresentFrame?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val plans = remember { mutableStateMapOf<Int, com.pdfpaper.engine.render.PageRenderPlan>() }

    LaunchedEffect(state.active, state.pageIndex, targetWidth, attempt) {
        if (!state.active) return@LaunchedEffect
        val index = state.pageIndex
        if (frame?.pageIndex != index) loading = true
        error = null
        val loaded = try {
            renderPage(index, targetWidth)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            null
        }
        if (loaded != null) {
            frame = PresentFrame(index, loaded)
            plans[index] = loaded.plan
        } else if (frame?.pageIndex != index) {
            error = "Slide ${index + 1} could not be rendered."
        }
        loading = false

        val nextIndex = index + 1
        if (nextIndex < state.pageCount) {
            nextFrame = try {
                renderPage(nextIndex, (targetWidth * 0.6f).roundToInt())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                null
            }?.let {
                plans[nextIndex] = it.plan
                PresentFrame(nextIndex, it)
            }
        } else {
            nextFrame = null
        }
    }

    // Keyed by the Display object, not just its id: a display that is
    // unplugged and plugged back gets a new object, and reusing the dismissed
    // Presentation for it would leave the projector blank.
    val externalPresentation = remember(external, context) {
        external?.let { display ->
            runCatching { PresenterPresentation(context, display) }.getOrNull()
        }
    }
    DisposableEffect(externalPresentation) {
        externalPresentation?.let { presentation ->
            runCatching { presentation.show() }
        }
        onDispose {
            externalPresentation?.let { presentation ->
                runCatching { presentation.dismiss() }
            }
        }
    }
    LaunchedEffect(
        externalPresentation,
        frame,
        state.pageIndex,
        state.black,
        state.strokes,
        state.draft,
        state.laser,
        slideTransition,
        state.direction
    ) {
        externalPresentation?.showFrame(
            bitmap = frame?.takeIf { it.pageIndex == state.pageIndex }?.bitmap,
            page = state.pageIndex,
            direction = state.direction,
            transition = slideTransition,
            strokes = state.strokes.filter { it.pageIndex == state.pageIndex },
            laser = state.laser,
            black = state.black
        )
    }

    val exitNow: () -> Unit = {
        viewModel.exit()
        onExit()
    }

    val currentCommand = rememberUpdatedState<(PresentCommand) -> Unit> { command ->
        when (command) {
            PresentCommand.NEXT -> {
                if (state.black) viewModel.setBlack(false)
                viewModel.next()
            }

            PresentCommand.PREVIOUS -> {
                if (state.black) viewModel.setBlack(false)
                viewModel.previous()
            }

            PresentCommand.FIRST -> {
                if (state.black) viewModel.setBlack(false)
                viewModel.first()
            }

            PresentCommand.LAST -> {
                if (state.black) viewModel.setBlack(false)
                viewModel.last()
            }

            PresentCommand.TOGGLE_BLACK -> viewModel.toggleBlack()
            PresentCommand.TOGGLE_CONTROLS -> viewModel.toggleControls()
            PresentCommand.TOGGLE_PEN -> viewModel.togglePen()
            PresentCommand.TOGGLE_TIMER -> viewModel.toggleTimer()
            PresentCommand.EXIT -> exitNow()
        }
    }

    DisposableEffect(viewModel) {
        val handler: (KeyEvent) -> Boolean = { event ->
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    val command = PresentKeyMapper.command(
                        event.keyCode,
                        event.action,
                        event.repeatCount
                    )
                    if (command != null) {
                        currentCommand.value(command)
                        true
                    } else {
                        PresentKeyMapper.handles(event.keyCode)
                    }
                }

                KeyEvent.ACTION_UP -> PresentKeyMapper.handles(event.keyCode)
                else -> false
            }
        }
        PresentKeyRouter.handler = handler
        onDispose {
            if (PresentKeyRouter.handler === handler) PresentKeyRouter.handler = null
        }
    }

    LaunchedEffect(
        state.showControls,
        state.penMode,
        state.showJumpGrid,
        state.showOptions,
        state.resumePage,
        state.pageIndex,
        external
    ) {
        if (
            external == null &&
            state.showControls &&
            !state.penMode &&
            !state.showJumpGrid &&
            !state.showOptions &&
            state.resumePage == null
        ) {
            delay(CONTROL_HIDE_MILLIS)
            viewModel.setControls(false)
        }
    }

    val currentStrokes = remember(state.strokes, state.pageIndex) {
        state.strokes.filter { it.pageIndex == state.pageIndex }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SlideBlack)
            .testTag(UiTags.PRESENT_ROOT)
    ) {
        when {
            state.black -> BlackScreen(
                timer = timer,
                onRestore = {
                    viewModel.setBlack(false)
                    // Coming back from black, the controls are useful again.
                    viewModel.setControls(true)
                }
            )

            external != null -> PresenterConsole(
                frame = frame,
                nextFrame = nextFrame,
                displayName = displayName,
                state = state,
                timer = timer,
                loading = loading,
                error = error,
                onExit = exitNow,
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
                onBlack = viewModel::toggleBlack,
                onPen = viewModel::togglePen,
                onOptions = { viewModel.showOptions(true) },
                onTimerToggle = viewModel::toggleTimer,
                onRestartTimer = viewModel::restartTimer,
                onGoTo = viewModel::goTo,
                onPenStart = { x, y -> viewModel.startStroke(state.pageIndex, x, y) },
                onPenPoint = viewModel::extendStroke,
                onPenEnd = viewModel::endStroke,
                onLaser = viewModel::setLaser,
                onRetry = { attempt++ }
            )

            else -> FullSlide(
                frame = frame,
                state = state,
                timer = timer,
                strokes = currentStrokes,
                loading = loading,
                error = error,
                onExit = exitNow,
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
                onBlack = viewModel::toggleBlack,
                onPen = viewModel::togglePen,
                onTimerToggle = viewModel::toggleTimer,
                onJump = { viewModel.showJumpGrid(true) },
                onOptions = { viewModel.showOptions(true) },
                onPenStart = { x, y -> viewModel.startStroke(state.pageIndex, x, y) },
                onPenPoint = viewModel::extendStroke,
                onPenEnd = viewModel::endStroke,
                onLaser = viewModel::setLaser,
                onPenTap = { viewModel.setControls(true) },
                transition = slideTransition,
                onTapZone = { fraction ->
                    when {
                        fraction < 0.32f -> viewModel.previous()
                        fraction > 0.68f -> viewModel.next()
                        else -> viewModel.toggleControls()
                    }
                },
                onSwipe = { forward ->
                    if (forward) viewModel.next() else viewModel.previous()
                },
                onRetry = { attempt++ }
            )
        }

        if (state.showJumpGrid) {
            JumpGridDialog(
                state = state,
                onGoTo = viewModel::goTo,
                onDismiss = { viewModel.showJumpGrid(false) }
            )
        }

        if (state.showOptions) {
            PresentOptionsDialog(
                state = state,
                onTransition = viewModel::setTransition,
                onTimerMinutes = viewModel::setTimerMinutes,
                onRestartTimer = viewModel::restartTimer,
                onUndoInk = viewModel::undoStroke,
                onClearInk = viewModel::clearStrokes,
                onSaveInk = {
                    val specs = PresentInk.toInkSpecs(state.strokes, plans)
                    if (specs.isNotEmpty()) {
                        viewModel.exit()
                        onSaveInk(specs)
                    }
                },
                onDismiss = { viewModel.showOptions(false) }
            )
        }

        val resumePage = state.resumePage
        if (resumePage != null) {
            AlertDialog(
                onDismissRequest = viewModel::startHere,
                title = { Text("Resume presentation") },
                text = {
                    Text(
                        "You stopped at slide ${resumePage + 1} of ${state.pageCount}. " +
                            "Resume there, or start from slide ${state.pageNumber}?"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = viewModel::resume,
                        modifier = Modifier.testTag(UiTags.PRESENT_RESUME)
                    ) {
                        Text("Resume slide ${resumePage + 1}")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = viewModel::startHere,
                        modifier = Modifier.testTag(UiTags.PRESENT_START_HERE)
                    ) {
                        Text("Start from slide ${state.pageNumber}")
                    }
                }
            )
        }
    }
}

@Composable
private fun FullSlide(
    frame: PresentFrame?,
    state: PresentUiState,
    timer: PresentTimerReading,
    strokes: List<PenStroke>,
    loading: Boolean,
    error: String?,
    transition: PresentTransition,
    onExit: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBlack: () -> Unit,
    onPen: () -> Unit,
    onTimerToggle: () -> Unit,
    onJump: () -> Unit,
    onOptions: () -> Unit,
    onPenStart: (Float, Float) -> Unit,
    onPenPoint: (Float, Float) -> Unit,
    onPenEnd: () -> Unit,
    onLaser: (Float?, Float?) -> Unit,
    onPenTap: () -> Unit,
    onTapZone: (Float) -> Unit,
    onSwipe: (Boolean) -> Unit,
    onRetry: () -> Unit
) {
    val aspect = frame?.aspect ?: 0.75f
    Box(modifier = Modifier.fillMaxSize()) {
        SlideTransition(
            frame = frame,
            strokes = strokes,
            draft = state.draft,
            laser = state.laser,
            transition = transition,
            direction = state.direction,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .presentGestures(
                    penMode = state.penMode,
                    aspect = aspect,
                    onTapZone = onTapZone,
                    onSwipe = onSwipe,
                    onStrokeStart = onPenStart,
                    onStrokePoint = onPenPoint,
                    onStrokeEnd = onPenEnd,
                    onLaser = onLaser,
                    onPenTap = onPenTap
                )
                .testTag(UiTags.PRESENT_SLIDE)
        )

        when {
            frame == null && loading -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )

            frame == null && error != null -> Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(error, color = Color.White, textAlign = TextAlign.Center)
                TextButton(onClick = onRetry) { Text("Try again") }
            }
        }

        if (state.showControls) {
            PresentControls(
                state = state,
                timer = timer,
                onExit = onExit,
                onPrevious = onPrevious,
                onNext = onNext,
                onBlack = onBlack,
                onPen = onPen,
                onJump = onJump,
                onOptions = onOptions,
                onTimerToggle = onTimerToggle
            )
        }

        if (state.canSaveInk) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 84.dp)
            ) {
                Text(
                    "Pen marks are temporary",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PresentControls(
    state: PresentUiState,
    timer: PresentTimerReading,
    onExit: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBlack: () -> Unit,
    onPen: () -> Unit,
    onJump: () -> Unit,
    onOptions: () -> Unit,
    onTimerToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(UiTags.PRESENT_CONTROLS)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlButton(Icons.Outlined.Close, "Exit presentation", UiTags.PRESENT_EXIT, onExit)
            Spacer(Modifier.width(6.dp))
            TimerPill(timer, state.limitMinutes, onTimerToggle)
            Spacer(Modifier.weight(1f))
            ControlButton(Icons.Outlined.VisibilityOff, "Black screen", UiTags.PRESENT_BLACK, onBlack)
            ControlButton(Icons.Outlined.Tune, "Options", UiTags.PRESENT_OPTIONS, onOptions)
        }
        // The middle stays free: the slide's own gesture layer handles taps,
        // swipes, the laser and the pen underneath the controls. A tap in the
        // centre still toggles the controls there.
        Spacer(Modifier.weight(1f).fillMaxWidth())
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ControlButton(
                Icons.Outlined.ChevronLeft,
                "Previous slide",
                UiTags.PRESENT_PREVIOUS,
                onPrevious,
                enabled = state.hasPrevious
            )
            Text(
                "${state.pageNumber} / ${state.pageCount}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag(UiTags.PRESENT_PAGE_LABEL)
            )
            ControlButton(
                Icons.Outlined.ChevronRight,
                "Next slide",
                UiTags.PRESENT_NEXT,
                onNext,
                enabled = state.hasNext
            )
            ControlButton(Icons.Outlined.GridView, "Jump to slide", UiTags.PRESENT_JUMP, onJump)
            ControlButton(
                Icons.Outlined.Edit,
                if (state.penMode) "Pen on" else "Pen",
                UiTags.PRESENT_PEN,
                onPen,
                tint = if (state.penMode) InkRed else Color.White
            )
        }
    }
}

@Composable
private fun PresenterConsole(
    frame: PresentFrame?,
    nextFrame: PresentFrame?,
    displayName: String?,
    state: PresentUiState,
    timer: PresentTimerReading,
    loading: Boolean,
    error: String?,
    onExit: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBlack: () -> Unit,
    onPen: () -> Unit,
    onOptions: () -> Unit,
    onTimerToggle: () -> Unit,
    onRestartTimer: () -> Unit,
    onGoTo: (Int) -> Unit,
    onPenStart: (Float, Float) -> Unit,
    onPenPoint: (Float, Float) -> Unit,
    onPenEnd: () -> Unit,
    onLaser: (Float?, Float?) -> Unit,
    onRetry: () -> Unit
) {
    val strokes = remember(state.strokes, state.pageIndex) {
        state.strokes.filter { it.pageIndex == state.pageIndex }
    }
    val aspect = frame?.aspect ?: 0.75f
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ConsoleBackground)
            .padding(10.dp)
            .testTag(UiTags.PRESENT_CONSOLE)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ControlButton(Icons.Outlined.Close, "Exit presentation", UiTags.PRESENT_EXIT, onExit)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    displayName ?: "Presenter view",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                Text(
                    if (state.penMode) "Presenter view · Pen on" else "Presenter view · External display",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            ControlButton(Icons.Outlined.VisibilityOff, "Black screen", UiTags.PRESENT_BLACK, onBlack)
            ControlButton(Icons.Outlined.Tune, "Options", UiTags.PRESENT_OPTIONS, onOptions)
        }

        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.weight(1.15f).fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                Text("Current", color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.labelSmall)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SlideBlack)
                        .presentGestures(
                            penMode = state.penMode,
                            aspect = aspect,
                            onTapZone = { fraction ->
                                when {
                                    fraction < 0.4f -> onPrevious()
                                    fraction > 0.6f -> onNext()
                                }
                            },
                            onSwipe = { forward -> if (forward) onNext() else onPrevious() },
                            onStrokeStart = onPenStart,
                            onStrokePoint = onPenPoint,
                            onStrokeEnd = onPenEnd,
                            onLaser = onLaser
                        )
                        .testTag(UiTags.PRESENT_CURRENT_PREVIEW)
                ) {
                    SlideCanvas(
                        frame = frame,
                        strokes = strokes,
                        draft = state.draft,
                        laser = state.laser,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (frame == null && loading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    if (frame == null && error != null) {
                        Column(
                            modifier = Modifier.align(Alignment.Center).padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                error,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center
                            )
                            TextButton(onClick = onRetry) { Text("Try again") }
                        }
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            Column(modifier = Modifier.width(118.dp).fillMaxSize()) {
                Text("Next", color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.labelSmall)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SlideBlack)
                        .testTag(UiTags.PRESENT_NEXT_PREVIEW)
                ) {
                    SlideCanvas(
                        frame = nextFrame,
                        strokes = emptyList(),
                        draft = null,
                        laser = null,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (nextFrame == null) {
                        Text(
                            if (state.hasNext) "—" else "End",
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                TimerCard(
                    timer = timer,
                    limitMinutes = state.limitMinutes,
                    onToggle = onTimerToggle,
                    onRestart = onRestartTimer
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${state.pageNumber} / ${state.pageCount}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag(UiTags.PRESENT_PAGE_LABEL)
            )
            Spacer(Modifier.width(10.dp))
            ControlButton(
                Icons.Outlined.ChevronLeft,
                "Previous slide",
                UiTags.PRESENT_PREVIOUS,
                onPrevious,
                enabled = state.hasPrevious
            )
            ControlButton(
                Icons.Outlined.ChevronRight,
                "Next slide",
                UiTags.PRESENT_NEXT,
                onNext,
                enabled = state.hasNext
            )
            ControlButton(
                Icons.Outlined.Edit,
                if (state.penMode) "Pen on" else "Pen",
                UiTags.PRESENT_PEN,
                onPen,
                tint = if (state.penMode) InkRed else Color.White
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Jump to slide",
                color = Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelMedium
            )
        }

        Spacer(Modifier.height(4.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.weight(1f).fillMaxWidth().testTag(UiTags.PRESENT_JUMP_GRID),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(state.pageCount) { index ->
                PageCell(
                    index = index,
                    selected = index == state.pageIndex,
                    onClick = { onGoTo(index) }
                )
            }
        }
    }
}

@Composable
private fun PageCell(index: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else ConsoleCard)
            .clickable(onClick = onClick)
            .testTag(UiTags.presentPageCell(index)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "${index + 1}",
            color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun BlackScreen(timer: PresentTimerReading, onRestore: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { onRestore() } }
            .testTag(UiTags.PRESENT_BLACK_SCREEN),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                PresenterTimer.format(timer.elapsedMillis),
                color = Color.White.copy(alpha = 0.22f),
                fontFamily = FontFamily.Monospace,
                fontSize = 44.sp
            )
            Text(
                "Tap here or press B to return",
                color = Color.White.copy(alpha = 0.35f),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun TimerPill(
    timer: PresentTimerReading,
    limitMinutes: Int,
    onToggle: () -> Unit
) {
    val limitMillis = PresentViewModel.minutesToMillis(limitMinutes)
    val over = limitMillis > 0L && (timer.remainingMillis ?: 0L) <= 0L
    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(50)
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Timer,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(5.dp))
            Column {
                Text(
                    PresenterTimer.format(timer.elapsedMillis),
                    color = if (over) LaserRed else Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag(UiTags.PRESENT_TIMER)
                )
                if (limitMillis > 0L) {
                    Text(
                        "${PresenterTimer.format(timer.remainingMillis ?: 0L)} left",
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(36.dp).testTag(UiTags.PRESENT_TIMER_TOGGLE)
            ) {
                Icon(
                    if (timer.running) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (timer.running) "Pause timer" else "Resume timer",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun TimerCard(
    timer: PresentTimerReading,
    limitMinutes: Int,
    onToggle: () -> Unit,
    onRestart: () -> Unit
) {
    val limitMillis = PresentViewModel.minutesToMillis(limitMinutes)
    val over = limitMillis > 0L && (timer.remainingMillis ?: 0L) <= 0L
    Surface(color = ConsoleCard, shape = RoundedCornerShape(10.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                PresenterTimer.format(timer.elapsedMillis),
                color = if (over) LaserRed else Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                modifier = Modifier.testTag(UiTags.PRESENT_TIMER)
            )
            Text(
                if (limitMillis > 0L) {
                    "${PresenterTimer.format(timer.remainingMillis ?: 0L)} left"
                } else {
                    "elapsed"
                },
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall
            )
            Row {
                IconButton(
                    onClick = onToggle,
                    modifier = Modifier.size(32.dp).testTag(UiTags.PRESENT_TIMER_TOGGLE)
                ) {
                    Icon(
                        if (timer.running) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (timer.running) "Pause timer" else "Resume timer",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onRestart, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.RestartAlt,
                        contentDescription = "Restart timer",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun JumpGridDialog(
    state: PresentUiState,
    onGoTo: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jump to slide") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .fillMaxWidth()
                    .testTag(UiTags.PRESENT_JUMP_GRID),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.pageCount) { index ->
                    PageCell(
                        index = index,
                        selected = index == state.pageIndex,
                        onClick = {
                            onGoTo(index)
                            onDismiss()
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun PresentOptionsDialog(
    state: PresentUiState,
    onTransition: (PresentTransition) -> Unit,
    onTimerMinutes: (Int) -> Unit,
    onRestartTimer: () -> Unit,
    onUndoInk: () -> Unit,
    onClearInk: () -> Unit,
    onSaveInk: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(UiTags.PRESENT_OPTIONS_DIALOG),
        title = { Text("Presentation options") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Page transition", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PresentTransition.entries.forEach { transition ->
                        FilterChip(
                            selected = state.transition == transition,
                            onClick = { onTransition(transition) },
                            label = { Text(transition.label) },
                            modifier = Modifier.testTag(UiTags.presentTransition(transition.name))
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("Timer", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0, 5, 15, 30, 60).forEach { minutes ->
                        FilterChip(
                            selected = state.limitMinutes == minutes,
                            onClick = { onTimerMinutes(minutes) },
                            label = { Text(if (minutes == 0) "No limit" else "$minutes min") },
                            modifier = Modifier.testTag(UiTags.presentTimerLimit(minutes))
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onRestartTimer) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Restart")
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("Temporary pen", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Marks stay on the presentation screen only. Saving adds them to a new PDF copy as ink annotations.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onUndoInk,
                        enabled = state.strokes.isNotEmpty(),
                        modifier = Modifier.testTag(UiTags.PRESENT_UNDO_INK)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Undo,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Undo")
                    }
                    TextButton(
                        onClick = onClearInk,
                        enabled = state.strokes.isNotEmpty(),
                        modifier = Modifier.testTag(UiTags.PRESENT_CLEAR_INK)
                    ) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear")
                    }
                }
                Button(
                    onClick = onSaveInk,
                    enabled = state.canSaveInk,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.PRESENT_SAVE_INK)
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save pen as annotations")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Color.White
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(42.dp)
            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            .testTag(tag)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun SlideTransition(
    frame: PresentFrame?,
    strokes: List<PenStroke>,
    draft: PenStroke?,
    laser: Pair<Float, Float>?,
    transition: PresentTransition,
    direction: Int,
    modifier: Modifier
) {
    var current by remember { mutableStateOf<PresentFrame?>(frame) }
    var outgoing by remember { mutableStateOf<PresentFrame?>(null) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(frame, transition) {
        val next = frame ?: return@LaunchedEffect
        val previous = current
        if (
            previous == null ||
            previous.pageIndex == next.pageIndex ||
            transition == PresentTransition.NONE
        ) {
            current = next
            outgoing = null
            progress.snapTo(1f)
        } else {
            outgoing = previous
            current = next
            progress.snapTo(0f)
            progress.animateTo(1f, animationSpec = tween(220))
            outgoing = null
        }
    }

    Box(modifier = modifier) {
        val p = progress.value
        val old = outgoing
        if (old != null) {
            SlideCanvas(
                frame = old,
                strokes = emptyList(),
                draft = null,
                laser = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        when (transition) {
                            PresentTransition.FADE -> alpha = 1f - p
                            PresentTransition.SLIDE -> translationX = -direction * p * size.width
                            PresentTransition.NONE -> Unit
                        }
                    }
            )
        }
        val shown = current
        if (shown != null) {
            SlideCanvas(
                frame = shown,
                strokes = strokes,
                draft = draft,
                laser = laser,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        when (transition) {
                            PresentTransition.FADE -> alpha = p
                            PresentTransition.SLIDE -> translationX = direction * (1f - p) * size.width
                            PresentTransition.NONE -> Unit
                        }
                    }
            )
        }
    }
}

@Composable
private fun SlideCanvas(
    frame: PresentFrame?,
    strokes: List<PenStroke>,
    draft: PenStroke?,
    laser: Pair<Float, Float>?,
    modifier: Modifier
) {
    val image: ImageBitmap? = remember(frame?.bitmap) {
        frame?.bitmap?.asImageBitmap()
    }
    Canvas(modifier = modifier) {
        drawRect(SlideBlack)
        if (image == null || image.width <= 0 || image.height <= 0) return@Canvas
        val fit = PresentGeometry.fit(
            size.width,
            size.height,
            image.width.toFloat() / image.height.toFloat()
        )
        if (fit.width <= 0f || fit.height <= 0f) return@Canvas
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
            dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
            filterQuality = FilterQuality.Medium
        )
        val width = (minOf(fit.width, fit.height) * 0.006f).coerceIn(3f, 16f)
        strokes.forEach { drawInkStroke(it, fit, width) }
        draft?.let { drawInkStroke(it, fit, width) }
        laser?.let { (fx, fy) ->
            val center = Offset(
                fit.left + fx.coerceIn(0f, 1f) * fit.width,
                fit.top + fy.coerceIn(0f, 1f) * fit.height
            )
            val radius = (minOf(fit.width, fit.height) * 0.010f).coerceIn(7f, 26f)
            drawCircle(LaserGlow, radius * 2.6f, center)
            drawCircle(LaserRed, radius, center)
        }
    }
}

private fun DrawScope.drawInkStroke(
    stroke: PenStroke,
    fit: PresentGeometry.Fit,
    width: Float
) {
    if (stroke.points.size < 4) return
    var i = 0
    var last = Offset.Zero
    while (i + 1 < stroke.points.size) {
        val point = Offset(
            fit.left + stroke.points[i].coerceIn(0f, 1f) * fit.width,
            fit.top + stroke.points[i + 1].coerceIn(0f, 1f) * fit.height
        )
        if (i > 0) {
            drawLine(
                color = InkRed,
                start = last,
                end = point,
                strokeWidth = width,
                cap = StrokeCap.Round
            )
        }
        last = point
        i += 2
    }
    if (stroke.points.size == 2) {
        drawCircle(InkRed, width / 2f, last)
    }
}

/**
 * Gestures of the slide surface (T53): tap zones, horizontal swipe, long-press
 * laser and (in pen mode) a freehand stroke. Pen mode starts a stroke on the
 * first touch and ignores the tap/laser detector so drawing never navigates.
 * A tap that never moves is not a stroke (a single dot is discarded anyway), so
 * it reveals the controls instead — otherwise the pen would hide them with no
 * way back.
 */
private fun Modifier.presentGestures(
    penMode: Boolean,
    aspect: Float,
    onTapZone: (Float) -> Unit,
    onSwipe: (Boolean) -> Unit,
    onStrokeStart: (Float, Float) -> Unit,
    onStrokePoint: (Float, Float) -> Unit,
    onStrokeEnd: () -> Unit,
    onLaser: (Float?, Float?) -> Unit,
    onPenTap: () -> Unit = {}
): Modifier {
    fun fraction(position: Offset, width: Float, height: Float): Pair<Float, Float>? =
        PresentGeometry.fraction(position.x, position.y, width, height, aspect)

    return this
        .pointerInput(penMode, aspect) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var drawing = false
                var moved = false
                var dx = 0f
                if (penMode) {
                    fraction(down.position, size.width.toFloat(), size.height.toFloat())?.let {
                        onStrokeStart(it.first, it.second)
                        drawing = true
                    }
                }
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        // A swipe is not a tap: consume the release so the tap
                        // detector below does not navigate a second time.
                        if (moved && abs(dx) > size.width * 0.12f) change.consume()
                        break
                    }
                    if (drawing) {
                        fraction(change.position, size.width.toFloat(), size.height.toFloat())?.let {
                            onStrokePoint(it.first, it.second)
                        }
                        change.consume()
                    }
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        moved = true
                        dx = change.position.x - down.position.x
                        // Once the drag is clearly a swipe, consume the moves so
                        // the tap detector cancels instead of navigating again.
                        if (!penMode && abs(dx) > size.width * 0.12f) change.consume()
                    }
                }
                if (penMode) {
                    if (drawing) onStrokeEnd()
                    if (!moved) onPenTap()
                } else if (moved && abs(dx) > size.width * 0.12f) {
                    onSwipe(dx < 0f)
                }
            }
        }
        .then(
            if (penMode) {
                Modifier
            } else {
                Modifier.pointerInput(aspect) {
                    detectTapGestures(
                        onPress = {
                            tryAwaitRelease()
                            onLaser(null, null)
                        },
                        onLongPress = { position ->
                            fraction(position, size.width.toFloat(), size.height.toFloat())?.let {
                                onLaser(it.first, it.second)
                            }
                        },
                        onTap = { position ->
                            onTapZone(position.x / size.width.toFloat().coerceAtLeast(1f))
                        }
                    )
                }
            }
        )
}

/**
 * Keeps the screen on and hides the system bars while presenting (T53). The
 * previous window state is restored on exit; the presentation never changes
 * orientation or locks the device.
 */
@Composable
private fun PresentWindowEffect() {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        val activity = context.findActivity()
        val window = activity?.window
        if (window == null) return@DisposableEffect onDispose { }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            runCatching {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.setDecorFitsSystemWindows(window, true)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

private const val CONTROL_HIDE_MILLIS: Long = 4_000L
private const val EXTERNAL_RENDER_WIDTH: Int = 1_600
private const val MIN_RENDER_WIDTH: Int = 640
