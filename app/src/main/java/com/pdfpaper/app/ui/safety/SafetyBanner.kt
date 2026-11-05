package com.pdfpaper.app.ui.safety

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.safety.RiskLevel
import com.pdfpaper.safety.SafetyReport

/**
 * A dismissible strip shown when the background safety check found something
 * medium or high (T49). Low findings do not interrupt reading; they are still
 * in the details screen.
 */
@Composable
fun SafetyBanner(
    report: SafetyReport,
    onOpenDetails: () -> Unit,
    onDismiss: () -> Unit
) {
    val color = if (report.risk == RiskLevel.HIGH) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val onColor = if (report.risk == RiskLevel.HIGH) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(
        color = color,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.SAFETY_BANNER)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = onColor
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    text = "Safety check: ${report.risk.label.lowercase()}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = onColor
                )
                Text(
                    text = report.summary() + " Tap for details.",
                    style = MaterialTheme.typography.bodySmall,
                    color = onColor
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Heuristic check, not antivirus. No script or link was run.",
                    style = MaterialTheme.typography.labelSmall,
                    color = onColor
                )
            }
            TextButton(
                onClick = onOpenDetails,
                modifier = Modifier.testTag(UiTags.SAFETY_BANNER_DETAILS)
            ) {
                Text("Details", color = onColor)
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(UiTags.SAFETY_BANNER_DISMISS)
            ) {
                Text("×", style = MaterialTheme.typography.titleLarge, color = onColor)
            }
        }
    }
}
