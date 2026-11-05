package com.pdfpaper.engine.annot

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfBoolean
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T22 acceptance: comments (§12.5.6) are listed with their threads, authors,
 * dates, colours and review states, and new notes, markup comments, replies,
 * popup windows and state changes round-trip through a saved document.
 */
class PdfCommentsTest {

    private fun open(vararg objects: TestObj): PdfDocument =
        PdfDocument.open(ByteArraySource(buildTestPdf(objects.toList()))).getOrThrow()

    /** A one-page document whose /Annots list is [annots]; annotation objects are 5..n. */
    private fun document(vararg annots: String): PdfDocument {
        val objs = ArrayList<TestObj>()
        objs += TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>")
        objs += TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        val refs = (5..(4 + annots.size)).joinToString(" ") { "$it 0 R" }
        objs += TestObj(
            3, 0,
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> " +
                "/Annots [$refs] >>"
        )
        annots.forEachIndexed { index, body -> objs += TestObj(5 + index, 0, body) }
        return open(*objs.toTypedArray())
    }

    private fun reopen(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private fun epoch(text: String): Long =
        LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun content(stream: com.pdfpaper.engine.model.PdfStream): String =
        String(stream.data, StandardCharsets.ISO_8859_1)

    // -- Reading -----------------------------------------------------------------

    @Test
    fun listsCommentsByPageThenTime() {
        val doc = open(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>"),
            TestObj(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> " +
                    "/Annots [5 0 R 6 0 R] >>"
            ),
            TestObj(
                4, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> " +
                    "/Annots [7 0 R] >>"
            ),
            TestObj(
                5, 0,
                "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (page 1 later) " +
                    "/T (Alice) /M (D:20240102120000Z) >>"
            ),
            TestObj(
                6, 0,
                "<< /Type /Annot /Subtype /Text /Rect [10 40 30 60] /Contents (page 1 earlier) " +
                    "/T (Alice) /M (D:20240101120000Z) >>"
            ),
            TestObj(
                7, 0,
                "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (page 2 oldest) " +
                    "/T (Bob) /M (D:20230101000000Z) >>"
            )
        )
        val comments = doc.comments()
        assertEquals(3, comments.size)
        // Page first, then creation time, not document object order.
        assertEquals(
            listOf("page 1 earlier", "page 1 later", "page 2 oldest"),
            comments.map { it.contents }
        )
        assertEquals(listOf(0, 0, 1), comments.map { it.pageIndex })
        assertEquals(listOf(6, 5, 7), comments.map { it.id })
        assertEquals(PdfRect(10.0, 40.0, 30.0, 60.0), comments[0].rect)
        assertEquals("Alice", comments[0].author)
        assertEquals(epoch("2024-01-01T12:00:00"), PdfDate.millis(comments[0].modified))
        doc.close()
    }

    @Test
    fun threadsRepliesUnderTheirParent() {
        val doc = document(
            "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (question) " +
                "/T (Alice) /M (D:20240101100000Z) >>",
            "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (second) " +
                "/T (Carol) /IRT 5 0 R /RT /R /M (D:20240101120000Z) >>",
            "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (first) " +
                "/T (Bob) /IRT 5 0 R /RT /R /M (D:20240101110000Z) >>",
            "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (orphan) " +
                "/T (Dan) /IRT 99 0 R /M (D:20240101130000Z) >>"
        )
        val comments = doc.comments()
        // The dangling /IRT cannot be resolved, so its annotation is a root.
        assertEquals(listOf(5, 8), comments.map { it.id })

        val root = comments.first()
        assertEquals("question", root.contents)
        assertTrue(root.replies.isNotEmpty())
        assertEquals(listOf("first", "second"), root.replies.map { it.contents })
        assertTrue(root.replies.all { it.inReplyToId == 5 })
        assertTrue(root.replies.all { it.replyType == ReplyType.REPLY })
        assertEquals(listOf(7, 6), root.replies.map { it.id })

        val orphan = comments[1]
        assertEquals(99, orphan.inReplyToId)
        assertTrue(orphan.replies.isEmpty())
        doc.close()
    }

    @Test
    fun foldsReviewStatesIntoStatusAndResolved() {
        val doc = document(
            "<< /Type /Annot /Subtype /Highlight /Rect [10 10 30 30] " +
                "/QuadPoints [10 30 30 30 10 10 30 10] /Contents (please fix) /T (Alice) " +
                "/M (D:20240101100000Z) >>",
            "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /T (Bob) /IRT 5 0 R " +
                "/State (Completed) /StateModel /Review /F 30 /M (D:20240101110000Z) >>",
            "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (agreed) " +
                "/T (Carol) /IRT 5 0 R /RT /R /M (D:20240101115000Z) >>",
            "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /T (Alice) /IRT 5 0 R " +
                "/State (Accepted) /StateModel /Review /F 30 /M (D:20240101120000Z) >>",
            // Bob changes his mind; his new state replies to his previous one.
            "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /T (Bob) /IRT 6 0 R " +
                "/State (Rejected) /StateModel /Review /F 30 /M (D:20240101130000Z) >>"
        )
        val comment = doc.comments().single()
        assertEquals("please fix", comment.contents)
        // State annotations are folded into the comment, not listed as replies.
        assertEquals(listOf("agreed"), comment.replies.map { it.contents })
        // Bob's latest state by time wins.
        assertEquals(CommentStatus.REJECTED, comment.status)
        // Resolved is per reviewer: Bob's latest state is not Completed.
        assertFalse(comment.resolved)
        val states = doc.pages().first().annotations().filter { it.state != null }
        assertEquals(3, states.size)
        doc.close()
    }

    @Test
    fun resolvedIsAnyReviewersLatestCompletedState() {
        val doc = document(
            "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (fix) /T (Alice) " +
                "/M (D:20240101100000Z) >>",
            "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /T (Alice) /IRT 5 0 R " +
                "/State (Rejected) /StateModel /Review /F 30 /M (D:20240101110000Z) >>",
            "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /T (Bob) /IRT 5 0 R " +
                "/State (Completed) /StateModel /Review /F 30 /M (D:20240101120000Z) >>"
        )
        val comment = doc.comments().single()
        // The newest state overall is Bob's Completed.
        assertEquals(CommentStatus.COMPLETED, comment.status)
        // Bob completed the comment, so it stays resolved even if Alice objects.
        assertTrue(comment.resolved)
        doc.close()
    }

    @Test
    fun ownStateEntryIsUsedWhenNoStateAnnotations() {
        val doc = document(
            "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (done) " +
                "/State (Completed) /StateModel /Review >>"
        )
        val comment = doc.comments().single()
        assertEquals(CommentStatus.COMPLETED, comment.status)
        assertTrue(comment.resolved)
        doc.close()
    }

    // -- Writing -----------------------------------------------------------------

    @Test
    fun addsNoteWithPopupWindow() {
        val doc = document()
        val bytes = PdfComments.edit(
            doc,
            listOf(
                NoteSpec(
                    0, PdfRect(100.0, 600.0, 120.0, 620.0), "please check",
                    title = "Alice", subject = "Review", open = true, popup = true
                )
            )
        ).getOrThrow()
        doc.close()

        val reopened = reopen(bytes)
        val annots = reopened.pages().first().annotations()
        assertEquals(2, annots.size)
        val note = annots.single { it.type == AnnotationType.TEXT }
        assertEquals("please check", note.contents)
        assertEquals("Alice", note.title)
        assertEquals("Review", note.subject)
        assertEquals(NoteSpec.DEFAULT_NOTE_COLOR, note.color)
        assertEquals("Note", (note.dict["Name"] as PdfName).name)
        assertNotNull(note.appearance)
        assertTrue(note.creationDate!!.startsWith("D:"))
        assertEquals(note.creationDate, note.modifiedDate)

        val popupRef = assertNotNull(note.popup)
        val popup = reopened.resolve(popupRef) as PdfDict
        assertEquals("Popup", ((popup["Subtype"]) as PdfName).name)
        assertEquals(note.ref, popup["Parent"])
        assertEquals(true, (popup["Open"] as PdfBoolean).value)
        assertNull(popup["AP"])
        assertNull(popup["Contents"])
        // The popup window is not a comment of its own.
        val comments = reopened.comments()
        assertEquals(1, comments.size)
        assertEquals(PdfRect(100.0, 600.0, 120.0, 620.0), comments.single().rect)
        assertTrue(comments.single().open!!)
        reopened.close()
    }

    @Test
    fun addsHighlightAndUnderlineWithAppearanceStreams() {
        val doc = document()
        val bytes = PdfComments.edit(
            doc,
            listOf(
                HighlightSpec(
                    0,
                    listOf(PdfRect(100.0, 100.0, 200.0, 120.0), PdfRect(100.0, 80.0, 180.0, 100.0)),
                    contents = "key point",
                    title = "Alice",
                    subject = "Review"
                ),
                UnderlineSpec(
                    0, listOf(PdfRect(100.0, 40.0, 200.0, 60.0)),
                    contents = "under", title = "Bob"
                )
            )
        ).getOrThrow()
        doc.close()

        val reopened = reopen(bytes)
        val annots = reopened.pages().first().annotations()
        assertEquals(2, annots.size) // No popups unless asked for.
        val highlight = annots.single { it.type == AnnotationType.HIGHLIGHT }
        assertEquals(2, highlight.quadPoints.size)
        assertEquals(PdfRect(100.0, 80.0, 200.0, 120.0), highlight.rect)
        assertEquals(HighlightSpec.DEFAULT_HIGHLIGHT_COLOR, highlight.color)
        assertEquals("key point", highlight.contents)
        val hlContent = content(assertNotNull(highlight.appearance))
        assertTrue(hlContent.contains("/GS0 gs"))
        assertTrue(hlContent.contains("re"))
        assertTrue(hlContent.contains("f"))

        val underline = annots.single { it.type == AnnotationType.UNDERLINE }
        assertEquals(UnderlineSpec.DEFAULT_UNDERLINE_COLOR, underline.color)
        val ulContent = content(assertNotNull(underline.appearance))
        // Coordinates are relative to the appearance box: one rule per rectangle.
        assertTrue(ulContent.contains("0 0 m 100 0 l"), ulContent)
        assertTrue(ulContent.contains(" m "))
        assertTrue(ulContent.contains(" l "))
        assertTrue(ulContent.contains("S"))
        assertEquals("under", underline.contents)

        val comments = reopened.comments()
        assertEquals(listOf("key point", "under"), comments.map { it.contents })
        assertEquals(listOf("Alice", "Bob"), comments.map { it.author })
        reopened.close()
    }

    @Test
    fun addsReplyThreadedOnTheTarget() {
        val doc = document(
            "<< /Type /Annot /Subtype /Highlight /Rect [100 100 200 120] /C [1 1 0] " +
                "/QuadPoints [100 120 200 120 100 100 200 100] /Contents (marked) /T (Alice) >>"
        )
        val target = doc.comments().single()
        val bytes = PdfComments.edit(
            doc,
            listOf(ReplySpec(0, target.id, "agreed", title = "Bob", subject = "Reply"))
        ).getOrThrow()
        doc.close()

        val reopened = reopen(bytes)
        val root = reopened.comments().single()
        assertEquals("marked", root.contents)
        val reply = root.replies.single()
        assertEquals("agreed", reply.contents)
        assertEquals("Bob", reply.author)
        assertEquals("Reply", reply.subject)
        assertEquals(root.id, reply.inReplyToId)
        assertEquals(ReplyType.REPLY, reply.replyType)
        assertEquals(PdfColor(1.0, 1.0, 0.0), reply.color) // Inherited from the target.
        // The reply icon sits over the target's upper-left corner.
        assertEquals(100.0, reply.rect!!.left)
        assertEquals(120.0, reply.rect!!.top)

        val raw = reopened.pages().first().annotations().single { it.contents == "agreed" }
        assertEquals("Comment", (raw.dict["Name"] as PdfName).name)
        assertEquals("R", (raw.dict["RT"] as PdfName).name)
        assertNotNull(raw.inReplyTo)
        assertFalse(raw.hidden)
        assertEquals((raw.inReplyTo as PdfRef).objNum, root.id)
        reopened.close()
    }

    @Test
    fun setsReviewStatusAsHiddenStateAnnotation() {
        val doc = document(
            "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (fix) " +
                "/T (Alice) /M (D:20240101100000Z) >>"
        )
        val target = doc.comments().single()
        val bytes = PdfComments.edit(
            doc,
            listOf(StatusSpec(0, target.id, CommentStatus.COMPLETED, title = "Bob"))
        ).getOrThrow()
        doc.close()

        val first = reopen(bytes)
        val comment = first.comments().single()
        assertEquals(CommentStatus.COMPLETED, comment.status)
        assertTrue(comment.resolved)
        assertTrue(comment.replies.isEmpty()) // The state annotation is not a reply.
        val state = first.pages().first().annotations().single { it.state != null }
        assertEquals("Completed", state.state)
        assertEquals("Review", state.stateModel)
        assertEquals("Bob", state.title)
        assertNull(state.contents)
        assertTrue(state.hidden) // /F 30.
        assertEquals(comment.id, state.inReplyTo!!.objNum)

        // A second review by another author folds over the first across saves.
        val second = PdfComments.edit(
            first,
            listOf(StatusSpec(0, comment.id, CommentStatus.ACCEPTED, title = "Carol"))
        ).getOrThrow()
        first.close()
        val reopened = reopen(second)
        val folded = reopened.comments().single()
        assertEquals(CommentStatus.ACCEPTED, folded.status)
        assertTrue(folded.resolved) // Bob still completed it.
        assertEquals(3, reopened.pages().first().annotations().size) // Comment + both states.
        reopened.close()
    }

    @Test
    fun deletesACommentWithItsPopupRepliesAndStates() {
        val doc = open(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> " +
                    "/Annots [5 0 R 6 0 R 7 0 R 8 0 R 9 0 R] >>"
            ),
            TestObj(
                5, 0,
                "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (remove me) " +
                    "/T (Alice) /Popup 6 0 R >>"
            ),
            TestObj(6, 0, "<< /Type /Annot /Subtype /Popup /Rect [35 10 251 118] /Parent 5 0 R >>"),
            TestObj(
                7, 0,
                "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (a reply) " +
                    "/T (Bob) /IRT 5 0 R /RT /R >>"
            ),
            TestObj(
                8, 0,
                "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /T (Bob) /IRT 5 0 R " +
                    "/State (Completed) /StateModel /Review /F 30 >>"
            ),
            TestObj(
                9, 0,
                "<< /Type /Annot /Subtype /Text /Rect [50 50 70 70] /Contents (keep me) /T (Carol) >>"
            )
        )
        val bytes = PdfComments.edit(doc, deletions = listOf(5)).getOrThrow()
        doc.close()

        val reopened = reopen(bytes)
        val annots = reopened.pages().first().annotations()
        assertEquals(listOf("keep me"), annots.map { it.contents })
        val comments = reopened.comments()
        assertEquals(1, comments.size)
        assertEquals("keep me", comments.single().contents)
        assertEquals(0, comments.single().pageIndex)
        reopened.close()
    }

