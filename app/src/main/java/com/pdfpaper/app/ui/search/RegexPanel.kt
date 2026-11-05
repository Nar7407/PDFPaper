package com.pdfpaper.app.ui.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.engine.text.RegexExample
import com.pdfpaper.engine.text.RegexExamples
import com.pdfpaper.engine.text.RegexFlag

/**
 * T65: the stateless regex panel — scope, pattern, flags, the tester line, the
 * six examples and the saved patterns. Both the Search tab (library scope) and
 * the viewer's sheet (document scope) draw this one composable, so the two
 * scopes cannot drift.
 */
@Composable
fun RegexPanel(
    state: RegexUiState,
    saved: List<RegexPattern>,
    onPatternChange: (String) -> Unit,
    onFlagsChange: (Set<RegexFlag>) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onExample: (RegexExample) -> Unit,
    onSavePattern: (String) -> Unit,
    onApplySaved: (String) -> Unit,
    onRenameSaved: (String, String) -> Unit,
    onDeleteSaved: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var dialog by remember { mutableStateOf<NameDialog?>(null) }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        AssistChip(onClick = {}, label = { Text(state.scope.label) })
        OutlinedTextField(
            value = state.pattern,
            onValueChange = onPatternChange,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.REGEX_PATTERN_FIELD),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            label = { Text("Regular expression") }
        )
        val error = state.error
        if (error != null) {
            Text(
                text = " ".repeat(error.position.coerceAtLeast(0)) + "^",
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(UiTags.REGEX_CARET)
            )
            Text(
                text = error.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        state.tester?.let { tester ->
            Text(
                text = tester.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FlagChip("Case", RegexFlag.CASE_INSENSITIVE, state, onFlagsChange)
            FlagChip("Multiline", RegexFlag.MULTILINE, state, onFlagsChange)
            FlagChip("Across lines", RegexFlag.ACROSS_LINES, state, onFlagsChange)
            FlagChip("Whole word", RegexFlag.WHOLE_WORD, state, onFlagsChange)
        }
        // Run stays outside the scrolling chip row so it is reachable on a
        // narrow screen, where four chips already fill the width.
        if (state.running) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.REGEX_CANCEL)
            ) {
                Text("Cancel")
            }
        } else {
            Button(
                onClick = onRun,
                enabled = state.pattern.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag(UiTags.REGEX_RUN)
            ) {
                Text("Run")
            }
        }
        describeRegexFlagsNote(state.scope)?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag(UiTags.REGEX_EXAMPLES),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (example in RegexExamples.ALL) {
                AssistChip(onClick = { onExample(example) }, label = { Text(example.label) })
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag(UiTags.REGEX_SAVED),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextButton(
                onClick = { dialog = NameDialog.Save },
                enabled = state.pattern.isNotBlank()
            ) {
                Text("Save pattern")
            }
            for (pattern in saved) {
                InputChip(
                    selected = false,
                    onClick = { onApplySaved(pattern.id) },
                    label = { Text(pattern.name) },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { dialog = NameDialog.Rename(pattern) }) {
                                Icon(Icons.Outlined.Edit, contentDescription = "Rename ${pattern.name}")
                            }
                            IconButton(onClick = { onDeleteSaved(pattern.id) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete ${pattern.name}")
                            }
                        }
                    }
                )
            }
        }
    }
    dialog?.let { open ->
        NameDialogContent(
            dialog = open,
            onDismiss = { dialog = null },
            onConfirm = { name ->
                when (open) {
                    is NameDialog.Save -> onSavePattern(name)
                    is NameDialog.Rename -> onRenameSaved(open.pattern.id, name)
                }
                dialog = null
            }
        )
    }
}

@Composable
private fun FlagChip(
    label: String,
    flag: RegexFlag,
    state: RegexUiState,
    onFlagsChange: (Set<RegexFlag>) -> Unit
) {
    FilterChip(
        selected = flag in state.flags,
        onClick = { onFlagsChange(toggleRegexFlag(state.flags, flag, flag !in state.flags)) },
        label = { Text(label) }
    )
}

/** The small save/rename dialog. */
private sealed interface NameDialog {
    data object Save : NameDialog
    data class Rename(val pattern: RegexPattern) : NameDialog
}

@Composable
private fun NameDialogContent(
    dialog: NameDialog,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val initial = when (dialog) {
        is NameDialog.Save -> ""
        is NameDialog.Rename -> dialog.pattern.name
    }
    var name by remember(dialog) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (dialog is NameDialog.Save) "Save pattern" else "Rename pattern")
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
