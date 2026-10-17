package com.pdfpaper.app.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageRenderPlan
import com.pdfpaper.engine.text.TextRect
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 6f

/**
 * Continuous page viewer (T9) with text search and selection (T11): a
 * LazyColumn of page bitmaps rendered in the background by [ViewerViewModel],
 * with pinch-to-zoom and explicit zoom buttons. Renders are requested at the
 * width the page is actually drawn at, so a zoom change re-renders only the
 * visible pages. Search hits and the current selection are drawn over the
 * bitmap in page space, and a tap (or long-press then drag) selects the word
 * under the finger.
 */
@Composable
fun ViewerScreen(
    path: String,
    onOrganize: (String) -> Unit = {},
    viewModel: ViewerViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val boxes by viewModel.pageBoxes.collectAsState()
    val bitmaps by viewModel.bitmaps.collectAsState()
    val plans by viewModel.plans.collectAsState()
    val search by viewModel.search.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val message by viewModel.message.collectAsState()

    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenWidthPx = with(density) { screenWidthDp.dp.toPx() }
    // Render resolution follows a coarse zoom step so a pinch does not queue a
    // render per frame; the on-screen width follows the smooth value.
    val renderZoom = (zoom * 10f).roundToInt() / 10f
    val contentWidth = with(density) { (screenWidthDp.dp * zoom).coerceAtLeast(screenWidthDp.dp) }

    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(path) { viewModel.load(path) }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.dismissMessage()
    }
    // Debounced search: run once the user pauses typing. Closing Find clears
    // the highlights again.
    LaunchedEffect(showSearch, query) {
        if (!showSearch) {
            viewModel.setSearchQuery("")
            return@LaunchedEffect
        }
        delay(250)
        viewModel.setSearchQuery(query)
    }
    // Jump to whichever match is active.
    val activeMatch = search.activeMatch
    LaunchedEffect(activeMatch) {
        if (activeMatch != null) listState.animateScrollToItem(activeMatch.pageIndex)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ViewerToolbar(
                title = state.displayName ?: "PDF",
                pageLabel = if (state.pageCount > 0) "${state.pageCount} pages" else null,
                zoom = zoom,
                findActive = showSearch,
                onZoomIn = { zoom = (zoom * 1.25f).coerceAtMost(MAX_ZOOM) },
                onZoomOut = { zoom = (zoom / 1.25f).coerceAtLeast(MIN_ZOOM) },
                onFit = { zoom = 1f },
                onFind = { showSearch = !showSearch },
                onOrganize = { onOrganize(path) }
            )

            if (showSearch) {
                SearchRow(
                    query = query,
                    matchCount = search.matches.size,
                    activeIndex = search.activeIndex,
                    running = search.running,
                    onQueryChange = { query = it },
                    onPrevious = viewModel::previousMatch,
                    onNext = viewModel::nextMatch
                )
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.error != null -> Text(
                        text = state.error ?: "Unknown error",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    )
                    else -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(hScroll)
                            .pinchToZoom { factor -> zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM) }
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .width(contentWidth)
                                .fillMaxHeight()
                        ) {
                            items(state.pageCount) { index ->
                                val searchRects = remember(search.matches, index) {
                                    search.matches.filter { it.pageIndex == index }.flatMap { it.rects }
                                }
                                val selectionRects = selection?.takeIf { it.pageIndex == index }?.rects
                                    ?: emptyList()
                                PageItem(
                                    index = index,
                                    box = boxes.getOrNull(index),
                                    bitmap = bitmaps[index],
                                    plan = plans[index],
                                    searchRects = searchRects,
                                    selectionRects = selectionRects,
                                    targetPixelWidth = (screenWidthPx * renderZoom).roundToInt(),
                                    onVisible = viewModel::onPageVisible,
                                    onHidden = viewModel::onPageHidden,
                                    onSelect = viewModel::selectAt
                                )
                            }
                        }
                    }
                }
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            val current = selection
            if (current != null) {
                SelectionBar(
                    text = current.text,
                    onCopy = { clipboard.setText(AnnotatedString(current.text)) },
                    onSelectAll = { viewModel.selectAll(current.pageIndex) },
                    onClear = viewModel::clearSelection
                )
            }
        }
    }
}

@Composable
private fun ViewerToolbar(
    title: String,
    pageLabel: String?,
    zoom: Float,
    findActive: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onFind: () -> Unit,
    onOrganize: () -> Unit
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                if (pageLabel != null) {
                    Text(pageLabel, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text("${(zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onZoomOut) { Text("−") }
            TextButton(onClick = onZoomIn) { Text("+") }
            TextButton(onClick = onFit) { Text("Fit") }
            TextButton(onClick = onFind) { Text(if (findActive) "Close" else "Find") }
            TextButton(onClick = onOrganize) { Text("Organize") }
        }
    }
}

@Composable
private fun SearchRow(
    query: String,
    matchCount: Int,
    activeIndex: Int,
    running: Boolean,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            label = { Text("Find in document") },
            modifier = Modifier.weight(1f)
        )
        val label = when {
            running -> "…"
            matchCount == 0 -> "0"
            else -> "${activeIndex + 1}/$matchCount"
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = onPrevious, enabled = matchCount > 0) { Text("‹") }
        TextButton(onClick = onNext, enabled = matchCount > 0) { Text("›") }
    }
}

