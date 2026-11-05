package com.pdfpaper.app.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.signatures.ChainStatus
import com.pdfpaper.signatures.SignatureReport
import com.pdfpaper.signatures.SignatureState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The post-signing result screen. A visual signature always says it is only
 * visual; a digital signature shows the verifier's per-check result and never
 * invents a trust verdict the verifier did not reach.
 */
@Composable
fun SigningResultDialog(
    outcome: ViewerSigningOutcome,
    onSave: () -> Unit,
    onDone: () -> Unit
) {
    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize().testTag(UiTags.SIGNING_RESULT_DIALOG)) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                ) {
                    if (outcome.visualOnly) {
                        VisualResult(outcome)
                    } else {
                        DigitalResult(outcome)
                    }
                }

                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onDone,
                            modifier = Modifier.testTag(UiTags.SIGNING_RESULT_DONE)
                        ) { Text("Done") }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = onSave,
                            modifier = Modifier.testTag(UiTags.SIGNING_RESULT_SAVE)
                        ) { Text("Save signed PDF") }
                    }
                }
            }
        }
    }
}

@Composable
private fun VisualResult(outcome: ViewerSigningOutcome) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Draw,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "Visual signature added",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Page ${outcome.pageIndex + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                VISUAL_SIGNATURE_WARNING,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "This is ink on the page, not a cryptographic signature. Anyone with a PDF " +
                    "editor can move or change it, and other apps cannot verify who signed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
    Spacer(Modifier.height(14.dp))
    Text(
        "The signature is still only in this unsaved session. Save a copy to keep it.",
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun DigitalResult(outcome: ViewerSigningOutcome) {
    val report = outcome.report
    val headline = when {
        report != null -> signatureHeadline(report)
        else -> "The file was signed, but the new signature could not be checked."
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        VerdictBubble(report)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "Digital signature",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                headline,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    outcome.certificate?.let { certificate ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Signed by", style = MaterialTheme.typography.labelMedium)
                Text(
                    certificate.commonName ?: certificate.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    certificate.subject,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Detail("Field", report?.fieldName ?: "new signature")
                report?.signingTime?.let { Detail("Signed at", formatSigningInstant(it)) }
                outcome.reason?.let { Detail("Reason", it) }
                outcome.location?.let { Detail("Location", it) }
                Detail(
                    "Appearance",
                    if (outcome.visible) "Visible on page ${outcome.pageIndex + 1}" else "Invisible"
                )
                report?.chainStatus?.let { chain ->
                    Detail(
                        "Certificate",
                        when (chain) {
                            ChainStatus.TRUSTED -> "Trusted on this device"
                            ChainStatus.UNTRUSTED -> "Not trusted on this device"
                            ChainStatus.NOT_CHECKED -> "Trust not checked"
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    if (report != null) {
        Text("Checks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            signatureChecks(report).forEach { check -> ResultCheckRow(check) }
        }
        if (report.problems.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
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
    } else {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "Verification did not run",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    outcome.verificationError
                        ?: "The signature was written, but the verifier could not read the file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            DIGITAL_SIGNATURE_WARNING,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(11.dp)
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "The signature is still only in this unsaved session. Save a signed copy, then open " +
            "that file and choose Signatures to check it again.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun VerdictBubble(report: SignatureReport?) {
    val (icon, tint) = when {
        report == null -> Icons.Outlined.Info to MaterialTheme.colorScheme.tertiary
        allChecksPassed(report) -> Icons.Filled.CheckCircle to Color(0xFF2E7D32)
        report.state == SignatureState.INVALID ||
            report.state == SignatureState.MODIFIED_AFTER_SIGNING ->
            Icons.Filled.Cancel to MaterialTheme.colorScheme.error
        report.state == SignatureState.INTACT_AND_TRUSTED ->
            Icons.Outlined.Info to MaterialTheme.colorScheme.primary
        else -> Icons.Outlined.Info to MaterialTheme.colorScheme.tertiary
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(tint.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ResultCheckRow(check: SignatureCheck) {
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
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(82.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private val RESULT_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())

private fun formatSigningInstant(instant: Instant): String = RESULT_TIME.format(instant)
