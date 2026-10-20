package com.pdfpaper.app.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.engine.links.DocumentLink
import com.pdfpaper.engine.links.LinkTarget
import com.pdfpaper.engine.links.label

/** State for the modal preview card (T61). */
data class LinkCardRequest(
    val link: DocumentLink,
    val initialTarget: Int = 0
)

/** State for the external-link confirmation dialogs (T49/T61). */
data class ExternalLinkRequest(
    val uri: String? = null,
    val file: String? = null,
    val note: String? = null
)

private sealed interface PreviewState {
    data object Loading : PreviewState
    data class Ready(val bitmap: Bitmap) : PreviewState
    data object Missing : PreviewState
}

/**
 * Floating preview card for internal links and citations. It opens as a bottom
 * sheet over the reading position, shows a small rendered region, and offers
 * Go to / Pin to side / Copy text / Close. A horizontal pager exposes several
 * targets from one citation ("[3, 5-7]"). Dismissing never moves the page.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LinkPreviewSheet(
    request: LinkCardRequest,
    onDismiss: () -> Unit,
    onGoTo: (LinkTarget) -> Unit,
    onPin: (LinkTarget) -> Unit,
    onCopy: (LinkTarget) -> Unit,
    loadPreview: suspend (LinkTarget, Int) -> Bitmap?
) {
    val link = request.link
    val targets = link.targets
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(link) { focusRequester.requestFocus() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(UiTags.LINK_PREVIEW_SHEET)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .focusable()
                .semantics {
                    contentDescription = if (targets.isEmpty()) {
                        "Link preview: ${link.text}. No target found."
                    } else {
                        "Link preview: ${link.text}. ${targets.size} target${if (targets.size == 1) "" else "s"}."
                    }
                }
                .padding(start = 20.dp, end = 12.dp, bottom = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = link.kind.label(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = link.text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.semantics { contentDescription = "Close preview" }
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                }
            }
            Spacer(Modifier.height(12.dp))

            if (targets.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "No matching target",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            link.note ?: "This reference has no target in this document.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .semantics { contentDescription = "Close preview" }
                ) { Text("Close") }
            } else {
                val start = request.initialTarget.coerceIn(0, targets.lastIndex)
                val pagerState = rememberPagerState(initialPage = start) { targets.size }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth(),
                    userScrollEnabled = targets.size > 1,
                    pageSpacing = 12.dp
                ) { page ->
                    LinkTargetPage(
                        target = targets[page],
                        loadPreview = loadPreview
                    )
                }
                if (targets.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(targets.size) { dot ->
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(if (dot == pagerState.currentPage) 8.dp else 6.dp)
                                    .background(
                                        if (dot == pagerState.currentPage) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                        CircleShape
                                    )
                            )
                        }
                    }
                    Text(
                        text = "Swipe for the next target (${pagerState.currentPage + 1} of ${targets.size})",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                val current = targets[pagerState.currentPage.coerceIn(0, targets.lastIndex)]
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onGoTo(current) },
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription =
                                    "Go to ${current.title} on page ${current.pageIndex + 1}"
                            }
                    ) { Text("Go to") }
                    OutlinedButton(
                        onClick = { onPin(current) },
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = "Pin ${current.title} to the side"
                            }
                    ) { Text("Pin to side") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { onCopy(current) },
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = "Copy text of ${current.title}"
                            }
                    ) { Text("Copy text") }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "Close preview" }
                    ) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun LinkTargetPage(
    target: LinkTarget,
    loadPreview: suspend (LinkTarget, Int) -> Bitmap?
) {
    var preview by remember(target) { mutableStateOf<PreviewState>(PreviewState.Loading) }
    LaunchedEffect(target) {
        preview = PreviewState.Loading
        preview = runCatching { loadPreview(target, 900) }
            .getOrNull()
            ?.let { PreviewState.Ready(it) }
            ?: PreviewState.Missing
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 270.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                when (val state = preview) {
                    PreviewState.Loading -> CircularProgressIndicator(Modifier.size(28.dp))
                    PreviewState.Missing -> Text(
                        "Preview unavailable",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(24.dp)
                    )
                    is PreviewState.Ready -> Image(
                        bitmap = state.bitmap.asImageBitmap(),
                        contentDescription = "Preview of ${target.title}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = target.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${target.label} · Page ${target.pageIndex + 1}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        target.note?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * Persistent split/pinned panel (T37 integration point). It sits over the page
 * on the right, so the reader can check a target and keep reading.
 */
@Composable
fun PinnedLinkPanel(
    target: LinkTarget,
    onClose: () -> Unit,
    onGoTo: (LinkTarget) -> Unit,
    loadPreview: suspend (LinkTarget, Int) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val panelWidth = (configuration.screenWidthDp * 0.78f).coerceAtMost(340f).dp
    var preview by remember(target) { mutableStateOf<PreviewState>(PreviewState.Loading) }
    LaunchedEffect(target) {
        preview = PreviewState.Loading
        preview = runCatching { loadPreview(target, 700) }
            .getOrNull()
            ?.let { PreviewState.Ready(it) }
            ?: PreviewState.Missing
    }
    Surface(
        modifier = modifier
            .width(panelWidth)
            .fillMaxHeight()
            .testTag(UiTags.LINK_PINNED_PANEL)
            .semantics { contentDescription = "Pinned preview: ${target.title}" },
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Pinned preview",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.semantics { contentDescription = "Unpin preview" }
                ) { Icon(Icons.Filled.Close, contentDescription = null) }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when (val state = preview) {
                        PreviewState.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
                        PreviewState.Missing -> Text(
                            "Preview unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp)
                        )
                        is PreviewState.Ready -> Image(
                            bitmap = state.bitmap.asImageBitmap(),
                            contentDescription = "Preview of ${target.title}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                target.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${target.label} · Page ${target.pageIndex + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onGoTo(target) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Go to ${target.title} on page ${target.pageIndex + 1}"
                    }
            ) { Text("Go to") }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "Tap another link to preview it without leaving your place.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** T49-style confirmation before leaving the app or opening another file. */
@Composable
fun ExternalLinkDialog(
    request: ExternalLinkRequest,
    onDismiss: () -> Unit,
    onOpen: () -> Unit
) {
    val isFile = request.file != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isFile) "Open linked file?" else "Open external link?")
        },
        text = {
            Column {
                val target = request.file ?: request.uri ?: ""
                Text(target, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    request.note ?: if (isFile) {
                        "This link points to a different file. PDFPaper will ask before opening it."
                    } else {
                        "This link opens in another app and leaves your reading position."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onOpen,
                modifier = Modifier.semantics {
                    contentDescription = if (isFile) "Open linked file" else "Open external link"
                }
            ) { Text("Open") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Cancel opening link" }
            ) { Text("Cancel") }
        }
    )
}
