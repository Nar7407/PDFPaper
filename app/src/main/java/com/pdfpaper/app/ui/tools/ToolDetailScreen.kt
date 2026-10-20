package com.pdfpaper.app.ui.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.data.cachePdfAndRemember
import com.pdfpaper.app.edit.EditingRuntime
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.AppTopBar
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import kotlinx.coroutines.launch

/**
 * A tool's start screen. Organizer tools hand the chosen PDF to the existing
 * page organizer with a short hint; annotate/fill open the viewer in edit mode;
 * info opens the document details; encrypt explains the read-only support.
 */
@Composable
fun ToolDetailScreen(
    toolId: String,
    onBack: () -> Unit,
    onOpenOrganizer: (path: String, hint: String?) -> Unit,
    onOpenViewer: (
        path: String,
        startInEdit: Boolean,
        startWithForms: Boolean,
        startEditTool: String?,
        startPanel: String?
    ) -> Unit,
    onOpenInfo: (path: String) -> Unit,
    onOpenCompress: (path: String) -> Unit,
    onOpenMark: (path: String, tool: String) -> Unit,
    onOpenExport: (path: String) -> Unit
) {
    val tool = ToolRegistry.byId(toolId)
    if (tool == null) {
        ToolMissing(onBack)
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val demoEngine = EditingRuntime.isDemo(context)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val available = tool.isAvailable(demoEngine)

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            busy = true
            error = null
            scope.launch {
                runCatching { cachePdfAndRemember(context, uri) }
                    .onSuccess { cached ->
                        busy = false
                        val path = cached.file.absolutePath
                        when (tool.destination) {
                            ToolDestination.ORGANIZER -> onOpenOrganizer(path, tool.organizerHint)
                            ToolDestination.INFO -> onOpenInfo(path)
                            ToolDestination.COMPRESS -> onOpenCompress(path)
                            ToolDestination.MARK -> onOpenMark(path, tool.id)
                            ToolDestination.EXPORT_IMAGES -> onOpenExport(path)
                            ToolDestination.VIEWER -> onOpenViewer(
                                path,
                                tool.viewerTool != null || tool.opensForms,
                                tool.opensForms,
                                tool.viewerTool,
                                tool.viewerAction
                            )
                            // New PDF and Scan have no source document; the All
                            // tools screen opens their routes without a picker.
                            ToolDestination.NEW_PDF, ToolDestination.SCAN -> Unit
                        }
                    }
                    .onFailure {
                        busy = false
                        error = it.message ?: "Could not open that PDF"
                    }
            }
        }
    }

    ToolDetailContent(
        tool = tool,
        onBack = onBack,
        busy = busy,
        error = error,
        available = available,
        onPick = { picker.launch(arrayOf("application/pdf")) }
    )
}

@Composable
private fun ToolDetailContent(
    tool: ToolDefinition,
    onBack: () -> Unit,
    busy: Boolean,
    error: String?,
    available: Boolean,
    onPick: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize().testTag(UiTags.TOOL_DETAIL_SCREEN)) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(
                title = tool.name,
                onBack = onBack,
                titleTag = UiTags.TOOL_DETAIL_TITLE
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = tool.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = tool.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )

                Spacer(Modifier.height(20.dp))
                GuidanceCard("How it works", guidanceFor(tool))
                if (!available) {
                    Spacer(Modifier.height(12.dp))
                    GuidanceCard(
                        "Coming soon",
                        "This tool is shown here so the planned workflow is discoverable, " +
                            "but nothing in this build can do it yet. It is disabled for now."
                    )
                }

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onPick,
                    enabled = !busy && available,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text("Choose PDF")
                }
                if (busy) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    BusyLabel()
                }
                error?.let {
                    Spacer(Modifier.height(16.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun GuidanceCard(title: String, body: String) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** The one-line "how it works" note shown on a tool's start screen. */
private fun guidanceFor(tool: ToolDefinition): String = when (tool.id) {
    "rotate", "reorder", "delete", "extract", "insert", "split", "merge" ->
        tool.organizerHint ?: "Choose a PDF to open the page organizer."
    "add-text" ->
        "Choose a PDF, tap a page, then type. Pick the font, size and colour in the dialog."
    "edit-text" ->
        "Choose a PDF and tap a text object to select it, then Edit text to change its content, size and colour."
    "add-image" ->
        "Choose a PDF, pick a picture from the gallery, then tap the page to place it."
    "edit-image" ->
        "Choose a PDF and tap a picture to select it, then move, resize, replace or delete it."
    "highlight", "draw", "note" ->
        "Choose a PDF to open the viewer with the ${tool.name} tool ready."
    "fill-form" ->
        "Choose a PDF to open its form fields. Enter text, tick boxes or pick choices, then Save."
    "info" ->
        "Choose a PDF to see its page count, PDF version, encryption state, size and object count."
    "compress" ->
        "Choose a PDF to see how much smaller it can get, then write the smaller copy to a new file."
    "watermark" ->
        "Choose a PDF, then type the watermark (or pick a picture) and choose where it sits."
    "page-numbers" ->
        "Choose a PDF, then write the header and the footer. {page} and {pages} can be used in them."
    "bates" ->
        "Choose a PDF, then set the prefix, the first number and the corner the number goes in."
    else ->
        "Choose a PDF to open it, then open the tools menu (⋮) to use ${tool.name}."
}

@Composable
private fun BusyLabel() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            "Copying the document…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ToolMissing(onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = "Tool", onBack = onBack, titleTag = UiTags.TOOL_DETAIL_TITLE)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("That tool is not in this catalogue.")
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun ToolDetailScreenLightPreview() {
    PDFPaperTheme(darkTheme = false) {
        ToolDetailContent(
            tool = ToolRegistry.byId("add-text") ?: ToolRegistry.tools.first(),
            onBack = {},
            busy = false,
            error = null,
            available = true,
            onPick = {}
        )
    }
}