    @Test
    fun deletingAReplyKeepsTheCommentAndItsPopup() {
        val doc = open(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> " +
                    "/Annots [5 0 R 6 0 R 7 0 R] >>"
            ),
            TestObj(
                5, 0,
                "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (keep me) " +
                    "/T (Alice) /Popup 6 0 R >>"
            ),
            TestObj(6, 0, "<< /Type /Annot /Subtype /Popup /Rect [35 10 251 118] /Parent 5 0 R >>"),
            TestObj(
                7, 0,
                "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (remove me) " +
                    "/T (Bob) /IRT 5 0 R /RT /R >>"
            )
        )
        val bytes = PdfComments.edit(doc, deletions = listOf(7)).getOrThrow()
        doc.close()

        val reopened = reopen(bytes)
        val root = reopened.comments().single()
        assertEquals("keep me", root.contents)
        assertTrue(root.replies.isEmpty())
        val kept = reopened.pages().first().annotations().single { it.contents == "keep me" }
        assertNotNull(kept.popup)
        assertEquals(2, reopened.pages().first().annotations().size)
        reopened.close()
    }

    @Test
    fun existingCommentAndPopupSurviveAnEdit() {
        val doc = open(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> " +
                    "/Annots [5 0 R 6 0 R] >>"
            ),
            TestObj(
                5, 0,
                "<< /Type /Annot /Subtype /Text /Rect [50 50 70 70] /Contents (old note) " +
                    "/T (Alice) /Popup 6 0 R /Open true >>"
            ),
            TestObj(
                6, 0,
                "<< /Type /Annot /Subtype /Popup /Rect [75 50 291 158] /Parent 5 0 R /Open true >>"
            )
        )
        val bytes = PdfComments.edit(
            doc,
            listOf(NoteSpec(0, PdfRect(300.0, 300.0, 320.0, 320.0), "new note", title = "Bob"))
        ).getOrThrow()
        doc.close()

        val reopened = reopen(bytes)
        val annots = reopened.pages().first().annotations()
        assertEquals(3, annots.size)
        val old = annots.single { it.contents == "old note" }
        assertEquals(true, old.open)
        val popupRef = assertNotNull(old.popup)
        val popup = reopened.resolve(popupRef) as PdfDict
        assertEquals(old.ref, popup["Parent"])
        assertEquals("Popup", ((popup["Subtype"]) as PdfName).name)
        assertEquals(listOf("old note", "new note"), reopened.comments().map { it.contents })
        reopened.close()
    }

    @Test
    fun keepsUnicodeTextAndListOrder() {
        val doc = document()
        val bytes = PdfComments.edit(
            doc,
            listOf(
                NoteSpec(0, PdfRect(10.0, 10.0, 30.0, 30.0), "Grüße", title = "Åsa"),
                NoteSpec(0, PdfRect(40.0, 10.0, 60.0, 30.0), "你好", title = "Li")
            )
        ).getOrThrow()
        doc.close()

        val comments = reopen(bytes).comments()
        assertEquals(listOf("Grüße", "你好"), comments.map { it.contents })
        assertEquals(listOf("Åsa", "Li"), comments.map { it.author })
        assertEquals(listOf(10.0, 40.0), comments.map { it.rect!!.left })
    }

    @Test
    fun unknownIdsFailTheEdit() {
        val doc = document("<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (q) >>")
        assertTrue(doc.comments().isNotEmpty())

        val noAnnot = PdfComments.edit(doc, deletions = listOf(99))
        assertTrue(noAnnot.isFailure)
        assertTrue(noAnnot.exceptionOrNull()!!.message!!.contains("No annotation with id 99"))

        val noComment = PdfComments.edit(doc, listOf(ReplySpec(0, 99, "hi")))
        assertTrue(noComment.isFailure)
        assertTrue(noComment.exceptionOrNull()!!.message!!.contains("No comment with id 99"))

        val noStatus = PdfComments.edit(doc, listOf(StatusSpec(0, 99, CommentStatus.ACCEPTED)))
        assertTrue(noStatus.isFailure)
        doc.close()
    }

    @Test
    fun replyOnTheWrongPageFails() {
        val doc = open(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>"),
            TestObj(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> " +
                    "/Annots [5 0 R] >>"
            ),
            TestObj(4, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> >>"),
            TestObj(5, 0, "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (q) >>")
        )
        val result = PdfComments.edit(doc, listOf(StatusSpec(1, 5, CommentStatus.ACCEPTED)))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("on page 1, not 2"))
        doc.close()
    }

    @Test
    fun documentsWithoutCommentsRoundTrip() {
        val doc = document()
        assertTrue(doc.comments().isEmpty())
        val bytes = PdfComments.edit(doc).getOrThrow()
        doc.close()
        val reopened = reopen(bytes)
        assertTrue(reopened.comments().isEmpty())
        reopened.close()
    }

    @Test
    fun readsAndFormatsPdfDates() {
        assertEquals(epoch("2024-01-01T12:00:00"), PdfDate.millis("D:20240101120000Z"))
        assertEquals(epoch("2024-01-01T12:00:00"), PdfDate.millis("D:20240101120000"))
        assertEquals(epoch("2024-01-01T10:00:00"), PdfDate.millis("D:20240101120000+02'00'"))
        assertEquals(epoch("2024-01-01T17:00:00"), PdfDate.millis("D:20240101120000-05'00'"))
        assertEquals(epoch("2024-01-01T00:00:00"), PdfDate.millis("D:2024"))
        assertEquals(epoch("2024-01-01T12:00:00"), PdfDate.millis("20240101120000Z"))
        assertEquals(epoch("2024-01-01T12:00:00"), PdfDate.millis("D:20240101120000.500Z"))
        assertNull(PdfDate.millis(null))
        assertNull(PdfDate.millis("D:banana"))
        assertNull(PdfDate.millis("D:20240101120000ZX"))
        assertNull(PdfDate.millis("D:20241301120000Z"))
        assertTrue(PdfDate.now().startsWith("D:"))
        assertNotNull(PdfDate.millis(PdfDate.now()))
    }
}
