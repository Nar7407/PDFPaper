package com.pdfpaper.app.ui.viewer

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.ui.PasswordDialog
import com.pdfpaper.engine.annot.AnnotationSpec
import com.pdfpaper.engine.annot.HighlightSpec
import com.pdfpaper.engine.annot.InkSpec
import com.pdfpaper.engine.annot.NoteSpec
import com.pdfpaper.engine.form.FieldKind
import com.pdfpaper.engine.form.PdfField
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageRenderPlan
import com.pdfpaper.engine.text.TextRect
import kotlinx.coroutines.delay
import kotlin.math.abs
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
    val edit by viewModel.edit.collectAsState()
    val export by viewModel.export.collectAsState()
    val message by viewModel.message.collectAsState()

    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showForms by rememberSaveable { mutableStateOf(false) }
    var noteTarget by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) }

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

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) viewModel.completeExport(uri) else viewModel.cancelExport()
    }

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
    // Ask for a destination once the engine has produced the new bytes (T12).
    LaunchedEffect(export) {
        val pending = export ?: return@LaunchedEffect
        createDocument.launch(pending.suggestedName)
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
                editActive = edit.active,
                onZoomIn = { zoom = (zoom * 1.25f).coerceAtMost(MAX_ZOOM) },
                onZoomOut = { zoom = (zoom / 1.25f).coerceAtLeast(MIN_ZOOM) },
                onFit = { zoom = 1f },
                onFind = { showSearch = !showSearch },
                onEdit = { viewModel.setEditMode(!edit.active) },
                onOrganize = { onOrganize(path) }
            )

            if (edit.active) {
                EditBar(
                    tool = edit.tool,
                    annotationCount = edit.annotations.size,
                    fieldCount = edit.fields.size,
                    showForms = showForms,
                    saving = edit.saving,
                    hasChanges = edit.hasPendingChanges,
                    onTool = viewModel::setTool,
                    onUndo = viewModel::undoAnnotation,
                    onClear = viewModel::clearAnnotations,
                    onSave = viewModel::save,
                    onToggleForms = { showForms = !showForms }
                )
                if (showForms) {
                    FormPanel(
                        fields = edit.fields,
                        values = edit.fieldValues,
                        loading = edit.loadingFields,
                        onValue = viewModel::setFieldValue
                    )
                }
            }

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
                                val pageAnnotations = remember(edit.annotations, index) {
                                    edit.annotations.filter { it.pageIndex == index }
                                }
                                val inkDraft = edit.inkDraft?.takeIf { it.pageIndex == index }
                                PageItem(
                                    index = index,
                                    box = boxes.getOrNull(index),
                                    bitmap = bitmaps[index],
                                    plan = plans[index],
                                    searchRects = searchRects,
                                    selectionRects = selectionRects,
                                    editTool = if (edit.active) edit.tool else null,
                                    annotations = pageAnnotations,
                                    inkDraft = inkDraft,
                                    targetPixelWidth = (screenWidthPx * renderZoom).roundToInt(),
                                    onVisible = viewModel::onPageVisible,
                                    onHidden = viewModel::onPageHidden,
                                    onSelect = viewModel::selectAt,
                                    onInkStart = viewModel::startInk,
                                    onInkExtend = viewModel::extendInk,
                                    onInkEnd = viewModel::finishInk,
                                    onNoteTap = { i, fx, fy -> noteTarget = Triple(i, fx, fy) }
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
                    allowHighlight = edit.active,
                    onHighlight = viewModel::highlightSelection,
                    onCopy = { clipboard.setText(AnnotatedString(current.text)) },
                    onSelectAll = { viewModel.selectAll(current.pageIndex) },
                    onClear = viewModel::clearSelection
                )
            }
        }
    }

    val target = noteTarget
    if (target != null) {
        NoteDialog(
            onDismiss = { noteTarget = null },
            onConfirm = { text ->
                viewModel.addNote(target.first, target.second, target.third, text)
                noteTarget = null
            }
        )
    }

    if (state.requiresPassword) {
        PasswordDialog(
            fileName = state.displayName,
            incorrect = state.passwordError != null,
            onDismiss = viewModel::cancelUnlock,
            onSubmit = viewModel::unlock
        )
    }
}

@Composable
private fun ViewerToolbar(
    title: String,
    pageLabel: String?,
    zoom: Float,
    findActive: Boolean,
    editActive: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onFind: () -> Unit,
    onEdit: () -> Unit,
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
            TextButton(onClick = onEdit) { Text(if (editActive) "Done" else "Annotate") }
            TextButton(onClick = onOrganize) { Text("Organize") }
        }
    }
}

