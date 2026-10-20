package com.pdfpaper.app.ui.compress

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.AppTopBar
import com.pdfpaper.engine.compress.CompressResult
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The "Compress PDF" screen: pick how hard the file is squeezed, see what
 * that would roughly achieve and write the smaller copy to a new file.
 *
 * The original file is never touched; the compressed copy is written to the
 * place the user picks.
 */
@Composable
fun CompressScreen(
    path: String,
    onBack: () -> Unit,
    onSaved: () -> Unit = {},
    viewModel: CompressViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var confirmSignature by remember { mutableStateOf(false) }

    LaunchedEffect(path) { viewModel.load(path) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) viewModel.saveTo(uri)
    }

    // The note that the file is saved stays on screen for a moment before the
    // screen closes itself.
    LaunchedEffect(state.saved) {
        if (state.saved) {
            delay(SAVED_LINGER_MS)
            onSaved()
        }
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(UiTags.COMPRESS_SCREEN)) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = "Compress PDF", onBack = onBack)
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
                    text = formatSize(state.fileSizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))

                Text(
                    text = "How hard",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompressMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.mode == mode,
                            onClick = { viewModel.selectPreset(mode) },
                            label = { Text(mode.label) },
                            modifier = Modifier.testTag(presetTag(mode))
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = state.mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                if (state.mode == CompressMode.CUSTOM) {
                    CustomCard(
                        state = state,
                        onDpi = viewModel::setTargetDpi,
                        onQuality = viewModel::setQuality,
                        onGrayscale = viewModel::setGrayscale,
                        onStripMetadata = viewModel::setStripMetadata
                    )
                    Spacer(Modifier.height(16.dp))
                }

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.COMPRESS_ESTIMATE)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        val estimate = state.estimate
                        Text(
                            text = when {
                                state.estimating -> "Working out the new size…"
                                estimate != null -> estimateLine(estimate)
                                else -> "The estimate is not available"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                if (state.hasSignatures) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = "This document has a digital signature. Compressing removes it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }

                state.error?.let { error ->
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
                    Spacer(Modifier.height(16.dp))
                }

                if (state.cancelled) {
                    Text(
                        text = "The compression was stopped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Button(
                    onClick = {
                        if (state.hasSignatures) confirmSignature = true else viewModel.compress()
                    },
                    enabled = !state.compressing && !state.loading && state.path.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .testTag(UiTags.COMPRESS_START)
                ) {
                    Text("Compress")
                }

                if (state.compressing) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Compressing…",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = viewModel::cancel,
                            modifier = Modifier.testTag(UiTags.COMPRESS_CANCEL)
                        ) {
                            Text("Cancel")
                        }
                    }
                }

                state.result?.let { result ->
                    Spacer(Modifier.height(20.dp))
                    ResultCard(
                        result = result,
                        saved = state.saved,
                        onSave = { saveLauncher.launch(suggestedFileName(state.fileName)) },
                        onDismiss = viewModel::dismissResult
                    )
                }
            }
        }
    }

    if (confirmSignature) {
        AlertDialog(
            onDismissRequest = { confirmSignature = false },
            title = { Text("Remove the signature?") },
            text = {
                Text(
                    "The document is signed. Compressing writes a new copy without the " +
                        "signature, and the signature can no longer be checked."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignature = false
                        viewModel.compress()
                    }
                ) { Text("Compress anyway") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignature = false }) { Text("Keep it") }
            }
        )
    }
}

/** The hand-made settings: the resolution, the quality and the extras. */
@Composable
private fun CustomCard(
    state: CompressUiState,
    onDpi: (Int) -> Unit,
    onQuality: (Int) -> Unit,
    onGrayscale: (Boolean) -> Unit,
    onStripMetadata: (Boolean) -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Your settings", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            Text(
                text = "Image resolution: ${state.targetDpi} dpi",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = state.targetDpi.toFloat(),
                onValueChange = { onDpi(it.roundToInt()) },
                valueRange = MIN_TARGET_DPI.toFloat()..MAX_TARGET_DPI.toFloat(),
                modifier = Modifier.testTag(UiTags.COMPRESS_DPI)
            )

            Text(
                text = "JPEG quality: ${state.jpegQuality}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = state.jpegQuality.toFloat(),
                onValueChange = { onQuality(it.roundToInt()) },
                valueRange = MIN_JPEG_QUALITY.toFloat()..MAX_JPEG_QUALITY.toFloat(),
                modifier = Modifier.testTag(UiTags.COMPRESS_QUALITY)
            )

            SettingSwitch(
                label = "Grey images",
                checked = state.grayscale,
                onCheckedChange = onGrayscale
            )
            SettingSwitch(
                label = "Remove document metadata",
                checked = state.stripMetadata,
                onCheckedChange = onStripMetadata
            )
        }
    }
}

/** One switch with its label, the way the rest of the app does it. */
@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** What the run did: the sizes, the parts of the file and the counters. */
@Composable
private fun ResultCard(
    result: CompressResult,
    saved: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val report = result.report
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().testTag(UiTags.COMPRESS_RESULT)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("The smaller copy", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text = formatSizes(report.originalBytes, report.compressedBytes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = savedLine(report),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            categoryLines(report).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
            if (countLines(report).isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                countLines(report).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (skippedLines(report).isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                skippedLines(report).forEach { line ->
                    Text(
                        text = "· $line",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            if (report.usedOriginal) {
                Text(
                    text = "The compressed copy was not smaller, so the original file was kept.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Button(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.COMPRESS_SAVE)
                ) {
                    Text("Save as new copy")
                }
                if (saved) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Saved.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/** How long the "Saved" note stays on screen before the screen closes itself. */
private const val SAVED_LINGER_MS = 1200L

/** The tag of one preset chip. */
private fun presetTag(mode: CompressMode): String = when (mode) {
    CompressMode.LIGHT -> UiTags.COMPRESS_PRESET_LIGHT
    CompressMode.BALANCED -> UiTags.COMPRESS_PRESET_BALANCED
    CompressMode.STRONG -> UiTags.COMPRESS_PRESET_STRONG
    CompressMode.CUSTOM -> UiTags.COMPRESS_PRESET_CUSTOM
}
