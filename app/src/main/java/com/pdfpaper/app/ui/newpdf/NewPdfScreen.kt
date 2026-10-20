package com.pdfpaper.app.ui.newpdf

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.AppTopBar
import com.pdfpaper.app.ui.components.rememberUriImageBitmap

/**
 * New PDF (task UI-2): blank, images or text with page size and orientation.
 * Keeps every step local; the save dialog is the only place the file leaves
 * the app's private cache.
 */
@Composable
fun NewPdfScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    viewModel: NewPdfViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.addImages(uris) }

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

    Scaffold(
        topBar = { AppTopBar(title = "New PDF", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.testTag(UiTags.NEW_PDF_SCREEN)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Text(
                "Create a PDF",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Start blank, turn photos into pages, or lay out plain text. Everything is built on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NewPdfMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = { viewModel.setMode(mode) },
                        label = { Text(mode.label) },
                        modifier = Modifier.testTag(UiTags.newPdfMode(mode.name))
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Page setup", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Page size",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NewPdfPageSize.entries.forEach { size ->
                            FilterChip(
                                selected = state.pageSize == size,
                                onClick = { viewModel.setPageSize(size) },
                                label = { Text(size.label) }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Orientation",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !state.landscape,
                            onClick = { viewModel.setLandscape(false) },
                            label = { Text("Portrait") }
                        )
                        FilterChip(
                            selected = state.landscape,
                            onClick = { viewModel.setLandscape(true) },
                            label = { Text("Landscape") }
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            when (state.mode) {
                NewPdfMode.BLANK -> BlankCard()
                NewPdfMode.IMAGES -> ImagesCard(
                    state = state,
                    onAdd = { imagePicker.launch(arrayOf("image/*")) },
                    onRemove = viewModel::removeImage
                )
                NewPdfMode.TEXT -> TextCard(
                    text = state.text,
                    onTextChange = viewModel::setText
                )
            }

            state.error?.let { error ->
                Spacer(Modifier.height(14.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
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

            Spacer(Modifier.height(22.dp))
            Button(
                onClick = viewModel::create,
                enabled = state.canCreate,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag(UiTags.NEW_PDF_CREATE)
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Working…")
                } else {
                    Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Create PDF")
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "You will choose where to save the new PDF next.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun BlankCard() {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.PictureAsPdf,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("One blank page", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A clean page ready for notes, stamps or typing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ImagesCard(
    state: NewPdfUiState,
    onAdd: () -> Unit,
    onRemove: (android.net.Uri) -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Photos and scans", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.images.isEmpty()) {
                            "Each image becomes one page, scaled to fit."
                        } else {
                            "${state.images.size} image${if (state.images.size == 1) "" else "s"} · one page each"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onAdd, modifier = Modifier.testTag(UiTags.NEW_PDF_ADD_IMAGES)) {
                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add")
                }
            }
            if (state.images.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.images, key = { it.uri.toString() }) { image ->
                        ImageTile(image, onRemove)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageTile(image: PickedImage, onRemove: (android.net.Uri) -> Unit) {
    val bitmap = rememberUriImageBitmap(image.uri)
    Card(modifier = Modifier.width(104.dp)) {
        Box {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = image.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(82.dp)
                        .background(Color(0xFFF2F2F2))
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(82.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                }
            }
            IconButton(
                onClick = { onRemove(image.uri) },
                modifier = Modifier.align(Alignment.TopEnd).size(28.dp)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove ${image.name}",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Text(
            image.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun TextCard(text: String, onTextChange: (String) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Write the content", style = MaterialTheme.typography.titleMedium)
            Text(
                "Line breaks are kept; pages flow automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text("Start writing…") },
                minLines = 7,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.NEW_PDF_TEXT)
            )
        }
    }
}
