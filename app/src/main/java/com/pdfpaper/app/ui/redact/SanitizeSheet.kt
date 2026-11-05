package com.pdfpaper.app.ui.redact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.engine.sanitize.FormDataHandling
import com.pdfpaper.engine.sanitize.SanitizeItem
import com.pdfpaper.engine.sanitize.SanitizeOptions
import com.pdfpaper.engine.sanitize.SanitizeReport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SanitizeSheet(
    state: SanitizeUiState,
    onDismiss: () -> Unit,
    onOptionToggle: (SanitizeItem, Boolean) -> Unit,
    onFormHandling: (FormDataHandling) -> Unit,
    onClean: () -> Unit,
    onSave: () -> Unit,
    onBackToOptions: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { if (!state.running) onDismiss() },
        sheetState = sheetState,
        modifier = Modifier.testTag(UiTags.SANITIZE_SHEET)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Sanitize document",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Remove hidden data and write a fresh copy",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss, enabled = !state.running) {
                    Icon(Icons.Filled.Close, contentDescription = "Close sanitize")
                }
            }
            Spacer(Modifier.height(14.dp))

            when {
                state.running -> SanitizeRunning()
                state.outcome != null -> SanitizeResult(
                    outcome = state.outcome,
                    onSave = onSave,
                    onBackToOptions = onBackToOptions
                )
                else -> SanitizeOptionsList(
                    options = state.options,
                    error = state.error,
                    onOptionToggle = onOptionToggle,
                    onFormHandling = onFormHandling,
                    onClean = onClean
                )
            }
        }
    }
}

@Composable
private fun SanitizeRunning() {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Cleaning the document…", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "The only copy changed is the new one. The original stays as it is.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun SanitizeOptionsList(
    options: SanitizeOptions,
    error: String?,
    onOptionToggle: (SanitizeItem, Boolean) -> Unit,
    onFormHandling: (FormDataHandling) -> Unit,
    onClean: () -> Unit
) {
    Text(
        "What to remove",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        "Everything is checked by default. Turn off an item only when you need to keep it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 3.dp, bottom = 8.dp)
    )

    SanitizeItem.entries.forEach { item ->
        SanitizeRow(
            item = item,
            options = options,
            onToggle = { onOptionToggle(item, it) }
        )
        if (item == SanitizeItem.FORM_DATA && options.formData != FormDataHandling.KEEP) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = options.formData == FormDataHandling.CLEAR,
                    onClick = { onFormHandling(FormDataHandling.CLEAR) },
                    label = { Text("Clear fields") }
                )
                FilterChip(
                    selected = options.formData == FormDataHandling.FLATTEN,
                    onClick = { onFormHandling(FormDataHandling.FLATTEN) },
                    label = { Text("Flatten first") }
                )
            }
        }
    }

    Text(
        "The clean copy is always a full rewrite. Older revisions cannot be carried over, " +
            "and unreachable objects are dropped with the other checked items.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp)
    )
    error?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
    Button(
        onClick = onClean,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .height(50.dp)
            .testTag(UiTags.SANITIZE_CLEAN)
    ) {
        Icon(Icons.Outlined.CleaningServices, contentDescription = null, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text("Clean document")
    }
    Text(
        "A new copy will be saved. The original stays unchanged.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun SanitizeRow(
    item: SanitizeItem,
    options: SanitizeOptions,
    onToggle: (Boolean) -> Unit
) {
    val checked = options.isRemovalEnabled(item)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                sanitizeItemTitle(item),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                sanitizeItemDescription(item),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            modifier = Modifier.testTag(UiTags.sanitizeItem(item.name.lowercase()))
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SanitizeResult(
    outcome: SanitizeOutcome,
    onSave: () -> Unit,
    onBackToOptions: () -> Unit
) {
    val report = outcome.report
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                reportHeadline(report),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Full rewrite: yes. The original file is unchanged.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    Text(
        "Removed items",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
    )
    if (report.removed.isEmpty()) {
        Text(
            "None of the selected item types were found in this document.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        report.removed.forEach { (item, count) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    sanitizeItemTitle(item),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    if (report.warnings.isNotEmpty()) {
        Text(
            "Warnings",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
        )
        report.warnings.forEach { warning ->
            Text(
                "\u2022 $warning",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }

    Text(
        "This report counts what the engine removed. Review any warnings before sharing the copy.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp)
    )
    Button(
        onClick = onSave,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(50.dp)
            .testTag(UiTags.SANITIZE_SAVE)
    ) {
        Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text("Save clean copy")
    }
    TextButton(
        onClick = onBackToOptions,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Change items and clean again")
    }
}

private fun reportHeadline(report: SanitizeReport): String =
    if (report.totalRemoved == 1) {
        "1 item removed"
    } else {
        "${report.totalRemoved} items removed"
    }
