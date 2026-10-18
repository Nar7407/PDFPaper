package com.pdfpaper.app.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.edit.EditFontFamily
import com.pdfpaper.app.edit.EditableBounds
import com.pdfpaper.app.edit.EditableHandle
import com.pdfpaper.app.edit.EditableObject
import com.pdfpaper.app.edit.EditableObjectType
import com.pdfpaper.app.edit.EditableStyle
import com.pdfpaper.app.edit.EditingRuntime
import com.pdfpaper.app.ui.PasswordDialog
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import com.pdfpaper.app.ui.tools.ToolDefinition
import com.pdfpaper.app.ui.tools.ToolRegistry
import com.pdfpaper.app.ui.tools.ToolSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** The text object being created or edited, plus where it belongs. */
private data class TextTarget(
    val pageIndex: Int,
    val bounds: EditableBounds,
    val existing: EditableObject? = null
)

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
    startInEditMode: Boolean = false,
    startWithForms: Boolean = false,
    startEditTool: String? = null,
    onBack: () -> Unit = {},
    onOrganize: (path: String, toolId: String?) -> Unit = { _, _ -> },
    onOpenInfo: (String) -> Unit = {},
    viewModel: ViewerViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val boxes by viewModel.pageBoxes.collectAsState()
    val bitmaps by viewModel.bitmaps.collectAsState()
    val plans by viewModel.plans.collectAsState()
    val search by viewModel.search.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val edit by viewModel.edit.collectAsState()
    val objectEdit by viewModel.objectEdit.collectAsState()
    val export by viewModel.export.collectAsState()
    val message by viewModel.message.collectAsState()

    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showForms by rememberSaveable { mutableStateOf(startWithForms) }
    var noteTarget by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) }
    var textTarget by remember { mutableStateOf<TextTarget?>(null) }
    var pendingImageUri by remember { mutableStateOf<Uri?>(null) }
    var replaceImageTarget by remember { mutableStateOf<EditableObject?>(null) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    var activeToolId by rememberSaveable { mutableStateOf<String?>(null) }
    // Annotation count when the current tool opened, so Cancel can undo its work.
    var toolAnnotationStart by remember { mutableIntStateOf(0) }

    val context = LocalContext.current
    val demoEngine = EditingRuntime.isDemo(context)
    val activeToolName = activeToolId?.let { ToolRegistry.byId(it)?.name }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val replace = replaceImageTarget
            if (replace != null) {
                viewModel.replaceImageObject(replace.id, uri.toString())
                replaceImageTarget = null
            } else {
                pendingImageUri = uri
                viewModel.setObjectTool(ObjectEditTool.IMAGE)
            }
        } else {
            replaceImageTarget = null
        }
    }

    val pickNewImage = {
        replaceImageTarget = null
        pendingImageUri = null
        imagePicker.launch("image/*")
    }

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
    LaunchedEffect(path, startInEditMode, startWithForms, startEditTool, state.isLoading, state.pageCount) {
        if (startInEditMode && !state.isLoading && state.error == null && state.pageCount > 0) {
            if (startEditTool.isNullOrBlank()) {
                viewModel.setEditMode(true)
            } else {
                viewModel.startObjectEditing(startEditTool)
                activeToolId = ToolRegistry.byViewerTool(startEditTool)?.id
            }
            if (startWithForms) showForms = true
        }
    }
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

    // Leaving the active tool keeps its work; cancelling drops what the tool
    // session added. Either way the viewer leaves edit mode.
    val closeTool = {
        activeToolId = null
        viewModel.setEditMode(false)
    }
    val cancelTool = {
        viewModel.trimAnnotationsTo(toolAnnotationStart)
        activeToolId = null
        viewModel.setEditMode(false)
    }
    val activateTool: (ToolDefinition) -> Unit = { tool ->
        showTools = false
        when (tool.section) {
            ToolSection.PAGES -> onOrganize(path, tool.id)
            ToolSection.EDIT, ToolSection.ANNOTATE -> {
                toolAnnotationStart = edit.annotations.size
                viewModel.startViewerTool(tool.viewerTool ?: "select")
                activeToolId = tool.id
            }
            ToolSection.FORMS_SECURITY ->
                if (tool.id == "fill-form") {
                    toolAnnotationStart = edit.annotations.size
                    viewModel.startViewerTool("select")
                    showForms = true
                    activeToolId = tool.id
                } else {
                    viewModel.showMessage("${tool.name} is coming soon")
                }
            ToolSection.DOCUMENT -> when (tool.id) {
                "info" -> onOpenInfo(path)
                "save", "save-as" -> viewModel.saveAll()
                "share" -> sharePdf(context, path, state.displayName)
                else -> viewModel.showMessage("${tool.name} is coming soon")
            }
        }
    }
    // Back exits the sheet, then the active tool, and only then the document.
    BackHandler(enabled = showTools || activeToolId != null) {
        if (showTools) showTools = false else closeTool()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ViewerToolbar(
                title = state.displayName ?: "PDF",
                subtitle = activeToolName
                    ?: if (state.pageCount > 0) "${state.pageCount} pages" else null,
                zoom = zoom,
                findActive = showSearch,
                canUndo = objectEdit.canUndo || edit.annotations.isNotEmpty(),
                canRedo = objectEdit.canRedo,
                onBack = {
                    when {
                        showTools -> showTools = false
                        activeToolId != null -> closeTool()
                        else -> onBack()
                    }
                },
                onZoomIn = { zoom = (zoom * 1.25f).coerceAtMost(MAX_ZOOM) },
                onZoomOut = { zoom = (zoom / 1.25f).coerceAtLeast(MIN_ZOOM) },
                onFit = { zoom = 1f },
                onFind = { showSearch = !showSearch },
                onUndo = viewModel::undoAny,
                onRedo = viewModel::redoAny,
                onMore = { showTools = true }
            )

            if (edit.active) {
                EditBar(
                    toolName = activeToolName,
                    objectState = objectEdit,
                    markupTool = edit.tool,
                    annotationCount = edit.annotations.size,
                    fieldCount = edit.fields.size,
                    showForms = showForms,
                    saving = edit.saving,
                    hasMarkupChanges = edit.hasPendingChanges,
                    onObjectTool = viewModel::setObjectTool,
                    onAddImage = pickNewImage,
                    onMarkupTool = viewModel::setTool,
                    onUndoObject = viewModel::undoObjectEdit,
                    onRedoObject = viewModel::redoObjectEdit,
                    onUndoMarkup = viewModel::undoAnnotation,
                    onClearMarkup = viewModel::clearAnnotations,
                    onSave = viewModel::saveAll,
                    onToggleForms = { showForms = !showForms },
                    onDone = closeTool,
                    onCancel = cancelTool
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
                                val pageObjects = remember(objectEdit.objects, index) {
                                    objectEdit.objects.filter { it.pageIndex == index }
                                }
                                PageItem(
                                    index = index,
                                    box = boxes.getOrNull(index),
                                    bitmap = bitmaps[index],
                                    plan = plans[index],
                                    searchRects = searchRects,
                                    selectionRects = selectionRects,
                                    editTool = if (edit.active) edit.tool else null,
                                    objectTool = if (edit.active) objectEdit.tool else null,
                                    objects = pageObjects,
                                    selectedObjectId = objectEdit.selectedId,
                                    annotations = pageAnnotations,
                                    inkDraft = inkDraft,
                                    targetPixelWidth = (screenWidthPx * renderZoom).roundToInt(),
                                    onVisible = viewModel::onPageVisible,
                                    onHidden = viewModel::onPageHidden,
                                    onSelect = viewModel::selectAt,
                                    onInkStart = viewModel::startInk,
                                    onInkExtend = viewModel::extendInk,
                                    onInkEnd = viewModel::finishInk,
                                    onNoteTap = { i, fx, fy -> noteTarget = Triple(i, fx, fy) },
                                    onPlaceText = { i, fx, fy ->
                                        textTarget = TextTarget(i, EditableBounds.around(fx, fy))
                                    },
                                    onPlaceImage = { i, fx, fy ->
                                        val uri = pendingImageUri
                                        if (uri == null) {
                                            pickNewImage()
                                        } else {
                                            viewModel.addImageObject(
                                                pageIndex = i,
                                                bounds = EditableBounds.around(
                                                    x = fx,
                                                    y = fy,
                                                    width = 0.5f,
                                                    height = 0.34f
                                                ),
                                                contentUri = uri.toString()
                                            )
                                            pendingImageUri = null
                                        }
                                    },
                                    onSelectObject = viewModel::selectObject,
                                    onBoundsChange = viewModel::updateObjectBounds,
                                    onClearObjectSelection = viewModel::clearObjectSelection
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

            val selectedObject = objectEdit.selected
            if (selectedObject != null) {
                SelectedObjectBar(
                    objectState = selectedObject,
                    onEditText = {
                        textTarget = TextTarget(
                            pageIndex = selectedObject.pageIndex,
                            bounds = selectedObject.bounds,
                            existing = selectedObject
                        )
                    },
                    onReplaceImage = {
                        replaceImageTarget = selectedObject
                        imagePicker.launch("image/*")
                    },
                    onDelete = { viewModel.deleteObject(selectedObject.id) },
                    onDone = viewModel::clearObjectSelection
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

    val textEdit = textTarget
    if (textEdit != null) {
        TextObjectDialog(
            existing = textEdit.existing,
            onDismiss = { textTarget = null },
            onConfirm = { content, style ->
                val existing = textEdit.existing
                if (existing == null) {
                    viewModel.addTextObject(
                        pageIndex = textEdit.pageIndex,
                        bounds = textEdit.bounds,
                        content = content,
                        style = style
                    )
                } else {
                    viewModel.updateTextObject(existing.id, content, style)
                }
                textTarget = null
            }
        )
    }

    if (showTools) {
        ViewerToolsSheet(
            fileName = state.displayName ?: "PDF",
            demoEngine = demoEngine,
            onDismiss = { showTools = false },
            onSelect = activateTool,
            // The sheet covers the snackbar host, so close it before reporting
            // that the tool is not available yet.
            onUnavailable = { tool ->
                showTools = false
                viewModel.showMessage("${tool.name} is coming soon")
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
    subtitle: String?,
    zoom: Float,
    findActive: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onFind: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onMore: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
                IconButton(onClick = onFind) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = if (findActive) "Close search" else "Search"
                    )
                }
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Undo,
                        contentDescription = "Undo"
                    )
                }
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Redo,
                        contentDescription = "Redo"
                    )
                }
                IconButton(
                    onClick = onMore,
                    modifier = Modifier.testTag(UiTags.VIEWER_TOOLS_BUTTON)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More tools"
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onZoomOut) { Text("−") }
                Text("${(zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = onZoomIn) { Text("+") }
                TextButton(onClick = onFit) { Text("Fit") }
            }
        }
    }
}

