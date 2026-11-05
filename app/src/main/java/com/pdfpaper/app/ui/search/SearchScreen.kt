package com.pdfpaper.app.ui.search

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.LocalActivityResultRegistryOwner
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.index.DateFilter
import com.pdfpaper.app.index.IndexStatusFile
import com.pdfpaper.app.index.SearchQueryBuilder
import com.pdfpaper.app.index.SearchResultGroup
import com.pdfpaper.app.index.SnippetHighlighter
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.AppTopBar
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Task T50: search every indexed page on this device. Results are grouped by
 * document, each page shows an FTS4 snippet with the matched words highlighted,
 * and a tap opens the viewer at that page with the search active.
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenResult: (path: String, name: String, pageIndex: Int, query: String) -> Unit,
    onNeedsOcr: (path: String, name: String, pageIndex: Int) -> Unit,
    viewModel: SearchViewModel = viewModel(),
    regexViewModel: RegexViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val regexState by regexViewModel.state.collectAsState()
    val regexSaved by regexViewModel.saved.collectAsState()
    var regexMode by rememberSaveable { mutableStateOf(false) }
    var pendingRegexExport by remember { mutableStateOf<RegexExportPayload?>(null) }
    val context = LocalContext.current
    val regexExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val payload = pendingRegexExport
        pendingRegexExport = null
        if (uri != null && payload != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(payload.text.toByteArray())
                }
            }
        }
    }
    // The tester compiles 300 ms after typing stops (T65); it never runs the
    // whole library, just the first indexed page.
    LaunchedEffect(regexState.pattern, regexState.flags) {
        if (regexState.pattern.isBlank()) return@LaunchedEffect
        delay(350)
        regexViewModel.runTester()
    }
    val registryOwner = LocalActivityResultRegistryOwner.current
    val folderPicker = if (registryOwner == null) {
        null
    } else {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? -> if (uri != null) viewModel.addFolder(uri) }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Search library",
                subtitle = "Local index, no upload",
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag(UiTags.SEARCH_SETTINGS)
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Index settings")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.testTag(UiTags.SEARCH_SCREEN)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.SEARCH_FIELD),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChanged("") }) {
                            Icon(Icons.Outlined.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                label = { Text("Words or \"exact phrase\"") },
                supportingText = {
                    Text("Words are prefixes: budg finds budget. Quotes search a phrase.")
                }
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = regexMode,
                    onClick = { regexMode = !regexMode },
                    label = { Text("Regex") },
                    modifier = Modifier.testTag(UiTags.REGEX_TOGGLE)
                )
            }
            SearchFiltersRow(state = state, viewModel = viewModel)
            Spacer(Modifier.height(8.dp))
            IndexStatusCard(
                state = state,
                onAddFolder = { folderPicker?.launch(null) },
                addFolderEnabled = folderPicker != null,
                onOpenSettings = onOpenSettings,
                viewModel = viewModel
            )
            Spacer(Modifier.height(8.dp))
            if (state.error != null) {
                Text(
                    text = state.error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            when {
                regexMode -> Column(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    RegexPanel(
                        state = regexState,
                        saved = regexSaved,
                        onPatternChange = regexViewModel::setPattern,
                        onFlagsChange = regexViewModel::setFlags,
                        onRun = { regexViewModel.run(state.filters) },
                        onCancel = regexViewModel::cancel,
                        onExample = { regexViewModel.setPattern(it.pattern) },
                        onSavePattern = regexViewModel::savePattern,
                        onApplySaved = regexViewModel::applySaved,
                        onRenameSaved = regexViewModel::renameSaved,
                        onDeleteSaved = regexViewModel::deleteSaved
                    )
                    RegexResults(
                        state = regexState,
                        onActive = regexViewModel::setActive,
                        onRow = { row ->
                            regexViewModel.openRow(row) { path, pageIndex, query, _, _ ->
                                onOpenResult(path, row.displayName, pageIndex, query)
                            }
                        },
                        onUniqueChange = regexViewModel::setUniqueOnly,
                        onExport = {
                            regexViewModel.exportPayload(csv = true)?.let { payload ->
                                pendingRegexExport = payload
                                regexExport.launch(payload.fileName)
                            }
                        },
                        onHighlightAll = null,
                        onRedactAll = null,
                        modifier = Modifier.weight(1f)
                    )
                }
                state.hasQuery -> Results(
                    state = state,
                    onOpenResult = onOpenResult,
                    modifier = Modifier.weight(1f)
                )
                else -> EmptyState(
                    state = state,
                    onAddFolder = { folderPicker?.launch(null) },
                    addFolderEnabled = folderPicker != null,
                    onOpenResult = onOpenResult,
                    onNeedsOcr = onNeedsOcr,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SearchFiltersRow(state: SearchUiState, viewModel: SearchViewModel) {
    var folderMenu by remember { mutableStateOf(false) }
    var dateMenu by remember { mutableStateOf(false) }
    val folderLabel = state.folders.firstOrNull { it.id == state.filters.folderId }
        ?.displayName ?: "All folders"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box {
            FilterChip(
                selected = state.filters.folderId != null,
                onClick = { folderMenu = true },
                label = { Text(folderLabel, maxLines = 1) },
                modifier = Modifier.testTag(UiTags.SEARCH_FILTER_FOLDER)
            )
            DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                DropdownMenuItem(
                    text = { Text("All folders") },
                    onClick = {
                        viewModel.setFolderFilter(null)
                        folderMenu = false
                    }
                )
                for (folder in state.folders) {
                    DropdownMenuItem(
                        text = { Text(folder.displayName) },
                        onClick = {
                            viewModel.setFolderFilter(folder.id)
                            folderMenu = false
                        }
                    )
                }
            }
        }
        Box {
            FilterChip(
                selected = state.filters.date != DateFilter.ANY,
                onClick = { dateMenu = true },
                label = { Text(state.filters.date.label) },
                modifier = Modifier.testTag(UiTags.SEARCH_FILTER_DATE)
            )
            DropdownMenu(expanded = dateMenu, onDismissRequest = { dateMenu = false }) {
                for (filter in DateFilter.entries) {
                    DropdownMenuItem(
                        text = { Text(filter.label) },
                        onClick = {
                            viewModel.setDateFilter(filter)
                            dateMenu = false
                        }
                    )
                }
            }
        }
        FilterChip(
            selected = state.filters.fileNameOnly,
            onClick = { viewModel.setFileNameOnly(!state.filters.fileNameOnly) },
            label = { Text("File name only") },
            modifier = Modifier.testTag(UiTags.SEARCH_FILTER_NAME_ONLY)
        )
    }
}

@Composable
private fun IndexStatusCard(
    state: SearchUiState,
    onAddFolder: () -> Unit,
    addFolderEnabled: Boolean,
    onOpenSettings: () -> Unit,
    viewModel: SearchViewModel
) {
    val context = LocalContext.current
    val stats = state.stats
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.SEARCH_STATUS)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (state.hasFolders) {
                            "${state.folders.size} folder${if (state.folders.size == 1) "" else "s"}"
                        } else {
                            "No folders indexed"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (stats != null) {
                        Text(
                            "${stats.fileCount} files · ${stats.pageCount} pages · " +
                                Formatter.formatShortFileSize(context, stats.databaseBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (state.hasFolders && !state.indexRunning) {
                    TextButton(
                        onClick = viewModel::indexNow,
                        modifier = Modifier.testTag(UiTags.SEARCH_INDEX_NOW)
                    ) {
                        Text("Update")
                    }
                }
            }
            if (state.indexRunning) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = {
                        if (state.indexTotal > 0) {
                            state.indexDone.toFloat() / state.indexTotal
                        } else {
                            0f
                        }
                    }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.indexPhase.ifBlank { "Indexing..." },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = viewModel::pause,
                        modifier = Modifier.testTag(UiTags.SEARCH_PAUSE)
                    ) {
                        Icon(Icons.Outlined.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Pause")
                    }
                    TextButton(
                        onClick = viewModel::cancel,
                        modifier = Modifier.testTag(UiTags.SEARCH_CANCEL)
                    ) {
                        Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Cancel")
                    }
                }
            } else if (state.indexPaused) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Paused",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = viewModel::resume,
                        modifier = Modifier.testTag(UiTags.SEARCH_RESUME)
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Resume")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onAddFolder,
                    enabled = addFolderEnabled,
                    modifier = Modifier.testTag(UiTags.SEARCH_ADD_FOLDER)
                ) {
                    Text(if (state.hasFolders) "Add folder" else "Choose a folder")
                }
                TextButton(onClick = onOpenSettings) {
                    Text("Manage folders")
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    state: SearchUiState,
    onAddFolder: () -> Unit,
    addFolderEnabled: Boolean,
    onOpenResult: (String, String, Int, String) -> Unit,
    onNeedsOcr: (String, String, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!state.hasFolders) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Search your PDFs on this device", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Choose one or more folders. PDFPaper indexes the text of every " +
                                "page locally; file contents and the index never leave the device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onAddFolder, enabled = addFolderEnabled) {
                            Text("Choose a folder")
                        }
                    }
                }
            }
        }
        if (state.locked.isNotEmpty()) {
            item {
                SectionHeader("Locked PDFs", state.locked.size)
            }
            items(state.locked, key = { "locked-${it.fileId}" }) { file ->
                StatusRow(
                    file = file,
                    icon = Icons.Outlined.Lock,
                    detail = "Encrypted. Unlock it in the viewer, then index it again.",
                    tag = UiTags.SEARCH_LOCKED,
                    onClick = { onOpenResult(file.documentUri, file.displayName, 0, "") }
                )
            }
        }
        if (state.needsOcr.isNotEmpty()) {
            item {
                SectionHeader("Needs OCR", state.needsOcr.size)
            }
            items(state.needsOcr, key = { "ocr-${it.fileId}" }) { file ->
                StatusRow(
                    file = file,
                    icon = Icons.Outlined.DocumentScanner,
                    detail = "${file.pagesWithoutText} of ${file.pageCount} pages have no text layer.",
                    tag = UiTags.SEARCH_NEEDS_OCR,
                    onClick = { onNeedsOcr(file.documentUri, file.displayName, 0) }
                )
            }
        }
        if (state.errors.isNotEmpty()) {
            item {
                SectionHeader("Could not index", state.errors.size)
            }
            items(state.errors, key = { "error-${it.fileId}" }) { file ->
                StatusRow(
                    file = file,
                    icon = Icons.Outlined.Refresh,
                    detail = file.error ?: "Open the document to check it.",
                    tag = null,
                    onClick = { onOpenResult(file.documentUri, file.displayName, 0, "") }
                )
            }
        }
        if (state.hasFolders && state.locked.isEmpty() && state.needsOcr.isEmpty() &&
            state.errors.isEmpty()
        ) {
            item {
                Text(
                    "Type above to search every indexed page.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp)
                )
            }
        }
    }
}

