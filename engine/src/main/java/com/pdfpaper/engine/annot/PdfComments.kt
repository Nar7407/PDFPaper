package com.pdfpaper.engine.annot

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.write.PdfPageOps

/**
 * Review states of the "Review" state model (§12.5.6.3, Table 170). The state
 * of an annotation lives in separate hidden text annotations that reply to it
 * with /IRT; [PdfDocument.comments] folds them into the comment they refer to.
 *
 * Acrobat DC presents the Completed state as "Resolved" and LibreOffice maps
 * it back the same way, so [resolved] is true exactly for [COMPLETED].
 */
enum class CommentStatus(val stateText: String) {
    NONE("None"),
    ACCEPTED("Accepted"),
    REJECTED("Rejected"),
    CANCELLED("Cancelled"),
    COMPLETED("Completed");

    /** Acrobat DC's "Resolved" flag: the Review/Completed state. */
    val resolved: Boolean get() = this == COMPLETED

    companion object {
        /** Maps a /State value; missing and unknown values read as [NONE]. */
        fun from(state: String?): CommentStatus = when (state) {
            "Accepted" -> ACCEPTED
            "Rejected" -> REJECTED
            "Cancelled" -> CANCELLED
            "Completed" -> COMPLETED
            else -> NONE
        }
    }
}

/** /RT — relation to the annotation named by /IRT (§12.5.6.2, Table 172). */
enum class ReplyType(val pdfName: String) {
    /** /R: the annotation is a reply to the one it refers to. */
    REPLY("R"),

    /** /Group: the annotations function as a single unit. */
    GROUP("Group");

    companion object {
        /** Parses /RT; a missing value means [REPLY] (Table 172). */
        fun from(name: String?): ReplyType =
            if (name.equals("Group", ignoreCase = true)) GROUP else REPLY
    }
}

/**
 * One comment (§12.5.6.2): a note or markup annotation, plus the replies
 * threaded under it. [id] is the annotation's object number, the handle that
 * [PdfComments.edit] and the CLI use; it is negative for a direct annotation,
 * which cannot be replied to or given a state.
 */
class PdfComment(
    val id: Int,
    val pageIndex: Int,
    val type: AnnotationType,
    /** /Rect — position and size on the page, in user space (§7.9.5). */
    val rect: PdfRect?,
    val contents: String?,
    /** /T — author of the comment. */
    val author: String?,
    /** /Subj — short subject (§12.5.6.2). */
    val subject: String?,
    /** /C — colour of the annotation. */
    val color: PdfColor?,
    /** /CreationDate. */
    val created: String?,
    /** /M — last modification date. */
    val modified: String?,
    /** Latest review state, folded from the state annotations that refer here. */
    val status: CommentStatus,
    /** Some reviewer's latest state is Completed (§12.5.6.3; Acrobat's "Resolved"). */
    val resolved: Boolean,
    /** /RT — reply relation; null when this comment is not a reply. */
    val replyType: ReplyType?,
    /** Object number named by /IRT; null for a root comment. */
    val inReplyToId: Int?,
    /** /Open — notes and popups initially displayed open. */
    val open: Boolean?,
    /** Replies to this comment, in time order. */
    val replies: List<PdfComment>
) {
    val isReply: Boolean get() = inReplyToId != null
}

/**
 * Every comment of the document: notes, markup annotations and other comment
 * types (§12.5.6.2), with replies nested under their parents. Roots are sorted
 * by page and creation time, replies by time; popup windows are not comments
 * and hidden state changes are folded into [PdfComment.status] instead of
 * being listed.
 */
fun PdfDocument.comments(): List<PdfComment> = CommentCollector(this).read()

/** Comment operations that write a fresh document (§12.5.6). */
object PdfComments {

    /**
     * Writes a new document with all pages of [document] kept, [additions]
     * applied and [deletions] removed. Deleting a comment also deletes its
     * replies, its popup and the state changes that refer to it.
     *
     * Fails when a reply or state change names an id that is not a comment of
     * this document (or names one on another page), and when a deletion names
     * an annotation that does not exist.
     */
    fun edit(
        document: PdfDocument,
        additions: List<AnnotationSpec> = emptyList(),
        deletions: List<Int> = emptyList()
    ): Result<ByteArray> = runCatching {
        val index = commentIndex(document)
        for (spec in additions) {
            when (spec) {
                is ReplySpec -> requireComment(index, spec.targetObjNum, spec.pageIndex)
                is StatusSpec -> requireComment(index, spec.targetObjNum, spec.pageIndex)
                else -> {}
            }
        }
        PdfPageOps.edit(document, additions, emptyMap(), deletionClosure(document, deletions)).getOrThrow()
    }

    /** Deletes [deletions] through the same closure used by [edit]. */
    fun deletionClosureFor(document: PdfDocument, deletions: List<Int>): Result<Set<Int>> =
        runCatching { deletionClosure(document, deletions) }

