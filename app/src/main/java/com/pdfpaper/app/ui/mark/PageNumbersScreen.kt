package com.pdfpaper.app.ui.mark

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.AppTopBar
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.mark.MarkAlign
import com.pdfpaper.engine.mark.NumberFormat
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * The page-number screen (T44): a header, a footer and the page numbers, in
 * the margins and in the format the user chooses. The preview shows the first
 * page with the header and the footer on it.
 */
@Composable
fun PageNumbersScreen(
    path: String,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: PageNumbersViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(path) { viewModel.load(path) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) viewModel.saveTo(uri)
    }

    LaunchedEffect(state.run.saved) {
        if (state.run.saved) {
            delay(SAVED_LINGER_MS)
            onSaved(state.run.savedPath ?: state.document.path)
        }
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(UiTags.MARK_NUMBERS_SCREEN)) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = "Page numbers", onBack = onBack)
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
                        .testTag(UiTags.MARK_NUMBERS_PREVIEW)
                ) { map ->
                    val total = state.document.pageCount.coerceAtLeast(1)
                    val number = state.startNumber
                    val color = PdfColor.BLACK.toComposeColor()
                    val header = expandMarkTokens(
                        state.header,
                        number,
                        total,
                        LocalDate.now().toString(),
                        state.document.fileName,
                        "",
                        state.format
                    )
                    val footer = expandMarkTokens(
                        state.footer,
                        number,
                        total,
                        LocalDate.now().toString(),
                        state.document.fileName,
                        "",
                        state.format
                    )
                    // The preview shows the first page, which is left alone
                    // when the first page is skipped.
                    if (!state.skipFirstPage && header.isNotBlank()) {
                        drawMarkText(
                            map = map,
                            text = header,
                            anchor = headerAnchor(
                                state.document.box,
                                state.marginTop,
                                state.marginLeft,
                                state.marginRight,
                                state.align
                            ),
                            sizeInPoints = state.fontSize,
                            color = color
                        )
                    }
                    if (!state.skipFirstPage && footer.isNotBlank()) {
                        drawMarkText(
                            map = map,
                            text = footer,
                            anchor = footerAnchor(
                                state.document.box,
                                state.marginBottom,
                                state.marginLeft,
                                state.marginRight,
                                state.align
                            ),
                            sizeInPoints = state.fontSize,
                            color = color
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("The lines", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value = state.header,
                            onValueChange = viewModel::setHeader,
                            label = { Text("Header (top of the page)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag(UiTags.MARK_NUMBERS_HEADER)
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = state.footer,
                            onValueChange = viewModel::setFooter,
                            label = { Text("Footer (bottom of the page)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag(UiTags.MARK_NUMBERS_FOOTER)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "In a line you can write {page}, {pages}, {date}, " +
                                "{filename} and {title}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "How the number is written",
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
                            NumberFormat.entries.forEach { format ->
                                FilterChip(
                                    selected = state.format == format,
                                    onClick = { viewModel.setFormat(format) },
                                    label = { Text(format.label) },
                                    modifier = Modifier.testTag(
                                        UiTags.markNumberFormat(format.name)
                                    )
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Where the lines sit",
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
                            MarkAlign.entries.forEach { align ->
                                FilterChip(
                                    selected = state.align == align,
                                    onClick = { viewModel.setAlign(align) },
                                    label = { Text(align.label) },
                                    modifier = Modifier.testTag(
                                        UiTags.markAlign(align.name)
                                    )
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
                            tag = UiTags.MARK_NUMBERS_FONT_SIZE
                        )
                        MarkSlider(
                            label = "Margin at the top",
                            valueLabel = "${state.marginTop.toInt()} pt",
                            value = state.marginTop.toFloat(),
                            range = MIN_MARGIN.toFloat()..MAX_MARGIN.toFloat(),
                            onValueChange = { viewModel.setMarginTop(it.toDouble()) },
                            tag = UiTags.MARK_NUMBERS_MARGIN_TOP
                        )
                        MarkSlider(
                            label = "Margin at the bottom",
                            valueLabel = "${state.marginBottom.toInt()} pt",
                            value = state.marginBottom.toFloat(),
                            range = MIN_MARGIN.toFloat()..MAX_MARGIN.toFloat(),
                            onValueChange = { viewModel.setMarginBottom(it.toDouble()) },
                            tag = UiTags.MARK_NUMBERS_MARGIN_BOTTOM
                        )
                        MarkSlider(
                            label = "Margin on the left",
                            valueLabel = "${state.marginLeft.toInt()} pt",
                            value = state.marginLeft.toFloat(),
                            range = MIN_MARGIN.toFloat()..MAX_MARGIN.toFloat(),
                            onValueChange = { viewModel.setMarginLeft(it.toDouble()) },
                            tag = UiTags.MARK_NUMBERS_MARGIN_LEFT
                        )
                        MarkSlider(
                            label = "Margin on the right",
                            valueLabel = "${state.marginRight.toInt()} pt",
                            value = state.marginRight.toFloat(),
                            range = MIN_MARGIN.toFloat()..MAX_MARGIN.toFloat(),
                            onValueChange = { viewModel.setMarginRight(it.toDouble()) },
                            tag = UiTags.MARK_NUMBERS_MARGIN_RIGHT
                        )

                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = state.startNumber.toString(),
                            onValueChange = { text ->
                                text.toIntOrNull()?.let { viewModel.setStartNumber(it) }
                            },
                            label = { Text("Start at") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().testTag(UiTags.MARK_NUMBERS_START)
                        )
                        Spacer(Modifier.height(8.dp))
                        MarkSettingSwitch(
                            label = "Skip the first page",
                            checked = state.skipFirstPage,
                            onCheckedChange = viewModel::setSkipFirstPage,
                            tag = UiTags.MARK_NUMBERS_SKIP_FIRST
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
                            tagPrefix = UiTags.MARK_NUMBERS_RANGE
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
                        state.document.path.isNotBlank() &&
                        (state.header.isNotBlank() || state.footer.isNotBlank()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .testTag(UiTags.MARK_NUMBERS_APPLY)
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
                            text = "Adding the page numbers…",
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
                        saveTag = UiTags.MARK_NUMBERS_SAVE
                    )
                }

                Spacer(Modifier.height(18.dp))
                MarkRemoveButton(
                    enabled = !state.run.working && state.document.path.isNotBlank(),
                    onClick = viewModel::removeAdded,
                    tag = UiTags.MARK_NUMBERS_REMOVE
                )
            }
        }
    }
}

/** "report.pdf" becomes "report-numbered.pdf". */
private fun suggestedFileName(name: String): String {
    val base = name.substringBeforeLast('.', name).trim()
    val safe = if (base.isEmpty()) "document" else base
    return "$safe-numbered.pdf"
}

/** How long the "Saved" note stays on screen before the screen closes itself. */
private const val SAVED_LINGER_MS = 1200L

private const val MIN_FONT_SIZE = 6f
private const val MAX_FONT_SIZE = 36f
private const val MIN_MARGIN = 0f
private const val MAX_MARGIN = 144f
