package com.pdfpaper.app.ui.redact

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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

/**
 * The required warning before anything is rewritten. The primary action is
 * "Save as new copy", matching the promise that the original stays untouched.
 */
@Composable
fun RedactionConfirmDialog(
    markCount: Int,
    pageCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply redactions?") },
        text = {
            Column {
                Text(
                    "This permanently removes content. A new copy will be saved. " +
                        "The original stays unchanged."
                )
                Text(
                    "$markCount mark${if (markCount == 1) "" else "s"} on " +
                        "$pageCount page${if (pageCount == 1) "" else "s"}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(UiTags.REDACT_CONFIRM_SAVE)
            ) { Text("Save as new copy") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        modifier = Modifier.testTag(UiTags.REDACT_CONFIRM)
    )
}

@Composable
fun RedactionBusyDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Redacting") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Removing the marked content, then checking the new copy.")
            }
        },
        confirmButton = {}
    )
}

/**
 * The report is built only from the engine's verification of the saved bytes.
 * When no text was marked there is no text match count, and the dialog says so
 * instead of claiming a clean result.
 */
@Composable
fun RedactionVerificationDialog(
    outcome: RedactionOutcome,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val verification = outcome.verification
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Verification report")
                Text(
                    "${outcome.marksApplied} mark${if (outcome.marksApplied == 1) "" else "s"} applied",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Surface(
                    color = if (verification.passed) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = if (verification.passed) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!verification.passed) {
                                Icon(
                                    Icons.Outlined.WarningAmber,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                verification.headline,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            verification.detail,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                    }
                }

                if (verification.problems.isNotEmpty()) {
                    Text(
                        "Problems found",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                    )
                    verification.problems.forEach { problem ->
                        OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "\u201c${problem.expected}\u201d",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    problem.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }

                if (verification.unredacted.isNotEmpty()) {
                    Text(
                        "Could not remove",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                    )
                    verification.unredacted.forEach { item ->
                        Text(
                            "\u2022 $item",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }

                if (outcome.warnings.isNotEmpty()) {
                    Text(
                        "Warnings",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                    )
                    outcome.warnings.take(8).forEach { warning ->
                        Text(
                            "\u2022 $warning",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }

                Text(
                    if (verification.passed) {
                        "The new copy is ready. Saving writes these verified bytes to a file you choose."
                    } else {
                        "Do not share this copy until the problems above are understood. " +
                            "You can discard it and try a larger mark instead."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                modifier = Modifier.testTag(UiTags.REDACT_VERIFICATION_SAVE)
            ) {
                Text(if (verification.passed) "Save as new copy" else "Save anyway")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (verification.passed) "Not now" else "Discard")
            }
        },
        modifier = Modifier.testTag(UiTags.REDACT_VERIFICATION)
    )
}

@Composable
fun SanitizeBusyDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Cleaning document") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Removing the checked items and writing a fresh copy.")
            }
        },
        confirmButton = {}
    )
}
