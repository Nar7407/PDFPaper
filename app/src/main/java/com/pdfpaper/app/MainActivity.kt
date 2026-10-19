package com.pdfpaper.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.pdfpaper.app.ui.navigation.AppNav
import com.pdfpaper.app.ui.theme.PDFPaperTheme

class MainActivity : ComponentActivity() {

    /**
     * The file a VIEW intent asks the app to open. It is passed into [AppNav],
     * which runs it through the same open flow as the picker, and cleared once
     * the flow has been handed the URI.
     */
    private val incomingDocument = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingDocument.value = documentUri(intent)
        setContent {
            PDFPaperTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNav(
                        incomingDocument = incomingDocument.value,
                        onIncomingConsumed = { incomingDocument.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        documentUri(intent)?.let { incomingDocument.value = it }
    }

    /** The file an ACTION_VIEW intent carries, if that is what this is. */
    private fun documentUri(intent: Intent?): Uri? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
}
