package com.pdfpaper.app.ui.viewer.reflow

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.engine.reflow.ReflowBlock
import com.pdfpaper.engine.reflow.ReflowBlockKind
import com.pdfpaper.engine.reflow.ReflowPage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * T33: the reflow reading view. It is a full-screen overlay inside the viewer,
 * so it reuses the open document, its password state and the page theme, and
 * it never touches the file.
 *
 * The list has one item per page. A page's item is a placeholder until its
 * blocks are extracted, then the same item index shows the real paragraphs, so
 * the reader's scroll position never jumps when text arrives.
 */
@Composable
fun ReflowOverlay(
    state: ReflowUiState,
    pageCount: Int,
    regions: Map<String, ReflowRegion>,
    onBack: () -> Unit,
    onRequestPage: (Int) -> Unit,
    onRequestRegion: (page: Int, block: Int, pixelWidth: Int) -> Unit,
    onPosition: (page: Int, fraction: Float) -> Unit,
    onSettings: (ReflowSettings) -> Unit,
    onOcr: (page: Int, language: String) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.active) return
    val settings = state.settings
    val listState = rememberLazyListState()
    val heights = remember(state.active) { mutableStateMapOf<Int, Int>() }
    val blockOffsets = remember(state.active) { mutableStateMapOf<String, Int>() }
    val latestState by rememberUpdatedState(state)
    var showSettings by remember { mutableStateOf(false) }
    var appliedEntry by remember { mutableStateOf(false) }

    val currentPage by remember {
        derivedStateOf { listState.firstVisibleItemIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) }
    }

    // Start on the paragraph the reader was on in page view. The block offsets
    // are only known once the page has been laid out, so this waits for them
    // and then puts that block at the top of the screen.
    LaunchedEffect(state.entry, state.active) {
        appliedEntry = false
        val entry = state.entry ?: return@LaunchedEffect
        listState.scrollToItem(entry.page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
        if (entry.fraction <= 0f) appliedEntry = true
    }
    LaunchedEffect(state.entry, state.pages, blockOffsets.size, heights.size, appliedEntry) {
        if (appliedEntry) return@LaunchedEffect
        val entry = state.entry ?: return@LaunchedEffect
        val page = state.pages[entry.page]
        if (page != null && page.blocks.isNotEmpty()) {
            val block = page.blockForFraction(entry.fraction.toDouble()) ?: return@LaunchedEffect
            val blockIndex = page.blocks.indexOf(block)
            val offset = blockOffsets["${entry.page}:$blockIndex"] ?: return@LaunchedEffect
            listState.scrollToItem(entry.page, offset.coerceAtLeast(0))
            appliedEntry = true
            return@LaunchedEffect
        }
        // A scanned, blank or not-yet-read page: fall back to the page fraction.
        val height = heights[entry.page] ?: return@LaunchedEffect
        if (entry.fraction > 0f) {
            listState.scrollToItem(entry.page, offsetForFraction(entry.fraction, height))
        }
        appliedEntry = true
    }

    // Pages near the viewport are extracted first; the rest stay placeholders.
    LaunchedEffect(listState, pageCount) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                for (offset in -1..2) {
                    val target = index + offset
                    if (target in 0 until pageCount) onRequestPage(target)
                }
            }
    }

    // Report the reading spot as the paragraph at the top of the viewport, so
    // switching back to page view lands on that paragraph rather than a pixel.
    LaunchedEffect(listState) {
        snapshotFlow {
            val index = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            val page = latestState.pages[index]
            val top = page?.blocks?.indices?.lastOrNull { blockIndex ->
                (blockOffsets["$index:$blockIndex"] ?: Int.MAX_VALUE) <= offset
            }
            Triple(index, offset, top)
        }
            .distinctUntilChanged()
            .collect { (page, offset, topBlock) ->
                val extracted = latestState.pages[page]
                val fraction = if (extracted != null && topBlock != null) {
                    extracted.fractionOf(extracted.blocks[topBlock]).toFloat()
                } else {
                    pageFraction(offset, heights[page] ?: 0)
                }
                onPosition(page, fraction.coerceIn(0f, 1f))
            }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize().testTag(UiTags.REFLOW_OVERLAY)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ReflowTopBar(
                page = currentPage,
                pageCount = pageCount,
                settings = settings,
                onBack = onBack,
                onSettings = { showSettings = true }
            )
            val message = state.ocrMessage
            if (message != null) {
                OcrMessageBar(message = message, onDismiss = onDismissMessage)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).testTag(UiTags.REFLOW_LIST),
                contentPadding = PaddingValues(
                    start = settings.marginDp.dp,
                    end = settings.marginDp.dp,
                    top = 8.dp,
                    bottom = 32.dp
                )
            ) {
                items(count = pageCount, key = { it }) { index ->
                    ReflowPageItem(
                        index = index,
                        state = state,
                        regions = regions,
                        onRequestPage = onRequestPage,
                        onRequestRegion = onRequestRegion,
                        onOcr = onOcr,
                        onHeight = { heights[index] = it },
                        onBlockOffset = { blockIndex, y ->
                            blockOffsets["$index:$blockIndex"] = y
                        }
                    )
                }
            }
        }
    }

    if (showSettings) {
        ReflowSettingsSheet(
            settings = settings,
            onSettings = onSettings,
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun ReflowTopBar(
    page: Int,
    pageCount: Int,
    settings: ReflowSettings,
    onBack: () -> Unit,
    onSettings: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag(UiTags.REFLOW_BACK)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to page view"
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Reflow", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (pageCount > 0) {
                        "Page ${page + 1} of $pageCount · ${describeReflow(settings)}"
                    } else {
                        describeReflow(settings)
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            }
            IconButton(
                onClick = onSettings,
                modifier = Modifier.testTag(UiTags.REFLOW_SETTINGS)
            ) {
                Icon(imageVector = Icons.Filled.Settings, contentDescription = "Reading settings")
            }
        }
    }
}

