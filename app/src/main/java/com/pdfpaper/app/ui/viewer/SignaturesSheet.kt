package com.pdfpaper.app.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.signatures.SignatureReport
import com.pdfpaper.signatures.SignatureState
import com.pdfpaper.signatures.SignatureVerification
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ViewerSignaturesState(
    val loading: Boolean = false,
    val verification: SignatureVerification? = null,
    val error: String? = null
)

/**
 * Signatures panel (task UI-2). Every check is shown with its own state, and a
 * green tick appears only when the verifier actually passed every check. The
 * "Not checked" card is always present, so the panel never implies more
 * certainty than the offline verifier has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignaturesSheet(
    state: ViewerSignaturesState,
    onDismiss: () -> Unit,
    onReload: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(UiTags.SIGNATURES_SHEET)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Signatures",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Checked on this device · nothing is uploaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close signatures")
                }
            }
            Spacer(Modifier.height(8.dp))

            when {
                state.loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Checking signatures…")
                    }
                }

                state.error != null -> {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Could not check signatures", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                state.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.material3.TextButton(onClick = onReload) {
                                Text("Try again")
                            }
                        }
                    }
                }

                state.verification == null || state.verification.signatures.isEmpty() -> {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            Text("No signatures in this PDF", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "If the document was signed, its signature field will be listed here with " +
                                    "the result of every check.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                else -> {
                    val verification = state.verification
                    Text(
                        "${verification.signatures.size} signature field" +
                            if (verification.signatures.size == 1) "" else "s",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(verification.signatures, key = { it.fieldName }) { report ->
                            SignatureCard(report)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "This checks the file and the certificate chain on this device. It does not decide " +
                    "whether the signer had authority to sign.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SignatureCard(report: SignatureReport) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().testTag(UiTags.signatureCard(report.fieldName))
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                VerdictIcon(report)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        report.fieldName.ifBlank { "Signature field" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        signatureHeadline(report),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                signatureChecks(report).forEach { check -> CheckRow(check) }
            }

            report.signer?.let { signer ->
                Spacer(Modifier.height(12.dp))
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Signer", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(5.dp))
                        Detail("Name", signer.commonName ?: signer.subject)
                        Detail("Subject", signer.subject)
                        Detail("Issuer", signer.issuer)
                        Detail("Serial", signer.serialNumber)
                        Detail("Valid from", formatInstant(signer.notBefore))
                        Detail("Valid until", formatInstant(signer.notAfter))
                    }
                }
            }

            val details = ArrayList<Pair<String, String>>()
            report.subFilter?.let { details += "SubFilter" to it }
            report.digestAlgorithm?.let { details += "Digest" to it }
            val signingTime = report.signingTime
            if (signingTime != null) {
                details += "Signing time" to formatInstant(signingTime)
            } else {
                report.signingTimeText?.let { details += "Signing time" to it }
            }
            report.reason?.let { details += "Reason" to it }
            report.location?.let { details += "Location" to it }
            if (details.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column {
                    details.forEach { (label, value) -> Detail(label, value) }
                }
            }

            if (report.problems.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(9.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "Problems",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        report.problems.forEach { problem ->
                            Text(
                                "• $problem",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("Not checked", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(3.dp))
                    signatureNotChecked(report).forEach { note ->
                        Text(
                            "• $note",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VerdictIcon(report: SignatureReport) {
    val complete = allChecksPassed(report)
    val (icon, tint) = when {
        complete -> Icons.Filled.CheckCircle to Color(0xFF2E7D32)
        report.state == SignatureState.INVALID ||
            report.state == SignatureState.MODIFIED_AFTER_SIGNING ->
            Icons.Filled.Cancel to MaterialTheme.colorScheme.error
        report.state == SignatureState.INTACT_AND_TRUSTED ->
            Icons.Outlined.Info to MaterialTheme.colorScheme.primary
        else -> Icons.Outlined.Info to MaterialTheme.colorScheme.tertiary
    }
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(tint.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun CheckRow(check: SignatureCheck) {
    val (icon, tint) = when (check.state) {
        SignatureCheckState.PASSED -> Icons.Filled.CheckCircle to Color(0xFF2E7D32)
        SignatureCheckState.FAILED -> Icons.Filled.Cancel to MaterialTheme.colorScheme.error
        SignatureCheckState.NOT_CHECKED ->
            Icons.Outlined.RemoveCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                check.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                check.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private val SIGNATURE_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())

private fun formatInstant(instant: Instant): String = SIGNATURE_TIME.format(instant)
