package com.pdfpaper.app.ui.pages

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.AppTopBar
import com.pdfpaper.engine.split.TocSplitIssue
import java.io.File

/**
 * The route wrapper: opens the document once, then shows the preview. The
 * screen itself never opens a file, so a test can hand it a ViewModel that is
 * already loaded.
 */
@Composable
fun TocSplitRoute(
    path: String,
    onBack: () -> Unit,
    viewModel: TocSplitViewModel = viewModel()
) {
    LaunchedEffect(path) {
        if (path.isNotBlank()) viewModel.load(path)
    }
    TocSplitScreen(viewModel = viewModel, onNavigateBack = onBack)
}

/**
 * "Split by chapters" (T66): pick the outline level, edit the preview ranges,
 * then write one file per chapter into a folder the user picks, or share them
 * as one ZIP.
 */
@Composable
fun TocSplitScreen(
    viewModel: TocSplitViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var renaming by remember { mutableStateOf<EditableTocSplitPart?>(null) }
    var splitting by remember { mutableStateOf<EditableTocSplitPart?>(null) }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.exportToFolder(uri)
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.TOC_SPLIT_SCREEN)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(
                title = "Split by chapters",
                subtitle = state.fileName.takeIf { it.isNotBlank() },
                onBack = onNavigateBack
            )
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> Text(
                    text = state.error ?: "This document could not be read",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp)
                )
                else -> {
                    if (state.isExporting) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(UiTags.TOC_SPLIT_PROGRESS)
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        item { OptionsRow(state, viewModel) }
                        if (needsWarning(state)) {
                            item {
                                WarningBanner(
                                    text = state.issues.joinToString("\n") {
                                        TocSplitModel.formatIssueDescription(it)
                                    }
                                )
                            }
                        }
                        if (state.issues.any { it is TocSplitIssue.NoOutline || it is TocSplitIssue.NoUsableEntries }) {
                            item {
                                TextButton(
                                    onClick = { viewModel.retryWithHeadings() },
                                    modifier = Modifier.testTag(UiTags.TOC_SPLIT_FALLBACK_HEADINGS)
                                ) {
                                    Text("Detect headings instead")
                                }
                            }
                        }
                        items(state.parts, key = { it.id }) { part ->
                            val index = state.parts.indexOf(part)
                            PartRow(
                                part = part,
                                canMergeForward = index >= 0 && index < state.parts.lastIndex,
                                onCheck = { viewModel.togglePart(part.id) },
                                onRename = { renaming = part },
                                onMerge = {
                                    val next = state.parts.getOrNull(index + 1)
                                    if (next != null) viewModel.mergeParts(part.id, next.id)
                                },
                                onSplit = { splitting = part }
                            )
                        }
                        item { Spacer(Modifier.height(12.dp)) }
                    }
                    BottomBar(
                        state = state,
                        onPickFolder = { pickFolder.launch(null) },
                        onShareZip = {
                            val zip = viewModel.exportToZipSync()
                            if (zip != null) shareZip(context, zip)
                        },
                        onCancel = viewModel::cancel
                    )
                }
            }
        }
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            title = { Text("Split by chapters") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
            }
        )
    }

    if (state.showNoOutlineDialog) {
        NoOutlineDialog(
            onFixed = { pages ->
                viewModel.retryWithFixedPages(pages)
            },
            onHeadings = { viewModel.retryWithHeadings() },
            onDismiss = viewModel::dismissNoOutline
        )
    }

    if (state.conflicts.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = viewModel::dismissConflicts,
            modifier = Modifier.testTag(UiTags.TOC_SPLIT_CONFLICT_DIALOG),
            title = { Text("Some files already exist") },
            text = {
                Text(
                    "These files are already in the folder:\n\n" +
                        state.conflicts.joinToString("\n") +
                        "\n\nReplace them with the new parts?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmOverwrite,
                    modifier = Modifier.testTag(UiTags.TOC_SPLIT_CONFLICT_REPLACE)
                ) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConflicts) { Text("Cancel") }
            }
        )
    }

    renaming?.let { part ->
        RenameDialog(
            part = part,
            onDismiss = { renaming = null },
            onRename = { title ->
                viewModel.renamePart(part.id, title)
                renaming = null
            }
        )
    }

    splitting?.let { part ->
        SplitDialog(
            part = part,
            onDismiss = { splitting = null },
            onSplit = { page, first, second ->
                if (viewModel.splitPart(part.id, page, first, second)) splitting = null
            }
        )
    }
}