    /**
     * Incremental variant of [edit] (T55): adds and deletes comments by
     * appending a new revision to [originalBytes]. Use this for replies, review
     * states and ordinary comment changes when the file can be written in
     * place; page operations still use a full rewrite.
     */
    fun editIncremental(
        document: PdfDocument,
        originalBytes: ByteArray,
        additions: List<AnnotationSpec> = emptyList(),
        deletions: List<Int> = emptyList()
    ): Result<ByteArray> = runCatching {
        val index = commentIndex(document)
        for (spec in additions) {
            when (spec) {
                is ReplySpec -> requireComment(index, spec.targetObjNum, spec.pageIndex)
                is StatusSpec -> requireComment(index, spec.targetObjNum, spec.pageIndex)
                else -> {}
            }
        }
        val dropped = deletionClosure(document, deletions)
        com.pdfpaper.engine.write.PdfPageOps.editIncremental(
            document,
            originalBytes,
            additions,
            emptyMap(),
            dropped
        ).getOrThrow()
    }

    /** All comments by object number, replies included. */
    private fun commentIndex(document: PdfDocument): Map<Int, PdfComment> {
        val out = HashMap<Int, PdfComment>()
        fun collect(comment: PdfComment) {
            if (comment.id > 0) out[comment.id] = comment
            for (reply in comment.replies) collect(reply)
        }
        for (thread in document.comments()) collect(thread)
        return out
    }

    private fun requireComment(index: Map<Int, PdfComment>, id: Int, pageIndex: Int) {
        val comment = index[id] ?: throw IllegalArgumentException("No comment with id $id")
        require(comment.pageIndex == pageIndex) {
            "Comment $id is on page ${comment.pageIndex + 1}, not ${pageIndex + 1}"
        }
    }

    /**
     * The annotations to drop: each deleted annotation plus, transitively, its
     * popup, the popups that point at it, and every reply or state change that
     * refers to it, directly or through another state change.
     */
    private fun deletionClosure(document: PdfDocument, deletions: List<Int>): Set<Int> {
        if (deletions.isEmpty()) return emptySet()
        val refs = HashMap<Int, PdfAnnotation>()
        for (page in document.pages()) {
            for (annot in page.annotations()) {
                val ref = annot.ref ?: continue
                refs[ref.objNum] = annot
            }
        }
        val dropped = HashSet<Int>()
        val queue = ArrayDeque<Int>()
        for (id in deletions) {
            require(refs.containsKey(id)) { "No annotation with id $id" }
            if (dropped.add(id)) queue += id
        }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            // The deleted annotation's own popup goes with it.
            refs[id]?.popup?.objNum?.let { if (dropped.add(it)) queue += it }
            // Popups, replies and state changes that refer to it go with it too.
            for ((other, candidate) in refs) {
                if (other == id) continue
                val linked = candidate.parent?.objNum == id || candidate.inReplyTo?.objNum == id
                if (linked && dropped.add(other)) queue += other
            }
        }
        return dropped
    }
}

/** Comment types of §12.5.6.2: the annotations that appear in a comment list. */
private val COMMENT_TYPES: Set<AnnotationType> = setOf(
    AnnotationType.TEXT, AnnotationType.FREE_TEXT, AnnotationType.LINE,
    AnnotationType.SQUARE, AnnotationType.CIRCLE, AnnotationType.POLYGON,
    AnnotationType.POLYLINE, AnnotationType.HIGHLIGHT, AnnotationType.UNDERLINE,
    AnnotationType.SQUIGGLY, AnnotationType.STRIKEOUT, AnnotationType.STAMP,
    AnnotationType.CARET, AnnotationType.INK
)

private class CommentCollector(private val document: PdfDocument) {

    private class Node(val annot: PdfAnnotation, val pageIndex: Int)

    private class StateChange(
        val id: Int,
        val targetId: Int,
        val status: CommentStatus,
        val author: String?,
        val time: Long
    )

