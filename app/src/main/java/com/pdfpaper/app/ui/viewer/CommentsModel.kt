package com.pdfpaper.app.ui.viewer

import com.pdfpaper.engine.annot.CommentStatus
import com.pdfpaper.engine.annot.PdfComment
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A reply shown in the comments sheet. [depth] indents nested replies. */
data class CommentReplyUi(
    val author: String,
    val text: String,
    val date: String?,
    val depth: Int = 0,
    val status: CommentStatus = CommentStatus.NONE
)

/** A root comment thread, either read from the PDF or added in this session. */
data class CommentThreadUi(
    val id: Int,
    val pageIndex: Int,
    val author: String,
    val text: String,
    val date: String?,
    val status: CommentStatus,
    val resolved: Boolean,
    val pending: Boolean,
    val replies: List<CommentReplyUi>,
    val kind: String
) {
    /** Only saved comments with an object number can be replied to or resolved. */
    val canReply: Boolean get() = !pending && id > 0
    val statusLabel: String get() = commentStatusLabel(status, resolved)
}

/** A note placed in this session but not yet written to the PDF. */
data class CommentDraftUi(
    val pageIndex: Int,
    val author: String,
    val text: String
)

/**
 * Folds loaded comments, this session's replies/status changes and unsaved
 * drafts into the sheet's list. Pure so the ordering and status rules can be
 * unit-tested without Android.
 */
fun buildCommentThreads(
    saved: List<PdfComment>,
    localReplies: Map<Int, List<CommentReplyUi>> = emptyMap(),
    localStatuses: Map<Int, CommentStatus> = emptyMap(),
    drafts: List<CommentDraftUi> = emptyList()
): List<CommentThreadUi> {
    val threads = ArrayList<CommentThreadUi>(saved.size + drafts.size)
    for (comment in saved) {
        val status = localStatuses[comment.id] ?: comment.status
        val resolved = status.resolved || (comment.resolved && localStatuses[comment.id] == null)
        val replies = ArrayList<CommentReplyUi>()
        fun collect(list: List<PdfComment>, depth: Int) {
            for (reply in list) {
                replies += CommentReplyUi(
                    author = reply.author?.takeIf { it.isNotBlank() } ?: "Unknown",
                    text = reply.contents?.takeIf { it.isNotBlank() } ?: "(no text)",
                    date = formatPdfDate(reply.created),
                    depth = depth,
                    status = reply.status
                )
                collect(reply.replies, depth + 1)
            }
        }
        collect(comment.replies, 0)
        localReplies[comment.id]?.let { replies += it }
        threads += CommentThreadUi(
            id = comment.id,
            pageIndex = comment.pageIndex,
            author = comment.author?.takeIf { it.isNotBlank() } ?: "Unknown",
            text = comment.contents?.takeIf { it.isNotBlank() }
                ?: comment.subject?.takeIf { it.isNotBlank() }
                ?: "(no text)",
            date = formatPdfDate(comment.created),
            status = status,
            resolved = resolved,
            pending = false,
            replies = replies,
            kind = comment.type.name.lowercase().replace('_', ' ')
        )
    }
    drafts.forEachIndexed { index, draft ->
        threads += CommentThreadUi(
            id = -2 - index,
            pageIndex = draft.pageIndex,
            author = draft.author.ifBlank { "You" },
            text = draft.text,
            date = null,
            status = CommentStatus.NONE,
            resolved = false,
            pending = true,
            replies = emptyList(),
            kind = "comment"
        )
    }
    return threads
}

fun commentStatusLabel(status: CommentStatus, resolved: Boolean): String = when {
    resolved -> "Resolved"
    status == CommentStatus.NONE -> "Open"
    else -> status.stateText
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

/**
 * Formats a PDF date string ("D:20261019153000+02'00'") for display. Unknown
 * shapes are returned unchanged, so a hand-written date never disappears.
 */
fun formatPdfDate(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val digits = value.removePrefix("D:").takeWhile { it.isDigit() }
    if (digits.length < 8) return value
    return runCatching {
        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        val hour = if (digits.length >= 10) digits.substring(8, 10).toInt() else 0
        val minute = if (digits.length >= 12) digits.substring(10, 12).toInt() else 0
        val second = if (digits.length >= 14) digits.substring(12, 14).toInt() else 0
        LocalDateTime.of(year, month, day, hour, minute, second).format(DATE_FORMAT)
    }.getOrElse { value }
}

fun formatCommentNow(): String = LocalDateTime.now().format(DATE_FORMAT)
