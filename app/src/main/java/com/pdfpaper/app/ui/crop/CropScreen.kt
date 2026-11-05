package com.pdfpaper.app.ui.crop

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.AppTopBar
import com.pdfpaper.engine.crop.MarginPreset
import kotlinx.coroutines.delay

/**
 * The "Crop pages" screen (T46).
 *
 * The preview is the page rendered by the viewer's own renderer. Dragging a
 * corner changes the CropBox (non-destructively; the MediaBox is untouched).
 * "Apply to all pages" uses the same margins on every selected page, while
 * "Per page" keeps one box per page. Auto-crop detects the white border from a
 * rendered page, and the remove-hidden switch runs the T28 redaction pass so
 * content outside the new box is deleted instead of merely hidden.
 */
@Composable
fun CropScreen(
    path: String,
    onBack: () -> Unit,
    onSaved: () -> Unit = {},
    viewModel: CropViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val page = state.page()
    val box = state.boxFor()
    val bitmap = state.bitmap
    val plan = state.plan
    // In per-page mode the shown margins belong to this page, not to the last
    // shared preset, so the preset chips and the "Current" line stay honest.
    val currentMargins = if (page != null && box != null) {
        marginsFor(page.current, box) ?: CropMargins.ZERO
    } else {
        state.margins
    }

    LaunchedEffect(path) { viewModel.load(path, PendingCrop.take(path)) }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            delay(SAVED_LINGER_MS)
            onSaved()
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) viewModel.saveTo(uri)
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(UiTags.CROP_SCREEN)) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = "Crop pages", onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = state.fileName.ifBlank { "This PDF" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "A crop only hides what is outside the box. The full page stays in the file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (state.loading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                } else if (page != null && (bitmap == null || plan == null)) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val renderError = state.error
                        if (renderError != null) {
                            Text(
                                text = renderError,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                } else if (page != null && bitmap != null && plan != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(336.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .testTag(UiTags.CROP_PREVIEW)
                    ) {
                        CropPreview(
                            bitmap = bitmap,
                            plan = plan,
                            pageBox = page.current,
                            cropBox = box ?: page.current,
                            showGrid = state.showGrid,
                            onCropChange = viewModel::setCropBox,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (state.rendering) {
                            Box(
                                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center
                            ) { CircularProgressIndicator() }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = viewModel::previousPage,
                            enabled = state.currentPage > 0 && !state.rendering,
                            modifier = Modifier.testTag(UiTags.CROP_PAGE_PREVIOUS)
                        ) { Text("Previous") }
                        OutlinedButton(
                            onClick = viewModel::nextPage,
                            enabled = state.currentPage < state.pages.lastIndex && !state.rendering,
                            modifier = Modifier.testTag(UiTags.CROP_PAGE_NEXT)
                        ) { Text("Next") }
                        Text(
                            text = "Page ${state.currentPage + 1} of ${state.pages.size}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = viewModel::resetCurrent,
                            enabled = !state.applying
                        ) { Text("Reset") }
                    }

                    if (box != null) {
                        CropBeforeAfter(
                            bitmap = bitmap,
                            plan = plan,
                            cropBox = box,
                            modifier = Modifier.testTag(UiTags.CROP_COMPARISON)
                        )
                        Text(
                            text = "Crop box: ${formatPoints(box.left)}, ${formatPoints(box.bottom)} → " +
                                "${formatPoints(box.right)}, ${formatPoints(box.top)} pt " +
                                "(${formatPoints(box.width)} × ${formatPoints(box.height)} pt)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    state.autoSummary?.let { autoSummary ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            ),
                            modifier = Modifier.testTag(UiTags.CROP_AUTO_SUMMARY)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(autoSummary, style = MaterialTheme.typography.bodyMedium)
                                state.autoWarnings.take(3).forEach {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }

                    SectionTitle("Pages")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        CropPageMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.pageMode == mode,
                                onClick = { viewModel.setPageMode(mode) },
                                label = { Text(mode.label) },
                                modifier = Modifier.testTag(UiTags.cropPageMode(mode.name.lowercase()))
                            )
                        }
                    }
                    if (state.pageMode == CropPageMode.CUSTOM) {
                        OutlinedTextField(
                            value = state.customPages,
                            onValueChange = viewModel::setCustomPages,
                            singleLine = true,
                            label = { Text("Page numbers") },
                            placeholder = { Text("1,3-5") },
                            modifier = Modifier.fillMaxWidth().testTag(UiTags.CROP_CUSTOM_PAGES)
                        )
                    }

                    SectionTitle("How to apply")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        CropEditMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.editMode == mode,
                                onClick = { viewModel.setEditMode(mode) },
                                label = { Text(mode.label) },
                                modifier = Modifier.testTag(UiTags.cropEditMode(mode.name.lowercase()))
                            )
                        }
                    }

                    SectionTitle("Margins")
                    Text(
                        text = "Current: ${describeCropMargins(currentMargins)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        MarginPreset.PRESETS.forEach { preset ->
                            val presetMargins = CropMargins(
                                preset.margins[0],
                                preset.margins[1],
                                preset.margins[2],
                                preset.margins[3]
                            )
                            FilterChip(
                                selected = sameMargins(presetMargins, currentMargins),
                                onClick = { viewModel.setPreset(preset) },
                                label = { Text(preset.label) },
                                modifier = Modifier.testTag(UiTags.cropPreset(preset.id))
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Switch(
                            checked = state.showGrid,
                            onCheckedChange = viewModel::setShowGrid
                        )
                        Text("Show grid", modifier = Modifier.testTag(UiTags.CROP_GRID))
                    }

                    SectionTitle("Auto-crop")
                    Text(
                        text = "Detect the white border and propose a box. Higher tolerance ignores more near-white paper.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Tolerance: ${state.tolerance}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = state.tolerance.toFloat(),
                        onValueChange = { viewModel.setTolerance(it.toInt()) },
                        valueRange = 0f..60f,
                        modifier = Modifier.testTag(UiTags.CROP_TOLERANCE)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Switch(
                            checked = state.sameSize,
                            onCheckedChange = viewModel::setSameSize,
                            modifier = Modifier.testTag(UiTags.CROP_SAME_SIZE)
                        )
                        Text("Make all pages the same size", style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(
                        onClick = viewModel::autoCrop,
                        enabled = !state.autoRunning && !state.applying,
                        modifier = Modifier.testTag(UiTags.CROP_AUTO)
                    ) {
                        if (state.autoRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (state.autoRunning) "Detecting…" else "Detect margins")
                    }

                    SectionTitle("Hidden content")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Switch(
                            checked = state.removeHidden,
                            onCheckedChange = viewModel::setRemoveHidden,
                            modifier = Modifier.testTag(UiTags.CROP_REMOVE_HIDDEN)
                        )
                        Text("Remove content outside the crop", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        text = if (state.removeHidden) {
                            "The text and images outside the new box are deleted, not just hidden. This cannot be undone."
                        } else {
                            "Off: a normal crop only hides the content outside the box. Turn this on to delete it permanently."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.removeHidden) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )

                    state.error?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Button(
                        onClick = viewModel::applyCrop,
                        enabled = !state.applying && !state.autoRunning && !state.rendering &&
                            !state.selectionIsEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.CROP_APPLY)
                    ) {
                        if (state.applying) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(if (state.applying) "Cropping…" else "Crop and preview result")
                    }

                    val result = state.result
                    if (result != null) {
                        OutlinedCard(modifier = Modifier.testTag(UiTags.CROP_RESULT)) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Ready to save",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${result.changedPages} " +
                                        (if (result.changedPages == 1) "page cropped" else "pages cropped") +
                                        if (result.hiddenContentRemoved) ", hidden content removed." else ".",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (!result.complete) {
                                    Text(
                                        text = "Some content outside the box could not be removed safely:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    result.unredacted.take(4).forEach {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                result.warnings.take(4).forEach {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Button(
                                    onClick = {
                                        saveLauncher.launch(suggestedCropName(state.fileName))
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag(UiTags.CROP_SAVE)
                                ) { Text("Save as new copy") }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                } else if (!state.loading) {
                    Text(
                        text = state.error ?: "This PDF has no pages.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

private fun sameMargins(a: CropMargins, b: CropMargins): Boolean =
    kotlin.math.abs(a.left - b.left) < 0.01 &&
        kotlin.math.abs(a.bottom - b.bottom) < 0.01 &&
        kotlin.math.abs(a.right - b.right) < 0.01 &&
        kotlin.math.abs(a.top - b.top) < 0.01

private const val SAVED_LINGER_MS = 700L
