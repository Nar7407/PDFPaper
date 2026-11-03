package com.pdfpaper.app.ui.pages

import com.pdfpaper.engine.split.TocSplitIssue
import com.pdfpaper.engine.split.TocSplitPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T66: pure preview-edit model unit tests for TOC chapter splitting.
 *
 * Covers:
 * - Checkbox toggling and filtering of active parts
 * - Title renaming with automatic file name sanitization and unique numeric prefix
 * - Range merging: constraint checks (must be adjacent) and range unification
 * - Range splitting: splitting a chapter at a given internal page index
 * - Boundary page policy: recomputing page ranges for shared boundary pages
 * - Size estimation: calculation based on page ratio and human-readable formatting
 * - Output conflict detection against existing file names
 * - Diagnostic issue presentation (empty outline fallbacks, out-of-order warnings)
 */
class TocSplitModelTest {

    private fun sampleParts(): List<EditableTocSplitPart> = listOf(
        EditableTocSplitPart(
            id = "part_1",
            title = "Introduction",
            pages = 0..4, // 5 pages
            fileName = "01 - Introduction.pdf",
            checked = true,
            estimateBytes = 500_000L
        ),
        EditableTocSplitPart(
            id = "part_2",
            title = "Background",
            pages = 5..9, // 5 pages
            fileName = "02 - Background.pdf",
            checked = true,
            estimateBytes = 500_000L
        ),
        EditableTocSplitPart(
            id = "part_3",
            title = "Methodology",
            pages = 10..19, // 10 pages
            fileName = "03 - Methodology.pdf",
            checked = true,
            estimateBytes = 1_000_000L
        )
    )

    // ---------------------------------------------------------------- Checkbox Filtering

    @Test
    fun toggleCheckUpdatesCheckedState() {
        val initial = sampleParts()
        val updated = TocSplitModel.toggleCheck(initial, "part_2")
        assertFalse(updated.first { it.id == "part_2" }.checked)
        assertTrue(updated.first { it.id == "part_1" }.checked)

        val restored = TocSplitModel.toggleCheck(updated, "part_2")
        assertTrue(restored.first { it.id == "part_2" }.checked)
    }

    @Test
    fun checkedPartsOnlyFiltersInactiveChapters() {
        val parts = sampleParts()
        val disabledSecond = TocSplitModel.toggleCheck(parts, "part_2")
        val active = TocSplitModel.activeParts(disabledSecond)
        assertEquals(2, active.size)
        assertEquals(listOf("part_1", "part_3"), active.map { it.id })
    }

    // ---------------------------------------------------------------- Renaming & File Names

    @Test
    fun renamingUpdatesTitleAndRecomputesSanitizedFileName() {
        val initial = sampleParts()
        val updated = TocSplitModel.rename(initial, "part_1", "New / Special: Title*")
        val renamed = updated.first { it.id == "part_1" }
        assertEquals("New / Special: Title*", renamed.title)
        // Sanitized fileName retains numeric prefix, removes illegal chars
        assertTrue(renamed.fileName.startsWith("01 - "))
        assertFalse(renamed.fileName.contains("/"))
        assertFalse(renamed.fileName.contains(":"))
        assertFalse(renamed.fileName.contains("*"))
        assertTrue(renamed.fileName.endsWith(".pdf"))
    }

    @Test
    fun duplicateRenamesAssignNumberedSuffixes() {
        val initial = sampleParts()
        // Rename part 2 to have same title as part 1
        val updated = TocSplitModel.rename(initial, "part_2", "Introduction")
        val p1 = updated.first { it.id == "part_1" }
        val p2 = updated.first { it.id == "part_2" }
        assertEquals("01 - Introduction.pdf", p1.fileName)
        assertEquals("02 - Introduction (2).pdf", p2.fileName)
    }

    // ---------------------------------------------------------------- Merge Chapters

    @Test
    fun mergeAdjacentPartsCombinesRangesAndRecomputesNames() {
        val initial = sampleParts()
        val result = TocSplitModel.merge(initial, "part_1", "part_2")
        assertTrue(result.isSuccess)
        val mergedList = result.getOrThrow()
        assertEquals(2, mergedList.size)

        val merged = mergedList[0]
        assertEquals("Introduction & Background", merged.title)
        assertEquals(0..9, merged.pages) // Combined range of 0..4 and 5..9
        assertEquals("01 - Introduction & Background.pdf", merged.fileName)

        // Following part renumbered to prefix 02
        val nextPart = mergedList[1]
        assertEquals("part_3", nextPart.id)
        assertEquals("02 - Methodology.pdf", nextPart.fileName)
    }

    @Test
    fun mergeNonAdjacentPartsFailsValidation() {
        val initial = sampleParts()
        // part_1 (0..4) and part_3 (10..19) are not adjacent (gap 5..9)
        val result = TocSplitModel.merge(initial, "part_1", "part_3")
        assertTrue(result.isFailure)
    }

    // ---------------------------------------------------------------- Split Chapter

