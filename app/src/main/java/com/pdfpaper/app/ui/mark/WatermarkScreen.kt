package com.pdfpaper.app.ui.mark

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.AppTopBar
import com.pdfpaper.engine.mark.WatermarkPosition
import kotlinx.coroutines.delay

/**
 * The watermark screen (T44): a text or a picture on the pages, with the
 * size, the colour, the angle and the place the user chooses. The preview
 * shows the first page with the watermark on it.
 */
@Composable
fun WatermarkScreen(
    path: String,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: WatermarkViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(path) { viewModel.load(path) }

    val picturePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) viewModel.pickImage(uri)
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) viewModel.saveTo(uri)
    }

    // The note that the file is saved stays on screen for a moment before the
    // screen hands the marked document over.
    LaunchedEffect(state.run.saved) {
        if (state.run.saved) {
            delay(SAVED_LINGER_MS)
            onSaved(state.run.savedPath ?: state.document.path)
        }
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(UiTags.MARK_WATERMARK_SCREEN)) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = "Watermark", onBack = onBack)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Text(
                    text = state.document.fileName.ifBlank { "This PDF" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (state.document.pageCount > 0) {
                        "${state.document.pageCount} page${if (state.document.pageCount == 1) "" else "s"}"
                    } else {
                        "Opening the document…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))

                MarkPreview(
                    bitmap = state.document.preview,
                    box = state.document.box,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .testTag(UiTags.MARK_WATERMARK_PREVIEW)
                ) { map ->
                    val color = state.color.toPdfColor().toComposeColor()
                    val picture = state.imageBitmap
                    if (state.useImage && picture != null) {
                        val box = state.document.box
                        val width = box.width * IMAGE_WIDTH_FRACTION
                        val height = width * (picture.height.toDouble() / picture.width.toDouble())
                        drawMarkImage(
                            map = map,
                            bitmap = picture,
                            userX = box.left + box.width / 2.0 - width / 2.0,
                            userY = box.bottom + box.height / 2.0 - height / 2.0,
                            widthInPoints = width,
                            heightInPoints = height,
                            alpha = state.opacity.toFloat(),
                            rotationDegrees = state.rotation
                        )
                    } else if (state.text.isNotBlank()) {
                        watermarkAnchors(state.position, state.document.box, state.fontSize)
                            .forEach { anchor ->
                                drawMarkText(
                                    map = map,
                                    text = state.text,
                                    anchor = anchor,
                                    sizeInPoints = state.fontSize,
                                    color = color,
                                    alpha = state.opacity.toFloat(),
                                    rotationDegrees = state.rotation
                                )
                            }
                    }
                }
                Spacer(Modifier.height(16.dp))

                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("The watermark", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value = state.text,
                            onValueChange = viewModel::setText,
                            label = { Text("Text on the page") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag(UiTags.MARK_WATERMARK_TEXT)
                        )
                        Spacer(Modifier.height(10.dp))

                        MarkSettingSwitch(
                            label = "Use a picture instead",
                            checked = state.useImage,
                            onCheckedChange = { useImage ->
                                viewModel.setUseImage(useImage)
                                // Turning the switch on opens the gallery
                                // right away.
                                if (useImage) picturePicker.launch("image/*")
                            },
                            tag = UiTags.MARK_WATERMARK_USE_IMAGE
                        )
                        if (state.useImage) {
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = { picturePicker.launch("image/*") },
                                modifier = Modifier.testTag(UiTags.MARK_WATERMARK_PICK_IMAGE)
                            ) {
                                Text(
                                    if (state.imageName.isBlank()) {
                                        "Choose a picture"
                                    } else {
                                        "Change the picture (${state.imageName})"
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        MarkSlider(
                            label = "Size",
                            valueLabel = "${state.fontSize.toInt()} pt",
                            value = state.fontSize.toFloat(),
                            range = MIN_FONT_SIZE.toFloat()..MAX_FONT_SIZE.toFloat(),
                            onValueChange = { viewModel.setFontSize(it.toDouble()) },
                            tag = UiTags.MARK_WATERMARK_FONT_SIZE
                        )
                        MarkSlider(
                            label = "Transparency",
                            valueLabel = "${(state.opacity * 100).toInt()}%",
                            value = state.opacity.toFloat(),
                            range = MIN_OPACITY.toFloat()..MAX_OPACITY.toFloat(),
                            onValueChange = { viewModel.setOpacity(it.toDouble()) },
                            tag = UiTags.MARK_WATERMARK_OPACITY
                        )
                        MarkSlider(
                            label = "Angle",
                            valueLabel = "${state.rotation.toInt()}°",
                            value = state.rotation.toFloat(),
                            range = MIN_ROTATION.toFloat()..MAX_ROTATION.toFloat(),
                            onValueChange = { viewModel.setRotation(it.toDouble()) },
                            tag = UiTags.MARK_WATERMARK_ROTATION
                        )

                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Colour",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            MarkColorChoice.entries.forEach { choice ->
                                val swatch = choice.toPdfColor().toComposeColor()
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .background(swatch, CircleShape)
                                        .border(
                                            width = if (choice == state.color) 3.dp else 1.dp,
                                            color = if (choice == state.color) {
                                                MaterialTheme.colorScheme.onSurface
                                            } else {
                                                MaterialTheme.colorScheme.outline
                                            },
                                            shape = CircleShape
                                        )
                                        .clickable { viewModel.setColor(choice) }
                                        .testTag(UiTags.markColor(choice.name))
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Where it sits",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            WatermarkPosition.entries.forEach { position ->
                                FilterChip(
                                    selected = state.position == position,
                                    onClick = { viewModel.setPosition(position) },
                                    label = { Text(position.label) },
                                    modifier = Modifier.testTag(
                                        UiTags.markWatermarkPosition(position.name)
                                    )
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        MarkSettingSwitch(
                            label = "Behind the content",
                            checked = state.behindContent,
                            onCheckedChange = viewModel::setBehindContent,
                            tag = UiTags.MARK_WATERMARK_BEHIND
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))

                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Which pages", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))
                        MarkPageRangePicker(
                            mode = state.rangeMode,
                            text = state.rangeText,
                            onModeChange = viewModel::setRangeMode,
                            onTextChange = viewModel::setRangeText,
                            tagPrefix = UiTags.MARK_WATERMARK_RANGE
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                state.run.error?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }

                Button(
                    onClick = viewModel::apply,
                    enabled = !state.run.working &&
                        !state.document.loading &&
                        state.document.path.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .testTag(UiTags.MARK_WATERMARK_APPLY)
                ) {
                    Text("Apply")
                }

                if (state.run.working) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            text = "Adding the watermark…",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (state.run.hasResult) {
                    Spacer(Modifier.height(18.dp))
                    MarkResultCard(
                        saved = state.run.saved,
                        onSave = { saveLauncher.launch(suggestedFileName(state.document.fileName)) },
                        onDismiss = viewModel::dismissResult,
                        saveTag = UiTags.MARK_WATERMARK_SAVE
                    )
                }

                Spacer(Modifier.height(18.dp))
                MarkRemoveButton(
                    enabled = !state.run.working && state.document.path.isNotBlank(),
                    onClick = viewModel::removeAdded,
                    tag = UiTags.MARK_WATERMARK_REMOVE
                )
            }
        }
    }
}

/** "report.pdf" becomes "report-watermarked.pdf". */
private fun suggestedFileName(name: String): String {
    val base = name.substringBeforeLast('.', name).trim()
    val safe = if (base.isEmpty()) "document" else base
    return "$safe-watermarked.pdf"
}

/** How long the "Saved" note stays on screen before the screen closes itself. */
private const val SAVED_LINGER_MS = 1200L

/** The width of a picture watermark, as a share of the page width. */
private const val IMAGE_WIDTH_FRACTION = 0.4

private const val MIN_FONT_SIZE = 8f
private const val MAX_FONT_SIZE = 144f
private const val MIN_OPACITY = 0.05f
private const val MAX_OPACITY = 1f
private const val MIN_ROTATION = -180f
private const val MAX_ROTATION = 180f