@Composable
private fun OcrMessageBar(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
    }
}

@Composable
private fun ReflowPageItem(
    index: Int,
    state: ReflowUiState,
    regions: Map<String, ReflowRegion>,
    onRequestPage: (Int) -> Unit,
    onRequestRegion: (page: Int, block: Int, pixelWidth: Int) -> Unit,
    onOcr: (page: Int, language: String) -> Unit,
    onHeight: (Int) -> Unit,
    onBlockOffset: (blockIndex: Int, y: Int) -> Unit
) {
    val page = state.pages[index]
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 28.dp)
            .onGloballyPositioned { onHeight(it.size.height) }
    ) {
        Text(
            text = "Page ${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        when {
            page == null -> ReflowPlaceholder(
                index = index,
                loading = index in state.loading,
                onRequestPage = onRequestPage
            )
            page.scanned -> ScannedPageCard(
                page = page,
                ocrBusy = state.ocrBusy,
                ocrPage = state.ocrPage,
                onOcr = onOcr
            )
            page.blocks.isEmpty() -> Text(
                text = "This page is blank.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> {
                val family = reflowFontFamily(state.settings.font)
                page.blocks.forEachIndexed { blockIndex, block ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned {
                                onBlockOffset(blockIndex, it.positionInParent().y.roundToInt())
                            }
                    ) {
                        ReflowBlockView(
                            pageIndex = index,
                            blockIndex = blockIndex,
                            block = block,
                            settings = state.settings,
                            family = family,
                            regions = regions,
                            onRequestRegion = onRequestRegion
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReflowPlaceholder(
    index: Int,
    loading: Boolean,
    onRequestPage: (Int) -> Unit
) {
    LaunchedEffect(index) { onRequestPage(index) }
    Box(
        modifier = Modifier.fillMaxWidth().height(REFLOW_PLACEHOLDER_DP.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = if (loading) "Reading page ${index + 1}…" else "Page ${index + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ScannedPageCard(
    page: ReflowPage,
    ocrBusy: Boolean,
    ocrPage: Int?,
    onOcr: (page: Int, language: String) -> Unit
) {
    val running = ocrBusy && ocrPage == page.index
    Card(modifier = Modifier.fillMaxWidth().testTag(UiTags.REFLOW_SCANNED)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "This page is scanned",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "There is no text layer to reflow. Run OCR to read this page, " +
                    "then it reflows like the rest of the document. The original file " +
                    "is not changed.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            if (running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Reading this page…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                // Two scripts, chosen before the run: a bare "Hindi" button
                // next to "Run OCR" read like a stray label in the T33
                // screenshots.
                var language by remember { mutableStateOf("eng") }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = language == "eng",
                        onClick = { language = "eng" },
                        label = { Text("English") },
                        modifier = Modifier.testTag(UiTags.reflowOcrLanguage("eng"))
                    )
                    FilterChip(
                        selected = language == "hin",
                        onClick = { language = "hin" },
                        label = { Text("Hindi") },
                        modifier = Modifier.testTag(UiTags.reflowOcrLanguage("hin"))
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onOcr(page.index, language) },
                    enabled = !ocrBusy,
                    modifier = Modifier.testTag(UiTags.REFLOW_OCR_BUTTON)
                ) {
                    Text("Run OCR")
                }
            }
        }
    }
}

@Composable
private fun ReflowBlockView(
    pageIndex: Int,
    blockIndex: Int,
    block: ReflowBlock,
    settings: ReflowSettings,
    family: FontFamily,
    regions: Map<String, ReflowRegion>,
    onRequestRegion: (page: Int, block: Int, pixelWidth: Int) -> Unit
) {
    when (block.kind) {
        ReflowBlockKind.PARAGRAPH,
        ReflowBlockKind.HEADING,
        ReflowBlockKind.LIST_ITEM -> TextBlock(
            block = block,
            settings = settings,
            family = family
        )
        ReflowBlockKind.IMAGE -> RegionBlock(
            pageIndex = pageIndex,
            blockIndex = blockIndex,
            label = "Picture",
            table = false,
            regions = regions,
            onRequestRegion = onRequestRegion
        )
        ReflowBlockKind.TABLE -> RegionBlock(
            pageIndex = pageIndex,
            blockIndex = blockIndex,
            label = "Table",
            table = true,
            regions = regions,
            onRequestRegion = onRequestRegion
        )
    }
}

@Composable
private fun TextBlock(
    block: ReflowBlock,
    settings: ReflowSettings,
    family: FontFamily
) {
    val heading = block.kind == ReflowBlockKind.HEADING
    val scale = when {
        !heading -> 1f
        block.level <= 1 -> 1.5f
        block.level == 2 -> 1.3f
        else -> 1.12f
    }
    val spacing = if (heading) settings.paragraphSpacingDp * 0.6f else settings.paragraphSpacingDp
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = family,
        fontSize = (settings.fontSizeSp * scale).sp,
        lineHeight = (settings.lineHeightSp * scale).sp,
        fontWeight = if (heading) FontWeight.Bold else FontWeight.Normal,
        textAlign = when {
            heading -> TextAlign.Start
            settings.align == ReflowAlign.JUSTIFIED -> TextAlign.Justify
            else -> TextAlign.Start
        }
    )
    if (block.kind == ReflowBlockKind.LIST_ITEM && block.marker.isNotEmpty()) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = spacing.dp)) {
            Text(
                text = block.marker,
                style = style,
                modifier = Modifier.width(26.dp)
            )
            Text(text = block.body, style = style, modifier = Modifier.weight(1f))
        }
    } else {
        Text(
            text = block.text,
            style = style,
            modifier = Modifier.fillMaxWidth().padding(bottom = spacing.dp)
        )
    }
}