@Composable
private fun OptionsRow(state: TocSplitUiState, viewModel: TocSplitViewModel) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            text = when (state.source) {
                com.pdfpaper.engine.split.TocSplitSource.HEADINGS ->
                    "Chapters detected from the page headings"
                com.pdfpaper.engine.split.TocSplitSource.OUTLINE ->
                    "Chapters from this document's table of contents"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text("Split at level", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (level in 1..3) {
                FilterChip(
                    selected = state.level == level,
                    onClick = { viewModel.setLevel(level) },
                    label = { Text(if (level == 1) "1 (chapters)" else "$level (sub-levels)") },
                    modifier = Modifier.testTag(UiTags.tocSplitLevelTag(level))
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.duplicateBoundary,
                onCheckedChange = viewModel::setDuplicateBoundary,
                modifier = Modifier.testTag(UiTags.TOC_SPLIT_SHARED_PAGE)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Include the shared page", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Keep the page a chapter starts on at the end of the part before it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = "${state.parts.count { it.checked }} of ${state.parts.size} chapter(s) · " +
                "${state.pageCount} pages",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun WarningBanner(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag(UiTags.TOC_SPLIT_WARNING_BANNER),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun PartRow(
    part: EditableTocSplitPart,
    canMergeForward: Boolean,
    onCheck: () -> Unit,
    onRename: () -> Unit,
    onMerge: () -> Unit,
    onSplit: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = part.checked,
                onCheckedChange = { onCheck() },
                modifier = Modifier.testTag(UiTags.tocSplitPartTag(part.id))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = part.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Pages ${part.pages.first + 1}-${part.pages.last + 1} · " +
                        TocSplitModel.formatEstimate(part.estimateBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = part.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Row(modifier = Modifier.padding(start = 48.dp)) {
            TextButton(onClick = onRename) { Text("Rename") }
            TextButton(onClick = onMerge, enabled = canMergeForward) { Text("Merge forward") }
            TextButton(onClick = onSplit, enabled = part.pages.last > part.pages.first) { Text("Split") }
        }
    }
}

@Composable
private fun BottomBar(
    state: TocSplitUiState,
    onPickFolder: () -> Unit,
    onShareZip: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            state.summary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            if (state.isExporting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Writing ${state.done} of ${state.total}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag(UiTags.TOC_SPLIT_CANCEL)
                    ) { Text("Cancel") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onPickFolder,
                        enabled = state.parts.any { it.checked },
                        modifier = Modifier.weight(1f).testTag(UiTags.TOC_SPLIT_EXPORT_FOLDER)
                    ) { Text("Save to folder") }
                    OutlinedButton(
                        onClick = onShareZip,
                        enabled = state.parts.any { it.checked },
                        modifier = Modifier.testTag(UiTags.TOC_SPLIT_SHARE_ZIP)
                    ) { Text("Share as ZIP") }
                }
            }
        }
    }
}

@Composable
private fun NoOutlineDialog(
    onFixed: (Int) -> Unit,
    onHeadings: () -> Unit,
    onDismiss: () -> Unit
) {
    var pages by remember { mutableStateOf("5") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(UiTags.TOC_SPLIT_NO_OUTLINE_DIALOG),
        title = { Text("No table of contents") },
        text = {
            Column {
                Text(
                    "This document has no outline to split at. You can split " +
                        "every N pages instead, or detect headings on the pages."
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pages,
                    onValueChange = { pages = it.filter { ch -> ch.isDigit() }.take(4) },
                    label = { Text("Pages per part") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onFixed(pages.toIntOrNull() ?: 5) },
                modifier = Modifier.testTag(UiTags.TOC_SPLIT_FALLBACK_FIXED)
            ) { Text("Split every N pages") }
        },
        dismissButton = {
            TextButton(
                onClick = onHeadings,
                modifier = Modifier.testTag(UiTags.TOC_SPLIT_FALLBACK_HEADINGS)
            ) { Text("Detect headings") }
        }
    )
}

@Composable
private fun RenameDialog(
    part: EditableTocSplitPart,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var title by remember(part.id) { mutableStateOf(part.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chapter") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Chapter title") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(title.ifBlank { part.title }) },
                enabled = title.isNotBlank()
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SplitDialog(
    part: EditableTocSplitPart,
    onDismiss: () -> Unit,
    onSplit: (page: Int, firstTitle: String, secondTitle: String) -> Unit
) {
    val firstPage = part.pages.first + 2
    var page by remember(part.id) { mutableStateOf(firstPage.toString()) }
    var first by remember(part.id) { mutableStateOf("${part.title} (1)") }
    var second by remember(part.id) { mutableStateOf("${part.title} (2)") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split \"${part.title}\"") },
        text = {
            Column {
                Text(
                    "The second part starts on this page " +
                        "(pages ${part.pages.first + 1}-${part.pages.last + 1})."
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = page,
                    onValueChange = { page = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text("First page of the second part") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text("First part title") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = second,
                    onValueChange = { second = it },
                    label = { Text("Second part title") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val at = page.toIntOrNull() ?: part.pages.first + 1
                    onSplit(at, first, second)
                },
                enabled = page.toIntOrNull()?.let { it > part.pages.first } == true
            ) { Text("Split") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun needsWarning(state: TocSplitUiState): Boolean = state.issues.any {
    it is TocSplitIssue.OutOfOrder ||
        it is TocSplitIssue.InvalidDestination ||
        it is TocSplitIssue.SamePage ||
        it is TocSplitIssue.EmptyRange
}

/** Shares the ZIP through the app's FileProvider (same pattern as Share PDF). */
private fun shareZip(context: Context, file: File) {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "Share split parts").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}
