package com.pdfpaper.app.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.data.EinkDisplay
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.engine.eink.EinkDither
import com.pdfpaper.engine.eink.EinkProfile
import com.pdfpaper.engine.eink.EinkSettings
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Task T63: the E-Ink / reflex display sheet.
 *
 * Every control changes the page behind the sheet, exactly like the look
 * sheet (T62), so the reader chooses by looking at their own document. The
 * file is never written. Threshold changes are debounced because a full-page
 * post-process per slider pixel would fight the finger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EinkSheet(
    display: EinkDisplay,
    autoClearTurns: Int,
    onApply: (EinkSettings, forDocument: Boolean) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDocumentOverride: (Boolean) -> Unit,
    onAutoClear: (Int) -> Unit,
    onClearScreen: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        EinkSheetContent(
            display = display,
            autoClearTurns = autoClearTurns,
            onApply = onApply,
            onEnabledChange = onEnabledChange,
            onDocumentOverride = onDocumentOverride,
            onAutoClear = onAutoClear,
            onClearScreen = onClearScreen,
            onDismiss = onDismiss,
            modifier = Modifier.testTag(UiTags.EINK_SHEET)
        )
    }
}

@Composable
fun EinkSheetContent(
    display: EinkDisplay,
    autoClearTurns: Int,
    onApply: (EinkSettings, forDocument: Boolean) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDocumentOverride: (Boolean) -> Unit,
    onAutoClear: (Int) -> Unit,
    onClearScreen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by remember(display.settings) { mutableStateOf(display.settings) }
    var forDocument by remember(display.fromDocument) { mutableStateOf(display.fromDocument) }
    // Slider moves are sent once they settle; a profile tap is sent at once.
    LaunchedEffect(draft.threshold, draft.dither) {
        delay(160)
        if (draft != display.settings) onApply(draft, forDocument)
    }
    val enabled = display.enabled || draft.active

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp)
    ) {
        Text(
            text = "E-Ink display",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Reduces the page for a reflective or high-contrast screen. " +
                "Nothing is written into the PDF.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("E-Ink / high contrast", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (display.enabled) "On for this device" else "Off; the page keeps its colours",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = display.enabled,
                onCheckedChange = { onEnabledChange(it) },
                modifier = Modifier.testTag(UiTags.EINK_ENABLED)
            )
        }
        Spacer(Modifier.padding(top = 4.dp))

        EinkProfile.entries.forEach { profile ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(profile.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        profile.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilterChip(
                    selected = draft.profile == profile,
                    enabled = enabled,
                    onClick = {
                        draft = draft.copy(profile = profile)
                        if (!display.enabled) onEnabledChange(true)
                        onApply(draft, forDocument)
                    },
                    label = { Text(if (draft.profile == profile) "Chosen" else "Use") },
                    modifier = Modifier.testTag(UiTags.einkProfileChip(profile.name))
                )
            }
        }

        Text(
            "Contrast threshold: ${(draft.threshold * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            "Lower keeps more detail; higher makes faint scans solid black. " +
                "The grey profile uses it as the middle of its curve too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = draft.threshold.toFloat(),
            onValueChange = { draft = draft.copy(threshold = it.toDouble()) },
            onValueChangeFinished = { onApply(draft, forDocument) },
            valueRange = EinkSettings.MIN_THRESHOLD.toFloat()..EinkSettings.MAX_THRESHOLD.toFloat(),
            enabled = enabled && draft.profile != EinkProfile.OFF,
            modifier = Modifier.testTag(UiTags.EINK_THRESHOLD)
        )

        Text("Picture dithering", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EinkDither.entries.forEach { dither ->
                FilterChip(
                    selected = draft.dither == dither,
                    enabled = enabled && draft.profile == EinkProfile.TEXT_BITONAL,
                    onClick = {
                        draft = draft.copy(dither = dither)
                        onApply(draft, forDocument)
                    },
                    label = { Text(dither.label) },
                    modifier = Modifier.testTag(UiTags.einkDitherChip(dither.name))
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Remember for this document", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Otherwise the device profile is used. An explicit Off lets one " +
                        "drawing stay as authored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = forDocument,
                onCheckedChange = {
                    forDocument = it
                    onDocumentOverride(it)
                },
                modifier = Modifier.testTag(UiTags.EINK_DOCUMENT_ONLY)
            )
        }

        Text("Clear the screen every", style = MaterialTheme.typography.labelLarge)
        Text(
            "A full black-then-white flash removes the ghost of the previous page. " +
                "0 means only the Clear screen button.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 1, 5, 10, 20).forEach { turns ->
                FilterChip(
                    selected = autoClearTurns == turns,
                    onClick = { onAutoClear(turns) },
                    label = { Text(if (turns == 0) "Never" else "$turns") },
                    modifier = Modifier.testTag(UiTags.einkAutoClearChip(turns))
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = onClearScreen,
                modifier = Modifier.testTag(UiTags.EINK_CLEAR)
            ) {
                Text("Clear screen")
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(UiTags.EINK_DONE)
            ) {
                Text("Done")
            }
        }
    }
}
