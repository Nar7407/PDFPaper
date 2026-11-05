package com.pdfpaper.app.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.ui.UiTags

/** Exact warning wording required by the UI-3 brief. */
const val VISUAL_SIGNATURE_WARNING = "Visual signatures are not protected against changes"
const val DIGITAL_SIGNATURE_WARNING =
    "Digital signatures show as unverified in other apps unless the certificate comes from a trusted authority"

/**
 * The viewer's Sign tool entry point: pick a visual signature (ink, typed name
 * or image) or a digital signature backed by an imported certificate. Each
 * option carries its honest limitation before the user commits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignOptionsSheet(
    hasUnsavedEdits: Boolean = false,
    onDismiss: () -> Unit,
    onVisual: () -> Unit,
    onDigital: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(UiTags.SIGN_OPTIONS_SHEET)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 26.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Sign this PDF",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Add a visible signature or a cryptographic one",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close sign options")
                }
            }
            Spacer(Modifier.height(14.dp))

            if (hasUnsavedEdits) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "You have unsaved edits in this session. Save them before signing; " +
                            "signing uses the saved file and will not include pending edits.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(11.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.SIGN_VISUAL_OPTION)
                    .clickable(onClick = onVisual)
            ) {
                SignOptionRow(
                    title = "Visual signature",
                    description = "Draw, type your name, or use an image. Place it anywhere on the page.",
                    icon = { Icon(Icons.Outlined.Draw, contentDescription = null) },
                    warning = VISUAL_SIGNATURE_WARNING
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.SIGN_DIGITAL_OPTION)
                    .clickable(onClick = onDigital)
            ) {
                SignOptionRow(
                    title = "Digital signature",
                    description = "Sign with an imported certificate. The document is sealed with " +
                        "cryptography and checked on this device afterwards.",
                    icon = { Icon(Icons.Outlined.Verified, contentDescription = null) },
                    warning = DIGITAL_SIGNATURE_WARNING
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Signatures are added to a copy when you save. You can keep working on the PDF " +
                    "without changing the original file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SignOptionRow(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    warning: String
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.padding(top = 2.dp)) { icon() }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                )
            }
        }
    }
}