    fun read(): List<PdfComment> {
        val nodes = LinkedHashMap<Int, Node>()
        val stateChanges = ArrayList<StateChange>()
        var synthetic = -1
        for ((pageIndex, page) in document.pages().withIndex()) {
            for (annot in page.annotations()) {
                val id = annot.ref?.objNum ?: synthetic--
                when {
                    annot.type == AnnotationType.POPUP -> {}
                    isStateChange(annot) -> stateChanges += StateChange(
                        id = id,
                        targetId = annot.inReplyTo?.objNum ?: 0,
                        status = CommentStatus.from(annot.state),
                        author = annot.title,
                        time = timeOf(annot)
                    )
                    annot.type in COMMENT_TYPES -> nodes[id] = Node(annot, pageIndex)
                }
            }
        }

        // Fold each state change into the comment it ultimately refers to
        // (state changes may reply to earlier state changes, §12.5.6.3).
        val stateTargets = stateChanges.associate { it.id to it.targetId }
        val latest = HashMap<Int, StateChange>()
        val perAuthor = HashMap<Int, HashMap<String?, StateChange>>()
        for (change in stateChanges.sortedWith(compareBy({ it.time }, { it.id }))) {
            val owner = ownerOf(change.targetId, stateTargets, nodes) ?: continue
            latest[owner] = change
            perAuthor.getOrPut(owner) { HashMap() }[change.author] = change
        }

        // Thread replies under their parent; an unresolvable /IRT makes a root.
        val children = HashMap<Int, MutableList<Int>>()
        val roots = ArrayList<Int>()
        for ((id, node) in nodes) {
            val parent = node.annot.inReplyTo?.objNum
            if (parent != null && parent != id && nodes.containsKey(parent)) {
                children.getOrPut(parent) { ArrayList() } += id
            } else {
                roots += id
            }
        }
        val timeOfId = { id: Int -> timeOf(nodes.getValue(id).annot) }
        val byTime = compareBy<Int>({ timeOfId(it) }, { it })
        children.values.forEach { it.sortWith(byTime) }
        val byPageTime = compareBy<Int>({ nodes.getValue(it).pageIndex }, { timeOfId(it) }, { it })
        roots.sortWith(byPageTime)

        val visited = HashSet<Int>()
        val out = ArrayList<PdfComment>()
        for (id in roots) {
            if (visited.add(id)) out += buildThread(id, nodes, children, latest, perAuthor, visited)
        }
        // An /IRT cycle leaves nodes that no root reaches; list them as roots.
        for (id in nodes.keys.sortedWith(byPageTime)) {
            if (visited.add(id)) out += buildThread(id, nodes, children, latest, perAuthor, visited)
        }
        return out
    }

    /** Follows a state change's /IRT chain to the comment it describes. */
    private fun ownerOf(start: Int, stateTargets: Map<Int, Int>, nodes: Map<Int, Node>): Int? {
        var current = start
        val seen = HashSet<Int>()
        while (stateTargets.containsKey(current)) {
            if (!seen.add(current)) return null
            current = stateTargets.getValue(current)
        }
        return current.takeIf { nodes.containsKey(it) }
    }

    /** Iteratively builds a comment with its replies, breaking cycles. */
    private fun buildThread(
        rootId: Int,
        nodes: Map<Int, Node>,
        children: Map<Int, List<Int>>,
        latest: Map<Int, StateChange>,
        perAuthor: Map<Int, Map<String?, StateChange>>,
        visited: MutableSet<Int>
    ): PdfComment {
        class Frame(val id: Int, val childIds: List<Int>, var next: Int, val replies: MutableList<PdfComment>)
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(rootId, children[rootId].orEmpty(), 0, ArrayList()))
        var result: PdfComment? = null
        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (frame.next < frame.childIds.size) {
                val child = frame.childIds[frame.next++]
                if (visited.add(child)) {
                    stack.addLast(Frame(child, children[child].orEmpty(), 0, ArrayList()))
                }
            } else {
                val comment = makeComment(frame.id, frame.replies, nodes, latest, perAuthor)
                stack.removeLast()
                if (stack.isEmpty()) result = comment else stack.last().replies += comment
            }
        }
        return requireNotNull(result)
    }

    private fun makeComment(
        id: Int,
        replies: List<PdfComment>,
        nodes: Map<Int, Node>,
        latest: Map<Int, StateChange>,
        perAuthor: Map<Int, Map<String?, StateChange>>
    ): PdfComment {
        val node = nodes.getValue(id)
        val annot = node.annot
        val ownState = CommentStatus.from(annot.state)
        val status = latest[id]?.status ?: ownState
        val resolved = perAuthor[id]?.values?.any { it.status.resolved } ?: status.resolved
        return PdfComment(
            id = id,
            pageIndex = node.pageIndex,
            type = annot.type,
            rect = annot.rect,
            contents = annot.contents,
            author = annot.title,
            subject = annot.subject,
            color = annot.color,
            created = annot.creationDate,
            modified = annot.modifiedDate,
            status = status,
            resolved = resolved,
            replyType = if (annot.inReplyTo != null) ReplyType.from(annot.replyType) else null,
            inReplyToId = annot.inReplyTo?.objNum,
            open = annot.open,
            replies = replies
        )
    }

    /**
     * A state change is a text annotation that replies to the comment and
     * carries /State and /StateModel (§12.5.6.3, Table 170); /RT /Group marks
     * grouping instead.
     */
    private fun isStateChange(annot: PdfAnnotation): Boolean =
        annot.type == AnnotationType.TEXT &&
            annot.inReplyTo != null &&
            annot.state != null &&
            annot.stateModel != null &&
            !annot.replyType.equals("Group", ignoreCase = true)

    private fun timeOf(annot: PdfAnnotation): Long =
        PdfDate.millis(annot.creationDate) ?: PdfDate.millis(annot.modifiedDate) ?: 0L
}
