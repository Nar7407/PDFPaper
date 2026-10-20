package com.pdfpaper.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.LinkTapBehavior
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.AppTopBar

/**
 * Minimal settings surface (task UI-2). The author name is the only setting
 * used by comments, replies and dynamic stamps today; keeping it here means
 * those tools never invent a name.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(AppSettings.authorName(context)) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var linkBehavior by rememberSaveable {
        mutableStateOf(AppSettings.linkTapBehavior(context))
    }

    Scaffold(
        topBar = { AppTopBar(title = "Settings", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.testTag(UiTags.SETTINGS_SCREEN)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Text(
                text = "Your name",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Added to comments, replies and dynamic stamps. It never leaves this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    saved = false
                },
                label = { Text("Author name") },
                placeholder = { Text(AppSettings.DEFAULT_AUTHOR) },
                singleLine = true,
                supportingText = { Text("Shown to other reviewers in the PDF.") },
                modifier = Modifier.fillMaxWidth().testTag(UiTags.SETTINGS_AUTHOR)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    AppSettings.setAuthorName(context, name)
                    name = AppSettings.authorName(context)
                    saved = true
                },
                modifier = Modifier.testTag(UiTags.SETTINGS_SAVE)
            ) {
                Text(if (saved) "Saved" else "Save name")
            }
            Spacer(Modifier.height(28.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Links and references", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Choose what happens when you tap an internal link, citation or " +
                            "figure reference.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinkBehaviorOption(
                        label = "Preview first",
                        description = "Show a preview card and keep your place.",
                        selected = linkBehavior == LinkTapBehavior.PREVIEW_FIRST,
                        tag = UiTags.SETTINGS_LINK_PREVIEW,
                        onSelect = {
                            linkBehavior = LinkTapBehavior.PREVIEW_FIRST
                            AppSettings.setLinkTapBehavior(context, linkBehavior)
                        }
                    )
                    LinkBehaviorOption(
                        label = "Jump directly",
                        description = "Go to the destination without the preview card.",
                        selected = linkBehavior == LinkTapBehavior.JUMP_DIRECTLY,
                        tag = UiTags.SETTINGS_LINK_JUMP,
                        onSelect = {
                            linkBehavior = LinkTapBehavior.JUMP_DIRECTLY
                            AppSettings.setLinkTapBehavior(context, linkBehavior)
                        }
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            SignatureStorageSettings()
            Spacer(Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Privacy", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Documents, comments and OCR run on this device. OCR and scanning use " +
                            "Google Play services (ML Kit); language models may download on first use.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkBehaviorOption(
    label: String,
    description: String,
    selected: Boolean,
    tag: String,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag(tag)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
