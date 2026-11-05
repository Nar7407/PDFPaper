package com.pdfpaper.app.annot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * T56: the hub's text export. The renderer takes the export time and zone from
 * the caller, so the expected Markdown and plain text below are fixed strings
 * that do not depend on when or where the test runs.
 */
class AnnotationExportTest {

    private val zone = ZoneId.of("UTC")
    private val now = 1_700_000_000_000L // 2023-11-14T22:13:20Z
    private val date = "2023-11-14"

    private fun item(
        id: Long,
        displayName: String,
        kind: AnnotationKind,
        pageIndex: Int,
        color: String? = null,
        author: String? = null,
        quotedText: String? = null,
        noteText: String? = null,
        tag: String? = null,
        createdAt: Long = 1_600_000_000_000L
    ) = AnnotationItem(
        id = id,
        documentId = id,
        documentUri = "content://docs/$id",
        displayName = displayName,
        kind = kind,
        pageIndex = pageIndex,
        color = color,
        author = author,
        createdAt = createdAt,
        modifiedAt = null,
        quotedText = quotedText,
        noteText = noteText,
        bounds = null,
        objectNumber = null,
        label = null,
        tag = tag
    )

    private val highlight = item(
        id = 1,
        displayName = "Report.pdf",
        kind = AnnotationKind.HIGHLIGHT,
        pageIndex = 0,
        color = "#F5D547",
        author = "Alice",
        quotedText = "First line\nsecond line",
        noteText = "Worth a look",
        tag = "todo"
    )

    private val note = item(
        id = 2,
        displayName = "Notes.pdf",
        kind = AnnotationKind.NOTE,
        pageIndex = 1,
        color = "#4A9BE8",
        author = "Bob",
        noteText = "Remember"
    )

    private val items = listOf(highlight, note)

    @Test
    fun markdownMatchesTheGoldenFile() {
        val expected = """
            # Annotations

            $date · 2 annotations from 2 documents

            ## Notes.pdf

            ### Page 2 · Note · Blue · Bob

            Remember


            ## Report.pdf

            ### Page 1 · Highlight · Yellow · Alice

            > First line
            > second line

            Worth a look

            Tag: todo

            ---

            _Exported by PDFPaper. The annotation index stays on this device._
        """.trimIndent() + "\n"

        assertEquals(
            expected,
            AnnotationExport.render(items, AnnotationExportOptions(), now, zone)
        )
    }

    @Test
    fun plainTextMatchesTheGoldenFile() {
        val expected = """
            PDFPaper annotations
            $date
            2 annotations from 2 documents

            Notes.pdf
              Page 2 [Note] · Blue · Bob
                Remember

            Report.pdf
              Page 1 [Highlight] · Yellow · Alice
                "First line"
                "second line"
                Worth a look
                Tag: todo
        """.trimIndent() + "\n"

        assertEquals(
            expected,
            AnnotationExport.render(
                items,
                AnnotationExportOptions(format = AnnotationExportFormat.TEXT),
                now,
                zone
            )
        )
    }

    @Test
    fun obsidianFlavourAddsFrontmatterWikiLinksAndACallout() {
        val text = AnnotationExport.render(
            items,
            AnnotationExportOptions(format = AnnotationExportFormat.MARKDOWN, obsidian = true),
            now,
            zone
        )

        assertTrue(text.startsWith("---\ntags:\n  - pdfpaper\n  - annotations\nsource: PDFPaper\nexported: $date\n---\n\n"))
        assertTrue(text.contains("## [[Notes.pdf]]"))
        assertTrue(text.contains("### [[Notes.pdf#page=2|Page 2]] · Note · Blue · Bob"))
        assertTrue(text.contains("> [!quote]\n> First line\n> second line\n"))
    }

    @Test
    fun quotedTextAndNotesCanBeLeftOut() {
        val text = AnnotationExport.render(
            items,
            AnnotationExportOptions(
                format = AnnotationExportFormat.MARKDOWN,
                includeQuotedText = false,
                includeNotes = false
            ),
            now,
            zone
        )

        assertTrue(!text.contains("First line"))
        assertTrue(!text.contains("Worth a look"))
        assertTrue(!text.contains("Remember"))
        assertTrue(text.contains("### Page 1 · Highlight · Yellow · Alice"))
    }

    @Test
    fun rowsAreOrderedByDocumentThenPageWhateverTheInputOrder() {
        val outOfOrder = listOf(
            item(3, "doc.pdf", AnnotationKind.NOTE, 2),
            item(4, "A.pdf", AnnotationKind.NOTE, 0),
            item(5, "doc.pdf", AnnotationKind.NOTE, 0),
            item(6, "doc.pdf", AnnotationKind.NOTE, 1)
        )

        val text = AnnotationExport.render(outOfOrder, AnnotationExportOptions(), now, zone)
        val headings = text.lines().filter { it.startsWith("## ") }

        assertEquals(listOf("## A.pdf", "## doc.pdf"), headings)
        val one = text.indexOf("Page 1 · Note")
        val two = text.lastIndexOf("Page 2 · Note")
        val three = text.indexOf("Page 3 · Note")
        assertTrue(one in 0 until two)
        assertTrue(two < three)
    }

    @Test
    fun fileNamesNameTheDocumentWhenThereIsOnlyOne() {
        assertEquals(
            "Report-annotations-$date.md",
            AnnotationExport.fileName(listOf(highlight), AnnotationExportFormat.MARKDOWN, now, zone)
        )
        assertEquals(
            "annotations-$date.txt",
            AnnotationExport.fileName(items, AnnotationExportFormat.TEXT, now, zone)
        )
    }

    @Test
    fun anAwkwardDocumentNameBecomesASafeFileName() {
        val awkward = item(6, "Q3 report (draft).pdf", AnnotationKind.HIGHLIGHT, 0)

        assertEquals(
            "Q3-report--draft-annotations-$date.md",
            AnnotationExport.fileName(listOf(awkward), AnnotationExportFormat.MARKDOWN, now, zone)
        )
    }
}
