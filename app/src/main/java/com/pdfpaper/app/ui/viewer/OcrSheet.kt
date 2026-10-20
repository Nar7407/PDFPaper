package com.pdfpaper.app.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.ui.UiTags

data class ViewerOcrState(
    val running: Boolean = false,
    val requested: Int = 0,
    val processed: Int = 0,
    val language: String = "eng",
    val finished: Boolean = false,
    val hasResult: Boolean = false,
    val summary: String? = null
)

/**
 * "Make searchable" sheet (task UI-2). The OCR runs page by page through the
 * host OcrProvider (ML Kit), reports progress and can be cancelled; a finished
 * run hands the new bytes to the viewer's normal save flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrSheet(
    state: ViewerOcrState,
    currentPage: Int,
    pageCount: Int,
    onStart: (language: String, allPages: Boolean) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    var language by remember { mutableStateOf(if (state.language == "hin") "hin" else "eng") }
    var allPages by remember { mutableStateOf(true) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { if (!state.running) onDismiss() },
        sheetState = sheetState,
        modifier = Modifier.testTag(UiTags.OCR_SHEET)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Make searchable",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Adds invisible, selectable text to scans",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss, enabled = !state.running) {
                    Icon(Icons.Filled.Close, contentDescription = "Close OCR")
                }
            }
            Spacer(Modifier.height(14.dp))

            when {
                state.running -> {
                    val fraction = if (state.requested > 0) {
                        (state.processed.toFloat() / state.requested).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Reading page images…",
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            if (state.requested > 0 && fraction > 0f) {
                                LinearProgressIndicator(
                                    progress = fraction,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (state.requested > 0) {
                                    "${state.processed} of ${state.requested} page images checked"
                                } else {
                                    "Starting…"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            TextButton(onClick = onCancel, modifier = Modifier.testTag(UiTags.OCR_CANCEL)) {
                                Text("Cancel")
                            }
                        }
                    }
                }

                state.finished -> {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                state.summary ?: "OCR finished.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(10.dp))
                            if ((state.summary ?: "").contains("Added", ignoreCase = true)) {
                                Text(
                                    "The searchable copy is ready to save.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            if (state.hasResult) {
                                Button(
                                    onClick = onSave,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Save searchable copy")
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                            TextButton(onClick = onDismiss) { Text("Close") }
                        }
                    }
                }

                else -> {
                    Text("Language", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = language == "eng",
                            onClick = { language = "eng" },
                            label = { Text("English") }
                        )
                        FilterChip(
                            selected = language == "hin",
                            onClick = { language = "hin" },
                            label = { Text("Hindi") }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Pages", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = allPages,
                            onClick = { allPages = true },
                            label = { Text("All pages ($pageCount)") }
                        )
                        FilterChip(
                            selected = !allPages,
                            onClick = { allPages = false },
                            label = { Text("This page (${currentPage.coerceAtLeast(1)})") }
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "Recognition runs on device with ML Kit. The English or Hindi " +
                                    "model may download through Google Play services the first time.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Pages that already contain text are skipped unless you choose a single page.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { onStart(language, allPages) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag(UiTags.OCR_START)
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Make searchable")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "You can cancel while it runs. The original PDF is never changed in place.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
