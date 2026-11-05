package com.pdfpaper.app.ui.redact

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.ui.UiTags

/**
 * The persistent bar while a redact tool is active. It keeps the page usable
 * for marking and makes the pending count obvious.
 */
@Composable
fun RedactionBar(
    state: RedactionUiState,
    selectionText: String?,
    onToolChange: (RedactionTool) -> Unit,
    onMarkSelection: () -> Unit,
    onOpenMarks: () -> Unit,
    onApply: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.REDACT_BAR)
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.VisibilityOff,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "Redact",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when (state.marks.size) {
                            0 -> "No marks yet"
                            1 -> "1 mark"
                            else -> "${state.marks.size} marks"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onOpenMarks) { Text("Marks") }
                Button(
                    onClick = onApply,
                    enabled = state.marks.isNotEmpty() && !state.busy
                ) {
                    Text("Apply")
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RedactionTool.entries.forEach { tool ->
                    FilterChip(
                        selected = state.tool == tool,
                        onClick = { onToolChange(tool) },
                        label = { Text(tool.label) },
                        modifier = Modifier.testTag(UiTags.redactTool(tool.name.lowercase()))
                    )
                }
                val selected = selectionText?.trim().orEmpty()
                if (state.tool == RedactionTool.TEXT && selected.isNotEmpty()) {
                    TextButton(
                        onClick = onMarkSelection,
                        modifier = Modifier.testTag(UiTags.REDACT_MARK_SELECTION)
                    ) {
                        Text("Redact selection", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (state.tool == RedactionTool.TEXT && selectionText.isNullOrBlank()) {
                Text(
                    "Tap or drag across text, then choose Redact selection.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
