package com.pdfpaper.app.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import com.pdfpaper.app.ui.tools.ToolDefinition
import com.pdfpaper.app.ui.tools.ToolRegistry
import com.pdfpaper.app.ui.tools.ToolSection

/**
 * The viewer's tools menu: a one-handed modal sheet, titled with the open file,
 * that lists every tool from [ToolRegistry] grouped by section with a search box
 * on top. Tools the engine can't do yet are dimmed with a "Coming soon" label;
 * tapping one leaves the document unchanged and reports that instead
 * ([onUnavailable], a short snackbar in the viewer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerToolsSheet(
    fileName: String,
    demoEngine: Boolean,
    onDismiss: () -> Unit,
    onSelect: (ToolDefinition) -> Unit,
    onUnavailable: (ToolDefinition) -> Unit = {}
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        ViewerToolsSheetContent(
            fileName = fileName,
            demoEngine = demoEngine,
            onSelect = onSelect,
            onUnavailable = onUnavailable,
            modifier = Modifier.testTag(UiTags.VIEWER_TOOLS_SHEET)
        )
    }
}

@Composable
fun ViewerToolsSheetContent(
    fileName: String,
    demoEngine: Boolean,
    onSelect: (ToolDefinition) -> Unit,
    onUnavailable: (ToolDefinition) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = ToolRegistry.search(query)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = fileName,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            placeholder = { Text("Find a tool") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .testTag(UiTags.VIEWER_TOOLS_SEARCH)
        )

        if (results.isEmpty()) {
            Text(
                text = "No tools match \"$query\".",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
            ) {
                ToolRegistry.grouped(results).forEach { (section, tools) ->
                    item(key = "sheet-${section.name}") {
                        SectionHeader(section.label)
                    }
                    items(tools, key = { it.id }) { tool ->
                        val available = tool.isAvailable(demoEngine)
                        ToolSheetRow(
                            tool = tool,
                            available = available,
                            onClick = {
                                if (available) onSelect(tool) else onUnavailable(tool)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 20.dp, top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun ToolSheetRow(
    tool: ToolDefinition,
    available: Boolean,
    onClick: () -> Unit
) {
    val label = if (available) tool.name else "${tool.name}, coming soon"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag(UiTags.viewerToolItem(tool.id))
            .alpha(if (available) 1f else 0.5f)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = tool.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(
                text = tool.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (!available) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = "Coming soon",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 720, name = "Viewer tools sheet")
@Composable
private fun ViewerToolsSheetPreview() {
    PDFPaperTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            ViewerToolsSheetContent(
                fileName = "Project notes.pdf",
                demoEngine = true,
                onSelect = {}
            )
        }
    }
}
