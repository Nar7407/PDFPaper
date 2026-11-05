package com.pdfpaper.app.ui.scan

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.AppTopBar
import com.pdfpaper.app.ui.components.findActivity
import com.pdfpaper.app.ui.components.rememberUriImageBitmap

/**
 * Scan screen (task UI-2). Capture, multi-page handling, crop and filters are
 * provided by the ML Kit Document Scanner; the returned JPEG pages become a
 * PDF through the engine's [com.pdfpaper.engine.create.PdfFromImages].
 */
@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    viewModel: ScanViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var launching by remember { mutableStateOf(false) }

    val scanner = remember {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setPageLimit(100)
                .setGalleryImportAllowed(true)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
        )
    }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        launching = false
        if (result.resultCode == Activity.RESULT_OK) {
            val scan = result.data?.let { GmsDocumentScanningResult.fromActivityResultIntent(it) }
            val pages = scan?.pages?.map { it.imageUri }.orEmpty()
            viewModel.onScanned(pages)
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) viewModel.saveTo(uri)
    }

    LaunchedEffect(state.pendingBytes) {
        state.pendingBytes?.let { saveLauncher.launch(state.pendingName) }
    }
    LaunchedEffect(state.createdPath) {
        state.createdPath?.let(onCreated)
    }

    fun startScan() {
        val activity = context.findActivity()
        if (activity == null) {
            viewModel.scanFailed("Scanning needs an activity window. Reopen the app and try again.")
            return
        }
        launching = true
        scanner.getStartScanIntent(activity)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { failure ->
                launching = false
                val message = failure.message.orEmpty()
                viewModel.scanFailed(
                    if (message.contains("play services", true) ||
                        message.contains("MlKit", true) ||
                        message.contains("not available", true)
                    ) {
                        "Document scanning needs Google Play services. Install or enable it, then try again."
                    } else {
                        "The scanner could not start. ${message.take(160)}".trim()
                    }
                )
            }
    }

    Scaffold(
        topBar = { AppTopBar(title = "Scan", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.testTag(UiTags.SCAN_SCREEN)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Text(
                "Scan a document",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Multiple pages, automatic crop and colour filters. The scanner runs on your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))

            ScanHero(hasPages = state.hasScan, pageCount = state.pages.size)
            Spacer(Modifier.height(14.dp))

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Feature(Icons.Outlined.DocumentScanner, "Multi-page", "Add pages in one pass, then review them.")
                    Feature(Icons.Outlined.Crop, "Crop & straighten", "Edge detection squares up each page.")
                    Feature(Icons.Outlined.Tune, "Filters", "Colour, grayscale or high-contrast black and white.")
                }
            }

            state.error?.let { error ->
                Spacer(Modifier.height(14.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        error,
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (state.pages.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text(
                    "${state.pages.size} page${if (state.pages.size == 1) "" else "s"} ready",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.pages, key = { it.toString() }) { uri ->
                        val bitmap = rememberUriImageBitmap(uri)
                        Card(modifier = Modifier.width(96.dp)) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(128.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (bitmap != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bitmap,
                                        contentDescription = "Scanned page",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(Icons.Outlined.DocumentScanner, contentDescription = null)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            if (state.busy) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Building the PDF…", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (state.readyBytes != null) {
                Button(
                    onClick = viewModel::saveAsPdf,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag(UiTags.SCAN_SAVE)
                ) {
                    Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save as PDF")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { startScan() },
                    enabled = !launching,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text("Scan another document")
                }
            } else {
                Button(
                    onClick = { startScan() },
                    enabled = !launching,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag(UiTags.SCAN_START)
                ) {
                    if (launching) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Outlined.DocumentScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (launching) "Opening scanner…" else "Start scan")
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Saving creates a new PDF. Pages are not uploaded by PDFPaper.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun ScanHero(hasPages: Boolean, pageCount: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(vertical = 24.dp, horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.DocumentScanner,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (hasPages) "Document captured" else "Ready to scan",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (hasPages) "$pageCount page${if (pageCount == 1) "" else "s"} · review, then save"
                else "Point the camera at a page; edges are detected for you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            if (hasPages) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Captured pages stay on this device",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun Feature(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
