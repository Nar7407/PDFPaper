package com.pdfpaper.app.ui.viewer.speech

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.speech.ReadAloudHub
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.engine.speech.ChapterSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * T34: the read-aloud controls. It shows the running reading's speed, sleep
 * timer, chapter list and voice, and changes them through [ReadAloudHub], so the
 * service that owns the speech stays the only owner.
 *
 * Only offline voices are listed: the app has no network permission, and a
 * voice that needs the network could not read a document offline.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReadAloudSheet(
    state: ReadAloudPlaybackState,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val commands = ReadAloudHub.commands
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var voices by remember { mutableStateOf<List<SpeechVoice>?>(null) }
    var storedVoice by remember { mutableStateOf(AppSettings.speechVoice(context)) }
    var showVoices by rememberSaveable { mutableStateOf(false) }
    var autoScroll by rememberSaveable { mutableStateOf(AppSettings.speechAutoScroll(context)) }

    // The engine needs a moment to enumerate its voices; the sheet opens at
    // once and asks only when the reader opens the voice list.
    LaunchedEffect(showVoices) {
        if (showVoices && voices == null) {
            voices = withContext(Dispatchers.IO) { SpeechVoices.list(context) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier.testTag(UiTags.READ_ALOUD_SHEET)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("Read aloud", style = MaterialTheme.typography.titleLarge)
            Text(
                state.documentName.ifBlank { "This document" } +
                    " · page ${state.pageIndex + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.paragraphText.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    state.paragraphText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.message != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(18.dp))
            Text("Speed", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (speed in SpeechSpeed.PRESETS) {
                    FilterChip(
                        selected = SpeechSpeed.nearest(state.speed) == speed,
                        onClick = { commands?.setSpeed(speed) },
                        label = { Text(SpeechSpeed.label(speed)) },
                        modifier = Modifier.testTag(UiTags.readAloudSpeedTag(speed))
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("Sleep timer", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in SleepOption.entries) {
                    FilterChip(
                        selected = state.sleep == option,
                        onClick = { commands?.setSleep(option) },
                        label = { Text(option.label()) },
                        modifier = Modifier.testTag(UiTags.readAloudSleepTag(option.name))
                    )
                }
            }
            if (state.sleep == SleepOption.END_OF_CHAPTER &&
                (state.chapterSource == null || state.chapters.isEmpty())
            ) {
                Text(
                    "This document has no chapters, so this timer is off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Follow the reading", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Turn the page when the reading moves on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoScroll,
                    onCheckedChange = {
                        autoScroll = it
                        AppSettings.setSpeechAutoScroll(context, it)
                    },
                    modifier = Modifier.testTag(UiTags.READ_ALOUD_AUTO_SCROLL)
                )
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Chapters", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            val source = state.chapterSource
            Text(
                when (source) {
                    ChapterSource.OUTLINE -> "From the document's outline."
                    ChapterSource.HEADING -> "From the document's headings."
                    null -> "This document has no chapters."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.chapters.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                for (chapter in state.chapters) {
                    val current = chapter.title == state.chapterTitle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .testTag(UiTags.readAloudChapterTag(chapter.pageIndex))
                    ) {
                        if (current) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = "Current chapter",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            chapter.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = if (current) 8.dp else 0.dp)
                        )
                        Text(
                            "p. ${chapter.pageIndex + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { commands?.previousChapter() }) {
                        Text("Previous chapter")
                    }
                    TextButton(onClick = { commands?.nextChapter() }) {
                        Text("Next chapter")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Voice", style = MaterialTheme.typography.titleSmall)
            val currentLabel = storedVoice?.let { key ->
                voices?.firstOrNull { it.key == key }?.label ?: key.name
            } ?: "Default voice"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showVoices = !showVoices }
                    .testTag(UiTags.READ_ALOUD_VOICE_ROW)
                    .padding(vertical = 8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(currentLabel, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Offline voices only",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    if (showVoices) "Hide" else "Change",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (showVoices) {
                val listed = voices
                if (listed == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(8.dp))
                        Text(
                            "Looking for installed voices…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                } else if (listed.isEmpty()) {
                    Text(
                        "This device has no offline voice. Read aloud needs one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    VoiceRow(
                        label = "Default voice",
                        selected = storedVoice == null,
                        onClick = {
                            storedVoice = null
                            AppSettings.setSpeechVoice(context, null)
                            commands?.setVoice(null)
                            showVoices = false
                        }
                    )
                    for (voice in listed) {
                        VoiceRow(
                            label = voice.label,
                            selected = storedVoice == voice.key,
                            onClick = {
                                storedVoice = voice.key
                                AppSettings.setSpeechVoice(context, voice.key)
                                commands?.setVoice(voice.key)
                                showVoices = false
                            }
                        )
                    }
                }
            }
            TextButton(
                onClick = { openTtsSettings(context) },
                modifier = Modifier.testTag(UiTags.READ_ALOUD_SYSTEM_VOICES)
            ) {
                Text("System voice settings")
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Read aloud uses your device's speech engine. It runs on the CPU, so it " +
                    "uses battery and can warm the device. Only offline voices are used; " +
                    "PDFPaper never sends the document anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
