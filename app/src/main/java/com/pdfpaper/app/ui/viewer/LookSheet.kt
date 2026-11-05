package com.pdfpaper.app.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.data.rgbOf
import com.pdfpaper.app.render.toArgb
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.render.ColorTransform
import com.pdfpaper.engine.render.PageFilter
import com.pdfpaper.engine.render.PageTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * T62: the page look. Filters and sliders are applied to this document only and
 * are remembered per document (keyed by its fingerprint); the PDF itself is
 * never written. Every change is live - the page behind the sheet re-renders -
 * so the reader chooses by looking at their own page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookSheet(
    look: ColorTransform,
    /** True when this document has a look of its own, so Reset has work to do. */
    fromDocument: Boolean,
    onApply: (ColorTransform) -> Unit,
    onReset: () -> Unit,
    onUseAsDefault: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        LookSheetContent(
            look = look,
            fromDocument = fromDocument,
            onApply = onApply,
            onReset = onReset,
            onUseAsDefault = onUseAsDefault,
            onDismiss = onDismiss,
            modifier = Modifier.testTag(UiTags.LOOK_SHEET)
        )
    }
}

@Composable
fun LookSheetContent(
    look: ColorTransform,
    fromDocument: Boolean,
    onApply: (ColorTransform) -> Unit,
    onReset: () -> Unit,
    onUseAsDefault: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The draft is what the reader is looking at right now. It is sent to the
    // view model shortly after it stops moving, so dragging a slider does not
    // queue one full re-render per pixel of travel.
    var draft by remember { mutableStateOf(look) }
    LaunchedEffect(look) { if (draft != look) draft = look }
    LaunchedEffect(draft) {
        if (draft == look) return@LaunchedEffect
        delay(PREVIEW_DEBOUNCE_MS)
        onApply(draft)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = "Page look",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Applies to this document only. The file on disk is never changed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Text(
            text = "Filter",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PageFilter.entries.forEach { filter ->
                FilterChip(
                    selected = draft.filter == filter,
                    onClick = { draft = draft.copy(filter = filter) },
                    label = { Text(filter.label) },
                    modifier = Modifier.testTag(UiTags.lookFilterChip(filter.name))
                )
            }
        }

        if (draft.filter == PageFilter.SEPIA) {
            LookSlider(
                label = "Warmth",
                value = draft.warmth.toFloat(),
                range = 0f..1f,
                tag = UiTags.LOOK_WARMTH,
                valueLabel = "${(draft.warmth * 100).roundToInt()}%",
                onChange = { draft = draft.copy(warmth = it.toDouble()) }
            )
        }

        if (draft.filter == PageFilter.INVERTED_MONO) {
            Text(
                text = "Tint",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TINTS.forEach { tint ->
                    val selected = tint == draft.tint
                    val swatch = Color(rgbOf(tint) or 0xFF000000.toInt())
                    Surface(
                        shape = CircleShape,
                        color = swatch,
                        modifier = Modifier
                            .size(40.dp)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape
                            )
                            .testTag(UiTags.lookTint(rgbOf(tint).toString(16)))
                            .clickable(
                                role = Role.RadioButton,
                                onClick = { draft = draft.copy(tint = tint) }
                            )
                            .semantics { contentDescription = tintName(tint) }
                    ) {}
                }
            }
        }

        LookSlider(
            label = "Brightness",
            value = draft.brightness.toFloat(),
            range = -0.5f..0.5f,
            tag = UiTags.LOOK_BRIGHTNESS,
            valueLabel = signed(draft.brightness),
            onChange = { draft = draft.copy(brightness = it.toDouble()) }
        )
        LookSlider(
            label = "Contrast",
            value = draft.contrast.toFloat(),
            range = 0.5f..2f,
            tag = UiTags.LOOK_CONTRAST,
            valueLabel = "${(draft.contrast * 100).roundToInt()}%",
            onChange = { draft = draft.copy(contrast = it.toDouble()) }
        )
        LookSlider(
            label = "Gamma",
            value = draft.gamma.toFloat(),
            range = 0.4f..2.5f,
            tag = UiTags.LOOK_GAMMA,
            valueLabel = String.format(java.util.Locale.US, "%.2f", draft.gamma),
            onChange = { draft = draft.copy(gamma = it.toDouble()) }
        )

        Text(
            text = "Dark and true black keep the reader's ink and photo-brightness " +
                "settings from Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    draft = ColorTransform.NONE
                    onReset()
                },
                // Nothing to reset until this document has a look of its own.
                enabled = fromDocument,
                modifier = Modifier.testTag(UiTags.LOOK_RESET)
            ) {
                Text("Reset to default")
            }
            TextButton(
                onClick = onUseAsDefault,
                modifier = Modifier.testTag(UiTags.LOOK_USE_DEFAULT)
            ) {
                Text("Use as default for new documents", maxLines = 2)
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(UiTags.LOOK_DONE)
            ) {
                Text("Done")
            }
        }
    }
}

/** The tint choices of [PageFilter.INVERTED_MONO]; the first is the default. */
val TINTS = listOf(
    ColorTransform.DEFAULT_TINT,
    PdfColor(1.0, 1.0, 1.0),
    PdfColor(1.0, 0.85, 0.60),
    PdfColor(0.62, 0.95, 0.70)
)

private fun tintName(tint: PdfColor): String = when {
    tint.red > 0.95 && tint.green > 0.95 && tint.blue > 0.95 -> "White tint"
    tint.red > 0.95 -> "Amber tint"
    tint.green > 0.9 -> "Green tint"
    else -> "Blueprint blue tint"
}

private fun signed(value: Double): String =
    if (value >= 0) "+${(value * 100).roundToInt()}%" else "${(value * 100).roundToInt()}%"

@Composable
private fun LookSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    tag: String,
    valueLabel: String,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(text = valueLabel, style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange(it) },
            valueRange = range,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag)
        )
    }
}

/**
 * The look suggestion (T62): sepia for a scan on aged paper, inverted
 * monochrome for a line drawing. It is only ever offered - the page is left
 * exactly as it is until the reader taps the offer.
 */
@Composable
fun LookHintBanner(
    message: String,
    filter: PageFilter,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 3.dp,
        modifier = modifier.testTag(UiTags.LOOK_HINT)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onApply,
                modifier = Modifier.testTag(UiTags.LOOK_HINT_APPLY)
            ) {
                Text(filter.label)
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(UiTags.LOOK_HINT_DISMISS)
            ) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
    }
}

/** How long a slider has to stand still before the page is re-rendered. */
private const val PREVIEW_DEBOUNCE_MS = 110L

@Preview(showBackground = true, widthDp = 411, heightDp = 720, name = "Look sheet")
@Composable
private fun LookSheetPreview() {
    PDFPaperTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            LookSheetContent(
                look = ColorTransform(filter = PageFilter.SEPIA),
                fromDocument = true,
                onApply = {},
                onReset = {},
                onUseAsDefault = {},
                onDismiss = {},
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            )
        }
    }
}

/** The look's page background, for the surface behind the page bitmap. */
fun lookBackground(look: ColorTransform, theme: PageTheme): Color =
    Color(look.background(theme).toArgb(1.0))