@Composable
private fun Results(
    state: SearchUiState,
    onOpenResult: (String, String, Int, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewerQuery = SearchQueryBuilder.parse(state.query).plainTerms.firstOrNull()
        ?: state.query
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag(UiTags.SEARCH_RESULTS),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (state.searching) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        if (!state.searching && state.groups.isEmpty()) {
            item {
                Text(
                    "No matches in the indexed text.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp).testTag(UiTags.SEARCH_EMPTY)
                )
            }
        }
        items(state.groups, key = { "group-${it.fileId}" }) { group ->
            ResultGroup(
                group = group,
                fileNameOnly = state.filters.fileNameOnly,
                viewerQuery = viewerQuery,
                onOpenResult = onOpenResult
            )
        }
    }
}

@Composable
private fun ResultGroup(
    group: SearchResultGroup,
    fileNameOnly: Boolean,
    viewerQuery: String,
    onOpenResult: (String, String, Int, String) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                group.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${group.folderName} · ${group.relativePath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            for (hit in group.hits) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onOpenResult(
                                group.documentUri,
                                group.displayName,
                                if (fileNameOnly) 0 else hit.pageIndex,
                                if (fileNameOnly) "" else viewerQuery
                            )
                        }
                        .testTag(UiTags.searchHit(group.fileId, hit.pageIndex))
                        .padding(vertical = 6.dp)
                ) {
                    Text(
                        if (fileNameOnly) "File name" else "Page ${hit.pageIndex + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(72.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = highlight(hit.markedSnippet),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun highlight(marked: String): AnnotatedString {
    val parts = SnippetHighlighter.parse(marked)
    return buildAnnotatedString {
        for (part in parts) {
            if (part.highlighted) {
                withStyle(
                    SpanStyle(
                        background = Color(0xFFFFE082),
                        fontWeight = FontWeight.SemiBold
                    )
                ) {
                    append(part.text)
                }
            } else {
                append(part.text)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        "$title ($count)",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun StatusRow(
    file: IndexStatusFile,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    detail: String,
    tag: String?,
    onClick: () -> Unit
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(if (tag == null) Modifier else Modifier.testTag(tag))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (file.status.name == "NEEDS_OCR") {
                TextButton(
                    onClick = onClick,
                    modifier = Modifier.testTag(UiTags.SEARCH_OCR_BUTTON)
                ) {
                    Text("Make searchable")
                }
            }
        }
    }
}

/** "Jan 2, 2026" in the device locale; used by the settings folder list. */
fun indexDate(millis: Long): String =
    if (millis <= 0) "unknown" else DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
        .format(Date(millis))
