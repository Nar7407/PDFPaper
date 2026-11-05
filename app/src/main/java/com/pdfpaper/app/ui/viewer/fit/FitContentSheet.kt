package com.pdfpaper.app.ui.viewer.fit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pdfpaper.app.render.RenderedPage
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.crop.CropHandle
import com.pdfpaper.app.ui.crop.CropMargins
import com.pdfpaper.app.ui.crop.cropBoxFromMargins
import com.pdfpaper.app.ui.crop.dragCrop
import com.pdfpaper.app.ui.crop.marginsFor
import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageRenderPlan
import kotlin.math.hypot

/**
 * Task T57: the "Fit content" sheet. It offers the four modes, reports what
 * was detected, lets the reader drag the four corners in manual mode, and
 * links to the T46 crop screen ("Make this permanent") for a file change.
 *
 * The view itself is changed by the viewer, never by this sheet: closing it
 * leaves the file exactly as it was.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FitContentSheet(
    state: FitContentState,
    pages: List<PageBox>,
    currentPage: Int,
    renderPreview: suspend (pageIndex: Int, pixelWidth: Int) -> RenderedPage?,
    onMode: (FitContentMode) -> Unit,
    onMargins: (CropMargins) -> Unit,
    onIgnoreHeaderFooter: (Boolean) -> Unit,
    onMakePermanent: () -> Unit,
    onDismiss: () -> Unit
) {
    // Manual mode opens a full-screen editor: inside the sheet the sheet's own
    // swipe-to-dismiss swallowed the corner drag (the drag starts downward on
    // the top handles) and closed the sheet instead of resizing the box.
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(state.mode) {
        if (state.mode == FitContentMode.MANUAL) editing = true
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .testTag(UiTags.FIT_CONTENT_SHEET)
        ) {
            Text(
                text = "Fit content",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Shows the text edge to edge on a small screen. The file is not changed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            // Two rows: the four labels do not fit on one narrow phone, and a
            // wrapped chip showed "Adjust manually" cut in half.
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (pair in FitContentMode.entries.chunked(2)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (mode in pair) {
                            FilterChip(
                                selected = state.mode == mode,
                                onClick = { onMode(mode) },
                                enabled = state.mode == mode || !state.loading,
                                label = { Text(mode.label, maxLines = 1) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag(UiTags.fitModeButton(mode.name))
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                }
                Text(
                    text = fitDescription(state, pages),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }

            for (note in fitNotes(state)) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (state.active && state.mode != FitContentMode.MANUAL) {
                Text(
                    text = "${state.detectedPages} page(s) measured" +
                        if (state.uniform) ", one shared size" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (state.mode == FitContentMode.MANUAL) {
                OutlinedButton(
                    onClick = { editing = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .testTag(UiTags.FIT_CONTENT_ADJUST)
                ) {
                    Text("Adjust the box")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Leave out headers and footers", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Running heads and page numbers do not widen the box.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.ignoreHeaderFooter,
                    onCheckedChange = onIgnoreHeaderFooter,
                    modifier = Modifier.testTag(UiTags.FIT_CONTENT_HEADERS)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Done")
                }
                Button(
                    onClick = onMakePermanent,
                    enabled = state.active && (state.boxes.isNotEmpty() ||
                        state.mode == FitContentMode.MANUAL),
                    modifier = Modifier.weight(1.6f).testTag(UiTags.FIT_CONTENT_PERMANENT)
                ) {
                    Text("Make this permanent")
                }
            }
            Text(
                text = "That opens Crop pages with these margins; the file changes only when you save a copy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    if (editing && state.mode == FitContentMode.MANUAL && pages.isNotEmpty()) {
        ManualFitDialog(
            page = pages[currentPage.coerceIn(0, pages.lastIndex)],
            pageIndex = currentPage,
            margins = state.margins,
            renderPreview = renderPreview,
            onMargins = onMargins,
            onDone = { editing = false }
        )
    }
}

/** The full-screen manual editor: one page, four corners, no competing drags. */
@Composable
private fun ManualFitDialog(
    page: PageBox,
    pageIndex: Int,
    margins: CropMargins,
    renderPreview: suspend (pageIndex: Int, pixelWidth: Int) -> RenderedPage?,
    onMargins: (CropMargins) -> Unit,
    onDone: () -> Unit
) {
    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Adjust the box",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Drag a corner. Every page uses this box.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { onMargins(CropMargins.ZERO) }) { Text("Reset") }
                    TextButton(
                        onClick = onDone,
                        modifier = Modifier.testTag(UiTags.FIT_CONTENT_ADJUST_DONE)
                    ) {
                        Text("Done")
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ManualFitEditor(
                        page = page,
                        pageIndex = pageIndex,
                        margins = margins,
                        renderPreview = renderPreview,
                        onMargins = onMargins
                    )
                }
            }
        }
    }
}

/**
 * The manual mode: the whole page with the four corners of the fit box on it.
 * Dragging a corner moves that side; the margins are shared by every page, so
 * one gesture frames the document.
 */
