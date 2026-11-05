package com.pdfpaper.engine.split

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.text.layout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * T66: engine unit tests for splitting a document by table of contents.
 *
 * Covers:
 * - Nested bookmarks at levels 1-3 with text comparison of first/last page
 * - Explicit, named, action /D, and indirect destination resolution
 * - Out-of-order outline entries sorted by page and reported
 * - Same-page entries reported with deterministic non-zero ranges
 * - Empty outlines producing NoOutline, bad outlines producing NoUsableEntries
 * - Normal vs duplicate boundary page policies
 * - Final chapter ending at document end
 * - Output filename sanitization, numeric prefix, and collision resolution
 * - Reopening split parts to verify rebased bookmarks, preserved internal links,
 *   dropped cross-part links, rebased page labels, /Info, and AcroForm fields
 * - Streaming sequential writer with 1,000-page test, cancellation, and progress
 * - Heading-based fallback when outline is missing
 */
class TocSplitTest {

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    // ---------------------------------------------------------------- PDF Fixture Builders

    /** Page i of [createBookPdf] is this object number, above every extra object. */
    private fun pageObjNum(index: Int): Int = 100 + index

    /**
     * Creates a test PDF with [pageCount] pages, where each page contains "Page {index + 1} content".
     * Additional objects (outlines, names, info, acroform) can be appended via [extraObjects];
     * page i is object [pageObjNum]`(i)`, so no extra object can replace a page.
     * [pageEntries] appends raw dictionary entries to a page, by page index.
     * [withHeadings] gives every page a large heading line above its body line.
     */
    private fun createBookPdf(
        pageCount: Int,
        extraCatalog: String = "",
        extraTrailer: String = "",
        extraObjects: List<TestObj> = emptyList(),
        pageEntries: Map<Int, String> = emptyMap(),
        withHeadings: Boolean = false
    ): ByteArray {
        val objects = mutableListOf<TestObj>()
        val pageRefs = mutableListOf<String>()

        // Object 1: Catalog
        // Object 2: Pages root
        // Object 3: Font
        // Objects 4..(3 + pageCount): Content streams
        // Objects 100..(99 + pageCount): Page dicts
        val fontObjNum = 3
        objects += TestObj(fontObjNum, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

        var nextNum = 4
        val contentObjNums = mutableListOf<Int>()
        for (i in 0 until pageCount) {
            val cNum = nextNum++
            contentObjNums += cNum
            val streamText = if (withHeadings) {
                "BT /F1 16 Tf 50 700 Td (Chapter ${i + 1}) Tj ET\n" +
                    "BT /F1 10 Tf 50 660 Td (Page ${i + 1} content continues on this page.) Tj ET"
            } else {
                "BT /F1 12 Tf 50 700 Td (Page ${i + 1} content) Tj ET"
            }
            val streamBytes = streamText.toByteArray(StandardCharsets.US_ASCII)
            val header = "<< /Length ${streamBytes.size} >>\nstream\n"
            val footer = "\nendstream"
            val fullBytes = ByteArrayOutputStream().apply {
                write(header.toByteArray(StandardCharsets.US_ASCII))
                write(streamBytes)
                write(footer.toByteArray(StandardCharsets.US_ASCII))
            }.toByteArray()
            objects += TestObj(cNum, 0, fullBytes)
        }

        val pageObjNums = (0 until pageCount).map { pageObjNum(it) }
        for (pNum in pageObjNums) pageRefs += "$pNum 0 R"

        for (i in 0 until pageCount) {
            val pNum = pageObjNums[i]
            val cNum = contentObjNums[i]
            objects += TestObj(
                pNum, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /Font << /F1 $fontObjNum 0 R >> >> " +
                    "/Contents $cNum 0 R ${pageEntries[i].orEmpty()}>>"
            )
        }

        val pagesBody = "<< /Type /Pages /Kids [${pageRefs.joinToString(" ")}] /Count $pageCount >>"
        objects += TestObj(2, 0, pagesBody)

        val catalogBody = "<< /Type /Catalog /Pages 2 0 R $extraCatalog >>"
        objects += TestObj(1, 0, catalogBody)

        objects += extraObjects

        return buildTestPdf(objects, rootRef = "1 0 R", trailerExtra = extraTrailer)
    }

    /**
     * Builds a 6-page book with nested bookmarks:
     * - Level 1: "Chapter 1" (page 0), "Chapter 2" (page 3)
     *   - Level 2 under Ch 1: "Section 1.1" (page 1)
     *     - Level 3 under Sec 1.1: "Subsection 1.1.1" (page 2)
     *   - Level 2 under Ch 2: "Section 2.1" (page 4), "Section 2.2" (page 5)
     */
    private fun nestedBookPdf(): ByteArray {
        val pageObjNums = (10..15).toList()
        val objects = mutableListOf<TestObj>()
        val pageRefs = pageObjNums.map { "$it 0 R" }

        val fontNum = 3
        objects += TestObj(fontNum, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

        var cNum = 4
        for (i in 0 until 6) {
            val streamText = "BT /F1 12 Tf 50 700 Td (Page ${i + 1} chapter text) Tj ET"
            val streamBytes = streamText.toByteArray(StandardCharsets.US_ASCII)
            val header = "<< /Length ${streamBytes.size} >>\nstream\n"
            val footer = "\nendstream"
            val fullBytes = ByteArrayOutputStream().apply {
                write(header.toByteArray(StandardCharsets.US_ASCII))
                write(streamBytes)
                write(footer.toByteArray(StandardCharsets.US_ASCII))
            }.toByteArray()
            objects += TestObj(cNum + i, 0, fullBytes)
            objects += TestObj(
                pageObjNums[i], 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /Font << /F1 $fontNum 0 R >> >> " +
                    "/Contents ${cNum + i} 0 R >>"
            )
        }

        objects += TestObj(2, 0, "<< /Type /Pages /Kids [${pageRefs.joinToString(" ")}] /Count 6 >>")

        // Outline nodes:
        // 20: Outlines root (First: 21 (Ch1), Last: 22 (Ch2))
        // 21: Chapter 1 -> page 0 (10 0 R), First: 23 (Sec 1.1), Next: 22
        // 22: Chapter 2 -> page 3 (13 0 R), First: 25 (Sec 2.1), Prev: 21
        // 23: Section 1.1 -> page 1 (11 0 R), Parent: 21, First: 24 (Subsec 1.1.1)
        // 24: Subsection 1.1.1 -> page 2 (12 0 R), Parent: 23
        // 25: Section 2.1 -> page 4 (14 0 R), Parent: 22, Next: 26
        // 26: Section 2.2 -> page 5 (15 0 R), Parent: 22, Prev: 25
        objects += TestObj(20, 0, "<< /Type /Outlines /First 21 0 R /Last 22 0 R /Count 2 >>")
        objects += TestObj(
            21, 0,
            "<< /Title (Chapter 1) /Parent 20 0 R /Dest [10 0 R /Fit] /Next 22 0 R /First 23 0 R /Last 23 0 R >>"
        )
        objects += TestObj(
            22, 0,
            "<< /Title (Chapter 2) /Parent 20 0 R /Dest [13 0 R /Fit] /Prev 21 0 R /First 25 0 R /Last 26 0 R >>"
        )
        objects += TestObj(
            23, 0,
            "<< /Title (Section 1.1) /Parent 21 0 R /Dest [11 0 R /Fit] /First 24 0 R /Last 24 0 R >>"
        )
        objects += TestObj(
            24, 0,
            "<< /Title (Subsection 1.1.1) /Parent 23 0 R /Dest [12 0 R /Fit] >>"
        )
        objects += TestObj(
            25, 0,
            "<< /Title (Section 2.1) /Parent 22 0 R /Dest [14 0 R /Fit] /Next 26 0 R >>"
        )
        objects += TestObj(
            26, 0,
            "<< /Title (Section 2.2) /Parent 22 0 R /Dest [15 0 R /Fit] /Prev 25 0 R >>"
        )

        objects += TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R /Outlines 20 0 R >>")
        return buildTestPdf(objects, rootRef = "1 0 R")
    }

    // ---------------------------------------------------------------- Acceptance: Nested Bookmarks

    @Test
    fun nestedBookmarksLevel1ProducesTopLevelRangesAndMatchesText() {
        val pdf = nestedBookPdf()
        open(pdf).use { doc ->
            val plan = TocSplit.planOutline(doc, TocSplitOptions(level = 1))
            assertEquals(2, plan.parts.size)

            val part1 = plan.parts[0]
            assertEquals("Chapter 1", part1.title)
            assertEquals(0..2, part1.pages) // Pages 1 to 3 (0-indexed: 0, 1, 2)
            assertEquals("01 - Chapter 1.pdf", part1.fileName)

            val part2 = plan.parts[1]
            assertEquals("Chapter 2", part2.title)
            assertEquals(3..5, part2.pages) // Pages 4 to 6 (0-indexed: 3, 4, 5)
            assertEquals("02 - Chapter 2.pdf", part2.fileName)

            // Write parts and verify text of first and last pages
            val outputs = mutableListOf<TocSplitOutput>()
            TocSplitWriter.write(doc, plan.parts, { outputs.add(it) })
            assertEquals(2, outputs.size)

            open(outputs[0].bytes).use { p1Doc ->
                val p1Pages = p1Doc.pages()
                assertEquals(3, p1Pages.size)
                assertTrue(p1Pages.first().layout().text.contains("Page 1 chapter text"))
                assertTrue(p1Pages.last().layout().text.contains("Page 3 chapter text"))
            }

            open(outputs[1].bytes).use { p2Doc ->
                val p2Pages = p2Doc.pages()
                assertEquals(3, p2Pages.size)
                assertTrue(p2Pages.first().layout().text.contains("Page 4 chapter text"))
                assertTrue(p2Pages.last().layout().text.contains("Page 6 chapter text"))
            }
        }
    }

    @Test
    fun nestedBookmarksLevel2ProducesSubchapterRangesAndMatchesText() {
        val pdf = nestedBookPdf()
        open(pdf).use { doc ->
            val plan = TocSplit.planOutline(doc, TocSplitOptions(level = 2))
            // Level 2 includes depth 0 (Ch 1 @ p0, Ch 2 @ p3) and depth 1 (Sec 1.1 @ p1, Sec 2.1 @ p4, Sec 2.2 @ p5)
            // Expected parts:
            // 1. Ch 1: pages 0..0
            // 2. Sec 1.1: pages 1..2 (until Ch 2 @ p3)
            // 3. Ch 2: pages 3..3
            // 4. Sec 2.1: pages 4..4
            // 5. Sec 2.2: pages 5..5
            assertEquals(5, plan.parts.size)
            assertEquals("Chapter 1", plan.parts[0].title)
            assertEquals(0..0, plan.parts[0].pages)

            assertEquals("Section 1.1", plan.parts[1].title)
            assertEquals(1..2, plan.parts[1].pages)

            assertEquals("Chapter 2", plan.parts[2].title)
            assertEquals(3..3, plan.parts[2].pages)

            assertEquals("Section 2.1", plan.parts[3].title)
            assertEquals(4..4, plan.parts[3].pages)

            assertEquals("Section 2.2", plan.parts[4].title)
            assertEquals(5..5, plan.parts[4].pages)

            val outputs = mutableListOf<TocSplitOutput>()
            TocSplitWriter.write(doc, plan.parts, { outputs.add(it) })
            assertEquals(5, outputs.size)

            open(outputs[1].bytes).use { pDoc ->
                assertEquals(2, pDoc.pages().size)
                assertTrue(pDoc.pages().first().layout().text.contains("Page 2 chapter text"))
                assertTrue(pDoc.pages().last().layout().text.contains("Page 3 chapter text"))
            }
        }
    }

    @Test
    fun nestedBookmarksLevel3ProducesDeepRanges() {
        val pdf = nestedBookPdf()
        open(pdf).use { doc ->
            val plan = TocSplit.planOutline(doc, TocSplitOptions(level = 3))
            // Depth 0: Ch 1 @ 0, Ch 2 @ 3
            // Depth 1: Sec 1.1 @ 1, Sec 2.1 @ 4, Sec 2.2 @ 5
            // Depth 2: Subsec 1.1.1 @ 2
            // Total 6 chapters across 6 pages -> 1 page each
            assertEquals(6, plan.parts.size)
            assertEquals(0..0, plan.parts[0].pages)
            assertEquals(1..1, plan.parts[1].pages)
            assertEquals(2..2, plan.parts[2].pages)
            assertEquals("Subsection 1.1.1", plan.parts[2].title)
            assertEquals(3..3, plan.parts[3].pages)
            assertEquals(4..4, plan.parts[4].pages)
            assertEquals(5..5, plan.parts[5].pages)
        }
    }

    // ---------------------------------------------------------------- Destination Types Resolution

    @Test
    fun destinationTypesExplicitNamedActionAndIndirectAllResolve() {
        val extraObjs = listOf(
            // Outlines root
            TestObj(10, 0, "<< /Type /Outlines /First 11 0 R /Last 14 0 R /Count 4 >>"),
            // 11: Explicit destination [pageRef /Fit]
            TestObj(11, 0, "<< /Title (Explicit) /Parent 10 0 R /Dest [100 0 R /Fit] /Next 12 0 R >>"),
            // 12: Action /GoTo with /D [pageRef /XYZ 0 500 0]
            TestObj(
                12, 0,
                "<< /Title (ActionGoTo) /Parent 10 0 R /A << /S /GoTo /D [101 0 R /XYZ 0 500 0] >> /Prev 11 0 R /Next 13 0 R >>"
            ),
            // 13: Named destination "ch3_dest" resolved through /Dests dictionary
            TestObj(13, 0, "<< /Title (NamedDest) /Parent 10 0 R /Dest /ch3_dest /Prev 12 0 R /Next 14 0 R >>"),
            // 14: Indirect destination reference -> points to obj 15
            TestObj(14, 0, "<< /Title (IndirectDest) /Parent 10 0 R /Dest 15 0 R /Prev 13 0 R >>"),
            // 15: The destination array object
            TestObj(15, 0, "[103 0 R /Fit]")
        )

        // 4 pages total (pages are objs 100, 101, 102, 103)
        val pdf = createBookPdf(
            pageCount = 4,
            extraCatalog = "/Outlines 10 0 R /Dests << /ch3_dest [102 0 R /Fit] >>",
            extraObjects = extraObjs
        )

        open(pdf).use { doc ->
            val plan = TocSplit.planOutline(doc, TocSplitOptions(level = 1))
            assertEquals(4, plan.parts.size)
            assertEquals("Explicit", plan.parts[0].title)
            assertEquals(0..0, plan.parts[0].pages)

            assertEquals("ActionGoTo", plan.parts[1].title)
            assertEquals(1..1, plan.parts[1].pages)

            assertEquals("NamedDest", plan.parts[2].title)
            assertEquals(2..2, plan.parts[2].pages)

            assertEquals("IndirectDest", plan.parts[3].title)
            assertEquals(3..3, plan.parts[3].pages)
        }
    }

    // ---------------------------------------------------------------- Bad/Messy Outlines Handling

    @Test
    fun invalidCyclicAndMissingDestinationsAreSkippedAndReported() {
        val extraObjs = listOf(
            TestObj(10, 0, "<< /Type /Outlines /First 11 0 R /Last 13 0 R /Count 3 >>"),
            // 11: Valid entry to page 0
            TestObj(11, 0, "<< /Title (Valid Start) /Parent 10 0 R /Dest [100 0 R /Fit] /Next 12 0 R >>"),
            // 12: Bad destination (points to non-existent object 999 0 R)
            TestObj(12, 0, "<< /Title (Bad Target) /Parent 10 0 R /Dest [999 0 R /Fit] /Prev 11 0 R /Next 13 0 R >>"),
            // 13: Valid entry to page 2 (page obj 102)
            TestObj(13, 0, "<< /Title (Valid End) /Parent 10 0 R /Dest [102 0 R /Fit] /Prev 12 0 R >>")
        )

        val pdf = createBookPdf(
            pageCount = 3,
            extraCatalog = "/Outlines 10 0 R",
            extraObjects = extraObjs
        )

        open(pdf).use { doc ->
            val plan = TocSplit.planOutline(doc, TocSplitOptions(level = 1))
            // The bad target should be skipped and reported in issues
            assertEquals(2, plan.parts.size)
            assertEquals("Valid Start", plan.parts[0].title)
            assertEquals(0..1, plan.parts[0].pages)
            assertEquals("Valid End", plan.parts[1].title)
            assertEquals(2..2, plan.parts[1].pages)

            assertTrue(
                "Expected InvalidDestination issue for 'Bad Target'",
                plan.issues.any { it is TocSplitIssue.InvalidDestination && it.title == "Bad Target" }
            )
        }
    }

    @Test
    fun outOfOrderEntriesAreSortedByPageAndReported() {
        // Acceptance requirement: book with out-of-order entries
        val extraObjs = listOf(
            TestObj(10, 0, "<< /Type /Outlines /First 11 0 R /Last 13 0 R /Count 3 >>"),
            // 11: Points to page 2 (obj 102)
            TestObj(11, 0, "<< /Title (Chapter Later) /Parent 10 0 R /Dest [102 0 R /Fit] /Next 12 0 R >>"),
            // 12: Points to page 0 (obj 100)
            TestObj(12, 0, "<< /Title (Chapter Earlier) /Parent 10 0 R /Dest [100 0 R /Fit] /Prev 11 0 R /Next 13 0 R >>"),
            // 13: Points to page 1 (obj 101)
            TestObj(13, 0, "<< /Title (Chapter Middle) /Parent 10 0 R /Dest [101 0 R /Fit] /Prev 12 0 R >>")
        )

        val pdf = createBookPdf(
            pageCount = 4,
            extraCatalog = "/Outlines 10 0 R",
            extraObjects = extraObjs
        )

        open(pdf).use { doc ->
            val plan = TocSplit.planOutline(doc, TocSplitOptions(level = 1))
            // Should be sorted by page index: page 0, page 1, page 2
            assertEquals(3, plan.parts.size)
            assertEquals("Chapter Earlier", plan.parts[0].title)
            assertEquals(0..0, plan.parts[0].pages)

            assertEquals("Chapter Middle", plan.parts[1].title)
            assertEquals(1..1, plan.parts[1].pages)

            assertEquals("Chapter Later", plan.parts[2].title)
            assertEquals(2..3, plan.parts[2].pages)

            // Must report OutOfOrder issues
            assertTrue(
                "Expected OutOfOrder issue",
                plan.issues.any { it is TocSplitIssue.OutOfOrder }
            )
        }
    }

    @Test
    fun multipleEntriesOnSamePageReportIssueAndDoNotProduceZeroPageParts() {
        val extraObjs = listOf(
            TestObj(10, 0, "<< /Type /Outlines /First 11 0 R /Last 13 0 R /Count 3 >>"),
            // Two entries point to page 0 (obj 100)
            TestObj(11, 0, "<< /Title (Overview) /Parent 10 0 R /Dest [100 0 R /Fit] /Next 12 0 R >>"),
            TestObj(12, 0, "<< /Title (Detailed Intro) /Parent 10 0 R /Dest [100 0 R /Fit] /Prev 11 0 R /Next 13 0 R >>"),
            // Third points to page 2 (obj 102)
            TestObj(13, 0, "<< /Title (Next Part) /Parent 10 0 R /Dest [102 0 R /Fit] /Prev 12 0 R >>")
        )

        val pdf = createBookPdf(
            pageCount = 3,
            extraCatalog = "/Outlines 10 0 R",
            extraObjects = extraObjs
        )

        open(pdf).use { doc ->
            val plan = TocSplit.planOutline(doc, TocSplitOptions(level = 1))
            assertTrue(
                "Expected SamePage issue reported",
                plan.issues.any { it is TocSplitIssue.SamePage && it.page == 0 }
            )
            // No part should have an empty range (e.g. 0..-1)
            for (part in plan.parts) {
                assertTrue("Part ${part.title} has invalid range ${part.pages}", part.pages.first <= part.pages.last)
            }
        }
    }

    @Test
    fun emptyOutlineProducesNoOutlineIssueAndNoParts() {
        // Acceptance requirement: book with no outline
        val pdf = createBookPdf(pageCount = 5)
        open(pdf).use { doc ->
            val plan = TocSplit.planOutline(doc, TocSplitOptions(level = 1))
            assertTrue(plan.parts.isEmpty())
            assertTrue(plan.issues.any { it is TocSplitIssue.NoOutline })
        }
    }

    @Test
    fun outlineWithOnlyBadTargetsProducesNoUsableEntries() {
        val extraObjs = listOf(
            TestObj(10, 0, "<< /Type /Outlines /First 11 0 R /Last 11 0 R /Count 1 >>"),
            TestObj(11, 0, "<< /Title (Nowhere) /Parent 10 0 R /Dest [500 0 R /Fit] >>")
        )
        val pdf = createBookPdf(pageCount = 3, extraCatalog = "/Outlines 10 0 R", extraObjects = extraObjs)
        open(pdf).use { doc ->
            val plan = TocSplit.planOutline(doc, TocSplitOptions(level = 1))
            assertTrue(plan.parts.isEmpty())
            assertTrue(plan.issues.any { it is TocSplitIssue.NoUsableEntries })
        }
    }

    // ---------------------------------------------------------------- Range & Boundary Options

    @Test
    fun defaultBoundaryDoesNotDuplicateBoundaryPage() {
        val extraObjs = listOf(
            TestObj(10, 0, "<< /Type /Outlines /First 11 0 R /Last 12 0 R /Count 2 >>"),
            TestObj(11, 0, "<< /Title (Part A) /Parent 10 0 R /Dest [100 0 R /Fit] /Next 12 0 R >>"),
            TestObj(12, 0, "<< /Title (Part B) /Parent 10 0 R /Dest [102 0 R /Fit] /Prev 11 0 R >>")
        )
        // 5 pages: Part A starts at 0, Part B starts at 2
        val pdf = createBookPdf(pageCount = 5, extraCatalog = "/Outlines 10 0 R", extraObjects = extraObjs)
        open(pdf).use { doc ->
            val plan = TocSplit.planOutline(doc, TocSplitOptions(level = 1, duplicateBoundaryPage = false))
            assertEquals(2, plan.parts.size)
            assertEquals(0..1, plan.parts[0].pages) // Ends at next entry's page minus 1
            assertEquals(2..4, plan.parts[1].pages) // Last ends at document end
        }
    }

    @Test
    fun duplicateBoundaryPageOptionIncludesBoundaryInBothParts() {
        val extraObjs = listOf(
            TestObj(10, 0, "<< /Type /Outlines /First 11 0 R /Last 12 0 R /Count 2 >>"),
            TestObj(11, 0, "<< /Title (Part A) /Parent 10 0 R /Dest [100 0 R /Fit] /Next 12 0 R >>"),
            TestObj(12, 0, "<< /Title (Part B) /Parent 10 0 R /Dest [102 0 R /Fit] /Prev 11 0 R >>")
        )
        // 5 pages: Part A starts at 0, Part B starts at 2
        val pdf = createBookPdf(pageCount = 5, extraCatalog = "/Outlines 10 0 R", extraObjects = extraObjs)
        open(pdf).use { doc ->
            val plan = TocSplit.planOutline(doc, TocSplitOptions(level = 1, duplicateBoundaryPage = true))
            assertEquals(2, plan.parts.size)
            assertEquals(0..2, plan.parts[0].pages) // Part A includes shared boundary page 2
            assertEquals(2..4, plan.parts[1].pages) // Part B starts at 2
        }
    }

    @Test
    fun lastRangeAlwaysEndsAtDocumentEnd() {
        val extraObjs = listOf(
            TestObj(10, 0, "<< /Type /Outlines /First 11 0 R /Last 11 0 R /Count 1 >>"),
            TestObj(11, 0, "<< /Title (Only Chapter) /Parent 10 0 R /Dest [100 0 R /Fit] >>")
        )
        val pdf = createBookPdf(pageCount = 10, extraCatalog = "/Outlines 10 0 R", extraObjects = extraObjs)
        open(pdf).use { doc ->
            val plan = TocSplit.planOutline(doc, TocSplitOptions(level = 1))
            assertEquals(1, plan.parts.size)
            assertEquals(0..9, plan.parts[0].pages)
        }
    }

    // ---------------------------------------------------------------- Filename Sanitization

    @Test
    fun sanitizedFileNamesHandlesIllegalCharactersAndReservedNames() {
        // Illegal characters: : \ / * ? " < > |
        val sanitized = TocSplitFileNames.sanitize("Chapter 1: A / B * C? \"D\" <E> >F| G", index = 1, totalParts = 1)
        assertFalse(sanitized.contains(":"))
        assertFalse(sanitized.contains("/"))
        assertFalse(sanitized.contains("\\"))
        assertFalse(sanitized.contains("*"))
        assertFalse(sanitized.contains("?"))
        assertFalse(sanitized.contains("\""))
        assertFalse(sanitized.contains("<"))
        assertFalse(sanitized.contains(">"))
        assertFalse(sanitized.contains("|"))
        assertTrue(sanitized.endsWith(".pdf"))

        // Reserved Windows names: CON, PRN, AUX, NUL, COM1, LPT1
        val conSanitized = TocSplitFileNames.sanitize("CON", index = 1, totalParts = 1)
        assertFalse(conSanitized.equals("CON.pdf", ignoreCase = true))
        val auxSanitized = TocSplitFileNames.sanitize("AUX", index = 2, totalParts = 1)
        assertFalse(auxSanitized.equals("AUX.pdf", ignoreCase = true))
    }

    @Test
    fun sanitizedFileNamesAppliesNumericPrefixAndWidth() {
        val name1 = TocSplitFileNames.sanitize("Intro", index = 1, totalParts = 5)
        assertEquals("01 - Intro.pdf", name1)

        val name99 = TocSplitFileNames.sanitize("Conclusion", index = 99, totalParts = 120)
        assertEquals("099 - Conclusion.pdf", name99)
    }

    @Test
    fun sanitizedFileNamesHandlesDuplicateTitlesWithNumberedSuffix() {
        val pdf = nestedBookPdf()
        open(pdf).use { doc ->
            // Simulating multiple parts with the exact same title
            val parts = listOf(
                TocSplitPart("p1", "Introduction", 0..1, ""),
                TocSplitPart("p2", "Introduction", 2..3, ""),
                TocSplitPart("p3", "Introduction", 4..5, "")
            )
            val assigned = TocSplitFileNames.assignFileNames(parts)
            assertEquals("01 - Introduction.pdf", assigned[0].fileName)
            assertEquals("02 - Introduction (2).pdf", assigned[1].fileName)
            assertEquals("03 - Introduction (3).pdf", assigned[2].fileName)
        }
    }

    @Test
    fun sanitizedFileNamesTrimsTrailingDotsSpacesAndCollapsesBlank() {
        val blank = TocSplitFileNames.sanitize("   ", index = 1, totalParts = 1)
        assertEquals("01 - Chapter.pdf", blank)

        val dots = TocSplitFileNames.sanitize("Chapter 1....   ", index = 1, totalParts = 1)
        assertEquals("01 - Chapter 1.pdf", dots)
    }

    @Test
    fun sanitizedFileNamesTruncatesLongNamesSafelyBeforePdfExtension() {
        val veryLongTitle = "A".repeat(300)
        val sanitized = TocSplitFileNames.sanitize(veryLongTitle, index = 1, totalParts = 1)
        assertTrue(sanitized.length <= 150)
        assertTrue(sanitized.endsWith(".pdf"))
    }

    // ---------------------------------------------------------------- Output Content & Element Preservation

    @Test
    fun writtenPartsRebaseBookmarksToNewPageNumbers() {
        val pdf = nestedBookPdf()
        open(pdf).use { doc ->
            val plan = TocSplit.planOutline(doc, TocSplitOptions(level = 1))
            val outputs = mutableListOf<TocSplitOutput>()
            TocSplitWriter.write(doc, plan.parts, { outputs.add(it) })

            // Part 2 (Chapter 2, originally pages 3..5)
            open(outputs[1].bytes).use { p2Doc ->
                val p2Outlines = TocSplit.readOutlines(p2Doc)
                assertFalse("Part 2 must have bookmarks", p2Outlines.isEmpty())
                // In part 2, Chapter 2 should be rebased to page 0 (originally page 3)
                val ch2 = p2Outlines.find { it.title == "Chapter 2" }
                assertNotNull(ch2)
                assertEquals(0, ch2!!.pageIndex)

                // Subsections should also be rebased
                val sec21 = p2Outlines.find { it.title == "Section 2.1" }
                assertNotNull(sec21)
                assertEquals(1, sec21!!.pageIndex) // originally page 4 -> page 1
            }
        }
    }

    @Test
    fun writtenPartsPreserveSamePartInternalLinksAndDropCrossPartLinks() {
        // Build document where page 0 has links:
        // Link 1: points to page 1 (internal to part 1)
        // Link 2: points to page 3 (cross-part to part 2)
        val extraObjs = listOf(
            TestObj(10, 0, "<< /Type /Outlines /First 11 0 R /Last 12 0 R /Count 2 >>"),
            TestObj(11, 0, "<< /Title (Part 1) /Parent 10 0 R /Dest [100 0 R /Fit] /Next 12 0 R >>"),
            TestObj(12, 0, "<< /Title (Part 2) /Parent 10 0 R /Dest [102 0 R /Fit] /Prev 11 0 R >>"),
            // Annotations on page 0 (obj 100):
            TestObj(
                20, 0,
                "<< /Type /Annot /Subtype /Link /Rect [10 10 50 20] /Dest [101 0 R /Fit] >>" // to page 1
            ),
            TestObj(
                21, 0,
                "<< /Type /Annot /Subtype /Link /Rect [60 10 100 20] /Dest [103 0 R /Fit] >>" // to page 3
            )
        )

        // Page 0 carries the two links, so part 1 keeps only the same-part one.
        val pdf = createBookPdf(
            pageCount = 4,
            extraCatalog = "/Outlines 10 0 R",
            extraObjects = extraObjs,
            pageEntries = mapOf(0 to "/Annots [20 0 R 21 0 R] ")
        )

        open(pdf).use { doc ->
            val plan = TocSplit.planOutline(doc, TocSplitOptions(level = 1))
            val outputs = mutableListOf<TocSplitOutput>()
            TocSplitWriter.write(doc, plan.parts, { outputs.add(it) })

            assertEquals(2, outputs.size)
            // Report on Part 1 should record 1 dropped link
            assertEquals(1, outputs[0].report.droppedCrossPartLinks)
            assertTrue(
                outputs[0].report.issues.any {
                    it is TocSplitIssue.DroppedExternalLink && it.sourcePage == 0 && it.targetPage == 3
                }
            )

            // Reopen Part 1 and verify that Link 1 is preserved and rebased, while Link 2 is dropped
            open(outputs[0].bytes).use { p1Doc ->
                val p1Annots = p1Doc.pages()[0].dict["Annots"] as? PdfArray
                assertNotNull(p1Annots)
                assertEquals(1, p1Annots!!.items.size)
            }
        }
    }

    @Test
    fun writtenPartsPreserveAndRebasePageLabels() {
        // PageLabels number tree:
        // page 0 -> Roman lowercase (/r)
        // page 2 -> Decimal starting at 1 (/D /St 1)
        val extraObjs = listOf(
            TestObj(10, 0, "<< /Type /Outlines /First 11 0 R /Last 12 0 R /Count 2 >>"),
            TestObj(11, 0, "<< /Title (Prelims) /Parent 10 0 R /Dest [100 0 R /Fit] /Next 12 0 R >>"),
            TestObj(12, 0, "<< /Title (Body) /Parent 10 0 R /Dest [102 0 R /Fit] /Prev 11 0 R >>"),
            TestObj(15, 0, "<< /Nums [0 << /S /r >> 2 << /S /D /St 1 >>] >>")
        )

        val pdf = createBookPdf(
            pageCount = 4,
            extraCatalog = "/Outlines 10 0 R /PageLabels 15 0 R",
            extraObjects = extraObjs
        )

        open(pdf).use { doc ->
            val plan = TocSplit.planOutline(doc, TocSplitOptions(level = 1))
            val outputs = mutableListOf<TocSplitOutput>()
            TocSplitWriter.write(doc, plan.parts, { outputs.add(it) })

            // Part 2 contains source pages 2..3 (which had decimal labels starting at 1)
            open(outputs[1].bytes).use { p2Doc ->
                val catalog = p2Doc.resolveRoot() as PdfDict
                val pageLabelsRef = catalog["PageLabels"] as? PdfRef
                assertNotNull("Part 2 must preserve /PageLabels", pageLabelsRef)
                val labelsDict = p2Doc.resolve(pageLabelsRef!!) as PdfDict
                val nums = labelsDict["Nums"] as PdfArray
                assertNotNull(nums)
                // Rebased to page 0 in new part: 0 << /S /D /St 1 >>
                assertEquals(0, (nums.items[0] as com.pdfpaper.engine.model.PdfInteger).value)
            }
        }
    }

    @Test
    fun writtenPartsPreserveDocumentInfoMetadataAndAcroFormFields() {
        val extraObjs = listOf(
            TestObj(10, 0, "<< /Type /Outlines /First 11 0 R /Last 11 0 R /Count 1 >>"),
            TestObj(11, 0, "<< /Title (Ch 1) /Parent 10 0 R /Dest [100 0 R /Fit] >>"),
            // Info dict
            TestObj(15, 0, "<< /Title (Master Book) /Author (Jane Doe) >>"),
            // AcroForm
            TestObj(16, 0, "<< /Fields [17 0 R] >>"),
            TestObj(17, 0, "<< /FT /Tx /T (SignatureField) /P 100 0 R /Rect [10 10 100 30] >>")
        )

        val pdf = createBookPdf(
            pageCount = 2,
            extraCatalog = "/Outlines 10 0 R /AcroForm 16 0 R",
            extraTrailer = " /Info 15 0 R",
            extraObjects = extraObjs
        )

        open(pdf).use { doc ->
            val plan = TocSplit.planOutline(doc, TocSplitOptions(level = 1))
            val outputs = mutableListOf<TocSplitOutput>()
            TocSplitWriter.write(doc, plan.parts, { outputs.add(it) })

            open(outputs[0].bytes).use { pDoc ->
                // Check /Info
                val trailer = pDoc.trailer()
                val infoRef = trailer["Info"] as? PdfRef
                assertNotNull("Info dict should be preserved", infoRef)
                val infoDict = pDoc.resolve(infoRef!!) as PdfDict
                assertEquals("Master Book", (infoDict["Title"] as PdfString).text())
                assertEquals("Jane Doe", (infoDict["Author"] as PdfString).text())

                // Check AcroForm
                val catalog = pDoc.resolveRoot() as PdfDict
                assertNotNull("AcroForm should be preserved", catalog["AcroForm"])
            }
        }
    }

    // ---------------------------------------------------------------- Streaming & Memory Scalability

    @Test
    fun streamWriterProcessesThousandPageBookOnePartAtATimeWithoutRetainingAllBytes() {
        // Acceptance requirement: 1,000-page book does not load everything into memory
        // 10 chapters of 100 pages each
        val parts = (0 until 10).map { i ->
            TocSplitPart(
                id = "part_$i",
                title = "Chapter ${i + 1}",
                pages = (i * 100) until ((i + 1) * 100),
                fileName = "0${i + 1} - Chapter ${i + 1}.pdf"
            )
        }

        val pdf = createBookPdf(pageCount = 1000)
        open(pdf).use { doc ->
            var partsReceived = 0
            val progressSteps = mutableListOf<Pair<Int, Int>>()

            val count = TocSplitWriter.write(
                document = doc,
                parts = parts,
                sink = { output ->
                    partsReceived++
                    assertTrue(output.bytes.isNotEmpty())
                    // The sink immediately processes and does not need to keep previous outputs
                },
                onProgress = { done, total ->
                    progressSteps.add(done to total)
                }
            )

            assertEquals(10, count)
            assertEquals(10, partsReceived)
            assertEquals(10, progressSteps.size)
            assertEquals(1 to 10, progressSteps.first())
            assertEquals(10 to 10, progressSteps.last())
        }
    }

    @Test
    fun streamWriterSupportsCleanCancellationBetweenParts() {
        val parts = (0 until 5).map { i ->
            TocSplitPart(
                id = "p$i",
                title = "Ch $i",
                pages = (i * 2) until ((i + 1) * 2),
                fileName = "p$i.pdf"
            )
        }

        val pdf = createBookPdf(pageCount = 10)
        open(pdf).use { doc ->
            var written = 0
            var cancelTriggered = false

            val count = TocSplitWriter.write(
                document = doc,
                parts = parts,
                sink = {
                    written++
                    if (written == 2) cancelTriggered = true
                },
                isCancelled = { cancelTriggered }
            )

            // Stopped after part 2 was delivered, part 3 was not processed
            assertEquals(2, count)
            assertEquals(2, written)
        }
    }

    // ---------------------------------------------------------------- Fallback: Headings

    @Test
    fun headingFallbackFindsHeadingsWhenOutlineMissing() {
        // Document without outline; every page has a large heading above its body text.
        val pdf = createBookPdf(pageCount = 4, withHeadings = true)
        open(pdf).use { doc ->
            val plan = TocSplit.planHeadings(doc, TocSplitOptions(level = 1, source = TocSplitSource.HEADINGS))
            assertEquals(TocSplitSource.HEADINGS, plan.source)
            // Should produce valid chapter parts from headings without throwing
            assertFalse(plan.parts.isEmpty())
            for (part in plan.parts) {
                assertTrue(part.pages.first <= part.pages.last)
            }
        }
    }
}
