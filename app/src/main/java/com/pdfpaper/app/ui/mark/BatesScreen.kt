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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.AppTopBar
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.mark.BatesPlacement
import kotlinx.coroutines.delay

/**
 * The Bates-number screen (T44): a fixed prefix, a number that counts up
 * from the first page and a fixed suffix, stamped in one corner of every
 * page. The preview shows the first page with the number on it.
 */
@Composable
fun BatesScreen(
    path: String,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: BatesViewModel = viewModel()
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

    Surface(modifier = Modifier.fillMaxSize().testTag(UiTags.MARK_BATES_SCREEN)) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = "Bates numbering", onBack = onBack)
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
                        .testTag(UiTags.MARK_BATES_PREVIEW)
                ) { map ->
                    val text = batesText(state.prefix, state.digits, state.startNumber, state.suffix)
                    drawMarkText(
                        map = map,
                        text = text,
                        anchor = batesAnchor(
                            state.placement,
                            state.document.box,
                            BATES_MARGIN_TOP,
                            BATES_MARGIN_BOTTOM,
                            BATES_MARGIN_LEFT,
                            BATES_MARGIN_RIGHT
                        ),
                        sizeInPoints = state.fontSize,
                        color = PdfColor.BLACK.toComposeColor()
                    )
                }
                Spacer(Modifier.height(16.dp))

                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("The number", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value = state.prefix,
                            onValueChange = viewModel::setPrefix,
                            label = { Text("Before the number") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag(UiTags.MARK_BATES_PREFIX)
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = state.suffix,
                            onValueChange = viewModel::setSuffix,
                            label = { Text("After the number") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag(UiTags.MARK_BATES_SUFFIX)
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = state.digits.toString(),
                            onValueChange = { text ->
                                text.toIntOrNull()?.let { viewModel.setDigits(it) }
                            },
                            label = { Text("How many digits") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().testTag(UiTags.MARK_BATES_DIGITS)
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
                            modifier = Modifier.fillMaxWidth().testTag(UiTags.MARK_BATES_START)
                        )

                        Spacer(Modifier.height(14.dp))
                        MarkSlider(
                            label = "Size",
                            valueLabel = "${state.fontSize.toInt()} pt",
                            value = state.fontSize.toFloat(),
                            range = MIN_FONT_SIZE.toFloat()..MAX_FONT_SIZE.toFloat(),
                            onValueChange = { viewModel.setFontSize(it.toDouble()) },
                            tag = UiTags.MARK_BATES_FONT_SIZE
                        )

                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Where the number sits",
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
                            BatesPlacement.entries.forEach { placement ->
                                FilterChip(
                                    selected = state.placement == placement,
                                    onClick = { viewModel.setPlacement(placement) },
                                    label = { Text(placement.label) },
                                    modifier = Modifier.testTag(
                                        UiTags.markBatesPlacement(placement.name)
                                    )
                                )
                            }
                        }
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
                            tagPrefix = UiTags.MARK_BATES_RANGE
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
                        .testTag(UiTags.MARK_BATES_APPLY)
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
                            text = "Stamping the numbers…",
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
                        saveTag = UiTags.MARK_BATES_SAVE
                    )
                }

                Spacer(Modifier.height(18.dp))
                MarkRemoveButton(
                    enabled = !state.run.working && state.document.path.isNotBlank(),
                    onClick = viewModel::removeAdded,
                    tag = UiTags.MARK_BATES_REMOVE
                )
            }
        }
    }
}

/** "report.pdf" becomes "report-bates.pdf". */
private fun suggestedFileName(name: String): String {
    val base = name.substringBeforeLast('.', name).trim()
    val safe = if (base.isEmpty()) "document" else base
    return "$safe-bates.pdf"
}

/** How long the "Saved" note stays on screen before the screen closes itself. */
private const val SAVED_LINGER_MS = 1200L

/** The margins the engine uses for a Bates number, in points. */
private const val BATES_MARGIN_TOP = 28.0
private const val BATES_MARGIN_BOTTOM = 28.0
private const val BATES_MARGIN_LEFT = 36.0
private const val BATES_MARGIN_RIGHT = 36.0

private const val MIN_FONT_SIZE = 6f
private const val MAX_FONT_SIZE = 36f