/**
 * A picture or a table is painted from the page itself, so it looks exactly as
 * it does in page view (theme included). A wide table is rendered larger than
 * the column and shown in a sideways scroll box, so its columns stay legible
 * instead of shrinking to nothing.
 */
@Composable
private fun RegionBlock(
    pageIndex: Int,
    blockIndex: Int,
    label: String,
    table: Boolean,
    regions: Map<String, ReflowRegion>,
    onRequestRegion: (page: Int, block: Int, pixelWidth: Int) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        val density = LocalDensity.current
        val columnPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val requestPx = if (table) min(columnPx * 3, 2400) else columnPx
        LaunchedEffect(requestPx) { onRequestRegion(pageIndex, blockIndex, requestPx) }
        val region = regions[reflowRegionKey(pageIndex, blockIndex, requestPx)]
        if (region == null) {
            Box(
                modifier = Modifier.fillMaxWidth().height(if (table) 140.dp else 180.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            return@BoxWithConstraints
        }
        val bitmap = region.bitmap
        val naturalWidth = with(density) { region.pixelWidth.toDp() }
        val naturalHeight = naturalWidth * reflowRegionAspect(bitmap.width, bitmap.height)
        val scrollable = region.pixelWidth > columnPx
        val imageModifier = if (scrollable) {
            Modifier.width(naturalWidth).height(naturalHeight)
        } else {
            Modifier.fillMaxWidth().height(naturalHeight)
        }
        if (scrollable) {
            Column {
                Text(
                    text = "$label — scroll sideways to see all of it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "$label on page ${pageIndex + 1}",
                        contentScale = ContentScale.FillWidth,
                        modifier = imageModifier
                    )
                }
            }
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "$label on page ${pageIndex + 1}",
                contentScale = ContentScale.FillWidth,
                modifier = imageModifier
            )
        }
    }
}

