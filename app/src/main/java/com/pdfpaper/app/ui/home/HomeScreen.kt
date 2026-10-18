package com.pdfpaper.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.data.CachedPdf
import com.pdfpaper.app.ui.UiTags

@Composable
fun HomeScreen(
    onOpenViewer: (CachedPdf) -> Unit,
    onBrowseTools: () -> Unit = {},
    onBrowseRecent: () -> Unit = {},
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            viewModel.onPickerCancelled()
        } else {
            viewModel.onDocumentPicked(context, uri)
        }
    }

    LaunchedEffect(state.pendingNavigation) {
        state.pendingNavigation?.let {
            onOpenViewer(it)
            viewModel.onNavigationConsumed()
        }
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(UiTags.HOME_SCREEN)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(84.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "PDFPaper",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Your PDF workspace, on device.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.height(28.dp))
            OpenDocumentCard(
                loading = state.isLoading,
                onOpen = { picker.launch(arrayOf("application/pdf")) }
            )

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ShortcutCard(
                    title = "Tools",
                    subtitle = "Merge, split,\nrotate and more",
                    onClick = onBrowseTools,
                    modifier = Modifier.weight(1f)
                )
                ShortcutCard(
                    title = "Recent",
                    subtitle = "Pick up where\nyou left off",
                    onClick = onBrowseRecent,
                    modifier = Modifier.weight(1f)
                )
            }

            if (state.isLoading) {
                Spacer(Modifier.height(20.dp))
                CircularProgressIndicator()
            }
            state.error?.let { error ->
                Spacer(Modifier.height(18.dp))
                MessageCard(
                    text = error,
                    isError = true,
                    onDismiss = viewModel::clearMessages
                )
            }
            state.message?.let { message ->
                Spacer(Modifier.height(18.dp))
                MessageCard(
                    text = message,
                    isError = false,
                    onDismiss = viewModel::clearMessages
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OpenDocumentCard(loading: Boolean, onOpen: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Open a document", style = MaterialTheme.typography.titleLarge)
            Text(
                "Pick a PDF to view, organize, annotate or fill.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onOpen,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("Open PDF")
            }
        }
    }
}

@Composable
private fun ShortcutCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(role = Role.Button, onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun MessageCard(text: String, isError: Boolean, onDismiss: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
