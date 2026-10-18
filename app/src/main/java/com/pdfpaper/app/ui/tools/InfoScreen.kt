package com.pdfpaper.app.ui.tools

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.AppTopBar
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Read-only document facts shown by the Info tool. */
private data class PdfInfo(
    val name: String,
    val sizeBytes: Long,
    val pageCount: Int,
    val version: String,
    val encrypted: Boolean,
    val rebuilt: Boolean,
    val objectCount: Int
)

@Composable
fun InfoScreen(path: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<PdfInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        info = null
        error = null
        val result = withContext(Dispatchers.IO) { runCatching { readInfo(path) } }
        result.fold(
            onSuccess = { info = it },
            onFailure = { error = it.message ?: "Could not read this PDF" }
        )
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(UiTags.INFO_SCREEN)) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(
                title = "Document info",
                subtitle = info?.name ?: File(path).name,
                onBack = onBack
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    error != null -> Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = error ?: "Could not read this PDF",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(20.dp)
                            )
                        }
                    }
                    info == null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                    else -> InfoBody(info!!, Formatter.formatShortFileSize(context, info!!.sizeBytes))
                }
            }
        }
    }
}

@Composable
private fun InfoBody(info: PdfInfo, sizeLabel: String) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = info.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                InfoRow("Pages", info.pageCount.toString())
                HorizontalDivider()
                InfoRow("PDF version", info.version.ifBlank { "Unknown" })
                HorizontalDivider()
                InfoRow("File size", sizeLabel)
                HorizontalDivider()
                InfoRow("Encrypted", if (info.encrypted) "Yes" else "No")
                HorizontalDivider()
                InfoRow("Cross-reference", if (info.rebuilt) "Rebuilt by scan" else "Standard")
                HorizontalDivider()
                InfoRow("Objects", info.objectCount.toString())
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Read-only details. Nothing in this file has been changed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 720)
@Composable
private fun InfoScreenLightPreview() {
    PDFPaperTheme(darkTheme = false) {
        Surface(modifier = Modifier.fillMaxSize().testTag(UiTags.INFO_SCREEN)) {
            Column(modifier = Modifier.fillMaxSize()) {
                AppTopBar(
                    title = "Document info",
                    subtitle = "Project notes.pdf",
                    onBack = {}
                )
                InfoBody(
                    info = PdfInfo(
                        name = "Project notes.pdf",
                        sizeBytes = 1_240_000,
                        pageCount = 8,
                        version = "1.7",
                        encrypted = false,
                        rebuilt = false,
                        objectCount = 214
                    ),
                    sizeLabel = "1.2 MB"
                )
            }
        }
    }
}

private fun readInfo(path: String): PdfInfo {
    if (path.isBlank()) throw IllegalArgumentException("No file selected")
    val file = File(path)
    val document = PdfDocument.open(FileSource(file)).getOrThrow()
    return try {
        PdfInfo(
            name = file.name,
            sizeBytes = file.length(),
            pageCount = document.pages().size,
            version = document.file.headerVersion,
            encrypted = document.isEncrypted,
            rebuilt = document.file.rebuiltFromScan,
            objectCount = document.file.objectCount
        )
    } finally {
        document.close()
    }
}
