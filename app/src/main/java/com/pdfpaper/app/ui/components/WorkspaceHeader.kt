package com.pdfpaper.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.R
import com.pdfpaper.app.ui.theme.PDFPaperTheme

/**
 * Workspace chrome shared by Home, All tools and Recent: a black-and-white
 * brand bar with the app's paper mark, an Open action and search. The three
 * top-level destinations live in the bottom [AppBottomBar], matching the phone
 * adaptation of PdfCraft's header + navigation split.
 */
@Composable
fun WorkspaceHeader(
    onOpen: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onDebug: (() -> Unit)? = null
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(30.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_pdfpaper_mark),
                            contentDescription = "PDFPaper",
                            tint = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
                Text(
                    text = "PDFPaper",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 10.dp)
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpen) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text("Open", modifier = Modifier.padding(start = 4.dp))
                }
                IconButton(onClick = onSearch) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search tools"
                    )
                }
                if (onDebug != null) {
                    IconButton(onClick = onDebug) {
                        Icon(
                            imageVector = Icons.Outlined.BugReport,
                            contentDescription = "Debug menu"
                        )
                    }
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 411)
@Composable
private fun WorkspaceHeaderLightPreview() {
    PDFPaperTheme(darkTheme = false) {
        WorkspaceHeader(onOpen = {}, onSearch = {}, onDebug = {})
    }
}
