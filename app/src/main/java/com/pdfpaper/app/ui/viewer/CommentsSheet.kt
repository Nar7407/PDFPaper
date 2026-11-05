package com.pdfpaper.app.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.engine.annot.CommentStatus
import com.pdfpaper.engine.annot.PdfComment

/**
 * Comments bottom sheet (task UI-2): threads read from the PDF plus notes that
 * are still pending, with replies, review status and resolve/reopen. The author
 * comes from Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(
    saved: List<PdfComment>,
    localReplies: Map<Int, List<CommentReplyUi>>,
    localStatuses: Map<Int, CommentStatus>,
    drafts: List<CommentDraftUi>,
    author: String,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onAddComment: () -> Unit,
    onReply: (CommentThreadUi, String) -> Unit,
    onResolve: (CommentThreadUi, Boolean) -> Unit,
    onOpenSettings: () -> Unit
) {
    val threads = remember(saved, localReplies, localStatuses, drafts) {
        buildCommentThreads(saved, localReplies, localStatuses, drafts)
    }
    var replyingTo by remember { mutableStateOf<Int?>(null) }
    var replyText by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(UiTags.COMMENTS_SHEET)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Comments",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (threads.isEmpty()) "No comments yet"
                        else "${threads.size} thread${if (threads.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close comments")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Author: $author",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onOpenSettings) { Text("Change in Settings") }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onAddComment,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.COMMENTS_ADD)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add comment")
            }
            if (loading && threads.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(10.dp))

            if (threads.isEmpty() && !loading) {
                EmptyComments(author)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = threads,
                        key = { thread ->
                            if (thread.pending) "draft-${thread.pageIndex}-${thread.text.hashCode()}"
                            else "comment-${thread.id}"
                        }
                    ) { thread ->
                        ThreadCard(
                            thread = thread,
                            replying = replyingTo == thread.id,
                            replyText = if (replyingTo == thread.id) replyText else "",
                            onReplyText = { replyText = it },
                            onStartReply = {
                                replyingTo = thread.id
                                replyText = ""
                            },
                            onCancelReply = {
                                replyingTo = null
                                replyText = ""
                            },
                            onSendReply = {
                                val text = replyText.trim()
                                if (text.isNotEmpty()) {
                                    onReply(thread, text)
                                    replyingTo = null
                                    replyText = ""
                                }
                            },
                            onResolve = { onResolve(thread, !thread.resolved) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Comments are written into the PDF when you save.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyComments(author: String) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("Start the conversation", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Add a comment and tap the page where it belongs. Replies and resolved states are saved with the PDF.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Comments will be signed as $author.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThreadCard(
    thread: CommentThreadUi,
    replying: Boolean,
    replyText: String,
    onReplyText: (String) -> Unit,
    onStartReply: () -> Unit,
    onCancelReply: () -> Unit,
    onSendReply: () -> Unit,
    onResolve: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().testTag(UiTags.commentThread(thread.id)),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (thread.resolved) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        thread.author.trim().take(1).uppercase().ifBlank { "?" },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        thread.author,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Page ${thread.pageIndex + 1} · ${thread.kind}" +
                            if (thread.date != null) " · ${thread.date}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusChip(thread)
            }
            Spacer(Modifier.height(9.dp))
            Text(thread.text, style = MaterialTheme.typography.bodyMedium)

            if (thread.replies.isNotEmpty()) {
                Spacer(Modifier.height(9.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    thread.replies.forEach { reply ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(9.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (reply.depth * 10).dp)
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        reply.author,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (reply.date != null) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            reply.date,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(reply.text, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            if (thread.pending) {
                Text(
                    "Draft · save the PDF to reply or resolve.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onStartReply,
                        enabled = thread.canReply,
                        modifier = Modifier.testTag(UiTags.COMMENTS_REPLY)
                    ) { Text("Reply") }
                    TextButton(
                        onClick = onResolve,
                        enabled = thread.canReply,
                        modifier = Modifier.testTag(UiTags.COMMENTS_RESOLVE)
                    ) { Text(if (thread.resolved) "Reopen" else "Resolve") }
                    if (!thread.canReply) {
                        Text(
                            "This annotation cannot be replied to.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (replying) {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = replyText,
                    onValueChange = onReplyText,
                    label = { Text("Reply to ${thread.author}") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onCancelReply) { Text("Cancel") }
                    TextButton(
                        onClick = onSendReply,
                        enabled = replyText.isNotBlank()
                    ) { Text("Send reply") }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(thread: CommentThreadUi) {
    val background = when {
        thread.resolved -> Color(0xFFD7EFDC)
        thread.status == CommentStatus.NONE -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val foreground = when {
        thread.resolved -> Color(0xFF1E5B2A)
        thread.status == CommentStatus.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(color = background, shape = RoundedCornerShape(50)) {
        Text(
            thread.statusLabel,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = foreground
        )
    }
}
