package com.pdfpaper.app.ui.mark

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The parts the three mark screens have in common: a switch, a slider, the
 * page-range picker and the card that offers the finished file.
 */

/** One switch with its label, the way the rest of the app does it. */
@Composable
fun MarkSettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String? = null
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (tag == null) Modifier else Modifier.testTag(tag)
        )
    }
}

/** One slider with the value it stands for. */
@Composable
fun MarkSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    tag: String? = null
) {
    Text(
        text = "$label: $valueLabel",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        modifier = if (tag == null) Modifier else Modifier.testTag(tag)
    )
}

/**
 * The page-range picker: all pages, the odd ones, the even ones or a list the
 * user types, such as "1,3-5".
 */
@Composable
fun MarkPageRangePicker(
    mode: PageRangeMode,
    text: String,
    onModeChange: (PageRangeMode) -> Unit,
    onTextChange: (String) -> Unit,
    tagPrefix: String
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PageRangeMode.entries.forEach { option ->
            FilterChip(
                selected = mode == option,
                onClick = { onModeChange(option) },
                label = { Text(option.label) },
                modifier = Modifier.testTag("${tagPrefix}_${option.name.lowercase()}")
            )
        }
    }
    if (mode == PageRangeMode.CUSTOM) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("1,3-5") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_text")
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = describePageRange(mode, text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** The card that offers the marked document once the run is done. */
@Composable
fun MarkResultCard(
    saved: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    saveTag: String
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("The marked document", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "The marks are added. Choose where the new file is written; " +
                    "the original file stays as it is.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().testTag(saveTag)
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
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/** The button that takes the marks PDFPaper added out of the document again. */
@Composable
fun MarkRemoveButton(
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(tag)
    ) {
        Text("Remove added watermark and headers")
    }
}