@Composable
private fun EditBar(
    toolName: String?,
    objectState: ObjectEditState,
    markupTool: AnnotationTool,
    annotationCount: Int,
    fieldCount: Int,
    showForms: Boolean,
    saving: Boolean,
    hasMarkupChanges: Boolean,
    onObjectTool: (ObjectEditTool) -> Unit,
    onAddImage: () -> Unit,
    onMarkupTool: (AnnotationTool) -> Unit,
    onUndoObject: () -> Unit,
    onRedoObject: () -> Unit,
    onUndoMarkup: () -> Unit,
    onClearMarkup: () -> Unit,
    onSave: () -> Unit,
    onToggleForms: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.VIEWER_TOOL_MODE_BAR)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = toolName ?: "Edit",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag(UiTags.VIEWER_TOOL_CANCEL)
                ) { Text("Cancel") }
                TextButton(
                    onClick = onDone,
                    modifier = Modifier.testTag(UiTags.VIEWER_TOOL_DONE)
                ) { Text("Done") }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag(UiTags.EDIT_TOOLBAR),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                EditAction(
                    label = "Select",
                    icon = Icons.Outlined.NearMe,
                    selected = objectState.tool == ObjectEditTool.SELECT,
                    tag = UiTags.EDIT_SELECT
                ) { onObjectTool(ObjectEditTool.SELECT) }
                EditAction(
                    label = "Add text",
                    icon = Icons.Outlined.TextFields,
                    selected = objectState.tool == ObjectEditTool.TEXT,
                    tag = UiTags.EDIT_ADD_TEXT
                ) { onObjectTool(ObjectEditTool.TEXT) }
                EditAction(
                    label = "Add image",
                    icon = Icons.Outlined.AddPhotoAlternate,
                    selected = objectState.tool == ObjectEditTool.IMAGE,
                    tag = UiTags.EDIT_ADD_IMAGE,
                    onClick = onAddImage
                )
                EditAction(
                    label = "Undo",
                    icon = Icons.AutoMirrored.Outlined.Undo,
                    enabled = objectState.canUndo,
                    tag = UiTags.EDIT_UNDO,
                    onClick = onUndoObject
                )
                EditAction(
                    label = "Redo",
                    icon = Icons.AutoMirrored.Outlined.Redo,
                    enabled = objectState.canRedo,
                    tag = UiTags.EDIT_REDO,
                    onClick = onRedoObject
                )
                EditAction(
                    label = "Save",
                    icon = Icons.Outlined.Save,
                    enabled = (objectState.dirty || hasMarkupChanges) && !saving,
                    tag = UiTags.EDIT_SAVE,
                    onClick = onSave
                )
            }
            if (objectState.fake) {
                Text(
                    text = "Demo engine - text and image edits are simulated, not written to the PDF.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ToolButton("Highlight", markupTool == AnnotationTool.HIGHLIGHT) {
                    onMarkupTool(AnnotationTool.HIGHLIGHT)
                }
                ToolButton("Ink", markupTool == AnnotationTool.INK) {
                    onMarkupTool(AnnotationTool.INK)
                }
                ToolButton("Note", markupTool == AnnotationTool.NOTE) {
                    onMarkupTool(AnnotationTool.NOTE)
                }
                Text("$annotationCount", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = onUndoMarkup, enabled = annotationCount > 0) { Text("Undo") }
                TextButton(onClick = onClearMarkup, enabled = annotationCount > 0) { Text("Clear") }
                if (fieldCount > 0) {
                    TextButton(onClick = onToggleForms) {
                        Text(if (showForms) "Hide fields" else "Fields ($fieldCount)")
                    }
                }
            }
        }
    }
}

