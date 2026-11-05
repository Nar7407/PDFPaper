package com.pdfpaper.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.LauncherLabels
import com.pdfpaper.app.data.RecentFile
import com.pdfpaper.app.data.RecentStore
import com.pdfpaper.app.launcher.LauncherAction
import com.pdfpaper.app.launcher.LauncherSurfaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The PDFPaper home-screen widget (task T48): Open, Scan and the three most
 * recent documents. It reads the same private recent list as the app and
 * follows the "Show file names on launcher/widget" setting. The widget has no
 * update period and starts no service; [LauncherSurfaces] refreshes it when the
 * recent list or the setting changes.
 */
class PdfPaperWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val recents = withContext(Dispatchers.IO) {
            RecentStore(context).list().filter { it.uri.isNotBlank() }.take(3)
        }
        val showNames = AppSettings.showLauncherNames(context)
        provideContent {
            WidgetContent(recents = recents, showNames = showNames)
        }
    }
}

@Composable
private fun WidgetContent(recents: List<RecentFile>, showNames: Boolean) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(WidgetBackground)
            .cornerRadius(20.dp)
            .padding(14.dp)
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PDFPaper",
                style = TextStyle(
                    color = WidgetTitle,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = if (recents.isEmpty()) "No recent files" else "${recents.size} recent",
                style = TextStyle(color = WidgetSecondary, fontSize = 12.sp),
                maxLines = 1
            )
        }

        Spacer(GlanceModifier.height(10.dp))

        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WidgetActionButton(
                label = "Open",
                action = LauncherAction.OPEN_PDF,
                modifier = GlanceModifier.defaultWeight()
            )
            Spacer(GlanceModifier.width(8.dp))
            WidgetActionButton(
                label = "Scan",
                action = LauncherAction.SCAN,
                modifier = GlanceModifier.defaultWeight()
            )
        }

        Spacer(GlanceModifier.height(8.dp))

        if (recents.isEmpty()) {
            Text(
                text = "Open a PDF to see it here.",
                style = TextStyle(color = WidgetSecondary, fontSize = 12.sp)
            )
        } else {
            recents.forEachIndexed { index, file ->
                RecentRow(index = index, file = file, showNames = showNames)
            }
        }
    }
}

@Composable
private fun WidgetActionButton(
    label: String,
    action: LauncherAction,
    modifier: GlanceModifier
) {
    val context = LocalContext.current
    Text(
        text = label,
        modifier = modifier
            .background(WidgetAccent)
            .cornerRadius(18.dp)
            .clickable(actionStartActivity(LauncherSurfaces.actionIntent(context, action)))
            .padding(vertical = 8.dp),
        style = TextStyle(
            color = ColorProvider(Color.White),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        ),
        maxLines = 1
    )
}

@Composable
private fun RecentRow(index: Int, file: RecentFile, showNames: Boolean) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(actionStartActivity(LauncherSurfaces.recentIntent(context, file)))
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${index + 1}",
            style = TextStyle(
                color = WidgetAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = LauncherLabels.recentLabel(index, file.displayName, showNames),
            style = TextStyle(color = WidgetBody, fontSize = 13.sp),
            maxLines = 1
        )
    }
}

private val WidgetBackground = DayNightColorProvider(
    Color(0xFFF6FAF9),
    Color(0xFF0F1B19)
)
private val WidgetTitle = DayNightColorProvider(
    Color(0xFF123B36),
    Color(0xFFE4F3EF)
)
private val WidgetBody = DayNightColorProvider(
    Color(0xFF20332F),
    Color(0xFFD4E6E1)
)
private val WidgetSecondary = DayNightColorProvider(
    Color(0xFF526662),
    Color(0xFF9AB5AF)
)
private val WidgetAccent = DayNightColorProvider(
    Color(0xFF00695C),
    Color(0xFF6FD8C6)
)
