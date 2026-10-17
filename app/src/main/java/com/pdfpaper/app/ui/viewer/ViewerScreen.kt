package com.pdfpaper.app.ui.viewer

import android.graphics.Bitmap
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.engine.render.PageBox
import kotlin.math.roundToInt

private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 6f

/**
 * Continuous page viewer (T9): a LazyColumn of page bitmaps rendered in the
 * background by [ViewerViewModel], with pinch-to-zoom and explicit zoom
 * buttons. Renders are requested at the width the page is actually drawn at,
 * so a zoom change re-renders only the visible pages.
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
    val message by viewModel.message.collectAsState()

    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
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

    LaunchedEffect(path) { viewModel.load(path) }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.dismissMessage()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ViewerToolbar(
                title = state.displayName ?: "PDF",
                pageLabel = if (state.pageCount > 0) "${state.pageCount} pages" else null,
                zoom = zoom,
                onZoomIn = { zoom = (zoom * 1.25f).coerceAtMost(MAX_ZOOM) },
                onZoomOut = { zoom = (zoom / 1.25f).coerceAtLeast(MIN_ZOOM) },
                onFit = { zoom = 1f },
                onOrganize = { onOrganize(path) }
            )

            Box(modifier = Modifier.fillMaxSize()) {
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
                                PageItem(
                                    index = index,
                                    box = boxes.getOrNull(index),
                                    bitmap = bitmaps[index],
                                    targetPixelWidth = (screenWidthPx * renderZoom).roundToInt(),
                                    onVisible = viewModel::onPageVisible,
                                    onHidden = viewModel::onPageHidden
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
        }
    }
}

@Composable
private fun ViewerToolbar(
    title: String,
    pageLabel: String?,
    zoom: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
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
            TextButton(onClick = onOrganize) { Text("Organize") }
        }
    }
}

@Composable
private fun PageItem(
    index: Int,
    box: PageBox?,
    bitmap: Bitmap?,
    targetPixelWidth: Int,
    onVisible: (Int, Int) -> Unit,
    onHidden: (Int) -> Unit
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
            .background(Color.White),
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
        } else {
            CircularProgressIndicator(Modifier.size(28.dp))
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