@Composable
private fun EditAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean = false,
    enabled: Boolean = true,
    tag: String,
    onClick: () -> Unit
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        selected -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .testTag(tag)
            .width(58.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = contentColor,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ToolButton(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                Color.Transparent
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    ) {
        Text(label)
    }
}

/** Actions for the object selected on the page. */
@Composable
private fun SelectedObjectBar(
    objectState: EditableObject,
    onEditText: () -> Unit,
    onReplaceImage: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag(UiTags.EDIT_OBJECT_BAR),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (objectState.type == EditableObjectType.TEXT) {
                        Icons.Outlined.TextFields
                    } else {
                        Icons.Outlined.Photo
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (objectState.type == EditableObjectType.TEXT) {
                            "Text selected"
                        } else {
                            "Image selected"
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Drag to move. Corner handles resize.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (objectState.type == EditableObjectType.TEXT) {
                    TextButton(onClick = onEditText) { Text("Edit text") }
                } else {
                    TextButton(onClick = onReplaceImage) { Text("Replace") }
                }
                TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDone) { Text("Done") }
            }
        }
    }
}

private val TEXT_COLORS = listOf(
    0xFF111111L to "Black",
    0xFF444444L to "Charcoal",
    0xFF777777L to "Grey"
)

/** Add/edit text with font, size and colour, all monochrome-friendly. */
@Composable
private fun TextObjectDialog(
    existing: EditableObject?,
    onDismiss: () -> Unit,
    onConfirm: (String, EditableStyle) -> Unit
) {
    val initialStyle = existing?.style ?: EditableStyle()
    var content by remember(existing?.id) { mutableStateOf(existing?.content.orEmpty()) }
    var family by remember(existing?.id) { mutableStateOf(initialStyle.fontFamily) }
    var fontSize by remember(existing?.id) { mutableFloatStateOf(initialStyle.fontSize) }
    var color by remember(existing?.id) { mutableStateOf(initialStyle.color) }
    var fontMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add text" else "Edit text") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Text") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Box(modifier = Modifier.padding(top = 12.dp)) {
                    OutlinedButton(onClick = { fontMenu = true }) {
                        Text("Font: ${family.label}")
                    }
                    DropdownMenu(
                        expanded = fontMenu,
                        onDismissRequest = { fontMenu = false }
                    ) {
                        EditFontFamily.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    family = option
                                    fontMenu = false
                                }
                            )
                        }
                    }
                }
                Text(
                    text = "Size ${fontSize.roundToInt()}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    valueRange = 8f..48f
                )
                Text(
                    text = "Colour",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for ((argb, label) in TEXT_COLORS) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .border(
                                        width = if (color == argb) 2.dp else 1.dp,
                                        color = if (color == argb) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        shape = CircleShape
                                    )
                                    .padding(5.dp)
                                    .background(Color(argb), CircleShape)
                                    .clickable { color = argb }
                            )
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        content.trim(),
                        EditableStyle(family, fontSize, color)
                    )
                },
                enabled = content.isNotBlank()
            ) {
                Text(if (existing == null) "Add" else "Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Editors for every AcroForm field (T12): text, checkbox, radio and choice. */
@Composable
private fun FormPanel(
    fields: List<PdfField>,
    values: Map<String, String>,
    loading: Boolean,
    onValue: (String, String) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
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
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
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
    objectTool: ObjectEditTool?,
    objects: List<EditableObject>,
    selectedObjectId: String?,
    annotations: List<AnnotationSpec>,
    inkDraft: InkDraft?,
    targetPixelWidth: Int,
    onVisible: (Int, Int) -> Unit,
    onHidden: (Int) -> Unit,
    onSelect: (Int, Float, Float, Boolean) -> Unit,
    onInkStart: (Int, Float, Float) -> Unit,
    onInkExtend: (Float, Float) -> Unit,
    onInkEnd: () -> Unit,
    onNoteTap: (Int, Float, Float) -> Unit,
    onPlaceText: (Int, Float, Float) -> Unit,
    onPlaceImage: (Int, Float, Float) -> Unit,
    onSelectObject: (String) -> Unit,
    onBoundsChange: (String, EditableBounds) -> Unit,
    onClearObjectSelection: () -> Unit
) {
    val aspect = remember(box) {
        val (w, h) = if (box != null) box.rotatedWidth to box.rotatedHeight else 612.0 to 792.0
        if (h > 0.0) (w / h).toFloat() else 612f / 792f
    }

    // The key handed to pointerInput must change whenever the active gesture
    // changes. Sharing the Unit key meant switching from text selection to a
    // note/text tap kept the old word-selection handler installed.
    val gesture = when {
        editTool == AnnotationTool.INK -> Modifier.inkGestures(
            key = "ink-$index",
            onStart = { fx, fy -> onInkStart(index, fx, fy) },
            onExtend = onInkExtend,
            onEnd = onInkEnd
        )
        editTool == AnnotationTool.NOTE ->
            Modifier.noteTapGesture(key = "note-$index") { fx, fy -> onNoteTap(index, fx, fy) }
        objectTool == ObjectEditTool.TEXT ->
            Modifier.noteTapGesture(key = ObjectEditTool.TEXT to objects) { fx, fy ->
                if (objects.none { it.bounds.contains(fx, fy) }) onPlaceText(index, fx, fy)
            }
        objectTool == ObjectEditTool.IMAGE ->
            Modifier.noteTapGesture(key = ObjectEditTool.IMAGE to objects) { fx, fy ->
                if (objects.none { it.bounds.contains(fx, fy) }) onPlaceImage(index, fx, fy)
            }
        objectTool == ObjectEditTool.SELECT ->
            Modifier.noteTapGesture(key = ObjectEditTool.SELECT to objects) { fx, fy ->
                if (objects.none { it.bounds.contains(fx, fy) }) onClearObjectSelection()
            }
        else -> Modifier.wordSelectionGestures(key = "select-$index") { fx, fy, extend ->
            onSelect(index, fx, fy, extend)
        }
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
            ObjectLayer(
                objects = objects,
                selectedId = selectedObjectId,
                editing = objectTool != null,
                onSelect = onSelectObject,
                onBoundsChange = onBoundsChange
            )
        } else {
            CircularProgressIndicator(Modifier.size(28.dp))
        }
    }
}

/**
 * Draws editable text and image objects over the page. Bounds are page
 * fractions, so the same object stays put through zoom and re-render.
 */
@Composable
private fun ObjectLayer(
    objects: List<EditableObject>,
    selectedId: String?,
    editing: Boolean,
    onSelect: (String) -> Unit,
    onBoundsChange: (String, EditableBounds) -> Unit
) {
    if (objects.isEmpty()) return
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pageWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val pageHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        for (item in objects) {
            EditableObjectBox(
                item = item,
                selected = editing && item.id == selectedId,
                editing = editing,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                onSelect = onSelect,
                onBoundsChange = onBoundsChange
            )
        }
    }
}