@Composable
private fun EditBar(
    tool: AnnotationTool,
    annotationCount: Int,
    fieldCount: Int,
    showForms: Boolean,
    saving: Boolean,
    hasChanges: Boolean,
    onTool: (AnnotationTool) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onToggleForms: () -> Unit
) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ToolButton("Highlight", tool == AnnotationTool.HIGHLIGHT) { onTool(AnnotationTool.HIGHLIGHT) }
            ToolButton("Ink", tool == AnnotationTool.INK) { onTool(AnnotationTool.INK) }
            ToolButton("Note", tool == AnnotationTool.NOTE) { onTool(AnnotationTool.NOTE) }
            Text("$annotationCount", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onUndo, enabled = annotationCount > 0) { Text("Undo") }
            TextButton(onClick = onClear, enabled = annotationCount > 0) { Text("Clear") }
            if (fieldCount > 0) {
                TextButton(onClick = onToggleForms) {
                    Text(if (showForms) "Hide fields" else "Fields ($fieldCount)")
                }
            }
            TextButton(onClick = onSave, enabled = hasChanges && !saving) {
                Text(if (saving) "Saving…" else "Save")
            }
        }
    }
}

@Composable
private fun ToolButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        TextButton(onClick = onClick) {
            Text(label, color = MaterialTheme.colorScheme.primary)
        }
    } else {
        TextButton(onClick = onClick) { Text(label) }
    }
}

/** Editors for every AcroForm field (T12): text, checkbox, radio and choice. */
@Composable
private fun FormPanel(
    fields: List<PdfField>,
    values: Map<String, String>,
    loading: Boolean,
    onValue: (String, String) -> Unit
) {
    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        when {
            loading -> Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Text("  Loading fields…", style = MaterialTheme.typography.bodySmall)
            }
            fields.isEmpty() -> Text(
                "This document has no fillable fields.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp)
            )
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                items(fields) { field -> FieldRow(field, values, onValue) }
            }
        }
    }
}

