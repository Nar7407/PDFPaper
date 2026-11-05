package com.pdfpaper.app.ui.viewer.flow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.engine.flow.FlowPage
import com.pdfpaper.engine.flow.FlowSegment
import com.pdfpaper.engine.flow.FlowSegmentKind
import kotlin.math.roundToInt

/**
 * T59: the column auto-flow reading view. It is a full-screen overlay inside the
 * viewer, like reflow (T33) and the snip tool (T60), so it reuses the open
 * document, its password state, its page theme and its look.
 *
 * It does **not** reflow text and it does not crop the file: it shows one
 * rectangle of the page at a time — a column, a figure, a table — drawn by the
 * normal page renderer at the screen's full width. The page keeps its own look;
 * the reader simply never has to pinch.
 *
 * One thumb is enough: the left and right edges step back and forward, a swipe
 * up at the bottom of a tall segment continues to the next one, and the volume
 * keys do the same when the reader has claimed them (T58).
 */
@Composable
fun ColumnFlowOverlay(
    state: ColumnFlowUiState,
    pageCount: Int,
    displayName: String?,
    bitmaps: Map<String, FlowSegmentBitmap>,
    background: Color,
    onClose: () -> Unit,
    onRequestPage: (Int) -> Unit,
    onRequestSegment: (page: Int, order: Int, pixelWidth: Int, fullPage: Boolean) -> Unit,
    onStep: (Int) -> Unit,
    onSettings: (ColumnFlowSettings) -> Unit,
    onToggleFullPage: () -> Unit,
    onMergeNext: () -> Unit,
    onSplitHere: () -> Unit,
    onResetPage: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.active) return
    var showSettings by remember { mutableStateOf(false) }
    val page = state.page

    Surface(
        color = background,
        modifier = modifier.fillMaxSize().testTag(UiTags.FLOW_OVERLAY)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FlowTopBar(
                displayName = displayName,
                state = state,
                pageCount = pageCount,
                onBack = onClose,
                onFullPage = onToggleFullPage,
                onSettings = { showSettings = true }
            )
            state.message?.let { message ->
                FlowMessageBar(message = message, onDismiss = onDismissMessage)
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    page == null -> FlowPlaceholder(
                        index = state.position.page,
                        loading = state.position.page in state.loading,
                        onRequestPage = onRequestPage
                    )
                    page.flowSegments.isEmpty() -> Text(
                        text = "This page has nothing to read.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    )
                    state.fullPage -> FullPageView(
                        page = page,
                        bitmaps = bitmaps,
                        onRequestSegment = onRequestSegment
                    )
                    else -> SegmentPager(
                        page = page,
                        position = state.position,
                        settings = state.settings,
                        bitmaps = bitmaps,
                        onRequestSegment = onRequestSegment,
                        onStep = onStep
                    )
                }
                if (state.settings.showMiniMap && page != null) {
                    FlowMiniMap(
                        page = page,
                        order = state.position.order,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .testTag(UiTags.FLOW_MINIMAP)
                    )
                }
            }
        }
    }

    if (showSettings) {
        FlowSettingsSheet(
            state = state,
            onSettings = onSettings,
            onMergeNext = onMergeNext,
            onSplitHere = onSplitHere,
            onResetPage = onResetPage,
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun FlowTopBar(
    displayName: String?,
    state: ColumnFlowUiState,
    pageCount: Int,
    onBack: () -> Unit,
    onFullPage: () -> Unit,
    onSettings: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag(UiTags.FLOW_BACK)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to page view"
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName?.takeIf { it.isNotBlank() } ?: "Column flow",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                val segment = state.segment
                Text(
                    text = if (segment != null) {
                        describeFlow(
                            pageIndex = state.position.page,
                            pageCount = pageCount,
                            order = state.position.order,
                            segmentCount = state.segmentCount,
                            kind = segment.kind
                        )
                    } else {
                        "Reading page ${state.position.page + 1}…"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onFullPage,
                modifier = Modifier.testTag(UiTags.FLOW_FULL_PAGE)
            ) {
                Icon(
                    imageVector = Icons.Filled.CropFree,
                    contentDescription = if (state.fullPage) {
                        "Back to the reading segment"
                    } else {
                        "Show the whole page"
                    }
                )
            }
            IconButton(
                onClick = onSettings,
                modifier = Modifier.testTag(UiTags.FLOW_SETTINGS)
            ) {
                Icon(imageVector = Icons.Filled.Settings, contentDescription = "Column flow settings")
            }
        }
    }
}

@Composable
private fun FlowMessageBar(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
    }
}

