package com.pdfpaper.app.ui.compress

import com.pdfpaper.engine.compress.CompressCategory
import com.pdfpaper.engine.compress.CompressEstimate
import com.pdfpaper.engine.compress.CompressReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressUiLogicTest {

    private fun report(
        original: Int = 1_000_000,
        compressed: Int = 400_000,
        categories: List<CompressCategory> = emptyList(),
        imagesRecompressed: Int = 0,
        imagesDownsampled: Int = 0,
        streamsRecompressed: Int = 0,
        duplicatesShared: Int = 0,
        unusedObjectsRemoved: Int = 0,
        skipped: List<String> = emptyList(),
        usedOriginal: Boolean = false
    ) = CompressReport(
        originalBytes = original,
        compressedBytes = compressed,
        categories = categories,
        imagesRecompressed = imagesRecompressed,
        imagesDownsampled = imagesDownsampled,
        streamsRecompressed = streamsRecompressed,
        duplicatesShared = duplicatesShared,
        unusedObjectsRemoved = unusedObjectsRemoved,
        skipped = skipped,
        usedOriginal = usedOriginal
    )

    @Test
    fun sizesAreShownInPlainLanguage() {
        assertEquals("0 B", formatSize(0))
        assertEquals("820 B", formatSize(820))
        assertEquals("1.0 KB", formatSize(1024))
        assertEquals("4.0 KB", formatSize(4096))
        assertEquals("1.0 MB", formatSize(1024 * 1024))
        assertEquals("1.2 MB", formatSize(1_240_000))
        assertEquals("2.0 MB", formatSize(2 * 1024 * 1024))
        assertEquals("1.0 GB", formatSize(1024 * 1024 * 1024))
    }

    @Test
    fun negativeSizesAreShownAsZero() {
        assertEquals("0 B", formatSize(-5))
    }

    @Test
    fun sizesBeforeAndAfterAreJoinedByAnArrow() {
        assertEquals("1.2 MB → 390.6 KB", formatSizes(1_240_000, 400_000))
    }

    @Test
    fun theEstimateLineNamesTheSizeTheSavingAndTheSample() {
        val estimate = CompressEstimate(
            originalBytes = 1_000_000,
            estimatedBytes = 650_000,
            sampledPages = 3,
            totalPages = 10
        )
        assertEquals(
            "Estimated size: about 634.8 KB (about 35% smaller) — estimated from the first 3 pages",
            estimateLine(estimate)
        )
    }

    @Test
    fun anEstimateWithoutSavingSaysTheFileStaysAboutTheSame() {
        val estimate = CompressEstimate(
            originalBytes = 1_000_000,
            estimatedBytes = 1_000_000,
            sampledPages = 10,
            totalPages = 10
        )
        assertEquals(
            "Estimated size: about 976.6 KB (about the same size) — estimated from all 10 pages",
            estimateLine(estimate)
        )
    }

    @Test
    fun theSavedLineNamesTheShareAndTheBytes() {
        assertEquals("Saved 60% (585.9 KB)", savedLine(report()))
    }

    @Test
    fun everyCategoryGetsARowWithWhatItSaved() {
        val lines = categoryLines(
            report(
                categories = listOf(
                    CompressCategory("Images", 800_000, 200_000),
                    CompressCategory("Fonts", 100_000, 100_000)
                )
            )
        )
        assertEquals(
            listOf("Images: 585.9 KB saved", "Fonts: nothing saved"),
            lines
        )
    }

    @Test
    fun theCountersAreWrittenInPlainLanguageAndSkipTheZeros() {
        val lines = countLines(
            report(
                imagesRecompressed = 1,
                imagesDownsampled = 4,
                streamsRecompressed = 0,
                duplicatesShared = 2,
                unusedObjectsRemoved = 12
            )
        )
        assertEquals(
            listOf(
                "1 image re-encoded",
                "4 images scaled down",
                "2 duplicate streams shared",
                "12 unused objects removed"
            ),
            lines
        )
    }

    @Test
    fun theReportLinesComeInTheOrderOfTheResultCard() {
        val lines = reportLines(
            report(
                categories = listOf(CompressCategory("Images", 800_000, 200_000)),
                imagesRecompressed = 2,
                skipped = listOf("Scanned images are kept as they are.")
            )
        )
        assertEquals(
            listOf(
                "Images: 585.9 KB saved",
                "2 images re-encoded",
                "Scanned images are kept as they are."
            ),
            lines
        )
    }

    @Test
    fun aRunThatDidNotHelpSaysTheOriginalWasKept() {
        val lines = skippedLines(report(usedOriginal = true))
        assertEquals(listOf(KEEP_ORIGINAL_NOTE), lines)
    }

    @Test
    fun theKeptOriginalNoteIsNotRepeated() {
        val lines = skippedLines(
            report(usedOriginal = true, skipped = listOf(KEEP_ORIGINAL_NOTE))
        )
        assertEquals(listOf(KEEP_ORIGINAL_NOTE), lines)
    }

    @Test
    fun theSuggestedNameIsTheBaseNameWithTheSuffix() {
        assertEquals("report-compressed.pdf", suggestedFileName("report.pdf"))
        assertEquals("My Report-compressed.pdf", suggestedFileName("My Report.PDF"))
        assertEquals("report-compressed.pdf", suggestedFileName("report"))
        assertEquals("document-compressed.pdf", suggestedFileName(".pdf"))
        assertEquals("document-compressed.pdf", suggestedFileName(""))
    }
}
