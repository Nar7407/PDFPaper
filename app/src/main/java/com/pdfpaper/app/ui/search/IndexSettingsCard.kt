package com.pdfpaper.app.ui.search

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.ui.UiTags

/**
 * Task T50: the Settings card owns the folder list and the index lifecycle.
 * The index can be rebuilt or deleted without deleting the folder grants, so
 * the user does not have to pick a folder twice.
 */
@Composable
fun IndexSettingsCard(viewModel: SearchViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    // SettingsScreen is also rendered in Compose tests and previews that have
    // no ActivityResultRegistryOwner. The picker is only usable when there is
    // an owner (the real app always provides one), so a missing owner must not
    // crash the whole settings page.
    val registryOwner = LocalActivityResultRegistryOwner.current
    val folderPicker = if (registryOwner == null) {
        null
    } else {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? -> if (uri != null) viewModel.addFolder(uri) }
    }
    var confirmRebuild by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Search index", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Full-text search runs only on this device. The index is excluded from " +
                    "Android backups and device transfer. Encrypted PDFs stay locked until " +
                    "they are unlocked in the viewer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            val stats = state.stats
            if (stats != null) {
                Text(
                    "${stats.fileCount} files · ${stats.pageCount} pages · " +
                        Formatter.formatShortFileSize(context, stats.databaseBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag(UiTags.INDEX_STATS)
                )
                Text(
                    "${stats.indexedCount} indexed · ${stats.lockedCount} locked · " +
                        "${stats.needsOcrCount} need OCR · ${stats.errorCount} errors",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))

            if (state.indexRunning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    state.indexPhase.ifBlank { "Indexing..." },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = viewModel::pause) {
                        Icon(Icons.Outlined.Pause, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Pause")
                    }
                    TextButton(onClick = viewModel::cancel) {
                        Icon(Icons.Outlined.Stop, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
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
                    TextButton(onClick = viewModel::resume) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Resume")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = { folderPicker?.launch(null) },
                    enabled = folderPicker != null,
                    modifier = Modifier.testTag(UiTags.INDEX_ADD_FOLDER)
                ) {
                    Text("Add folder")
                }
                Button(
                    onClick = viewModel::indexNow,
                    enabled = state.hasFolders,
                    modifier = Modifier.testTag(UiTags.INDEX_NOW)
                ) {
                    Text("Index now")
                }
            }

            if (state.folders.isEmpty()) {
                Text(
                    "No folders yet. Add a folder to search its PDFs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Spacer(Modifier.height(6.dp))
                for (folder in state.folders) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(UiTags.indexFolder(folder.id))
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                folder.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                folder.lastError ?: "Added ${indexDate(folder.addedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (folder.lastError == null) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { viewModel.removeFolder(folder) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Remove folder")
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setOnlyWhileCharging(!state.onlyWhileCharging) }
                    .testTag(UiTags.INDEX_CHARGING)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Only index while charging", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "A WorkManager constraint. Off by default.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = state.onlyWhileCharging, onCheckedChange = null)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { confirmRebuild = true },
                    modifier = Modifier.testTag(UiTags.INDEX_REBUILD)
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Rebuild")
                }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.testTag(UiTags.INDEX_DELETE)
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Delete index")
                }
            }
        }
    }

    if (confirmRebuild) {
        AlertDialog(
            onDismissRequest = { confirmRebuild = false },
            title = { Text("Rebuild the index?") },
            text = {
                Text(
                    "Every indexed page is removed and read again from the folders. " +
                        "The folder permissions are kept."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRebuild = false
                    viewModel.rebuild()
                }) { Text("Rebuild") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRebuild = false }) { Text("Cancel") }
            }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete the search index?") },
            text = {
                Text(
                    "All indexed text is removed from this device. The folders stay in the " +
                        "list so you can index them again later."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteIndex()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}
