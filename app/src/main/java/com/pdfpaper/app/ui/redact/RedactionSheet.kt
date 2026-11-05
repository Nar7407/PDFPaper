package com.pdfpaper.app.ui.redact

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.engine.graphics.PdfColor
import kotlin.math.roundToInt

/** The four panels of the redact sheet, chosen by the tool in the registry. */
enum class RedactionPanel {
    MARKS,
    PAGES,
    FIND,
    PROPERTIES
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedactionSheet(
    state: RedactionUiState,
    panel: RedactionPanel,
    selectionText: String?,
    pageCount: Int,
    onDismiss: () -> Unit,
    onPanelChange: (RedactionPanel) -> Unit,
    onToolChange: (RedactionTool) -> Unit,
    onMarkSelection: () -> Unit,
    onFindModeChange: (RedactionFindMode) -> Unit,
    onFindQueryChange: (String) -> Unit,
    onFind: () -> Unit,
    onToggleFindMatch: (Int) -> Unit,
    onToggleAllFindMatches: () -> Unit,
    onMarkFound: () -> Unit,
    onJumpToPage: (Int) -> Unit,
    onPageToggle: (Int) -> Unit,
    onSelectAllPages: () -> Unit,
    onClearPageSelection: () -> Unit,
    onMarkPages: () -> Unit,
    onPropertiesChange: (RedactionProperties) -> Unit,
    onApplyPropertiesToMarks: () -> Unit,
    onRemoveMark: (Long) -> Unit,
    onUpdateMark: (RedactionMarkUi) -> Unit,
    onClearMarks: () -> Unit,
    onMarkOnPage: () -> Unit,
    onApply: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editingMark by remember { mutableStateOf<RedactionMarkUi?>(null) }

    ModalBottomSheet(
        onDismissRequest = { if (!state.busy) onDismiss() },
        sheetState = sheetState,
        modifier = Modifier.testTag(UiTags.REDACT_SHEET)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
        ) {
            SheetHeader(
                panel = panel,
                onDismiss = onDismiss,
                onPanelChange = onPanelChange
            )
            Spacer(Modifier.height(14.dp))

            when (panel) {
                RedactionPanel.MARKS -> MarksPanel(
                    state = state,
                    selectionText = selectionText,
                    onToolChange = onToolChange,
                    onMarkSelection = onMarkSelection,
                    onMarkOnPage = onMarkOnPage,
                    onRemoveMark = onRemoveMark,
                    onEditMark = { editingMark = it },
                    onClearMarks = onClearMarks
                )
                RedactionPanel.PAGES -> PagesPanel(
                    state = state,
                    pageCount = pageCount,
                    onPageToggle = onPageToggle,
                    onSelectAllPages = onSelectAllPages,
                    onClearPageSelection = onClearPageSelection,
                    onMarkPages = onMarkPages,
                    onJumpToPage = onJumpToPage
                )
                RedactionPanel.FIND -> FindPanel(
                    state = state,
                    onFindModeChange = onFindModeChange,
                    onFindQueryChange = onFindQueryChange,
                    onFind = onFind,
                    onToggleMatch = onToggleFindMatch,
                    onToggleAll = onToggleAllFindMatches,
                    onMarkFound = onMarkFound,
                    onJumpToPage = onJumpToPage
                )
                RedactionPanel.PROPERTIES -> PropertiesPanel(
                    state = state,
                    onPropertiesChange = onPropertiesChange,
                    onApplyToMarks = onApplyPropertiesToMarks,
                    onMarkOnPage = onMarkOnPage
                )
            }

            HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = markCountLabel(state.marks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onApply,
                    enabled = state.marks.isNotEmpty() && !state.busy,
                    modifier = Modifier.testTag(UiTags.REDACT_APPLY)
                ) {
                    Text("Apply redactions")
                }
            }
            if (state.marks.isNotEmpty()) {
                Text(
                    "Applying permanently rewrites the document. The original stays unchanged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }

    val editing = editingMark
    if (editing != null) {
        RedactionMarkEditDialog(
            mark = editing,
            onDismiss = { editingMark = null },
            onSave = {
                onUpdateMark(it)
                editingMark = null
            }
        )
    }
}

@Composable
private fun SheetHeader(
    panel: RedactionPanel,
    onDismiss: () -> Unit,
    onPanelChange: (RedactionPanel) -> Unit
) {
    val title = when (panel) {
        RedactionPanel.MARKS -> "Redact"
        RedactionPanel.PAGES -> "Redact pages"
        RedactionPanel.FIND -> "Find text and redact"
        RedactionPanel.PROPERTIES -> "Redaction properties"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Permanent removal, not a black box drawn on top.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "Close redaction panel")
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PanelChip("Marks", panel == RedactionPanel.MARKS) { onPanelChange(RedactionPanel.MARKS) }
        PanelChip("Pages", panel == RedactionPanel.PAGES) { onPanelChange(RedactionPanel.PAGES) }
        PanelChip("Find", panel == RedactionPanel.FIND) { onPanelChange(RedactionPanel.FIND) }
        PanelChip("Properties", panel == RedactionPanel.PROPERTIES) {
            onPanelChange(RedactionPanel.PROPERTIES)
        }
    }
}

@Composable
private fun PanelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
private fun MarksPanel(
    state: RedactionUiState,
    selectionText: String?,
    onToolChange: (RedactionTool) -> Unit,
    onMarkSelection: () -> Unit,
    onMarkOnPage: () -> Unit,
    onRemoveMark: (Long) -> Unit,
    onEditMark: (RedactionMarkUi) -> Unit,
    onClearMarks: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Choose what to mark",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                state.tool.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp)
            )
            Row(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RedactionTool.entries.forEach { tool ->
                    FilterChip(
                        selected = state.tool == tool,
                        onClick = { onToolChange(tool) },
                        label = { Text(tool.label) },
                        modifier = Modifier.testTag(UiTags.redactTool(tool.name.lowercase()))
                    )
                }
            }
        }
    }

    val selected = selectionText?.trim().orEmpty()
    if (state.tool == RedactionTool.TEXT && selected.isNotEmpty()) {
        OutlinedCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Selected text",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "\u201c${selected.take(180)}\u201d",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Button(
                    onClick = onMarkSelection,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .testTag(UiTags.REDACT_MARK_SELECTION)
                ) {
                    Text("Redact selection")
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onMarkOnPage,
            modifier = Modifier.weight(1f)
        ) {
            Text("Mark on page")
        }
        if (state.marks.isNotEmpty()) {
            TextButton(onClick = onClearMarks) { Text("Clear all") }
        }
    }

    Text(
        "Marks",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
    )
    if (state.marks.isEmpty()) {
        Text(
            "No marks yet. Draw a box, select text or tap an image on the page.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 10.dp)
        )
    } else {
        MarkList(
            marks = state.marks,
            onRemove = onRemoveMark,
            onEdit = onEditMark
        )
    }
}

