package com.pdfpaper.app.ui.open

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.AppTopBar
import java.io.File

/**
 * The progress screen of the open flow: it copies the picked document, decides
 * what it is and converts office files to PDF, then replaces itself with the
 * viewer. Errors - a legacy .doc, a damaged package, a file that is too large -
 * are explained here, before anything tries to render them.
 */
@Composable
fun OpenDocumentScreen(
    uri: String,
    fallbackName: String?,
    onBack: () -> Unit,
    onOpened: (File) -> Unit,
    viewModel: OpenDocumentViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(uri, fallbackName) { viewModel.open(Uri.parse(uri), fallbackName) }
    LaunchedEffect(state.pdf) { state.pdf?.let(onOpened) }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize().testTag(UiTags.OPEN_SCREEN)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(
                title = "Opening document",
                subtitle = state.name,
                onBack = onBack
            )
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val error = state.error
                if (error != null) {
                    Card(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = "This file cannot be opened",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp).testTag(UiTags.OPEN_ERROR)
                            )
                            TextButton(
                                onClick = onBack,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text("Close")
                            }
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        CircularProgressIndicator()
                        state.name?.let { name ->
                            Text(
                                text = name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 20.dp)
                            )
                        }
                        Text(
                            text = state.stage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
