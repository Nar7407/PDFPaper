package com.pdfpaper.app.ui.pages

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.data.copyPdfToCache
import com.pdfpaper.app.ui.PasswordDialog
import com.pdfpaper.engine.render.PageBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Organize screen (T10): a grid of page thumbnails with multi-select and page
 * operations (rotate, delete, reorder), undo/redo, and export.
 *
 * The grid shows the whole plan; tapping toggles a page in the selection. Edits
 * go through [PagesViewModel]'s plan, so undo/redo is exact and the exported
 * file preserves links, bookmarks and form fields that still point at a kept
 * page.
 */
@Composable
fun PagesScreen(
    path: String,
    viewModel: PagesViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val plan by viewModel.plan.collectAsState()
    val boxes by viewModel.boxes.collectAsState()
    val thumbs by viewModel.thumbs.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(path) { viewModel.load(path) }
    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.dismissMessage()
    }

    // Ask the user where to write the produced bytes.
    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) viewModel.completeExport(uri) else viewModel.cancelExport()
    }
    LaunchedEffect(state.pendingExport) {
        val pending = state.pendingExport ?: return@LaunchedEffect
        createDoc.launch(pending.suggestedName)
    }

    // "Add pages from PDF" → copy into cache, then append its pages.
    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val cached = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        copyPdfToCache(input, "merge.pdf", context.cacheDir).file.absolutePath
                    }
                }.getOrNull()
            }
            if (cached != null) viewModel.addDocument(cached)
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OrganizerToolbar(
                title = state.displayName ?: "Organize",
                pageCount = plan.size,
                selectedCount = state.selection.size,
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onSelectAll = viewModel::selectAll,
                onClearSelection = viewModel::clearSelection,
                onReset = viewModel::reset
            )
            if (state.exporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.error != null -> Text(
                        text = state.error ?: "Unknown error",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 108.dp),
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(count = plan.size) { position ->
                            PageCell(
                                position = position,
                                box = boxes.getOrNull(position),
                                bitmap = thumbs[position],
                                selected = position in state.selection,
                                onToggle = viewModel::toggleSelection,
                                onVisible = viewModel::onThumbVisible,
                                onHidden = viewModel::onThumbHidden
                            )
                        }
                    }
                }
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
            }

            OrganizerActions(
                enabled = !state.isLoading && state.error == null && plan.isNotEmpty(),
                onRotateLeft = { viewModel.rotateSelection(-90) },
                onRotateRight = { viewModel.rotateSelection(90) },
                onMoveUp = viewModel::moveSelectionUp,
                onMoveDown = viewModel::moveSelectionDown,
                onDelete = viewModel::deleteSelection,
                onExtract = { viewModel.requestSave(selectedOnly = true) },
                onSave = { viewModel.requestSave(selectedOnly = false) },
                onAddPdf = { openDoc.launch(arrayOf("application/pdf")) },
                onSplit = { viewModel.requestSplit(5) }
            )
        }
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
private fun OrganizerToolbar(
    title: String,
    pageCount: Int,
    selectedCount: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onReset: () -> Unit
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        if (selectedCount > 0) "$selectedCount of $pageCount selected" else "$pageCount pages",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                TextButton(onClick = onUndo, enabled = canUndo) { Text("Undo") }
                TextButton(onClick = onRedo, enabled = canRedo) { Text("Redo") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSelectAll) { Text("Select all") }
                TextButton(onClick = onClearSelection) { Text("Clear") }
                TextButton(onClick = onReset) { Text("Reset") }
            }
        }
    }
}

@Composable
private fun OrganizerActions(
    enabled: Boolean,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onExtract: () -> Unit,
    onSave: () -> Unit,
    onAddPdf: () -> Unit,
    onSplit: () -> Unit
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onRotateLeft, enabled = enabled) { Text("⟲ 90") }
            TextButton(onClick = onRotateRight, enabled = enabled) { Text("⟳ 90") }
            TextButton(onClick = onMoveUp, enabled = enabled) { Text("↑") }
            TextButton(onClick = onMoveDown, enabled = enabled) { Text("↓") }
            TextButton(onClick = onDelete, enabled = enabled) { Text("Delete") }
            TextButton(onClick = onExtract, enabled = enabled) { Text("Extract") }
            TextButton(onClick = onSave, enabled = enabled) { Text("Save") }
            TextButton(onClick = onAddPdf, enabled = enabled) { Text("Add PDF") }
            TextButton(onClick = onSplit, enabled = enabled) { Text("Split 5") }
        }
    }
}

@Composable
private fun PageCell(
    position: Int,
    box: PageBox?,
    bitmap: Bitmap?,
    selected: Boolean,
    onToggle: (Int) -> Unit,
    onVisible: (Int, Int) -> Unit,
    onHidden: (Int) -> Unit
) {
    val aspect = remember(box) {
        val (w, h) = if (box != null) box.rotatedWidth to box.rotatedHeight else 612.0 to 792.0
        if (h > 0.0) (w / h).toFloat() else 612f / 792f
    }
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant

    LaunchedEffect(position) { onVisible(position, 320) }
    DisposableEffect(position) { onDispose { onHidden(position) } }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White)
            .border(if (selected) 3.dp else 1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable { onToggle(position) },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page ${position + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            CircularProgressIndicator(Modifier.size(22.dp))
        }
        Surface(
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
        ) {
            Text(
                "${position + 1}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}