    @Test
    fun splitPartDividesRangeAndCreatesNewPart() {
        val initial = sampleParts()
        // Split part_3 (pages 10..19) at page 15 (0-indexed: becomes 10..14 and 15..19)
        val result = TocSplitModel.split(
            parts = initial,
            targetId = "part_3",
            splitAtPage = 15,
            firstTitle = "Methodology - Part A",
            secondTitle = "Methodology - Part B"
        )
        assertTrue(result.isSuccess)
        val updated = result.getOrThrow()
        assertEquals(4, updated.size)

        val firstPart = updated[2]
        assertEquals("Methodology - Part A", firstPart.title)
        assertEquals(10..14, firstPart.pages)

        val secondPart = updated[3]
        assertEquals("Methodology - Part B", secondPart.title)
        assertEquals(15..19, secondPart.pages)
    }

    @Test
    fun splitAtInvalidPageBoundaryFailsValidation() {
        val initial = sampleParts()
        // part_1 is 0..4; splitting at 0 or at 5 or 10 is invalid
        val resultStart = TocSplitModel.split(initial, "part_1", splitAtPage = 0, firstTitle = "A", secondTitle = "B")
        assertTrue(resultStart.isFailure)

        val resultBeyond = TocSplitModel.split(initial, "part_1", splitAtPage = 10, firstTitle = "A", secondTitle = "B")
        assertTrue(resultBeyond.isFailure)
    }

    // ---------------------------------------------------------------- Duplicate Boundary Page

    @Test
    fun duplicateBoundaryPolicyExpandsEndPagesToOverlapNextStart() {
        val initial = sampleParts()
        // Part 1: 0..4, Part 2: 5..9, Part 3: 10..19
        // With duplicateBoundary = true: Part 1 becomes 0..5, Part 2 becomes 5..10, Part 3 remains 10..19
        val withDup = TocSplitModel.applyDuplicateBoundary(initial, duplicate = true)
        assertEquals(0..5, withDup[0].pages)
        assertEquals(5..10, withDup[1].pages)
        assertEquals(10..19, withDup[2].pages)

        // Toggling back to false restores standard ranges
        val withoutDup = TocSplitModel.applyDuplicateBoundary(withDup, duplicate = false)
        assertEquals(0..4, withoutDup[0].pages)
        assertEquals(5..9, withoutDup[1].pages)
        assertEquals(10..19, withoutDup[2].pages)
    }

    // ---------------------------------------------------------------- Size Estimates

    @Test
    fun sizeEstimatesCalculatedFromTotalBytesAndPageCount() {
        val parts = listOf(
            EditableTocSplitPart("p1", "Ch 1", 0..1, "01 - Ch 1.pdf"), // 2 pages
            EditableTocSplitPart("p2", "Ch 2", 2..9, "02 - Ch 2.pdf")  // 8 pages
        )
        val totalBytes = 10_000_000L // 10 MB total for 10 pages
        val totalPages = 10

        val estimated = TocSplitModel.recomputeEstimates(parts, totalBytes, totalPages)
        assertEquals(2_000_000L, estimated[0].estimateBytes)
        assertEquals(8_000_000L, estimated[1].estimateBytes)
    }

    @Test
    fun formatEstimateDisplaysReadableUnits() {
        assertEquals("500 B", TocSplitModel.formatEstimate(500L))
        assertEquals("450 KB", TocSplitModel.formatEstimate(450 * 1024L))
        assertEquals("2.5 MB", TocSplitModel.formatEstimate(2_621_440L))
    }

    // ---------------------------------------------------------------- Conflict Detection

    @Test
    fun findConflictsDetectsPlannedFilesAlreadyPresentInDestination() {
        val parts = sampleParts() // filenames: 01 - Introduction.pdf, 02 - Background.pdf, 03 - Methodology.pdf
        val existingFiles = setOf("01 - Introduction.pdf", "notes.txt", "03 - Methodology.pdf")

        val conflicts = TocSplitModel.findConflicts(parts, existingFiles)
        assertEquals(2, conflicts.size)
        assertTrue(conflicts.contains("01 - Introduction.pdf"))
        assertTrue(conflicts.contains("03 - Methodology.pdf"))
        assertFalse(conflicts.contains("02 - Background.pdf"))
    }

    @Test
    fun uncheckedPartsAreExcludedFromConflictDetection() {
        val parts = sampleParts()
        // Uncheck part 1
        val withUnchecked = TocSplitModel.toggleCheck(parts, "part_1")
        val existingFiles = setOf("01 - Introduction.pdf")

        val conflicts = TocSplitModel.findConflicts(withUnchecked, existingFiles)
        assertTrue(conflicts.isEmpty())
    }

    // ---------------------------------------------------------------- Diagnostics & Fallback UI Copy

    @Test
    fun diagnosticWordingForNoOutlineOffersFixedNAndHeadings() {
        val issue = TocSplitIssue.NoOutline
        val description = TocSplitModel.formatIssueDescription(issue)
        assertTrue(description.contains("no outline", ignoreCase = true))
        assertTrue(description.contains("split every", ignoreCase = true))
        assertTrue(description.contains("heading", ignoreCase = true))
    }

    @Test
    fun diagnosticWordingForOutOfOrderExplainsSorting() {
        val issue = TocSplitIssue.OutOfOrder("Late Chapter", originalPage = 5, sortedPage = 1)
        val description = TocSplitModel.formatIssueDescription(issue)
        assertTrue(description.contains("out-of-order", ignoreCase = true) || description.contains("sorted", ignoreCase = true))
    }
}