@Composable
private fun FieldRow(field: PdfField, values: Map<String, String>, onValue: (String, String) -> Unit) {
    val value = values[field.name] ?: field.value ?: ""
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(field.name, style = MaterialTheme.typography.labelMedium)
        when (field.kind) {
            FieldKind.TEXT, FieldKind.CHOICE ->
                if (field.kind == FieldKind.CHOICE && field.options.isNotEmpty()) {
                    Column {
                        for (option in field.options) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = value == option.exportValue,
                                    onClick = { onValue(field.name, option.exportValue) }
                                )
                                Text(option.displayValue, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { onValue(field.name, it) },
                        singleLine = !field.multiline,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            FieldKind.CHECKBOX -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = value.isNotEmpty() && value != "Off",
                    onCheckedChange = { checked ->
                        val on = field.onStates.firstOrNull() ?: "Yes"
                        onValue(field.name, if (checked) on else "Off")
                    }
                )
                Text("checked", style = MaterialTheme.typography.bodySmall)
            }
            FieldKind.RADIO -> Column {
                val states = field.onStates.ifEmpty { field.options.map { it.exportValue } }
                for (state in states) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = value == state, onClick = { onValue(field.name, state) })
                        Text(
                            field.options.firstOrNull { it.exportValue == state }?.displayValue ?: state,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            else -> Text(
                "(${field.kind.name.lowercase()} field — read only)",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun NoteDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("New note") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
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
    allowHighlight: Boolean,
    onHighlight: () -> Unit,
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
            if (allowHighlight) {
                TextButton(onClick = onHighlight) { Text("Highlight") }
            }
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
    editTool: AnnotationTool?,
    annotations: List<AnnotationSpec>,
    inkDraft: InkDraft?,
    targetPixelWidth: Int,
    onVisible: (Int, Int) -> Unit,
    onHidden: (Int) -> Unit,
    onSelect: (Int, Float, Float, Boolean) -> Unit,
    onInkStart: (Int, Float, Float) -> Unit,
    onInkExtend: (Float, Float) -> Unit,
    onInkEnd: () -> Unit,
    onNoteTap: (Int, Float, Float) -> Unit
) {
    val aspect = remember(box) {
        val (w, h) = if (box != null) box.rotatedWidth to box.rotatedHeight else 612.0 to 792.0
        if (h > 0.0) (w / h).toFloat() else 612f / 792f
    }

    val gesture = when (editTool) {
        AnnotationTool.INK -> Modifier.inkGestures(
            onStart = { fx, fy -> onInkStart(index, fx, fy) },
            onExtend = onInkExtend,
            onEnd = onInkEnd
        )
        AnnotationTool.NOTE -> Modifier.noteTapGesture { fx, fy -> onNoteTap(index, fx, fy) }
        else -> Modifier.wordSelectionGestures { fx, fy, extend -> onSelect(index, fx, fy, extend) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .aspectRatio(aspect)
            .shadow(2.dp)
            .background(Color.White)
            .then(gesture),
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
            PendingOverlay(plan, annotations, inkDraft)
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

/** Pending annotations (highlight, ink, note) drawn until the file is saved (T12). */
@Composable
private fun PendingOverlay(
    plan: PageRenderPlan?,
    annotations: List<AnnotationSpec>,
    inkDraft: InkDraft?
) {
    if (plan == null || (annotations.isEmpty() && inkDraft == null)) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawPendingAnnotations(plan, annotations, inkDraft)
    }
}

private fun DrawScope.pagePoint(plan: PageRenderPlan, x: Double, y: Double): Offset {
    val p = plan.matrix.apply(x, y)
    return Offset(
        (p[0] * size.width / plan.pixelWidth).toFloat(),
        (p[1] * size.height / plan.pixelHeight).toFloat()
    )
}

private fun DrawScope.drawPendingAnnotations(
    plan: PageRenderPlan,
    annotations: List<AnnotationSpec>,
    inkDraft: InkDraft?
) {
    if (plan.pixelWidth <= 0 || plan.pixelHeight <= 0) return
    val highlightColor = Color(0x99FFE082)
    val noteColor = Color(0xFFFFE082)
    val noteBorder = Color(0xFFB58900)
    val inkColor = Color(0xFF1A1A1A)
    for (spec in annotations) {
        when (spec) {
            is HighlightSpec -> for (r in spec.rects) {
                val a = pagePoint(plan, r.left, r.top)
                val b = pagePoint(plan, r.right, r.bottom)
                drawRect(
                    color = highlightColor,
                    topLeft = Offset(min(a.x, b.x), min(a.y, b.y)),
                    size = Size(abs(b.x - a.x), abs(b.y - a.y))
                )
            }
            is InkSpec -> for (stroke in spec.strokes) drawPolyline(plan, stroke, inkColor, 3f)
            is NoteSpec -> {
                val a = pagePoint(plan, spec.rect.left, spec.rect.top)
                val b = pagePoint(plan, spec.rect.right, spec.rect.bottom)
                val topLeft = Offset(min(a.x, b.x), min(a.y, b.y))
                val extent = Size(abs(b.x - a.x).coerceAtLeast(10f), abs(b.y - a.y).coerceAtLeast(10f))
                drawRect(color = noteColor, topLeft = topLeft, size = extent)
                drawRect(color = noteBorder, topLeft = topLeft, size = extent, style = Stroke(1.5f))
            }
        }
    }
    if (inkDraft != null) drawPolyline(plan, inkDraft.points, inkColor, 3f)
}

private fun DrawScope.drawPolyline(plan: PageRenderPlan, points: List<Double>, color: Color, width: Float) {
    var i = 0
    while (i + 3 < points.size) {
        drawLine(
            color = color,
            start = pagePoint(plan, points[i], points[i + 1]),
            end = pagePoint(plan, points[i + 2], points[i + 3]),
            strokeWidth = width
        )
        i += 2
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
 * Freehand ink: the first touch starts a stroke and every moved pointer extends
 * it, so the whole finger path becomes one polyline (T12).
 */
private fun Modifier.inkGestures(
    onStart: (fx: Float, fy: Float) -> Unit,
    onExtend: (fx: Float, fy: Float) -> Unit,
    onEnd: () -> Unit
): Modifier = pointerInput(Unit) {
    fun fraction(offset: Offset): Pair<Float, Float> =
        offset.x / size.width.coerceAtLeast(1) to offset.y / size.height.coerceAtLeast(1)

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val (sx, sy) = fraction(down.position)
        onStart(sx, sy)
        var last = down.position
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if ((change.position - last).getDistance() >= 2f) {
                last = change.position
                val (fx, fy) = fraction(change.position)
                onExtend(fx, fy)
                change.consume()
            }
        }
        onEnd()
    }
}

/** Tap anywhere on the page to drop a sticky note there (T12). */
private fun Modifier.noteTapGesture(onTap: (fx: Float, fy: Float) -> Unit): Modifier = pointerInput(Unit) {
    fun fraction(offset: Offset): Pair<Float, Float> =
        offset.x / size.width.coerceAtLeast(1) to offset.y / size.height.coerceAtLeast(1)

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var moved = false
        var tapped = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (!moved) tapped = true
                break
            }
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                moved = true
            }
        }
        if (tapped) {
            val (fx, fy) = fraction(down.position)
            onTap(fx, fy)
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
