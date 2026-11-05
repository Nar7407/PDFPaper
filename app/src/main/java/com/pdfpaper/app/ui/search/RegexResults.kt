package com.pdfpaper.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.ui.UiTags

/**
 * T65: the grouped results list. Library rows are grouped by file and page in
 * reading order; a document run has one group (itself) and its rows keep their
 * own page indexes. Unique-only filtering and the summary are pure state, so
 * this composable never computes match work.
 */
@Composable
fun RegexResults(
    state: RegexUiState,
    onActive: (Int) -> Unit,
    onRow: (RegexRow) -> Unit,
    onUniqueChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    onHighlightAll: (() -> Unit)?,
    onRedactAll: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val rows = state.visibleRows
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = state.summary,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).testTag(UiTags.REGEX_SUMMARY)
            )
            Text("Unique", style = MaterialTheme.typography.bodySmall)
            Switch(
                checked = state.uniqueOnly,
                onCheckedChange = onUniqueChange,
                modifier = Modifier.testTag(UiTags.REGEX_UNIQUE)
            )
        }
        state.progress?.let { progress ->
            Text(
                text = if (progress.currentName.isNotEmpty()) {
                    "${progress.filesDone} of ${progress.filesTotal} files · ${progress.currentName}"
                } else {
                    "page ${progress.filesDone} of ${progress.filesTotal}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.unsearchable.isNotEmpty()) {
            Text(
                text = "Could not search: " + state.unsearchable.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (rows.isEmpty() && !state.running) {
            Text(
                text = emptyText(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f, fill = false).testTag(UiTags.REGEX_RESULTS)) {
                for (group in groupRows(rows)) {
                    if (state.scope == RegexScope.LIBRARY) {
                        item {
                            Text(
                                text = group.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                    for (page in group.pages) {
                        item {
                            Text(
                                text = "page ${page.pageIndex + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(page.rows) { row ->
                            val index = rows.indexOf(row)
                            RowResult(
                                row = row,
                                active = row == state.activeRow,
                                onClick = {
                                    onActive(index)
                                    onRow(row)
                                }
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextButton(
                onClick = onExport,
                enabled = rows.isNotEmpty(),
                modifier = Modifier.testTag(UiTags.REGEX_EXPORT)
            ) {
                Text("Export")
            }
            onHighlightAll?.let { action ->
                TextButton(onClick = action, modifier = Modifier.testTag(UiTags.REGEX_HIGHLIGHT_ALL)) {
                    Text("Highlight all")
                }
            }
            onRedactAll?.let { action ->
                TextButton(onClick = action, modifier = Modifier.testTag(UiTags.REGEX_REDACT_ALL)) {
                    Text("Mark all as redactions")
                }
            }
        }
    }
}

/** The one-line empty state: which of the four "nothing here" cases this is. */
private fun emptyText(state: RegexUiState): String = when {
    state.scope == RegexScope.LIBRARY && state.filesTotal == 0 && state.pattern.isNotBlank() ->
        "The library has no indexed files"
    state.pattern.isBlank() -> "No pattern yet"
    else -> "No matches"
}

@Composable
private fun RowResult(row: RegexRow, active: Boolean, onClick: () -> Unit) {
    val text = buildAnnotatedString {
        append(row.before)
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(row.text)
        }
        append(row.after)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    )
}