@Composable
private fun MarkList(
    marks: List<RedactionMarkUi>,
    onRemove: (Long) -> Unit,
    onEdit: (RedactionMarkUi) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .testTag(UiTags.REDACT_MARKS)
            .verticalScroll(rememberScrollState())
    ) {
        marks.forEachIndexed { index, mark ->
            if (index > 0) HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(composeColor(mark.color), CircleShape)
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(
                        text = mark.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Page ${mark.pageIndex + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { onEdit(mark) }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "Edit mark on page ${mark.pageIndex + 1}"
                    )
                }
                IconButton(onClick = { onRemove(mark.id) }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Remove mark on page ${mark.pageIndex + 1}"
                    )
                }
            }
        }
    }
}

@Composable
private fun PagesPanel(
    state: RedactionUiState,
    pageCount: Int,
    onPageToggle: (Int) -> Unit,
    onSelectAllPages: () -> Unit,
    onClearPageSelection: () -> Unit,
    onMarkPages: () -> Unit,
    onJumpToPage: (Int) -> Unit
) {
    Text(
        "Cover whole pages",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        "Every item on a selected page is removed and replaced with the cover. " +
            "The page stays in the document.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 3.dp)
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onSelectAllPages, enabled = pageCount > 0) { Text("Select all") }
        TextButton(
            onClick = onClearPageSelection,
            enabled = state.pageSelection.isNotEmpty()
        ) { Text("Clear") }
        Spacer(Modifier.weight(1f))
        Text(
            "${state.pageSelection.size} selected",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        items((0 until pageCount).toList(), key = { it }) { pageIndex ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.redactPage(pageIndex))
                    .clickable { onPageToggle(pageIndex) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = pageIndex in state.pageSelection,
                    onCheckedChange = { onPageToggle(pageIndex) }
                )
                Column(Modifier.weight(1f)) {
                    Text("Page ${pageIndex + 1}", style = MaterialTheme.typography.bodyMedium)
                    if (state.marks.any { it.pageIndex == pageIndex && it.type == com.pdfpaper.engine.redact.RedactionMarkType.PAGE }) {
                        Text(
                            "Whole-page mark added",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = { onJumpToPage(pageIndex) }) { Text("View") }
            }
        }
    }
    Button(
        onClick = onMarkPages,
        enabled = state.pageSelection.isNotEmpty(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .testTag(UiTags.REDACT_MARK_PAGES)
    ) {
        Text("Mark selected pages")
    }
}

@Composable
private fun FindPanel(
    state: RedactionUiState,
    onFindModeChange: (RedactionFindMode) -> Unit,
    onFindQueryChange: (String) -> Unit,
    onFind: () -> Unit,
    onToggleMatch: (Int) -> Unit,
    onToggleAll: () -> Unit,
    onMarkFound: () -> Unit,
    onJumpToPage: (Int) -> Unit
) {
    val find = state.find
    Text(
        "Find private text",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    Row(
        modifier = Modifier
            .padding(top = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        RedactionFindMode.entries.forEach { mode ->
            FilterChip(
                selected = find.mode == mode,
                onClick = { onFindModeChange(mode) },
                label = { Text(mode.label) }
            )
        }
    }
    if (find.mode == RedactionFindMode.REGEX) {
        OutlinedTextField(
            value = find.query,
            onValueChange = onFindQueryChange,
            singleLine = true,
            label = { Text("Regular expression") },
            placeholder = { Text("e.g. INV-\\d{4}") },
            supportingText = { Text("Case-insensitive. Invalid patterns show an error.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    } else {
        Text(
            "Searches every page for ${find.mode.label.lowercase()} patterns.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    Button(
        onClick = onFind,
        enabled = !find.running && (find.mode != RedactionFindMode.REGEX || find.query.isNotBlank()),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .testTag(UiTags.REDACT_FIND)
    ) {
        if (find.running) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        } else {
            Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(if (find.running) "Searching…" else "Find matches")
    }
    find.error?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    if (find.searched && !find.running && find.error == null) {
        if (find.matches.isEmpty()) {
            Text(
                "No matches found.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${find.matches.size} match${if (find.matches.size == 1) "" else "es"}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onToggleAll) {
                    Text(if (find.allChecked) "Uncheck all" else "Check all")
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag(UiTags.REDACT_PANEL_FIND)
            ) {
                find.matches.forEachIndexed { index, match ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(UiTags.redactFindMatch(index))
                            .clickable { onToggleMatch(index) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = index in find.checked,
                            onCheckedChange = { onToggleMatch(index) }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                match.text,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Page ${match.pageIndex + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { onJumpToPage(match.pageIndex) }) { Text("View") }
                    }
                }
            }
            Button(
                onClick = onMarkFound,
                enabled = find.checked.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .testTag(UiTags.REDACT_FIND_MARK)
            ) {
                Icon(Icons.Outlined.FindReplace, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Mark selected (${find.checked.size})")
            }
        }
    }
}

@Composable
private fun PropertiesPanel(
    state: RedactionUiState,
    onPropertiesChange: (RedactionProperties) -> Unit,
    onApplyToMarks: () -> Unit,
    onMarkOnPage: () -> Unit
) {
    Text(
        "How marked areas look",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    RedactionPropertyFields(
        properties = state.properties,
        onPropertiesChange = onPropertiesChange,
        modifier = Modifier.padding(top = 8.dp)
    )
    if (state.marks.isNotEmpty()) {
        OutlinedButton(
            onClick = onApplyToMarks,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        ) {
            Text("Use these settings on all ${state.marks.size} marks")
        }
    }
    Text(
        "New marks use these settings. Existing marks keep their own unless you apply them above.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
    OutlinedButton(
        onClick = onMarkOnPage,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
    ) {
        Text("Back to page")
    }
}

@Composable
fun RedactionPropertyFields(
    properties: RedactionProperties,
    onPropertiesChange: (RedactionProperties) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text("Cover color", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            redactionColorOptions.forEach { option ->
                val selected = option.color == properties.coverColor
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        onClick = {
                            onPropertiesChange(properties.copy(coverColor = option.color))
                        },
                        shape = CircleShape,
                        color = composeColor(option.color),
                        border = BorderStroke(
                            if (selected) 3.dp else 1.dp,
                            if (selected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outline
                            }
                        ),
                        modifier = Modifier.size(34.dp)
                    ) {}
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }

        OutlinedTextField(
            value = properties.overlayText,
            onValueChange = {
                onPropertiesChange(properties.copy(overlayText = it.take(48)))
            },
            singleLine = true,
            label = { Text("Overlay text") },
            placeholder = { Text("REDACTED") },
            supportingText = { Text("Leave empty for a plain cover.") },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Label size", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                "${properties.fontSize.roundToInt()} pt",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = properties.fontSize,
            onValueChange = { onPropertiesChange(properties.copy(fontSize = it)) },
            valueRange = 8f..32f,
            steps = 5,
            modifier = Modifier.fillMaxWidth()
        )
        Surface(
            color = composeColor(properties.coverColor),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = properties.overlayText.ifBlank { " " },
                    color = if (properties.coverColor.isDark()) Color.White else Color.Black,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (properties.fontSize.coerceIn(8f, 32f) * 1.1f).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            "The PDF engine fits the label inside each mark. The size here previews the on-page label.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}

@Composable
private fun RedactionMarkEditDialog(
    mark: RedactionMarkUi,
    onDismiss: () -> Unit,
    onSave: (RedactionMarkUi) -> Unit
) {
    var overlay by remember(mark.id) { mutableStateOf(mark.overlayText.orEmpty()) }
    var color by remember(mark.id) { mutableStateOf(mark.color) }
    var fontSize by remember(mark.id) { mutableStateOf(mark.fontSize) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit mark") },
        text = {
            Column {
                Text(
                    "Page ${mark.pageIndex + 1} \u00b7 ${mark.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                RedactionPropertyFields(
                    properties = RedactionProperties(color, overlay, fontSize),
                    onPropertiesChange = {
                        color = it.coverColor
                        overlay = it.overlayText
                        fontSize = it.fontSize
                    },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        mark.copy(
                            overlayText = overlay.ifBlank { null },
                            color = color,
                            fontSize = fontSize
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun markCountLabel(count: Int): String = when (count) {
    0 -> "No marks yet"
    1 -> "1 mark on this document"
    else -> "$count marks on this document"
}

@Composable
private fun composeColor(color: PdfColor): Color = Color(
    red = color.red.coerceIn(0.0, 1.0).toFloat(),
    green = color.green.coerceIn(0.0, 1.0).toFloat(),
    blue = color.blue.coerceIn(0.0, 1.0).toFloat(),
    alpha = color.alpha.coerceIn(0.0, 1.0).toFloat()
)

private fun PdfColor.isDark(): Boolean =
    red * 0.299 + green * 0.587 + blue * 0.114 < 0.55