@Composable
private fun ManualFitEditor(
    page: PageBox,
    pageIndex: Int,
    margins: CropMargins,
    renderPreview: suspend (pageIndex: Int, pixelWidth: Int) -> RenderedPage?,
    onMargins: (CropMargins) -> Unit
) {
    val pageRect = remember(page) { PdfRect(page.left, page.bottom, page.right, page.top) }
    var preview by remember(pageIndex) { mutableStateOf<RenderedPage?>(null) }
    LaunchedEffect(pageIndex) { preview = renderPreview(pageIndex, 900) }

    val latestMargins by rememberUpdatedState(margins)
    val latestMarginsChange by rememberUpdatedState(onMargins)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val plan = preview?.plan
    val aspect = remember(page) {
        if (page.rotatedHeight > 0.0) {
            (page.rotatedWidth / page.rotatedHeight).toFloat()
        } else {
            612f / 792f
        }
    }
    val accent = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Margins: ${margins.describe()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(aspect)
                    .background(Color.White)
                    .onSizeChanged { canvasSize = it }
                    .testTag(UiTags.FIT_CONTENT_PREVIEW)
                    .pointerInput(page, plan) {
                        // The four corners claim the gesture at the first down, so a
                        // drag that starts on a handle never scrolls the surface around
                        // the page.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val activePlan = plan
                            if (activePlan == null || canvasSize.width <= 0 || canvasSize.height <= 0) {
                                return@awaitEachGesture
                            }
                            val base = cropBoxFromMargins(pageRect, latestMargins) ?: pageRect
                            val handle = nearestHandle(down.position, canvasSize, activePlan, base)
                                ?: return@awaitEachGesture
                            down.consume()
                            val inverse = activePlan.matrix.inverse() ?: return@awaitEachGesture
                            val sx = canvasSize.width.toFloat() / activePlan.pixelWidth
                            val sy = canvasSize.height.toFloat() / activePlan.pixelHeight
                            var box = base
                            while (true) {
                                val change = awaitPointerEvent().changes
                                    .firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (change.changedToUpIgnoreConsumed()) break
                                val point = inverse.apply(
                                    (change.position.x / sx).toDouble(),
                                    (change.position.y / sy).toDouble()
                                )
                                box = dragCrop(
                                    box = box,
                                    handle = handle,
                                    x = point[0],
                                    y = point[1],
                                    bounds = pageRect,
                                    minSize = 40.0
                                )
                                marginsFor(pageRect, box)?.let(latestMarginsChange)
                            }
                        }
                    }
            ) {
                preview?.let { rendered ->
                    if (!rendered.bitmap.isRecycled) {
                        Image(
                            bitmap = rendered.bitmap.asImageBitmap(),
                            contentDescription = "Page ${pageIndex + 1}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds
                        )
                    }
                }
                if (plan == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp))
                }
                Canvas(Modifier.fillMaxSize()) {
                    val activePlan = plan ?: return@Canvas
                    if (size.width <= 0f || size.height <= 0f) return@Canvas
                    val sx = size.width / activePlan.pixelWidth
                    val sy = size.height / activePlan.pixelHeight
                    val box = cropBoxFromMargins(pageRect, margins) ?: pageRect
                    val corners = cornerPoints(box, activePlan.matrix, sx, sy)
                    val left = corners.minOf { it.x }
                    val top = corners.minOf { it.y }
                    val right = corners.maxOf { it.x }
                    val bottom = corners.maxOf { it.y }
                    val dim = Color.Black.copy(alpha = 0.45f)
                    drawRect(dim, Offset(0f, 0f), Size(size.width, top.coerceAtLeast(0f)))
                    drawRect(dim, Offset(0f, bottom), Size(size.width, (size.height - bottom).coerceAtLeast(0f)))
                    drawRect(dim, Offset(0f, top), Size(left.coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)))
                    drawRect(
                        dim,
                        Offset(right, top),
                        Size((size.width - right).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f))
                    )
                    drawRect(
                        color = accent,
                        topLeft = Offset(left, top),
                        size = Size((right - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    for (corner in corners) {
                        drawCircle(color = accent, radius = 9.dp.toPx(), center = corner)
                        drawCircle(color = Color.White, radius = 4.dp.toPx(), center = corner)
                    }
                }
            }
        }
    }
}

/**
 * The corner handle nearest to [offset] in canvas pixels, named in page user
 * space so [dragCrop] moves the right side on a rotated page.
 */
private fun nearestHandle(
    offset: Offset,
    canvasSize: IntSize,
    plan: PageRenderPlan,
    box: PdfRect
): CropHandle? {
    if (canvasSize.width <= 0 || canvasSize.height <= 0 || plan.pixelWidth <= 0) return null
    val sx = canvasSize.width.toFloat() / plan.pixelWidth
    val sy = canvasSize.height.toFloat() / plan.pixelHeight
    val candidates = listOf(
        Triple(CropHandle.LEFT_BOTTOM, box.left, box.bottom),
        Triple(CropHandle.RIGHT_BOTTOM, box.right, box.bottom),
        Triple(CropHandle.RIGHT_TOP, box.right, box.top),
        Triple(CropHandle.LEFT_TOP, box.left, box.top)
    )
    var best: CropHandle? = null
    var bestDistance = Float.MAX_VALUE
    for ((handle, x, y) in candidates) {
        val p = plan.matrix.apply(x, y)
        val px = (p[0] * sx).toFloat()
        val py = (p[1] * sy).toFloat()
        val distance = hypot(px - offset.x, py - offset.y)
        if (distance < bestDistance) {
            bestDistance = distance
            best = handle
        }
    }
    // The handle is 9 dp; 48 px is a comfortable touch target around it.
    return if (bestDistance <= 48f) best else null
}

private fun cornerPoints(box: PdfRect, matrix: PdfMatrix, sx: Float, sy: Float): List<Offset> =
    listOf(
        box.left to box.bottom,
        box.right to box.bottom,
        box.right to box.top,
        box.left to box.top
    ).map { (x, y) ->
        val p = matrix.apply(x, y)
        Offset(p[0].toFloat() * sx, p[1].toFloat() * sy)
    }