@Composable
private fun EditableObjectBox(
    item: EditableObject,
    selected: Boolean,
    editing: Boolean,
    pageWidth: Float,
    pageHeight: Float,
    onSelect: (String) -> Unit,
    onBoundsChange: (String, EditableBounds) -> Unit
) {
    val density = LocalDensity.current
    var liveBounds by remember(item.id, item.bounds) { mutableStateOf(item.bounds) }
    val handle = remember(item.id) { mutableStateOf(EditableHandle.NONE) }

    val widthPx = (liveBounds.width * pageWidth).coerceAtLeast(1f)
    val heightPx = (liveBounds.height * pageHeight).coerceAtLeast(1f)

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (liveBounds.left * pageWidth).roundToInt(),
                    (liveBounds.top * pageHeight).roundToInt()
                )
            }
            .width(with(density) { widthPx.toDp() })
            .height(with(density) { heightPx.toDp() })
            .zIndex(if (selected) 2f else 1f)
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(2.dp)
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (editing) {
                    Modifier
                        .pointerInput(item.id, selected, pageWidth, pageHeight) {
                            detectTapGestures { onSelect(item.id) }
                        }
                        .pointerInput(item.id, selected, pageWidth, pageHeight) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    onSelect(item.id)
                                    handle.value = if (selected) {
                                        val grip = 28.dp.toPx()
                                        when {
                                            start.x <= grip && start.y <= grip ->
                                                EditableHandle.TOP_LEFT
                                            start.x >= size.width - grip && start.y <= grip ->
                                                EditableHandle.TOP_RIGHT
                                            start.x <= grip && start.y >= size.height - grip ->
                                                EditableHandle.BOTTOM_LEFT
                                            start.x >= size.width - grip && start.y >= size.height - grip ->
                                                EditableHandle.BOTTOM_RIGHT
                                            else -> EditableHandle.NONE
                                        }
                                    } else {
                                        EditableHandle.NONE
                                    }
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    val dx = drag.x / pageWidth
                                    val dy = drag.y / pageHeight
                                    liveBounds = if (handle.value == EditableHandle.NONE) {
                                        liveBounds.movedBy(dx, dy)
                                    } else {
                                        liveBounds.resized(handle.value, dx, dy)
                                    }
                                },
                                onDragEnd = { onBoundsChange(item.id, liveBounds) },
                                onDragCancel = { liveBounds = item.bounds }
                            )
                        }
                } else {
                    Modifier
                }
            )
    ) {
        EditableObjectContent(item = item, pagePixelWidth = pageWidth, compact = false)
        if (selected) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val grip = 5.dp.toPx()
                val points = listOf(
                    Offset(0f, 0f),
                    Offset(size.width, 0f),
                    Offset(0f, size.height),
                    Offset(size.width, size.height)
                )
                for (point in points) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(point.x - grip, point.y - grip),
                        size = Size(grip * 2f, grip * 2f)
                    )
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(point.x - grip + 1f, point.y - grip + 1f),
                        size = Size(grip * 2f - 2f, grip * 2f - 2f),
                        style = Stroke(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun EditableObjectContent(
    item: EditableObject,
    pagePixelWidth: Float,
    compact: Boolean
) {
    when (item.type) {
        EditableObjectType.TEXT -> {
            val size = (item.style.fontSize * pagePixelWidth / 612f)
                .coerceAtLeast(7f)
                .sp
            Text(
                text = item.content,
                color = Color(item.style.color),
                fontFamily = when (item.style.fontFamily) {
                    EditFontFamily.SANS -> FontFamily.SansSerif
                    EditFontFamily.SERIF -> FontFamily.Serif
                    EditFontFamily.MONO -> FontFamily.Monospace
                },
                fontSize = size,
                lineHeight = (size.value * 1.15f).sp,
                maxLines = if (compact) 2 else 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxSize().padding(2.dp)
            )
        }
        EditableObjectType.IMAGE -> {
            val bitmap = rememberUriImage(item.content)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Placed image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Photo,
                        contentDescription = "Placed image",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberUriImage(uri: String): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    return bitmap
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
    key: Any?,
    onSelect: (fx: Float, fy: Float, extend: Boolean) -> Unit
): Modifier = pointerInput(key) {
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
    key: Any?,
    onStart: (fx: Float, fy: Float) -> Unit,
    onExtend: (fx: Float, fy: Float) -> Unit,
    onEnd: () -> Unit
): Modifier = pointerInput(key) {
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
private fun Modifier.noteTapGesture(
    key: Any?,
    onTap: (fx: Float, fy: Float) -> Unit
): Modifier = pointerInput(key) {
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

@Preview(showBackground = true, widthDp = 411, heightDp = 891, name = "Viewer edit mode - light")
@Composable
private fun ViewerEditModeLightPreview() {
    PDFPaperTheme(darkTheme = false) {
        val sample = EditableObject(
            id = "preview-text",
            type = EditableObjectType.TEXT,
            pageIndex = 0,
            bounds = EditableBounds(0.12f, 0.22f, 0.72f, 0.32f),
            content = "Project notes",
            style = EditableStyle(fontSize = 26f, color = 0xFF111111)
        )
        val objectState = ObjectEditState(
            objects = listOf(sample),
            selectedId = sample.id,
            tool = ObjectEditTool.SELECT,
            canUndo = true,
            canRedo = false,
            dirty = true,
            fake = true,
            engineName = "FakeEngine (demo)"
        )
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ViewerToolbar(
                title = "Project notes.pdf",
                subtitle = "Edit text",
                zoom = 1f,
                findActive = false,
                canUndo = true,
                canRedo = false,
                onBack = {},
                onZoomIn = {},
                onZoomOut = {},
                onFit = {},
                onFind = {},
                onUndo = {},
                onRedo = {},
                onMore = {}
            )
            EditBar(
                toolName = "Edit text",
                objectState = objectState,
                markupTool = AnnotationTool.HIGHLIGHT,
                annotationCount = 2,
                fieldCount = 0,
                showForms = false,
                saving = false,
                hasMarkupChanges = false,
                onObjectTool = {},
                onAddImage = {},
                onMarkupTool = {},
                onUndoObject = {},
                onRedoObject = {},
                onUndoMarkup = {},
                onClearMarkup = {},
                onSave = {},
                onToggleForms = {},
                onDone = {},
                onCancel = {}
            )
            Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(12.dp)) {
                Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
                    ObjectLayer(
                        objects = listOf(sample),
                        selectedId = sample.id,
                        editing = true,
                        onSelect = {},
                        onBoundsChange = { _, _ -> }
                    )
                }
            }
            SelectedObjectBar(
                objectState = sample,
                onEditText = {},
                onReplaceImage = {},
                onDelete = {},
                onDone = {}
            )
        }
    }
}

