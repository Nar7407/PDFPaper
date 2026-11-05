package com.pdfpaper.app.ui.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.AppTopBar
import com.pdfpaper.engine.export.ImageFormat
import kotlin.math.roundToInt

/**
 * "Export images": write every chosen page as PNG/JPEG/WebP at 72, 150, 300 or
 * a custom DPI, or pull the pictures embedded in the PDF out as files.
 *
 * The destination is a folder picked with the Storage Access Framework
 * (`ACTION_OPEN_DOCUMENT_TREE`), so nothing needs a storage permission. Files
 * are named `<document>-p003.png` / `<document>-img012.jpg`; an existing file
 * is only replaced after the overwrite dialog is confirmed. "Share" runs the
 * same export into the cache and sends a ZIP.
 */
@Composable
fun ExportImagesScreen(
    path: String,
    onBack: () -> Unit,
    viewModel: ExportImagesViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(path) { viewModel.load(path) }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.exportToTree(uri)
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(UiTags.EXPORT_SCREEN)) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = "Export images", onBack = onBack)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Text(
                    text = state.fileName.ifBlank { "This PDF" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when {
                        state.loading -> "Reading the document…"
                        state.pageCount > 0 -> "${state.pageCount} page(s), ${state.imageCount} embedded image(s)"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                Text(
                    text = "What to export",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.mode == mode,
                            onClick = { viewModel.setMode(mode) },
                            label = { Text(mode.label) },
                            modifier = Modifier.testTag(modeTag(mode))
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))

                if (state.mode == ExportMode.PAGES) {
                    PageOptions(state = state, viewModel = viewModel)
                } else {
                    EmbeddedOptions(state = state, viewModel = viewModel)
                }

                state.error?.let { error ->
                    Spacer(Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { folderLauncher.launch(null) },
                    enabled = !state.exporting && !state.loading && state.path.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .testTag(UiTags.EXPORT_CHOOSE_FOLDER)
                ) {
                    Text("Choose folder and export")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = viewModel::share,
                    enabled = !state.exporting && !state.loading && state.path.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .testTag(UiTags.EXPORT_SHARE)
                ) {
                    Text("Share as ZIP")
                }

                if (state.exporting) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.EXPORT_PROGRESS)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = state.progressLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = viewModel::cancel,
                                    modifier = Modifier.testTag(UiTags.EXPORT_CANCEL)
                                ) { Text("Cancel") }
                            }
                            Spacer(Modifier.height(10.dp))
                            if (state.total > 0) {
                                LinearProgressIndicator(
                                    progress = {
                                        (state.done.toFloat() / state.total).coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }

                state.summary?.let { summary ->
                    Spacer(Modifier.height(16.dp))
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.EXPORT_SUMMARY)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(summary, style = MaterialTheme.typography.bodyMedium)
                            if (state.cancelled) {
                                Text(
                                    text = "The export was stopped.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(
                                onClick = viewModel::dismissSummary,
                                modifier = Modifier.align(Alignment.End)
                            ) { Text("Done") }
                        }
                    }
                }
            }
        }
    }

    if (state.conflicts.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = viewModel::dismissConflicts,
            title = { Text("Replace existing files?") },
            text = {
                Column {
                    Text(
                        "${state.conflicts.size} file(s) with the same names are already in that folder:"
                    )
                    Spacer(Modifier.height(6.dp))
                    state.conflicts.take(6).forEach { name ->
                        Text("• $name", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.conflicts.size > 6) {
                        Text("…and ${state.conflicts.size - 6} more")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Writing the export will replace them.")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmOverwrite,
                    modifier = Modifier.testTag(UiTags.EXPORT_CONFLICT_REPLACE)
                ) { Text("Replace") }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::dismissConflicts,
                    modifier = Modifier.testTag(UiTags.EXPORT_CONFLICT_CANCEL)
                ) { Text("Keep them") }
            }
        )
    }
}

@Composable
private fun PageOptions(state: ExportImagesUiState, viewModel: ExportImagesViewModel) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = state.rangeText,
                onValueChange = viewModel::setRangeText,
                label = { Text("Pages") },
                singleLine = true,
                placeholder = { Text("all, or 1-3,7") },
                modifier = Modifier.fillMaxWidth().testTag(UiTags.EXPORT_RANGE)
            )
            Spacer(Modifier.height(12.dp))

            Text(
                text = "Resolution",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(72, 150, 300).forEach { dpi ->
                    FilterChip(
                        selected = state.dpi == dpi && state.dpiText == dpi.toString(),
                        onClick = { viewModel.setDpi(dpi) },
                        label = { Text("$dpi dpi") },
                        modifier = Modifier.testTag(dpiTag(dpi))
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.dpiText,
                onValueChange = viewModel::setDpiText,
                label = { Text("Custom DPI") },
                singleLine = true,
                modifier = Modifier.width(160.dp).testTag(UiTags.EXPORT_DPI_CUSTOM)
            )
            Spacer(Modifier.height(12.dp))

            Text(
                text = "Format",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ImageFormat.entries.forEach { format ->
                    FilterChip(
                        selected = state.format == format,
                        onClick = { viewModel.setFormat(format) },
                        label = { Text(format.label) },
                        modifier = Modifier.testTag(formatTag(format))
                    )
                }
            }
            if (state.format != ImageFormat.PNG) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Quality: ${state.quality}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = state.quality.toFloat(),
                    onValueChange = { viewModel.setQuality(it.roundToInt()) },
                    valueRange = 1f..100f,
                    modifier = Modifier.testTag(UiTags.EXPORT_QUALITY)
                )
            }
        }
    }
}

@Composable
private fun EmbeddedOptions(state: ExportImagesUiState, viewModel: ExportImagesViewModel) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Every picture stored in the PDF is written as it is stored: " +
                    "JPEG and JPEG 2000 keep their original bytes; raw, Flate and LZW " +
                    "pictures become PNG (alpha from their soft mask).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = if (state.minSize == 0) "" else state.minSize.toString(),
                onValueChange = viewModel::setMinSize,
                label = { Text("Skip images smaller than (px)") },
                singleLine = true,
                modifier = Modifier.width(240.dp).testTag(UiTags.EXPORT_MIN_SIZE)
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Keep duplicate images",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = state.keepDuplicates,
                    onCheckedChange = viewModel::setKeepDuplicates,
                    modifier = Modifier.testTag(UiTags.EXPORT_KEEP_DUPLICATES)
                )
            }
            Text(
                text = "${state.imageCount} image(s) found in the document.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun modeTag(mode: ExportMode): String = when (mode) {
    ExportMode.PAGES -> UiTags.EXPORT_MODE_PAGES
    ExportMode.EMBEDDED -> UiTags.EXPORT_MODE_EMBEDDED
}

private fun dpiTag(dpi: Int): String = when (dpi) {
    72 -> UiTags.EXPORT_DPI_72
    150 -> UiTags.EXPORT_DPI_150
    else -> UiTags.EXPORT_DPI_300
}

private fun formatTag(format: ImageFormat): String = when (format) {
    ImageFormat.PNG -> UiTags.EXPORT_FORMAT_PNG
    ImageFormat.JPEG -> UiTags.EXPORT_FORMAT_JPG
    ImageFormat.WEBP -> UiTags.EXPORT_FORMAT_WEBP
}
