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
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.data.cachePdfAndRemember
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.AppTopBar
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
    onOpenViewer: (path: String, startInEdit: Boolean, startWithForms: Boolean) -> Unit,
    onOpenInfo: (path: String) -> Unit
) {
    val tool = ToolCatalogue.byId(toolId)
    if (tool == null) {
        ToolMissing(onBack)
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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
                        when (tool.action) {
                            ToolAction.ORGANIZE -> onOpenOrganizer(path, tool.organizerHint)
                            ToolAction.ANNOTATE -> onOpenViewer(path, true, false)
                            ToolAction.FILL_FORM -> onOpenViewer(path, true, true)
                            ToolAction.INFO -> onOpenInfo(path)
                            ToolAction.ENCRYPT_INFO -> onOpenViewer(path, false, false)
                        }
                    }
                    .onFailure {
                        busy = false
                        error = it.message ?: "Could not open that PDF"
                    }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(UiTags.TOOL_DETAIL_SCREEN)) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(
                title = tool.title,
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
                    text = tool.title,
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
                when (tool.action) {
                    ToolAction.ORGANIZE -> GuidanceCard(
                        "How it works",
                        tool.organizerHint ?: "Choose a PDF to open the page organizer."
                    )
                    ToolAction.ANNOTATE -> GuidanceCard(
                        "How it works",
                        "Choose a PDF to open the viewer in annotate mode. Highlight text, draw ink or place a note, then Save to write a new PDF."
                    )
                    ToolAction.FILL_FORM -> GuidanceCard(
                        "How it works",
                        "Choose a PDF to open its form fields. Enter text, tick boxes or pick choices, then Save to write a new PDF."
                    )
                    ToolAction.INFO -> GuidanceCard(
                        "How it works",
                        "Choose a PDF to see its page count, PDF version, encryption state, size and object count."
                    )
                    ToolAction.ENCRYPT_INFO -> EncryptInfo()
                }

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { picker.launch(arrayOf("application/pdf")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(
                        when (tool.action) {
                            ToolAction.ENCRYPT_INFO -> "Open protected PDF"
                            else -> "Choose PDF"
                        }
                    )
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

@Composable
private fun EncryptInfo() {
    Column {
        GuidanceCard(
            "Read protected PDFs",
            "PDFPaper opens documents protected by the standard security handler (RC4, AES-128 and AES-256) and asks for the password when one is required."
        )
        Spacer(Modifier.height(12.dp))
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Writing encryption is planned",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "This build never writes encrypted files. Saving a protected document is refused so its protection is never silently dropped.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
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
