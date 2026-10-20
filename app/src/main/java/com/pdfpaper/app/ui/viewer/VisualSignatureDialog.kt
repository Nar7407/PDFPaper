package com.pdfpaper.app.ui.viewer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pdfpaper.app.signatures.SavedSignature
import com.pdfpaper.app.signatures.SavedSignatureStore
import com.pdfpaper.app.signatures.SignatureDraft
import com.pdfpaper.app.signatures.SignatureFontStyle
import com.pdfpaper.app.signatures.SignatureSample
import com.pdfpaper.app.signatures.isEmpty
import com.pdfpaper.app.signatures.previewAspect
import com.pdfpaper.app.ui.UiTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private enum class SignaturePadMode { DRAW, TYPE, IMAGE }

/**
 * Full-screen visual-signature editor: draw with a finger or stylus, type a
 * name in one of four styles, or choose an image. Saved signatures are loaded
 * from encrypted app storage and can be reused; saving a new one is optional.
 */
@Composable
fun VisualSignatureDialog(
    author: String,
    onDismiss: () -> Unit,
    onConfirm: (draft: SignatureDraft, captionName: String?, captionDate: String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by rememberSaveable { mutableStateOf(SignaturePadMode.DRAW) }
    var strokes by remember { mutableStateOf<List<List<SignatureSample>>>(emptyList()) }
    var typedName by rememberSaveable { mutableStateOf("") }
    var typedStyle by rememberSaveable { mutableStateOf(SignatureFontStyle.SCRIPT) }
    var imageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var imageError by remember { mutableStateOf<String?>(null) }
    var selectedSaved by remember { mutableStateOf<SavedSignature?>(null) }
    var saved by remember { mutableStateOf<List<SavedSignature>>(emptyList()) }
    var addCaption by rememberSaveable { mutableStateOf(true) }
    var captionName by rememberSaveable { mutableStateOf(author) }
    var captionDate by rememberSaveable { mutableStateOf(currentSignatureDate()) }
    var saveForLater by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        saved = withContext(Dispatchers.IO) {
            SavedSignatureStore(context).load().getOrDefault(emptyList())
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            imageError = null
            imageBytes = null
            busy = true
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                }
                busy = false
                if (bytes == null || bytes.isEmpty()) {
                    imageError = "Could not read that image. Choose a PNG or JPEG."
                } else if (bytes.size > 12 * 1024 * 1024) {
                    imageError = "That image is too large. Choose one under 12 MB."
                } else {
                    imageBytes = bytes
                    selectedSaved = null
                }
            }
        }
    }

    val currentDraft: SignatureDraft? = selectedSaved?.draft ?: when (mode) {
        SignaturePadMode.DRAW ->
            SignatureDraft.Drawn(strokes).takeUnless { it.isEmpty() }
        SignaturePadMode.TYPE ->
            SignatureDraft.Typed(typedName.trim(), typedStyle).takeUnless { it.isEmpty() }
        SignaturePadMode.IMAGE -> imageBytes?.let { SignatureDraft.Image(it) }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !busy,
            dismissOnClickOutside = false
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize().testTag(UiTags.VISUAL_SIGNATURE_DIALOG)) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (!busy) onDismiss() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close signature editor")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Add your signature",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            VISUAL_SIGNATURE_WARNING,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (saved.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Saved signatures",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "Manage in Settings",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(saved, key = { it.id }) { item ->
                                val selected = selectedSaved?.id == item.id
                                OutlinedCard(
                                    modifier = Modifier
                                        .width(148.dp)
                                        .clickable { selectedSaved = item }
                                        .testTag(UiTags.savedSignatureItem(item.id)),
                                    border = if (selected) {
                                        androidx.compose.foundation.BorderStroke(
                                            2.dp,
                                            MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant
                                        )
                                    }
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(6.dp)
                                    ) {
                                        SignaturePreview(
                                            draft = item.draft,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(46.dp)
                                                .border(
                                                    0.5.dp,
                                                    MaterialTheme.colorScheme.outlineVariant,
                                                    RoundedCornerShape(4.dp)
                                                ),
                                            compact = true
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            item.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            item.kindLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    if (selectedSaved != null) {
                        Text(
                            "Using a saved signature",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                SignaturePreview(
                                    draft = selectedSaved!!.draft,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(120.dp)
                                        .border(
                                            0.5.dp,
                                            MaterialTheme.colorScheme.outlineVariant,
                                            RoundedCornerShape(6.dp)
                                        )
                                )
                                TextButton(onClick = { selectedSaved = null }) {
                                    Text("Start a new signature")
                                }
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = mode == SignaturePadMode.DRAW,
                                onClick = {
                                    mode = SignaturePadMode.DRAW
                                    error = null
                                },
                                label = { Text("Draw") }
                            )
                            FilterChip(
                                selected = mode == SignaturePadMode.TYPE,
                                onClick = {
                                    mode = SignaturePadMode.TYPE
                                    error = null
                                },
                                label = { Text("Type") }
                            )
                            FilterChip(
                                selected = mode == SignaturePadMode.IMAGE,
                                onClick = {
                                    mode = SignaturePadMode.IMAGE
                                    error = null
                                },
                                label = { Text("Image") }
                            )
                        }
                        Spacer(Modifier.height(8.dp))

                        when (mode) {
                            SignaturePadMode.DRAW -> {
                                SignatureDrawPad(
                                    strokes = strokes,
                                    onStrokesChange = { strokes = it }
                                )
                                if (strokes.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(onClick = { strokes = strokes.dropLast(1) }) {
                                            Icon(
                                                Icons.Outlined.Undo,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("Undo stroke")
                                        }
                                        TextButton(onClick = { strokes = emptyList() }) {
                                            Icon(
                                                Icons.Outlined.Delete,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("Clear")
                                        }
                                    }
                                } else {
                                    Text(
                                        "Draw with your finger or stylus above the line.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            SignaturePadMode.TYPE -> {
                                OutlinedTextField(
                                    value = typedName,
                                    onValueChange = { typedName = it.take(80) },
                                    label = { Text("Your name") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag(UiTags.SIGNATURE_TYPE_NAME)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Style",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Spacer(Modifier.height(4.dp))
                                val styles = SignatureFontStyle.entries
                                styles.chunked(2).forEach { rowStyles ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        rowStyles.forEach { style ->
                                            FilterChip(
                                                selected = typedStyle == style,
                                                onClick = { typedStyle = style },
                                                label = {
                                                    Text(
                                                        style.label,
                                                        fontFamily = style.previewFamily(),
                                                        fontStyle = if (style == SignatureFontStyle.CLASSIC) {
                                                            FontStyle.Italic
                                                        } else {
                                                            FontStyle.Normal
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                                SignaturePreview(
                                    draft = SignatureDraft.Typed(typedName, typedStyle),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(110.dp)
                                        .border(
                                            0.5.dp,
                                            MaterialTheme.colorScheme.outlineVariant,
                                            RoundedCornerShape(8.dp)
                                        )
                                )
                            }

                            SignaturePadMode.IMAGE -> {
                                if (imageBytes == null) {
                                    OutlinedCard(Modifier.fillMaxWidth()) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(22.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                Icons.Outlined.AddPhotoAlternate,
                                                contentDescription = null,
                                                modifier = Modifier.size(34.dp)
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                "Choose a photo of your signature",
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                "PNG or JPEG. White paper around the signature is removed automatically.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(Modifier.height(12.dp))
                                            Button(
                                                onClick = { imagePicker.launch("image/*") },
                                                modifier = Modifier.testTag(UiTags.SIGNATURE_PICK_IMAGE)
                                            ) {
                                                Text("Choose image")
                                            }
                                        }
                                    }
                                } else {
                                    SignaturePreview(
                                        draft = SignatureDraft.Image(imageBytes!!),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(150.dp)
                                            .border(
                                                0.5.dp,
                                                MaterialTheme.colorScheme.outlineVariant,
                                                RoundedCornerShape(8.dp)
                                            )
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { imagePicker.launch("image/*") }) {
                                            Text("Choose another")
                                        }
                                        TextButton(onClick = { imageBytes = null }) {
                                            Text("Remove")
                                        }
                                    }
                                }
                                imageError?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = addCaption,
                            onCheckedChange = { addCaption = it },
                            modifier = Modifier.testTag(UiTags.SIGNATURE_ADD_CAPTION)
                        )
                        Column(Modifier.weight(1f)) {
                            Text("Add name and date below", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "The caption becomes part of the signature's appearance.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (addCaption) {
                        OutlinedTextField(
                            value = captionName,
                            onValueChange = { captionName = it.take(80) },
                            label = { Text("Printed name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = captionDate,
                            onValueChange = { captionDate = it.take(60) },
                            label = { Text("Date") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (selectedSaved == null) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = saveForLater,
                                onCheckedChange = { saveForLater = it },
                                modifier = Modifier.testTag(UiTags.SIGNATURE_SAVE_FOR_LATER)
                            )
                            Column(Modifier.weight(1f)) {
                                Text("Save this signature for later", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Stored only on this device, encrypted with an Android Keystore key.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { if (!busy) onDismiss() }) { Text("Cancel") }
                        Spacer(Modifier.weight(1f))
                        Button(
                            enabled = !busy && currentDraft != null,
                            onClick = {
                                val draft = currentDraft ?: return@Button
                                error = null
                                busy = true
                                scope.launch {
                                    if (saveForLater && selectedSaved == null) {
                                        val result = withContext(Dispatchers.IO) {
                                            SavedSignatureStore(context).save(draft)
                                        }
                                        if (result.isFailure) {
                                            busy = false
                                            error = result.exceptionOrNull()?.message
                                                ?: "The signature could not be saved."
                                            return@launch
                                        }
                                    }
                                    busy = false
                                    onConfirm(
                                        draft,
                                        captionName.takeIf { addCaption }?.trim()?.ifBlank { null },
                                        captionDate.takeIf { addCaption }?.trim()?.ifBlank { null }
                                    )
                                }
                            },
                            modifier = Modifier.testTag(UiTags.SIGNATURE_PLACE_BUTTON)
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Place signature")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignatureDrawPad(
    strokes: List<List<SignatureSample>>,
    onStrokesChange: (List<List<SignatureSample>>) -> Unit
) {
    // pointerInput(Unit) never restarts, so it must not close over the current
    // strokes parameter directly; a gesture would keep seeing the first frame's
    // value. rememberUpdatedState supplies the latest committed list at the
    // moment a new stroke starts, and the local list carries the points during
    // the gesture.
    val latestStrokes by rememberUpdatedState(strokes)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp, max = 230.dp)
            .height(205.dp)
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .testTag(UiTags.SIGNATURE_PAD)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    fun point(offset: Offset): SignatureSample? {
                        if (size.width <= 0 || size.height <= 0) return null
                        return SignatureSample(
                            x = (offset.x / size.width).coerceIn(0f, 1f),
                            y = (offset.y / size.height).coerceIn(0f, 1f)
                        )
                    }
                    var working: MutableList<MutableList<SignatureSample>>? = null
                    detectDragGestures(
                        onDragStart = { offset ->
                            point(offset)?.let { started ->
                                working = latestStrokes
                                    .map { it.toMutableList() }
                                    .toMutableList()
                                    .apply { add(mutableListOf(started)) }
                                onStrokesChange(working!!.map { it.toList() })
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val next = point(change.position) ?: return@detectDragGestures
                            val active = working ?: return@detectDragGestures
                            active.last().add(next)
                            onStrokesChange(active.map { it.toList() })
                        },
                        onDragEnd = { working = null },
                        onDragCancel = { working = null }
                    )
                }
        ) {
            val baseline = size.height * 0.78f
            drawLine(
                color = Color(0xFFD4D4D8),
                start = Offset(size.width * 0.08f, baseline),
                end = Offset(size.width * 0.92f, baseline),
                strokeWidth = 1.2.dp.toPx()
            )
            strokes.forEach { stroke ->
                if (stroke.isEmpty()) return@forEach
                val path = Path()
                path.moveTo(stroke.first().x * size.width, stroke.first().y * size.height)
                for (i in 1 until stroke.size) {
                    val sample = stroke[i]
                    path.lineTo(sample.x * size.width, sample.y * size.height)
                }
                drawPath(
                    path = path,
                    color = Color(0xFF18181B),
                    style = Stroke(
                        width = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
        if (strokes.isEmpty()) {
            Text(
                "Sign here",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFA1A1AA),
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

private fun currentSignatureDate(): String =
    LocalDate.now().format(
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    )
