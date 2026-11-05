package com.pdfpaper.app.ui.snip

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.render.PageRenderPlan
import com.pdfpaper.engine.table.CsvDelimiter
import com.pdfpaper.engine.table.Table
import kotlin.math.max
import kotlin.math.min

/**
 * T60: the marquee "snip a table" overlay.
 *
 * The page is drawn once and the reader long-presses and drags a box around a
 * table. Detection runs in the view model on the engine; the box, the detected
 * row and column separators and the table preview are painted over the page, so
 * the reader can see what was read before copying. The four corner handles
 * adjust a box that was already drawn, and the next page can be stitched on.
 *
 * Nothing is written into the PDF: the result is text the reader copies, shares
 * or saves.
 */
@Composable
fun SnipOverlay(
    state: SnipUiState,
    viewModel: SnipViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var ocrLanguage by remember { mutableStateOf("eng") }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) viewModel.saveTo(uri)
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxSize().testTag(UiTags.SNIP_OVERLAY)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag(UiTags.SNIP_BACK)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close snip")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Snip table",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        state.fileName.ifBlank { "This PDF" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Page stepping lives in the header, so it never covers the page
                // the reader is about to draw a box on.
                IconButton(
                    onClick = viewModel::previousPage,
                    enabled = state.currentPage > 0 && !state.rendering,
                    modifier = Modifier.testTag(UiTags.SNIP_PAGE_PREVIOUS)
                ) { Text("‹") }
                Text(
                    text = if (state.pageCount > 0) {
                        "${state.currentPage + 1}/${state.pageCount}"
                    } else {
                        "–"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag(UiTags.SNIP_PAGE_LABEL)
                )
                IconButton(
                    onClick = viewModel::nextPage,
                    enabled = state.currentPage + 1 < state.pageCount && !state.rendering,
                    modifier = Modifier.testTag(UiTags.SNIP_PAGE_NEXT)
                ) { Text("›") }
                if (state.busy || state.ocrRunning || state.rendering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            val bitmap = state.bitmap
            val plan = state.plan

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap == null || plan == null) {
                    val error = state.error
                    if (error != null && !state.rendering) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(24.dp)
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                } else {
                    SnipPage(
                        state = state,
                        bitmap = bitmap,
                        plan = plan,
                        onRect = viewModel::selectRect
                    )
                }
            }

            SnipPanel(
                state = state,
                viewModel = viewModel,
                ocrLanguage = ocrLanguage,
                onOcrLanguage = { ocrLanguage = it },
                onCopy = {
                    val text = viewModel.currentOutput()
                    if (text.isNotEmpty()) {
                        clipboard.setText(AnnotatedString(text))
                        viewModel.markCopied()
                    }
                },
                onShare = {
                    val text = viewModel.currentOutput()
                    if (text.isNotEmpty()) {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = state.settings.format.mime
                            putExtra(Intent.EXTRA_TEXT, text)
                            putExtra(Intent.EXTRA_SUBJECT, viewModel.suggestedName())
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(send, "Share table"))
                        }.onFailure {
                            viewModel.showMessage("No app can share this table")
                        }
                    }
                },
                onSave = { saveLauncher.launch(viewModel.suggestedName()) }
            )
        }
    }
}

