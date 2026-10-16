package com.pdfpaper.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.data.CachedPdf

@Composable
fun HomeScreen(
    onOpenViewer: (CachedPdf) -> Unit,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "PDFPaper", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { picker.launch(arrayOf("application/pdf")) },
            enabled = !state.isLoading
        ) {
            Text("Open PDF")
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (state.isLoading) {
            CircularProgressIndicator()
        }
        state.error?.let { error ->
            Text(text = error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { viewModel.clearMessages() }) {
                Text("Dismiss")
            }
        }
        state.message?.let { message ->
            Text(text = message)
            TextButton(onClick = { viewModel.clearMessages() }) {
                Text("Dismiss")
            }
        }
    }
}
