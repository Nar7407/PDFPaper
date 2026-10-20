package com.pdfpaper.app.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.pdfpaper.app.data.CustomStampStore
import com.pdfpaper.app.render.AndroidRasterDecoder
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.rememberUriImageBitmap
import com.pdfpaper.engine.stamp.CustomStamp
import com.pdfpaper.engine.stamp.StampCatalogue
import com.pdfpaper.engine.stamp.StampDesign
import com.pdfpaper.engine.stamp.StampGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * "Add a stamp" bottom sheet (task UI-2): Dynamic, Sign here, Standard business
 * and Custom groups, using the engine's original StampLibrary designs. A tap
 * selects a design and closes the sheet, after which the viewer places it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StampSheet(
    author: String,
    onDismiss: () -> Unit,
    onSelect: (StampDesign) -> Unit
) {
    val context = LocalContext.current
    val store = remember { CustomStampStore(context) }
    var customs by remember { mutableStateOf(store.load()) }
    var creating by remember { mutableStateOf(false) }
    val catalogue = remember(customs) { StampCatalogue(customs) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(UiTags.STAMP_SHEET)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Text(
                "Add a stamp",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Pick a stamp, then tap the page to place it. Drag to move and pinch to resize.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))

            for ((group, designs) in catalogue.groups()) {
                if (designs.isEmpty() && group != StampGroup.CUSTOM) continue
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        group.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${designs.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    designs.forEach { design ->
                        StampTile(
                            design = design,
                            author = author,
                            custom = catalogue.custom(design.id),
                            onClick = { onSelect(design) }
                        )
                    }
                    if (group == StampGroup.CUSTOM) {
                        CreateStampTile(onClick = { creating = true })
                    }
                }
                if (group == StampGroup.CUSTOM && designs.isEmpty()) {
                    Text(
                        "No custom stamps yet. Make one from text or a picture.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(18.dp))
            }

            Text(
                "Tip: dynamic stamps add your name and today's date when placed. Custom stamps are saved on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (creating) {
        CreateStampDialog(
            store = store,
            onDismiss = { creating = false },
            onCreated = { stamps ->
                customs = stamps
                creating = false
            }
        )
    }
}

@Composable
private fun StampTile(
    design: StampDesign,
    author: String,
    custom: CustomStamp?,
    onClick: () -> Unit
) {
    val aspect = design.aspect.takeIf { it.isFinite() && it > 0.35 }?.toFloat() ?: 2.6f
    OutlinedCard(
        modifier = Modifier
            .width(150.dp)
            .clickable(onClick = onClick)
            .testTag(UiTags.stampItem(design.id))
    ) {
        Box(Modifier.padding(horizontal = 9.dp, vertical = 9.dp)) {
            StampPreview(
                design = design,
                author = author,
                imageBytes = custom?.image,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().aspectRatio(aspect)
            )
        }
        Text(
            design.label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 9.dp)
        )
    }
}

@Composable
private fun CreateStampTile(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(150.dp)
            .height(82.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag(UiTags.STAMP_CREATE_CUSTOM),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                "Create custom stamp",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun CreateStampDialog(
    store: CustomStampStore,
    onDismiss: () -> Unit,
    onCreated: (List<CustomStamp>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(CustomKind.TEXT) }
    var label by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var imageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val imagePreview = rememberUriImageBitmap(imageUri)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            imageUri = uri
            imageBytes = null
            error = null
            busy = true
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { readBytes(context, uri) }.getOrNull()
                }
                imageBytes = bytes
                busy = false
                if (bytes == null) error = "Could not read that image."
            }
        }
    }

    fun create() {
        if (busy) return
        val cleanLabel = label.trim().ifBlank {
            if (kind == CustomKind.TEXT) text.trim().take(28) else ""
        }
        if (cleanLabel.isBlank()) {
            error = "Give the stamp a name."
            return
        }
        busy = true
        error = null
        val id = "custom-" + UUID.randomUUID().toString().replace("-", "").take(12)
        val result = when (kind) {
            CustomKind.TEXT -> CustomStamp.text(id, cleanLabel, text)
            CustomKind.IMAGE -> imageBytes?.let { CustomStamp.image(id, cleanLabel, it, AndroidRasterDecoder) }
                ?: Result.failure(IllegalArgumentException("Choose a JPEG or PNG image first."))
        }
        result.onSuccess { stamp ->
            store.add(stamp).fold(
                onSuccess = { onCreated(it) },
                onFailure = {
                    busy = false
                    error = "Could not save the stamp."
                }
            )
        }.onFailure {
            busy = false
            error = it.message ?: "Could not create the stamp."
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.STAMP_CUSTOM_DIALOG)
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Create custom stamp",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { if (!busy) onDismiss() }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == CustomKind.TEXT,
                        onClick = { kind = CustomKind.TEXT },
                        label = { Text("Text") }
                    )
                    FilterChip(
                        selected = kind == CustomKind.IMAGE,
                        onClick = { kind = CustomKind.IMAGE },
                        label = { Text("Image") }
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it; error = null },
                    label = { Text("Stamp name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                if (kind == CustomKind.TEXT) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; error = null },
                        label = { Text("Text on the stamp") },
                        placeholder = { Text("APPROVED") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    if (text.isNotBlank()) {
                        val design = remember(text, label) {
                            CustomStamp.text(
                                "preview",
                                label.ifBlank { "Preview" },
                                text
                            ).getOrNull()?.toDesign()
                        }
                        if (design != null) {
                            StampPreview(
                                design = design,
                                author = "",
                                fontSize = 14.sp,
                                showDynamicLine = false,
                                modifier = Modifier.fillMaxWidth().aspectRatio(2.6f)
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (imagePreview != null) {
                            androidx.compose.foundation.Image(
                                bitmap = imagePreview,
                                contentDescription = "Custom stamp image",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().padding(8.dp)
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Outlined.AddPhotoAlternate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Choose a JPEG or PNG",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { picker.launch("image/*") }, enabled = !busy) {
                        Text(if (imageBytes == null) "Choose image" else "Choose another image")
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { if (!busy) onDismiss() }, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { create() },
                        enabled = !busy && when (kind) {
                            CustomKind.TEXT -> label.isNotBlank() || text.isNotBlank()
                            CustomKind.IMAGE -> imageBytes != null
                        },
                        modifier = Modifier.weight(1f).testTag(UiTags.STAMP_CUSTOM_CREATE)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Create")
                        }
                    }
                }
            }
        }
    }
}

private enum class CustomKind { TEXT, IMAGE }

private fun readBytes(context: Context, uri: Uri): ByteArray {
    val input = context.contentResolver.openInputStream(uri) ?: error("Could not read the image.")
    return input.use { stream ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            require(total <= 24 * 1024 * 1024) { "The image is larger than 24 MB." }
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }
}
