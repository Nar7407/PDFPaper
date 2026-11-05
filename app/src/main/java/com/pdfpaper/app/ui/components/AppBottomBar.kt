package com.pdfpaper.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Note
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.navigation.Routes
import com.pdfpaper.app.ui.theme.PDFPaperTheme

/** Bottom navigation for the three top-level destinations. */
@Composable
fun AppBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier.height(72.dp)
        ) {
            BottomDestination(
                label = "Home",
                tag = UiTags.NAV_HOME,
                selected = currentRoute == Routes.HOME,
                icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                onClick = { onNavigate(Routes.HOME) }
            )
            BottomDestination(
                label = "All tools",
                tag = UiTags.NAV_TOOLS,
                selected = currentRoute == Routes.TOOLS || currentRoute == Routes.TOOL,
                icon = { Icon(Icons.Outlined.GridView, contentDescription = null) },
                onClick = { onNavigate(Routes.TOOLS) }
            )
            BottomDestination(
                label = "Recent",
                tag = UiTags.NAV_RECENT,
                selected = currentRoute == Routes.RECENT,
                icon = { Icon(Icons.Outlined.History, contentDescription = null) },
                onClick = { onNavigate(Routes.RECENT) }
            )
            BottomDestination(
                label = "Annotations",
                tag = UiTags.NAV_ANNOTATIONS,
                selected = currentRoute == Routes.ANNOTATIONS,
                icon = { Icon(Icons.Outlined.Note, contentDescription = null) },
                onClick = { onNavigate(Routes.ANNOTATIONS) }
            )
        }
    }
}

@Composable
private fun RowScope.BottomDestination(
    label: String,
    tag: String,
    selected: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSurface,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            indicatorColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.testTag(tag)
    )
}

@Preview(showBackground = true, widthDp = 411)
@Composable
private fun AppBottomBarLightPreview() {
    PDFPaperTheme(darkTheme = false) {
        AppBottomBar(currentRoute = Routes.HOME, onNavigate = {})
    }
}