@Composable
private fun SelectionBar(
    text: String,
    onCopy: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = text.replace('\n', ' '),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCopy) { Text("Copy") }
            TextButton(onClick = onSelectAll) { Text("Select all") }
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}

@Composable
private fun PageItem(
    index: Int,
    box: PageBox?,
    bitmap: Bitmap?,
    plan: PageRenderPlan?,
    searchRects: List<TextRect>,
    selectionRects: List<TextRect>,
    targetPixelWidth: Int,
    onVisible: (Int, Int) -> Unit,
    onHidden: (Int) -> Unit,
    onSelect: (Int, Float, Float, Boolean) -> Unit
) {
    val aspect = remember(box) {
        val (w, h) = if (box != null) box.rotatedWidth to box.rotatedHeight else 612.0 to 792.0
        if (h > 0.0) (w / h).toFloat() else 612f / 792f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .aspectRatio(aspect)
            .shadow(2.dp)
            .background(Color.White)
            .wordSelectionGestures { fx, fy, extend -> onSelect(index, fx, fy, extend) },
        contentAlignment = Alignment.Center
    ) {
        LaunchedEffect(index, targetPixelWidth) {
            onVisible(index, targetPixelWidth)
        }
        DisposableEffect(index) {
            onDispose { onHidden(index) }
        }

        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillWidth
            )
            HighlightOverlay(plan, searchRects, selectionRects)
        } else {
            CircularProgressIndicator(Modifier.size(28.dp))
        }
    }
}

/** Search hits (yellow) and the selection (blue) drawn over the page bitmap. */
@Composable
private fun HighlightOverlay(
    plan: PageRenderPlan?,
    searchRects: List<TextRect>,
    selectionRects: List<TextRect>
) {
    if (plan == null || (searchRects.isEmpty() && selectionRects.isEmpty())) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawTextRects(plan, searchRects, Color(0x66FFEB3B))
        drawTextRects(plan, selectionRects, Color(0x6690CAF9))
    }
}

/** Maps page-space rectangles through the render plan onto the drawn bitmap. */
private fun DrawScope.drawTextRects(plan: PageRenderPlan, rects: List<TextRect>, color: Color) {
    if (plan.pixelWidth <= 0 || plan.pixelHeight <= 0) return
    val sx = size.width / plan.pixelWidth
    val sy = size.height / plan.pixelHeight
    for (rect in rects) {
        val a = plan.matrix.apply(rect.x0, rect.y0)
        val b = plan.matrix.apply(rect.x1, rect.y1)
        val left = min(a[0], b[0]) * sx
        val top = min(a[1], b[1]) * sy
        val width = (max(a[0], b[0]) - min(a[0], b[0])) * sx
        val height = (max(a[1], b[1]) - min(a[1], b[1])) * sy
        if (width <= 0.0 || height <= 0.0) continue
        drawRect(
            color = color,
            topLeft = Offset(left.toFloat(), top.toFloat()),
            size = Size(width.toFloat(), height.toFloat())
        )
    }
}

/**
 * Tap to select the word under the finger, or hold and drag to extend the
 * selection by word. Movement before the long-press timeout is left to the
 * parent, so scrolling still works.
 */
private fun Modifier.wordSelectionGestures(
    onSelect: (fx: Float, fy: Float, extend: Boolean) -> Unit
): Modifier = pointerInput(Unit) {
    fun fraction(offset: Offset): Pair<Float, Float> =
        offset.x / size.width.coerceAtLeast(1) to offset.y / size.height.coerceAtLeast(1)

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var dragging = false
        var tap = true
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (tap || dragging) {
                    val (fx, fy) = fraction(change.position)
                    onSelect(fx, fy, false)
                }
                break
            }
            if ((change.position - down.position).getDistance() > slop) {
                if (!dragging && change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) {
                    dragging = true
                    val (fx, fy) = fraction(down.position)
                    onSelect(fx, fy, false)
                }
                if (dragging) {
                    val (fx, fy) = fraction(change.position)
                    onSelect(fx, fy, true)
                    change.consume()
                } else {
                    tap = false
                }
            }
        }
    }
}

/**
 * Pinch-to-zoom that leaves single-pointer gestures alone, so the LazyColumn
 * keeps scrolling (and the horizontal pan keeps working) while two fingers
 * scale the content.
 */
private fun Modifier.pinchToZoom(onZoom: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                val factor = event.calculateZoom()
                if (factor.isFinite() && factor > 0f && factor != 1f) {
                    onZoom(factor)
                    event.changes.forEach { it.consume() }
                }
            }
            if (event.changes.none { it.pressed }) break
        }
    }
}
