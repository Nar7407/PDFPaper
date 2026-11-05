package com.pdfpaper.app.ui.annot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Note
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StrikethroughS
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.annot.AnnotationColors
import com.pdfpaper.app.annot.AnnotationDateFilter
import com.pdfpaper.app.annot.AnnotationExportFormat
import com.pdfpaper.app.annot.AnnotationExportOptions
import com.pdfpaper.app.annot.AnnotationGrouping
import com.pdfpaper.app.annot.AnnotationGroupUi
import com.pdfpaper.app.annot.AnnotationItem
import com.pdfpaper.app.annot.AnnotationKind
import com.pdfpaper.app.annot.AnnotationStats
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.AppTopBar
import java.util.Date
import java.util.Locale

/**
 * T56: the Annotations hub. Every highlight, note, ink mark, stamp and app
 * bookmark of every indexed document in one list, filtered by kind, colour,
 * document or date and searchable by its quoted text or note. Tapping a row
 * opens the file at that page with the annotation highlighted; the row menu
 * copies the text or edits and deletes the mark through the T55 save path.
 *
 * The list comes from the local Room index, so opening the tab never reads a
 * PDF and nothing leaves the device.
 */
@Composable
fun AnnotationsScreen(
    onBack: (() -> Unit)? = null,
    onOpenAnnotation: (AnnotationOpenTarget) -> Unit = {},
    viewModel: AnnotationsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var showFilters by remember { mutableStateOf(false) }
    var showGrouping by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<AnnotationItem?>(null) }
    var colorTarget by remember { mutableStateOf<AnnotationItem?>(null) }
    var tagTarget by remember { mutableStateOf<AnnotationItem?>(null) }
    var deleteTarget by remember { mutableStateOf<AnnotationItem?>(null) }
    var pendingExport by remember { mutableStateOf<AnnotationExportPayload?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri != null && payload != null) viewModel.saveExport(uri, payload)
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Annotations",
                subtitle = countsLabel(state.stats),
                onBack = onBack,
                titleTag = UiTags.ANNOT_SCREEN,
                actions = {
                    IconButton(onClick = { showFilters = !showFilters }) {
                        Icon(Icons.Outlined.FilterList, contentDescription = "Filters")
                    }
                    IconButton(onClick = { showGrouping = true }) {
                        Icon(Icons.Outlined.SelectAll, contentDescription = "Grouping")
                    }
                    IconButton(
                        onClick = {
                            if (state.selecting) viewModel.cancelSelecting()
                            else viewModel.startSelecting()
                        }
                    ) {
                        Icon(
                            if (state.selecting) Icons.Outlined.Close else Icons.Outlined.Check,
                            contentDescription = if (state.selecting) "Stop selecting" else "Select"
                        )
                    }
                    IconButton(
                        onClick = { showExport = true },
                        enabled = state.items.isNotEmpty()
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = "Export")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.filter.query,
                onValueChange = viewModel::onQueryChange,
                singleLine = true,
                placeholder = { Text("Search quoted text and notes") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.filter.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Outlined.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag(UiTags.ANNOT_SEARCH)
            )

            if (showFilters) {
                FilterPanel(
                    state = state,
                    onKind = viewModel::toggleKind,
                    onColor = viewModel::setColor,
                    onDocument = viewModel::setDocument,
                    onDate = viewModel::setDate,
                    onClear = viewModel::clearFilters
                )
            }

            if (state.indexing.running) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .testTag(UiTags.ANNOT_PROGRESS)
                ) {
                    Text(
                        text = state.indexing.label.ifBlank { "Indexing" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val total = state.indexing.total
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (state.indexing.done.toFloat() / total).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }

            if (state.selecting) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${state.selection.size} selected",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = viewModel::selectAllVisible) { Text("Select all") }
                    TextButton(
                        onClick = { showExport = true },
                        enabled = state.selection.isNotEmpty()
                    ) { Text("Export") }
                    TextButton(
                        onClick = { viewModel.deleteSelection() },
                        enabled = state.selection.isNotEmpty()
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            state.message?.let { message -> NoticeBar(message, isError = false) }
            state.error?.let { message -> NoticeBar(message, isError = true) }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp))
                }
                state.isEmpty -> EmptyHub(hasIndex = state.stats.documents > 0)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 8.dp,
                        end = 8.dp,
                        top = 4.dp,
                        bottom = 24.dp
                    )
                ) {
                    for (group in state.groups) {
                        item(key = "header-${group.title}") {
                            GroupHeader(group)
                        }
                        items(group.items, key = { item -> item.id }) { item ->
                            AnnotationRow(
                                item = item,
                                selecting = state.selecting,
                                selected = item.id in state.selection,
                                onClick = {
                                    if (state.selecting) {
                                        viewModel.toggleSelection(item.id)
                                    } else {
                                        onOpenAnnotation(
                                            AnnotationOpenTarget(
                                                documentUri = item.documentUri,
                                                displayName = item.displayName,
                                                pageIndex = item.pageIndex,
                                                bounds = item.bounds
                                            )
                                        )
                                    }
                                },
                                onCopy = {
                                    copyToClipboard(context, viewModel.copyText(item))
                                    viewModel.dismissMessage()
                                },
                                onEdit = { editTarget = item },
                                onColor = { colorTarget = item },
                                onTag = { tagTarget = item },
                                onDelete = { deleteTarget = item }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showGrouping) {
        AlertDialog(
            onDismissRequest = { showGrouping = false },
            title = { Text("Group annotations") },
            text = {
                Column {
                    for (grouping in AnnotationGrouping.values()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setGrouping(grouping)
                                    showGrouping = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = state.grouping == grouping,
                                onClick = {
                                    viewModel.setGrouping(grouping)
                                    showGrouping = false
                                }
                            )
                            Text(grouping.label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGrouping = false }) { Text("Close") }
            }
        )
    }

    editTarget?.let { item ->
        TextEditDialog(
            title = if (item.kind.isBookmark) "Bookmark label" else "Edit note",
            initial = if (item.kind.isBookmark) item.label.orEmpty() else item.noteText.orEmpty(),
            fieldTag = UiTags.ANNOT_NOTE_FIELD,
            saveTag = UiTags.ANNOT_NOTE_SAVE,
            onDismiss = { editTarget = null },
            onSave = { text ->
                viewModel.saveNote(item, text)
                editTarget = null
            }
        )
    }

    tagTarget?.let { item ->
        TextEditDialog(
            title = "Tag",
            initial = item.tag.orEmpty(),
            fieldTag = UiTags.ANNOT_TAG_FIELD,
            saveTag = UiTags.ANNOT_TAG_SAVE,
            onDismiss = { tagTarget = null },
            onSave = { text ->
                viewModel.setTag(item, text)
                tagTarget = null
            }
        )
    }

    colorTarget?.let { item ->
        ColorDialog(
            current = item.color,
            onDismiss = { colorTarget = null },
            onPick = { hex ->
                viewModel.setColor(item, hex)
                colorTarget = null
            }
        )
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete annotation?") },
            text = {
                Text(
                    if (item.kind.isBookmark) {
                        "The bookmark is removed from the hub and, when bookmarks are written " +
                            "into the outline, from the PDF."
                    } else {
                        "The ${item.kind.label.lowercase(Locale.ROOT)} on page " +
                            "${item.pageIndex + 1} of ${item.displayName} is removed from the " +
                            "file itself. A pre-save version is kept, so it can be restored."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(item)
                        deleteTarget = null
                    },
                    modifier = Modifier.testTag(UiTags.ANNOT_DELETE_CONFIRM)
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (showExport) {
        ExportDialog(
            state = state,
            onDismiss = { showExport = false },
            onShare = { scope, options ->
                viewModel.buildExport(scope, options)?.let { payload ->
                    viewModel.shareExport(payload)
                    showExport = false
                }
            },
            onSave = { scope, options ->
                viewModel.buildExport(scope, options)?.let { payload ->
                    pendingExport = payload
                    saveLauncher.launch(payload.fileName)
                }
            }
        )
    }
}

/** Where the hub asks the app to open a document. */
data class AnnotationOpenTarget(
    val documentUri: String,
    val displayName: String,
    val pageIndex: Int,
    val bounds: String?
)

@Composable
private fun GroupHeader(group: AnnotationGroupUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = group.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = group.items.size.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AnnotationRow(
    item: AnnotationItem,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onColor: () -> Unit,
    onTag: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .testTag(UiTags.annotItem(item.id))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .heightIn(min = 64.dp)
                    .background(Color(AnnotationColors.argb(item.color)))
            )
            Column(modifier = Modifier.weight(1f).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = iconFor(item.kind),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = item.kind.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Page ${item.pageIndex + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    item.tag?.takeIf { it.isNotBlank() }?.let { tag ->
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "#$tag",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = dayLabel(item.time),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val preview = item.text.replace('\n', ' ').trim()
                if (preview.isNotEmpty()) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                val detail = listOfNotNull(
                    item.author?.takeIf { it.isNotBlank() },
                    AnnotationColors.labelFor(item.color)
                ).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            } else {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Actions")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Copy text") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = {
                                menu = false
                                onCopy()
                            },
                            modifier = Modifier.testTag(UiTags.ANNOT_MENU_COPY)
                        )
                        DropdownMenuItem(
                            text = {
                                Text(if (item.kind.isBookmark) "Edit label" else "Edit note")
                            },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                menu = false
                                onEdit()
                            },
                            modifier = Modifier.testTag(UiTags.ANNOT_MENU_EDIT)
                        )
                        DropdownMenuItem(
                            text = { Text("Change colour") },
                            leadingIcon = {
                                Icon(Icons.Outlined.FormatColorFill, contentDescription = null)
                            },
                            onClick = {
                                menu = false
                                onColor()
                            },
                            modifier = Modifier.testTag(UiTags.ANNOT_MENU_COLOR)
                        )
                        DropdownMenuItem(
                            text = { Text("Add tag") },
                            leadingIcon = { Icon(Icons.Outlined.Label, contentDescription = null) },
                            onClick = {
                                menu = false
                                onTag()
                            },
                            modifier = Modifier.testTag(UiTags.ANNOT_MENU_TAG)
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                            modifier = Modifier.testTag(UiTags.ANNOT_MENU_DELETE)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterPanel(
    state: AnnotationsUiState,
    onKind: (AnnotationKind) -> Unit,
    onColor: (String?) -> Unit,
    onDocument: (Long?) -> Unit,
    onDate: (AnnotationDateFilter) -> Unit,
    onClear: () -> Unit
) {
    var documentsOpen by remember { mutableStateOf(false) }
    var dateOpen by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth().testTag(UiTags.ANNOT_FILTERS)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (kind in AnnotationKind.values()) {
                    FilterChip(
                        selected = kind in state.filter.kinds,
                        onClick = { onKind(kind) },
                        label = { Text(kind.label) },
                        modifier = Modifier.testTag(UiTags.annotKind(kind.name))
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (swatch in AnnotationColors.all) {
                    val selected = swatch.hex.equals(state.filter.color, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(if (selected) 34.dp else 28.dp)
                            .background(Color(AnnotationColors.argb(swatch.hex)), CircleShape)
                            .clickable { onColor(if (selected) null else swatch.hex) }
                            .testTag(UiTags.annotColor(swatch.hex))
                    )
                }
                Spacer(Modifier.width(4.dp))
                Box {
                    OutlinedButton(
                        onClick = { documentsOpen = true },
                        modifier = Modifier.testTag(UiTags.ANNOT_FILTER_DOCUMENT)
                    ) {
                        val name = state.filter.documentId?.let { id ->
                            state.documents.firstOrNull { it.id == id }?.displayName
                        }
                        Text(name ?: "Any document", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = documentsOpen, onDismissRequest = { documentsOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Any document") },
                            onClick = {
                                onDocument(null)
                                documentsOpen = false
                            }
                        )
                        for (document in state.documents) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        document.displayName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                onClick = {
                                    onDocument(document.id)
                                    documentsOpen = false
                                }
                            )
                        }
                    }
                }
                Box {
                    OutlinedButton(
                        onClick = { dateOpen = true },
                        modifier = Modifier.testTag(UiTags.ANNOT_FILTER_DATE)
                    ) { Text(state.filter.date.label) }
                    DropdownMenu(expanded = dateOpen, onDismissRequest = { dateOpen = false }) {
                        for (date in AnnotationDateFilter.values()) {
                            DropdownMenuItem(
                                text = { Text(date.label) },
                                onClick = {
                                    onDate(date)
                                    dateOpen = false
                                }
                            )
                        }
                    }
                }
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.testTag(UiTags.ANNOT_FILTER_CLEAR)
                ) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun EmptyHub(hasIndex: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).testTag(UiTags.ANNOT_EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.Highlight,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text("No annotations yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (hasIndex) {
                "Nothing matches these filters. Highlights, notes, ink, stamps and bookmarks of " +
                    "your indexed folders show up here."
            } else {
                "Highlight or note something in a PDF, add a bookmark, or index a folder in " +
                    "Search. Everything stays on this device."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NoticeBar(message: String, isError: Boolean) {
    Surface(
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun TextEditDialog(
    title: String,
    initial: String,
    fieldTag: String,
    saveTag: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag(fieldTag)
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                modifier = Modifier.testTag(saveTag)
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ColorDialog(current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Colour") },
        text = {
            Column {
                for (swatch in AnnotationColors.all) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(swatch.hex) }
                            .padding(vertical = 8.dp)
                            .testTag(UiTags.annotColor(swatch.hex)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(Color(AnnotationColors.argb(swatch.hex)), CircleShape)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(swatch.label)
                        Spacer(Modifier.weight(1f))
                        if (swatch.hex.equals(current, ignoreCase = true)) {
                            Icon(Icons.Outlined.Check, contentDescription = null)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ExportDialog(
    state: AnnotationsUiState,
    onDismiss: () -> Unit,
    onShare: (AnnotationExportScope, AnnotationExportOptions) -> Unit,
    onSave: (AnnotationExportScope, AnnotationExportOptions) -> Unit
) {
    var scope by remember {
        mutableStateOf(
            if (state.selection.isNotEmpty()) AnnotationExportScope.SELECTED
            else AnnotationExportScope.EVERYTHING
        )
    }
    var format by remember { mutableStateOf(AnnotationExportFormat.MARKDOWN) }
    var obsidian by remember { mutableStateOf(false) }
    var includeQuoted by remember { mutableStateOf(true) }
    var includeNotes by remember { mutableStateOf(true) }

    val options = AnnotationExportOptions(
        format = format,
        obsidian = obsidian && format == AnnotationExportFormat.MARKDOWN,
        includeQuotedText = includeQuoted,
        includeNotes = includeNotes
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export annotations") },
        text = {
            Column {
                Text("What to export", style = MaterialTheme.typography.labelLarge)
                for (choice in AnnotationExportScope.values()) {
                    val enabled = choice != AnnotationExportScope.SELECTED ||
                        state.selection.isNotEmpty()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { scope = choice }
                            .padding(vertical = 4.dp)
                            .testTag(UiTags.annotExportScope(choice.name)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = scope == choice,
                            onClick = { scope = choice },
                            enabled = enabled
                        )
                        Text(
                            when (choice) {
                                AnnotationExportScope.SELECTED ->
                                    "Selected (${state.selection.size})"
                                AnnotationExportScope.DOCUMENT -> "This document"
                                AnnotationExportScope.EVERYTHING ->
                                    "Everything (${state.items.size})"
                            }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Format", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in AnnotationExportFormat.values()) {
                        FilterChip(
                            selected = format == choice,
                            onClick = { format = choice },
                            label = { Text(choice.label) },
                            modifier = Modifier.testTag(UiTags.annotExportFormat(choice.name))
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = obsidian,
                        onCheckedChange = { obsidian = it },
                        enabled = format == AnnotationExportFormat.MARKDOWN,
                        modifier = Modifier.testTag(UiTags.ANNOT_EXPORT_OBSIDIAN)
                    )
                    Text(
                        "Obsidian note (frontmatter and page links)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeQuoted, onCheckedChange = { includeQuoted = it })
                    Text("Include quoted text", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeNotes, onCheckedChange = { includeNotes = it })
                    Text("Include notes", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onShare(scope, options) },
                modifier = Modifier.testTag(UiTags.ANNOT_EXPORT_SHARE)
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(
                    onClick = { onSave(scope, options) },
                    modifier = Modifier.testTag(UiTags.ANNOT_EXPORT_SAVE)
                ) { Text("Save as file") }
            }
        }
    )
}

private fun iconFor(kind: AnnotationKind) = when (kind) {
    AnnotationKind.HIGHLIGHT -> Icons.Outlined.Highlight
    AnnotationKind.UNDERLINE -> Icons.Outlined.FormatUnderlined
    AnnotationKind.STRIKEOUT -> Icons.Outlined.StrikethroughS
    AnnotationKind.NOTE -> Icons.Outlined.Note
    AnnotationKind.INK -> Icons.Outlined.Draw
    AnnotationKind.STAMP -> Icons.Outlined.FormatColorFill
    AnnotationKind.FREE_TEXT -> Icons.Outlined.TextFields
    AnnotationKind.BOOKMARK -> Icons.Outlined.BookmarkBorder
}

private fun countsLabel(stats: AnnotationStats): String {
    if (stats.annotations == 0) return "Local index · nothing indexed yet"
    val documents = if (stats.documents == 1) "1 document" else "${stats.documents} documents"
    val annotations = if (stats.annotations == 1) "1 annotation" else "${stats.annotations} annotations"
    val bookmarks = if (stats.bookmarks > 0) {
        if (stats.bookmarks == 1) " · 1 bookmark" else " · ${stats.bookmarks} bookmarks"
    } else {
        ""
    }
    return "$annotations · $documents$bookmarks"
}

private fun dayLabel(millis: Long): String =
    DateFormat.format("d MMM", Date(millis)).toString()

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    runCatching { manager.setPrimaryClip(ClipData.newPlainText("Annotation", text)) }
}
