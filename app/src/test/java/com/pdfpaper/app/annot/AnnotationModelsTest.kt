package com.pdfpaper.app.annot

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.geom.PdfRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * T56: the pure parts of the annotation index — document identity, the hub's
 * grouping, colours and bounds. Everything that needs the Room database or a
 * PDF is covered by the connected tests.
 */
class AnnotationModelsTest {

    private fun item(
        id: Long,
        displayName: String,
        kind: AnnotationKind = AnnotationKind.HIGHLIGHT,
        pageIndex: Int = 0,
        createdAt: Long = 1_600_000_000_000L
    ) = AnnotationItem(
        id = id,
        documentId = id,
        documentUri = "content://docs/$id",
        displayName = displayName,
        kind = kind,
        pageIndex = pageIndex,
        color = null,
        author = null,
        createdAt = createdAt,
        modifiedAt = null,
        quotedText = null,
        noteText = null,
        bounds = null,
        objectNumber = null,
        label = null,
        tag = null
    )

    // -------------------------------------------------------------- identity

    @Test
    fun theSameBytesGiveTheSameFingerprintAtADifferentPlace() {
        val bytes = ByteArray(4096) { (it % 251).toByte() }

        assertEquals(
            AnnotationFingerprint.of(ByteArraySource(bytes)),
            AnnotationFingerprint.of(ByteArraySource(bytes.copyOf()))
        )
    }

    @Test
    fun aDifferentHeadATailOrSizeChangesTheFingerprint() {
        val size = 40 * 1024
        val bytes = ByteArray(size) { (it % 251).toByte() }
        val base = AnnotationFingerprint.of(ByteArraySource(bytes))

        val head = bytes.copyOf().also { it[3] = 0x7F }
        val tail = bytes.copyOf().also { it[size - 3] = 0x7F }
        val middle = bytes.copyOf().also { it[20 * 1024] = 0x7F }
        val longer = bytes + byteArrayOf(0)

        assertNotEquals(base, AnnotationFingerprint.of(ByteArraySource(head)))
        assertNotEquals(base, AnnotationFingerprint.of(ByteArraySource(tail)))
        assertNotEquals(base, AnnotationFingerprint.of(ByteArraySource(longer)))
        // The hash covers size plus the first and last 8 KiB, so a change deep
        // in the middle of a large file is deliberately not noticed: it is a
        // cheap identity, not a checksum of the whole document.
        assertEquals(base, AnnotationFingerprint.of(ByteArraySource(middle)))
    }

    @Test
    fun anEmptyFileStillHasAFingerprint() {
        val empty = AnnotationFingerprint.of(ByteArraySource(ByteArray(0)))

        assertTrue(empty.length == 40)
        assertEquals(empty, AnnotationFingerprint.of(ByteArraySource(ByteArray(0))))
    }

    // -------------------------------------------------------------- grouping

    @Test
    fun groupingByDocumentSortsNamesAndPages() {
        val items = listOf(
            item(1, "b.pdf", pageIndex = 2),
            item(2, "A.pdf", pageIndex = 1),
            item(3, "b.pdf", pageIndex = 0)
        )

        val groups = groupAnnotations(items, AnnotationGrouping.DOCUMENT)

        assertEquals(listOf("A.pdf", "b.pdf"), groups.map { it.title })
        assertEquals(listOf(0, 2), groups[1].items.map { it.pageIndex })
    }

    @Test
    fun groupingByDatePutsTheNewestDayFirst() {
        val zone = ZoneId.of("UTC")
        val day = 24L * 60 * 60 * 1000
        val newest = 1_700_000_000_000L
        val items = listOf(
            item(1, "a.pdf", createdAt = newest - 3 * day),
            item(2, "a.pdf", createdAt = newest),
            item(3, "a.pdf", createdAt = newest - day)
        )

        val groups = groupAnnotations(items, AnnotationGrouping.DATE, zone)

        assertEquals(
            listOf("2023-11-14", "2023-11-13", "2023-11-11"),
            groups.map { it.title }
        )
        assertEquals(listOf(2L), groups[0].items.map { it.id })
        assertEquals(newest, groups[0].items.first().time)
    }

    // ------------------------------------------------------- colour + bounds

    @Test
    fun aStoredColourRoundTripsThroughHexAndHasALabel() {
        val yellow = AnnotationColors.fromHex("#F5D547")!!

        assertEquals(0.9608, yellow.red, 0.001)
        assertEquals("#F5D547", AnnotationColors.of(yellow))
        assertEquals("Yellow", AnnotationColors.labelFor("#f5d547"))
        assertNull(AnnotationColors.labelFor(null))
        assertNull(AnnotationColors.fromHex("#12"))
        assertEquals(0xFFF5D547.toInt(), AnnotationColors.argb("#F5D547"))
        assertEquals(0xFF9E9E9E.toInt(), AnnotationColors.argb(null))
    }

    @Test
    fun boundsAreStoredAndReadBackAsARectangle() {
        val rect = PdfRect(10.5, 20.0, 30.25, 40.0)

        val text = formatBounds(rect)

        assertEquals("10.5,20.0,30.25,40.0", text)
        val back = parseBounds(text)!!
        assertEquals(10.5, back.left, 0.0001)
        assertEquals(20.0, back.bottom, 0.0001)
        assertEquals(30.25, back.right, 0.0001)
        assertEquals(40.0, back.top, 0.0001)
        assertNull(formatBounds(PdfRect(0.0, 0.0, 0.0, 0.0)))
        assertNull(parseBounds("10,20,30"))
        assertNull(parseBounds("a,b,c,d"))
        assertNull(parseBounds(null))
    }

    @Test
    fun aBookmarkIsAppOnlyAndEveryOtherKindIsAPdfAnnotation() {
        assertTrue(AnnotationKind.BOOKMARK.isBookmark)
        assertTrue(!AnnotationKind.BOOKMARK.isPdf)
        assertTrue(AnnotationKind.HIGHLIGHT.isPdf)
        assertTrue(!AnnotationKind.HIGHLIGHT.isBookmark)
    }

    // ---------------------------------------------------------------- labels

    @Test
    fun theItemTextAndTheRowSummaryReadTheSameWayAsTheHub() {
        val row = item(1, "Report.pdf", pageIndex = 1).copy(
            quotedText = "quoted words",
            noteText = "my note",
            label = "Chapter 2",
            color = "#F5D547"
        )

        assertEquals("Chapter 2\nquoted words\nmy note", row.text)
        assertEquals("Highlight · page 2 · Chapter 2 quoted words my note", AnnotationRepository.summary(row))
        assertEquals(1, AnnotationRepository.pageFor(row, 3))
        assertEquals(0, AnnotationRepository.pageFor(row, 1))
    }

    @Test
    fun anItemWithNoTextHasAShortSummary() {
        val row = item(1, "Report.pdf", kind = AnnotationKind.BOOKMARK, pageIndex = 4)

        assertEquals("Bookmark · page 5", AnnotationRepository.summary(row))
        assertEquals("", row.text)
    }
}
