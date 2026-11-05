package com.pdfpaper.app.ui.safety

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.AppTopBar
import com.pdfpaper.safety.RiskLevel
import com.pdfpaper.safety.SafetyCategory
import com.pdfpaper.safety.SafetyFinding
import com.pdfpaper.safety.SafetyReport

/**
 * Plain-language details of the static safety check (T49). The screen only
 * explains findings; it never runs an action, follows a link or contacts a
 * service. The wording deliberately never calls a document "safe".
 */
@Composable
fun SafetyScreen(
    path: String,
    displayName: String? = null,
    onBack: () -> Unit,
    viewModel: SafetyViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val blockActions = remember(context) { AppSettings.blockDocumentActions(context) }

    LaunchedEffect(path) { viewModel.scan(path) }

    Surface(modifier = Modifier.fillMaxSize().testTag(UiTags.SAFETY_SCREEN)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = "Safety check",
                subtitle = displayName ?: path.substringAfterLast('/'),
                onBack = onBack
            )
            when {
                state.loading || (state.report == null && state.error == null) -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Checking the document structure...")
                    }
                }
                state.error != null -> Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.error ?: "The safety check could not finish",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {
                    val report = state.report ?: SafetyReport.error("No report")
                    SafetyReportBody(report = report, blockActions = blockActions)
                }
            }
        }
    }
}

@Composable
private fun SafetyReportBody(report: SafetyReport, blockActions: Boolean) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(UiTags.SAFETY_FINDINGS),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            RiskSummaryCard(report)
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(
                            "Document actions and scripts are blocked",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (blockActions) {
                                "PDFPaper never executes JavaScript, form submissions or " +
                                    "launch actions. This setting is ON."
                            } else {
                                "This setting is off, but PDFPaper still never executes " +
                                    "JavaScript, actions or links."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (report.findings.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "No known risky features found",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "This does not prove the document is safe. It only means the " +
                                "check did not see the features it knows about.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(report.findings, key = { it.id }) { finding ->
                FindingCard(finding)
            }
        }
        item {
            Text(
                text = SafetyReport.DISCLAIMER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun RiskSummaryCard(report: SafetyReport) {
    val color = riskColor(report.risk)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = color.copy(alpha = 0.16f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = report.risk.label,
                        color = color,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
                Spacer(Modifier.height(0.dp))
                Text(
                    text = report.summary(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "${report.pageCount} page${if (report.pageCount == 1) "" else "s"} · " +
                    "${report.scannedObjects} objects inspected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (report.encrypted) {
                Text(
                    "The document is encrypted. Findings describe what could be read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FindingCard(finding: SafetyFinding) {
    val color = riskColor(finding.severity)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = finding.severity.label,
                    color = color,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "  ${finding.category.label}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = finding.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            finding.location?.let {
                Text(
                    text = it.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = finding.explanation,
                style = MaterialTheme.typography.bodyMedium
            )
            finding.evidence?.let { evidence ->
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Observed: $evidence",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            finding.link?.let { link ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Real domain: ${link.domainLabel()}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                link.visibleText?.takeIf { it.isNotBlank() }?.let { visible ->
                    Text(
                        text = "Visible text: $visible",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                link.warnings.forEach { warning ->
                    Text(
                        text = "• $warning",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun riskColor(level: RiskLevel): Color = when (level) {
    RiskLevel.HIGH -> MaterialTheme.colorScheme.error
    RiskLevel.MEDIUM -> Color(0xFFB26A00)
    RiskLevel.LOW -> MaterialTheme.colorScheme.primary
}
