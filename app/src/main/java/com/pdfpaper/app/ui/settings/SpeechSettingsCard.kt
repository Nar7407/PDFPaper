package com.pdfpaper.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.viewer.speech.SleepOption
import com.pdfpaper.app.ui.viewer.speech.SpeechSpeed
import com.pdfpaper.app.ui.viewer.speech.SpeechVoice
import com.pdfpaper.app.ui.viewer.speech.SpeechVoiceKey
import com.pdfpaper.app.ui.viewer.speech.SpeechVoices
import com.pdfpaper.app.ui.viewer.speech.openTtsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * T34: the "Read aloud" settings card.
 *
 * It holds the defaults for the next reading (speed, voice, sleep timer and
 * whether the page follows the speech). A reading that is already running has
 * its own sheet; changing a default here never reaches into it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeechSettingsCard() {
    val context = LocalContext.current
    var speed by remember { mutableStateOf(SpeechSpeed.nearest(AppSettings.speechSpeed(context))) }
    var sleep by remember { mutableStateOf(AppSettings.speechSleep(context)) }
    var autoScroll by remember { mutableStateOf(AppSettings.speechAutoScroll(context)) }
    var voice by remember { mutableStateOf(AppSettings.speechVoice(context)) }
    var voices by remember { mutableStateOf<List<SpeechVoice>?>(null) }
    var showVoices by remember { mutableStateOf(false) }

    LaunchedEffect(showVoices) {
        if (showVoices && voices == null) {
            voices = withContext(Dispatchers.IO) { SpeechVoices.list(context) }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.SETTINGS_SPEECH_CARD)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Read aloud", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Default speed",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (preset in SpeechSpeed.PRESETS) {
                    FilterChip(
                        selected = speed == preset,
                        onClick = {
                            speed = preset
                            AppSettings.setSpeechSpeed(context, preset)
                        },
                        label = { Text(SpeechSpeed.label(preset)) },
                        modifier = Modifier.testTag(UiTags.settingsSpeechSpeedTag(preset))
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Default sleep timer", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in SleepOption.entries) {
                    FilterChip(
                        selected = sleep == option,
                        onClick = {
                            sleep = option
                            AppSettings.setSpeechSleep(context, option)
                        },
                        label = { Text(option.label()) },
                        modifier = Modifier.testTag(UiTags.settingsSpeechSleepTag(option.name))
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showVoices = true }
                    .testTag(UiTags.SETTINGS_SPEECH_VOICE)
                    .padding(vertical = 8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Voice", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        voiceLabel(voice, voices),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "Change",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            TextButton(onClick = { openTtsSettings(context) }) {
                Text("System voice settings")
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Follow the reading", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Turn the page when read aloud moves on.",
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
                    modifier = Modifier.testTag(UiTags.SETTINGS_SPEECH_AUTO_SCROLL)
                )
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

    if (showVoices) {
        AlertDialog(
            onDismissRequest = { showVoices = false },
            title = { Text("Voice") },
            text = {
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
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        item {
                            VoiceOption(
                                label = "Default voice",
                                selected = voice == null,
                                onClick = {
                                    voice = null
                                    AppSettings.setSpeechVoice(context, null)
                                    showVoices = false
                                }
                            )
                        }
                        items(listed, key = { it.key.encode() }) { option ->
                            VoiceOption(
                                label = option.label,
                                selected = voice == option.key,
                                onClick = {
                                    voice = option.key
                                    AppSettings.setSpeechVoice(context, option.key)
                                    showVoices = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVoices = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun VoiceOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun voiceLabel(voice: SpeechVoiceKey?, voices: List<SpeechVoice>?): String {
    if (voice == null) return "Default voice · offline voices only"
    val found = voices?.firstOrNull { it.key == voice }
    return (found?.label ?: voice.name) + " · offline voices only"
}