/** The rendered page with the marquee, the handles and the live detection. */
@Composable
private fun SnipPage(
    state: SnipUiState,
    bitmap: android.graphics.Bitmap,
    plan: PageRenderPlan,
    onRect: (PdfRect?) -> Unit,
    modifier: Modifier = Modifier
) {
    val selection = state.selection
    val detection = state.detection
    val aspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
    // In-flight drag: two screen points, in pixels of the drawn box.
    var dragStart by remember(state.currentPage) { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember(state.currentPage) { mutableStateOf<Offset?>(null) }

    val accent = MaterialTheme.colorScheme.primary
    val boundary = MaterialTheme.colorScheme.tertiary

    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The page must be an exact fit: a stretched picture would make every
        // drag fraction lie about where the reader pointed.
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        val fitted = when {
            availableWidth.value <= 0f || availableHeight.value <= 0f ->
                DpSize(availableWidth, availableHeight)
            availableWidth / availableHeight > aspect ->
                DpSize(availableHeight * aspect, availableHeight)
            else -> DpSize(availableWidth, availableWidth / aspect)
        }
        Box(
            modifier = modifier
                .size(fitted.width, fitted.height)
                .testTag(UiTags.SNIP_SURFACE)
                .pointerInput(state.currentPage, plan, selection) {
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()
                    if (width <= 0f || height <= 0f) return@pointerInput

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        // A corner handle starts immediately: it is a small,
                        // deliberate target, so it must not wait for a long press.
                        val current = selection
                        val frac = current?.let { fractionRectOf(plan, it) }
                        val handleRadius = 26.dp.toPx()
                        if (frac != null) {
                            val corners = listOf(
                                Triple(0, frac.l, frac.t),
                                Triple(1, frac.r, frac.t),
                                Triple(2, frac.l, frac.b),
                                Triple(3, frac.r, frac.b)
                            )
                            val hit = corners.minByOrNull { corner ->
                                val dx = corner.second * width - down.position.x
                                val dy = corner.third * height - down.position.y
                                dx * dx + dy * dy
                            }
                            val near = hit != null &&
                                run {
                                    val dx = hit.second * width - down.position.x
                                    val dy = hit.third * height - down.position.y
                                    dx * dx + dy * dy <= handleRadius * handleRadius
                                }
                            if (near && hit != null) {
                                down.consume()
                                val fixed = when (hit.first) {
                                    0 -> frac.r to frac.b
                                    1 -> frac.l to frac.b
                                    2 -> frac.r to frac.t
                                    else -> frac.l to frac.t
                                }
                                dragStart = Offset(fixed.first * width, fixed.second * height)
                                dragCurrent = down.position
                                var last = down.position
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes
                                        .firstOrNull { it.id == down.id } ?: break
                                    change.consume()
                                    last = change.position
                                    dragCurrent = last
                                    if (change.changedToUpIgnoreConsumed()) break
                                }
                                commitDrag(plan, dragStart, last, width, height, onRect)
                                dragStart = null
                                dragCurrent = null
                                return@awaitEachGesture
                            }
                        }

                        // A new box needs a long press, so an ordinary tap still
                        // does nothing and the page cannot be boxed by accident.
                        val held = awaitLongPressOrCancellation(down.id)
                            ?: return@awaitEachGesture
                        held.consume()
                        dragStart = held.position
                        dragCurrent = held.position
                        var last = held.position
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes
                                .firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            last = change.position
                            dragCurrent = last
                            if (change.changedToUpIgnoreConsumed()) break
                        }
                        commitDrag(plan, held.position, last, width, height, onRect)
                        dragStart = null
                        dragCurrent = null
                    }
                }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page ${state.currentPage + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                if (size.width <= 0f || size.height <= 0f) return@Canvas

                // The box being dragged, or the committed one.
                val pendingStart = dragStart
                val pendingCurrent = dragCurrent
                val box: FracRect? = if (pendingStart != null && pendingCurrent != null) {
                    val a = (pendingStart.x / size.width).coerceIn(0f, 1f) to
                        (pendingStart.y / size.height).coerceIn(0f, 1f)
                    val b = (pendingCurrent.x / size.width).coerceIn(0f, 1f) to
                        (pendingCurrent.y / size.height).coerceIn(0f, 1f)
                    FracRect(
                        min(a.first, b.first),
                        min(a.second, b.second),
                        max(a.first, b.first),
                        max(a.second, b.second)
                    )
                } else {
                    selection?.let { fractionRectOf(plan, it) }
                }

                if (box != null) {
                    val left = box.l * size.width
                    val top = box.t * size.height
                    val right = box.r * size.width
                    val bottom = box.b * size.height
                    val dim = Color.Black.copy(alpha = 0.35f)
                    drawRect(dim, Offset(0f, 0f), Size(size.width, top.coerceAtLeast(0f)))
                    drawRect(
                        dim,
                        Offset(0f, bottom),
                        Size(size.width, (size.height - bottom).coerceAtLeast(0f))
                    )
                    drawRect(
                        dim,
                        Offset(0f, top),
                        Size(left.coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f))
                    )
                    drawRect(
                        dim,
                        Offset(right, top),
                        Size((size.width - right).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f))
                    )
                    drawRect(
                        color = accent,
                        topLeft = Offset(left, top),
                        size = Size(
                            (right - left).coerceAtLeast(0f),
                            (bottom - top).coerceAtLeast(0f)
                        ),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    // The four corner handles.
                    listOf(
                        Offset(left, top),
                        Offset(right, top),
                        Offset(left, bottom),
                        Offset(right, bottom)
                    ).forEach { corner ->
                        drawCircle(Color.White, radius = 7.dp.toPx(), center = corner)
                        drawCircle(accent, radius = 7.dp.toPx(), center = corner, style = Stroke(2.dp.toPx()))
                    }

                    // Live detection: one thin line per detected separator.
                    val rect = selection
                    if (rect != null && pendingStart == null) {
                        detection?.columnBoundaries?.forEach { x ->
                            val fx = snipFractionOf(plan, x, rect.top).first
                            drawLine(
                                color = boundary,
                                start = Offset(fx * size.width, box.t * size.height),
                                end = Offset(fx * size.width, box.b * size.height),
                                strokeWidth = 1.5.dp.toPx()
                            )
                        }
                        detection?.rowBoundaries?.forEach { y ->
                            val fy = snipFractionOf(plan, rect.left, y).second
                            drawLine(
                                color = boundary,
                                start = Offset(box.l * size.width, fy * size.height),
                                end = Offset(box.r * size.width, fy * size.height),
                                strokeWidth = 1.5.dp.toPx()
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Turns the two drag points into a page rectangle; too small a drag is ignored. */
private fun commitDrag(
    plan: PageRenderPlan,
    start: Offset?,
    end: Offset?,
    width: Float,
    height: Float,
    onRect: (PdfRect?) -> Unit
) {
    val a = start ?: return
    val b = end ?: return
    if (width <= 0f || height <= 0f) return
    val rect = snipRectFromDrag(
        plan = plan,
        x0 = (a.x / width).coerceIn(0f, 1f),
        y0 = (a.y / height).coerceIn(0f, 1f),
        x1 = (b.x / width).coerceIn(0f, 1f),
        y1 = (b.y / height).coerceIn(0f, 1f)
    )
    if (rect != null) onRect(rect)
}

/** The bottom sheet: detection summary, options, preview and actions. */
@Composable
private fun SnipPanel(
    state: SnipUiState,
    viewModel: SnipViewModel,
    ocrLanguage: String,
    onOcrLanguage: (String) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val detection = state.detection
            val output = state.output

            // The one-line result.
            Text(
                text = when {
                    state.multiPage -> {
                        val pages = state.regions.size
                        "Stitched $pages " + (if (pages == 1) "page" else "pages") +
                            (if (detection?.table != null) " + this page" else "") +
                            " · " + confidenceLabel(state.confidence(), state.source())
                    }
                    detection != null -> describeSnip(detection)
                    else -> "Long-press and drag a box around a table."
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag(UiTags.SNIP_DETECTION)
            )

            if (detection?.table == null && detection != null && !state.ocrNeeded) {
                state.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (detection != null && detection.notes.isNotEmpty() && detection.table != null) {
                Text(
                    text = detection.notes.take(2).joinToString(" "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // A scanned page: OCR reads the same box with the same structure code.
            if (state.ocrNeeded || (detection != null && detection.table == null && detection.words.isEmpty())) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "This page has no text layer",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Run OCR to read the table from the picture. The result is less certain.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = ocrLanguage == "eng",
                                onClick = { onOcrLanguage("eng") },
                                label = { Text("English") },
                                modifier = Modifier.testTag(UiTags.SNIP_OCR_LANGUAGE_ENGLISH)
                            )
                            FilterChip(
                                selected = ocrLanguage == "hin",
                                onClick = { onOcrLanguage("hin") },
                                label = { Text("Hindi") },
                                modifier = Modifier.testTag(UiTags.SNIP_OCR_LANGUAGE_HINDI)
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.runOcr(ocrLanguage) },
                            enabled = !state.ocrRunning,
                            modifier = Modifier.testTag(UiTags.SNIP_OCR)
                        ) {
                            if (state.ocrRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (state.ocrRunning) "Reading…" else "Read this page with OCR")
                        }
                    }
                }
            }

            // Format switch: the last choice is remembered app-wide.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                SnipFormat.entries.forEach { format ->
                    FilterChip(
                        selected = state.settings.format == format,
                        onClick = { viewModel.setFormat(format) },
                        label = { Text(format.label) },
                        modifier = Modifier.testTag(UiTags.snipFormat(format.name.lowercase()))
                    )
                }
            }

            // Options: header row, trim, merged cells and the CSV delimiter.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Switch(
                    checked = state.settings.options.headerRow,
                    onCheckedChange = {
                        viewModel.setOptions(state.settings.options.copy(headerRow = it))
                    },
                    modifier = Modifier.testTag(UiTags.SNIP_HEADER_ROW)
                )
                Text("First row is a header", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = state.settings.options.trimSpaces,
                    onCheckedChange = {
                        viewModel.setOptions(state.settings.options.copy(trimSpaces = it))
                    },
                    modifier = Modifier.testTag(UiTags.SNIP_TRIM_SPACES)
                )
                Text("Trim spaces", style = MaterialTheme.typography.bodySmall)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                MergedCells.entries.forEach { merged ->
                    FilterChip(
                        selected = state.settings.options.merged == merged,
                        onClick = {
                            viewModel.setOptions(state.settings.options.copy(merged = merged))
                        },
                        label = { Text(merged.label) },
                        modifier = Modifier.testTag(UiTags.snipMerged(merged.name.lowercase()))
                    )
                }
                if (state.settings.format == SnipFormat.CSV) {
                    CsvDelimiter.entries.forEach { delimiter ->
                        FilterChip(
                            selected = state.settings.options.delimiter == delimiter,
                            onClick = {
                                viewModel.setOptions(state.settings.options.copy(delimiter = delimiter))
                            },
                            label = {
                                Text(if (delimiter == CsvDelimiter.COMMA) "Comma" else "Semicolon")
                            },
                            modifier = Modifier.testTag(
                                UiTags.snipDelimiter(delimiter.name.lowercase())
                            )
                        )
                    }
                }
            }

            // The table, as it will be copied.
            val stitched = stitchTables(state.tables(), dropRepeatedHeader = true)
            if (stitched != null) {
                TablePreviewGrid(table = stitched, modifier = Modifier.testTag(UiTags.SNIP_PREVIEW))
            }

            if (output.isNotEmpty()) {
                OutlinedCard(modifier = Modifier.fillMaxWidth().testTag(UiTags.SNIP_OUTPUT)) {
                    Text(
                        text = output,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = Modifier
                            .heightIn(max = 120.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    )
                }
            }

            // Multi-page stitching.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = viewModel::addNextPage,
                    enabled = state.canAddNextPage() && !state.busy,
                    modifier = Modifier.testTag(UiTags.SNIP_ADD_NEXT_PAGE)
                ) { Text("Add next page") }
                if (state.multiPage) {
                    TextButton(
                        onClick = viewModel::clearRegions,
                        modifier = Modifier.testTag(UiTags.SNIP_CLEAR_PAGES)
                    ) { Text("Clear pages") }
                }
            }

            state.message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(UiTags.SNIP_MESSAGE)
                )
            }
            if (detection?.table != null && state.error != null) {
                Text(
                    text = state.error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(UiTags.SNIP_ERROR)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCopy,
                    enabled = output.isNotEmpty(),
                    modifier = Modifier.weight(1f).testTag(UiTags.SNIP_COPY)
                ) { Text("Copy") }
                OutlinedButton(
                    onClick = onShare,
                    enabled = output.isNotEmpty(),
                    modifier = Modifier.weight(1f).testTag(UiTags.SNIP_SHARE)
                ) { Text("Share") }
                Button(
                    onClick = onSave,
                    enabled = output.isNotEmpty(),
                    modifier = Modifier.weight(1f).testTag(UiTags.SNIP_SAVE)
                ) { Text("Save") }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** A small grid of the first rows and columns, so the read can be checked. */
@Composable
private fun TablePreviewGrid(table: Table, modifier: Modifier = Modifier) {
    val maxRows = 8
    val maxColumns = 8
    val rows = table.rows.take(maxRows)
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        rows.forEachIndexed { rowIndex, row ->
            Row {
                row.cells.take(maxColumns).forEach { cell ->
                    Box(
                        modifier = Modifier
                            .width(128.dp)
                            .border(0.5.dp, outline)
                            .padding(6.dp)
                    ) {
                        Text(
                            text = cell.text,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (rowIndex == 0 && table.hasHeader) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            },
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        if (table.rows.size > maxRows) {
            Text(
                "${table.rows.size - maxRows} more rows in the output below",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** A rectangle in fractional page coordinates (0..1, origin top-left). */
private data class FracRect(val l: Float, val t: Float, val r: Float, val b: Float)

/** The fractional box of a page-space rectangle on the drawn page. */
private fun fractionRectOf(plan: PageRenderPlan, rect: PdfRect): FracRect {
    val a = snipFractionOf(plan, rect.left, rect.top)
    val b = snipFractionOf(plan, rect.right, rect.bottom)
    return FracRect(
        min(a.first, b.first),
        min(a.second, b.second),
        max(a.first, b.first),
        max(a.second, b.second)
    )
}