/** The typeface for the reader's choice; the bundled font falls back to Sans. */
@Composable
private fun reflowFontFamily(font: ReflowFont): FontFamily {
    val assets = LocalContext.current.assets
    return remember(font) {
        when (font) {
            ReflowFont.SERIF -> FontFamily.Serif
            ReflowFont.SANS -> FontFamily.SansSerif
            ReflowFont.MONO -> FontFamily.Monospace
            ReflowFont.READABLE -> runCatching {
                FontFamily(Font("fonts/NotoSansDevanagari-Regular.ttf", assets))
            }.getOrDefault(FontFamily.SansSerif)
        }
    }
}

/**
 * The reading settings sheet. Every change is applied and remembered at once,
 * so the page behind the sheet re-lays out as the slider moves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReflowSettingsSheet(
    settings: ReflowSettings,
    onSettings: (ReflowSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(UiTags.REFLOW_SETTINGS_SHEET)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Text(
                "Reading settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                describeReflow(settings),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            SettingSlider(
                label = "Text size",
                value = settings.fontSizeSp,
                range = ReflowSettings.MIN_FONT_SP..ReflowSettings.MAX_FONT_SP,
                steps = 19,
                display = "${settings.fontSizeSp.roundToInt()} sp",
                tag = UiTags.REFLOW_FONT_SLIDER
            ) { onSettings(settings.copy(fontSizeSp = it)) }

            SettingSlider(
                label = "Line spacing",
                value = settings.lineSpacing,
                range = ReflowSettings.MIN_LINE_SPACING..ReflowSettings.MAX_LINE_SPACING,
                steps = 23,
                display = "${((settings.lineSpacing - 1f) * 100f).roundToInt()}% extra",
                tag = UiTags.REFLOW_LINE_SLIDER
            ) { onSettings(settings.copy(lineSpacing = it)) }

            SettingSlider(
                label = "Paragraph spacing",
                value = settings.paragraphSpacingDp,
                range = 0f..ReflowSettings.MAX_PARAGRAPH_DP,
                steps = 31,
                display = "${settings.paragraphSpacingDp.roundToInt()} dp",
                tag = UiTags.REFLOW_PARAGRAPH_SLIDER
            ) { onSettings(settings.copy(paragraphSpacingDp = it)) }

            SettingSlider(
                label = "Side margins",
                value = settings.marginDp,
                range = 0f..ReflowSettings.MAX_MARGIN_DP,
                steps = 23,
                display = "${settings.marginDp.roundToInt()} dp",
                tag = UiTags.REFLOW_MARGIN_SLIDER
            ) { onSettings(settings.copy(marginDp = it)) }

            Spacer(Modifier.height(8.dp))
            Text("Font", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (font in ReflowFont.entries) {
                    FilterChip(
                        selected = settings.font == font,
                        onClick = { onSettings(settings.copy(font = font)) },
                        label = { Text(font.label) },
                        modifier = Modifier.testTag("reflow_font_${font.name.lowercase()}")
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Alignment", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = settings.align == ReflowAlign.LEFT,
                    onClick = { onSettings(settings.copy(align = ReflowAlign.LEFT)) },
                    label = { Text("Left") },
                    modifier = Modifier.testTag(UiTags.REFLOW_ALIGN_LEFT)
                )
                FilterChip(
                    selected = settings.align == ReflowAlign.JUSTIFIED,
                    onClick = { onSettings(settings.copy(align = ReflowAlign.JUSTIFIED)) },
                    label = { Text("Justified") },
                    modifier = Modifier.testTag(UiTags.REFLOW_ALIGN_JUSTIFIED)
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            SwitchRow(
                label = "Hide page numbers",
                checked = settings.hidePageNumbers,
                tag = UiTags.REFLOW_HIDE_PAGE_NUMBERS
            ) { onSettings(settings.copy(hidePageNumbers = it)) }
            SwitchRow(
                label = "Hide footnotes",
                checked = settings.hideFootnotes,
                tag = UiTags.REFLOW_HIDE_FOOTNOTES
            ) { onSettings(settings.copy(hideFootnotes = it)) }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    tag: String,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(display, style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

/**
 * T33b: "Text is small. Try Reflow or Fit content." A suggestion only — the
 * viewer never switches mode by itself, and a dismissal is remembered per
 * document.
 */
@Composable
fun ReflowHintBanner(
    onTryReflow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 3.dp,
        modifier = modifier.testTag(UiTags.REFLOW_HINT)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Text is small. Try Reflow or Fit content.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onTryReflow,
                modifier = Modifier.testTag(UiTags.REFLOW_HINT_TRY)
            ) {
                Text("Reflow")
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(UiTags.REFLOW_HINT_DISMISS)
            ) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
    }
}