@Composable
private fun FlowPlaceholder(index: Int, loading: Boolean, onRequestPage: (Int) -> Unit) {
    LaunchedEffect(index) { onRequestPage(index) }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = if (loading) "Finding the columns on page ${index + 1}…" else "Page ${index + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One segment at a time, fitted to the width. A tall segment scrolls; when the
 * reader swipes past its bottom the next segment follows, so a column is read by
 * one continuous thumb movement. Two segments sit side by side when the screen
 * is wide enough (a tablet, or a phone in landscape).
 */
@Composable
private fun SegmentPager(
    page: FlowPage,
    position: FlowPosition,
    settings: ColumnFlowSettings,
    bitmaps: Map<String, FlowSegmentBitmap>,
    onRequestSegment: (page: Int, order: Int, pixelWidth: Int, fullPage: Boolean) -> Unit,
    onStep: (Int) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthDp = maxWidth.value
        val columns = flowColumns(widthDp, settings.sideBySide)
        val window = flowWindow(page, position.order, columns)
        if (window.isEmpty()) return@BoxWithConstraints
        val scroll = rememberScrollState()
        val stepNow by rememberUpdatedState(onStep)

        // Every new segment starts at its top, so a tall column is read from the
        // beginning rather than inheriting the previous scroll offset.
        LaunchedEffect(position.page, position.order, window.size) {
            scroll.scrollTo(0)
        }

        // A swipe up at the bottom continues to the next segment. The connection
        // only sees what the scroll container did not consume, so this fires on
        // a deliberate extra drag, not on every frame of a normal scroll. The
        // short window stops one long swipe from skipping several segments.
        val connection = remember(position.page, position.order) {
            object : NestedScrollConnection {
                private var last = 0L

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource
                ): Offset {
                    val atEnd = scroll.value >= scroll.maxValue - 1
                    if (source == NestedScrollSource.Drag && atEnd && available.y < -4f) {
                        val now = System.currentTimeMillis()
                        if (now - last > 350L) {
                            last = now
                            stepNow(1)
                        }
                    }
                    return Offset.Zero
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(connection)
                .pointerInput(position.page, position.order, window.size) {
                    detectTapGestures { offset ->
                        when {
                            offset.x < size.width * 0.28f -> stepNow(-1)
                            offset.x > size.width * 0.72f -> stepNow(1)
                        }
                    }
                }
                .verticalScroll(scroll)
                .padding(vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                window.forEachIndexed { index, segment ->
                    SegmentView(
                        pageIndex = page.pageIndex,
                        segment = segment,
                        bitmaps = bitmaps,
                        onRequestSegment = onRequestSegment,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(UiTags.flowSegment(page.pageIndex, segment.order)),
                        label = if (window.size > 1) "${index + 1}" else null
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Tap the left or right edge, or swipe up, for the next part",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
        }
    }
}

@Composable
private fun SegmentView(
    pageIndex: Int,
    segment: FlowSegment,
    bitmaps: Map<String, FlowSegmentBitmap>,
    onRequestSegment: (page: Int, order: Int, pixelWidth: Int, fullPage: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        // Read inside the BoxWithConstraints scope: the Column below is a
        // different receiver, so `maxWidth` cannot be reached from there.
        val boxWidth = maxWidth
        val widthPx = with(density) { boxWidth.roundToPx() }.coerceAtLeast(1)
        LaunchedEffect(pageIndex, segment.order, widthPx) {
            onRequestSegment(pageIndex, segment.order, widthPx, false)
        }
        val rendered = bitmaps[flowSegmentKey(pageIndex, segment.order, widthPx)]
        Column {
            if (label != null) {
                Text(
                    text = "Column $label",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            if (rendered == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                return@Column
            }
            val bitmap = rendered.bitmap
            val aspect = if (bitmap.width > 0) {
                bitmap.height.toFloat() / bitmap.width
            } else {
                1f
            }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "${flowKindLabel(segment.kind)} on page ${pageIndex + 1}",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(boxWidth * aspect)
            )
        }
    }
}

/** The whole page, fitted to the width: the "show me where I am" view. */
@Composable
private fun FullPageView(
    page: FlowPage,
    bitmaps: Map<String, FlowSegmentBitmap>,
    onRequestSegment: (page: Int, order: Int, pixelWidth: Int, fullPage: Boolean) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // Captured here because the Box below has its own receiver.
        val boxWidth = maxWidth
        val widthPx = with(density) { boxWidth.roundToPx() }.coerceAtLeast(1)
        val order = page.flowSegments.firstOrNull()?.order ?: 0
        LaunchedEffect(page.pageIndex, widthPx) {
            onRequestSegment(page.pageIndex, order, widthPx, true)
        }
        val rendered = bitmaps[flowSegmentKey(page.pageIndex, order, widthPx, fullPage = true)]
        Box(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter
        ) {
            if (rendered == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(22.dp), strokeWidth = 2.dp)
            } else {
                val bitmap = rendered.bitmap
                val aspect = if (bitmap.width > 0) bitmap.height.toFloat() / bitmap.width else 1f
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Page ${page.pageIndex + 1}",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().height(boxWidth * aspect)
                )
            }
        }
    }
}

/**
 * The page in miniature with every segment outlined and the current one filled,
 * so the reader can see how much of the page is left. It is drawn from the
 * detected geometry, not from a rendered bitmap, so it costs nothing.
 */
@Composable
private fun FlowMiniMap(page: FlowPage, order: Int, modifier: Modifier = Modifier) {
    val aspect = if (page.page.height > 0.0) {
        (page.page.width / page.page.height).toFloat()
    } else {
        0.77f
    }
    val outline = MaterialTheme.colorScheme.outline
    val paper = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier
            .width(54.dp)
            .aspectRatio(aspect.coerceIn(0.4f, 1.6f))
            .background(paper.copy(alpha = 0.72f), RoundedCornerShape(3.dp))
    ) {
        drawRect(color = outline.copy(alpha = 0.55f), style = Stroke(width = 1f))
        for (segment in page.flowSegments) {
            val rect = minimapRect(segment.bounds, page.page, page.rotate)
            val topLeft = Offset(rect.left * size.width, rect.top * size.height)
            val boxSize = Size(
                (rect.width * size.width).coerceAtLeast(1f),
                (rect.height * size.height).coerceAtLeast(1f)
            )
            if (segment.order == order) {
                drawRect(color = ink.copy(alpha = 0.55f), topLeft = topLeft, size = boxSize)
                drawRect(
                    color = ink,
                    topLeft = topLeft,
                    size = boxSize,
                    style = Stroke(width = 2f)
                )
            } else {
                drawRect(
                    color = outline.copy(alpha = 0.6f),
                    topLeft = topLeft,
                    size = boxSize,
                    style = Stroke(width = 1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlowSettingsSheet(
    state: ColumnFlowUiState,
    onSettings: (ColumnFlowSettings) -> Unit,
    onMergeNext: () -> Unit,
    onSplitHere: () -> Unit,
    onResetPage: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(UiTags.FLOW_SETTINGS_SHEET)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("Column flow", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "The page keeps its own look. These choices only change what is on screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            FlowSwitch(
                title = "Two columns together",
                subtitle = "On a wide screen, pair the next column of the same band.",
                checked = state.settings.sideBySide,
                onChecked = { onSettings(state.settings.copy(sideBySide = it)) },
                tag = UiTags.FLOW_SIDE_BY_SIDE
            )
            FlowSwitch(
                title = "Animate the step",
                subtitle = "A short transition between segments.",
                checked = state.settings.animate,
                onChecked = { onSettings(state.settings.copy(animate = it)) },
                tag = UiTags.FLOW_ANIMATE
            )
            FlowSwitch(
                title = "Show the mini-map",
                subtitle = "A small page with the current segment outlined.",
                checked = state.settings.showMiniMap,
                onChecked = { onSettings(state.settings.copy(showMiniMap = it)) },
                tag = UiTags.FLOW_MINIMAP_TOGGLE
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text(
                text = "Reading order",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "If the detector read this page wrong, correct it here. " +
                    "The correction is remembered for this document and never written into the file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            val canMerge = state.page?.let { page ->
                flowStep(page, state.position.order, 1) != null
            } == true
            val canSplit = state.segment != null
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onMergeNext,
                    enabled = canMerge,
                    modifier = Modifier.testTag(UiTags.FLOW_MERGE)
                ) {
                    Text("Merge with next")
                }
                TextButton(
                    onClick = onSplitHere,
                    enabled = canSplit,
                    modifier = Modifier.testTag(UiTags.FLOW_SPLIT)
                ) {
                    Text("Split in half")
                }
            }
            TextButton(
                onClick = onResetPage,
                modifier = Modifier.testTag(UiTags.FLOW_RESET)
            ) {
                Text("Use the detected order for this page")
            }
            if (state.fallback) {
                Text(
                    text = "This page was left as one block because its columns were not clear.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun FlowSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            modifier = Modifier.testTag(tag)
        )
    }
}
