package com.pdfpaper.app.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.theme.PDFPaperTheme

/**
 * The banner the viewer shows above a PDF that was converted from a .docx or
 * .pptx. The conversion keeps the text and the layout but not everything, so
 * the banner says so, offers the result to the user, and lists what could not
 * be reproduced under "What's missing?".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SimplifiedViewBanner(
    warnings: List<String>,
    onSaveAsPdf: () -> Unit,
    onOpenInAnotherApp: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMissing by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().testTag(UiTags.OFFICE_BANNER)
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Simplified view: some elements may not display correctly",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(UiTags.OFFICE_BANNER_DISMISS)
                ) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = "Dismiss")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onSaveAsPdf) { Text("Save as PDF") }
                TextButton(onClick = onOpenInAnotherApp) { Text("Open in another app") }
                TextButton(
                    onClick = { showMissing = true },
                    modifier = Modifier.testTag(UiTags.OFFICE_BANNER_MISSING)
                ) {
                    Text("What's missing?")
                }
            }
        }
    }

    if (showMissing) {
        MissingDialog(warnings = warnings, onClose = { showMissing = false })
    }
}

@Composable
private fun MissingDialog(warnings: List<String>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("What's missing?") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (warnings.isEmpty()) {
                    Text("Nothing was dropped from this document.")
                } else {
                    Text(
                        "The PDF keeps the text and the layout. These parts of the " +
                            "original document could not be reproduced:"
                    )
                    Spacer(Modifier.height(10.dp))
                    for (warning in warnings) {
                        Text(
                            text = "- $warning",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("Close") }
        }
    )
}

@Preview(showBackground = true, widthDp = 411)
@Composable
private fun SimplifiedViewBannerPreview() {
    PDFPaperTheme(darkTheme = false) {
        SimplifiedViewBanner(
            warnings = listOf(
                "font 'Calibri' is replaced by Helvetica; line breaks may differ",
                "a chart is not converted",
                "a rotated shape is drawn upright"
            ),
            onSaveAsPdf = {},
            onOpenInAnotherApp = {},
            onDismiss = {}
        )
    }
}
