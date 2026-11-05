package com.pdfpaper.app.ui.viewer.speech

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.speech.ReadAloudHub
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.engine.text.TextRect

/**
 * T64: the transport of the running reading, inside the viewer.
 *
 * It is a pure observer: it shows what [ReadAloudHub] says the service is
 * reading and sends the same commands the notification, the lock screen and
 * the headset buttons send. Nothing here opens the document or owns the
 * speech engine - the service does, and two owners of one reading would drift.
 *
 * The bar is only shown while a reading is active, so a paused reading keeps
 * its controls but a stopped one does not leave a dead strip on the page.
 */
@Composable
fun ReadAloudBar(
    modifier: Modifier = Modifier,
    onOpenOptions: () -> Unit = {}
) {
    val state by ReadAloudHub.state.collectAsState()
    if (!state.active) return
    val commands = ReadAloudHub.commands
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag(UiTags.VIEWER_READ_ALOUD_BAR)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.chapterTitle ?: "Page ${state.pageIndex + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (state.paragraphText.isNotBlank()) {
                    Text(
                        state.paragraphText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(
                onClick = { commands?.previousParagraph() },
                modifier = Modifier.testTag(UiTags.VIEWER_READ_ALOUD_PREVIOUS)
            ) {
                Icon(Icons.Outlined.SkipPrevious, contentDescription = "Previous paragraph")
            }
            IconButton(
                onClick = { if (state.speaking) commands?.pause() else commands?.resume() },
                modifier = Modifier.testTag(UiTags.VIEWER_READ_ALOUD_PLAY)
            ) {
                Icon(
                    if (state.speaking) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (state.speaking) "Pause reading" else "Resume reading"
                )
            }
            IconButton(
                onClick = { commands?.nextParagraph() },
                modifier = Modifier.testTag(UiTags.VIEWER_READ_ALOUD_NEXT)
            ) {
                Icon(Icons.Outlined.SkipNext, contentDescription = "Next paragraph")
            }
            IconButton(
                onClick = onOpenOptions,
                modifier = Modifier.testTag(UiTags.VIEWER_READ_ALOUD_OPTIONS)
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = "Reading options")
            }
            IconButton(
                onClick = { commands?.stop() },
                modifier = Modifier.testTag(UiTags.VIEWER_READ_ALOUD_STOP)
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Stop reading")
            }
        }
    }
}

/**
 * T64: the boxes of the spoken words, but only on the page actually on screen.
 *
 * The state carries the word of the paragraph being spoken, which is usually
 * the page the reader is looking at - but after "next chapter" or a manual
 * scroll it may not be, and a highlight drawn on the wrong page would be a
 * rectangle floating over unrelated text.
 */
fun speechHighlight(state: ReadAloudPlaybackState, pageIndex: Int): List<TextRect> =
    if (state.active && state.wordPageIndex == pageIndex) state.wordRects else emptyList()
