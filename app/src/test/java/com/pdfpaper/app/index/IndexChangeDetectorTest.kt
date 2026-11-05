package com.pdfpaper.app.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexChangeDetectorTest {

    private fun remote(
        uri: String = "content://docs/1",
        path: String = "Reports/one.pdf",
        name: String = "one.pdf",
        size: Long = 100,
        modified: Long = 1_000
    ) = RemotePdf(uri, path, name, size, modified)

    private fun known(
        uri: String = "content://docs/1",
        path: String = "Reports/one.pdf",
        name: String = "one.pdf",
        size: Long = 100,
        modified: Long = 1_000
    ) = KnownPdf(uri, path, name, size, modified)

    @Test
    fun newFileIsNewAndKnownFileIsUnchanged() {
        val diff = IndexChangeDetector.detect(
            known = listOf(known(uri = "content://docs/known")),
            remote = listOf(
                remote(),
                remote(uri = "content://docs/known")
            )
        )

        assertEquals(1, diff.newCount)
        assertEquals(0, diff.changedCount)
        assertEquals(0, diff.removedCount)
        assertEquals(1, diff.changes.size)
        assertEquals(1, diff.unchanged.size)
        assertTrue(diff.changes.single() is IndexChange.New)
    }

    @Test
    fun sizeOrTimestampChangeReindexes() {
        val sizeDiff = IndexChangeDetector.detect(
            known = listOf(known(size = 100)),
            remote = listOf(remote(size = 101))
        )
        assertTrue(sizeDiff.changes.single() is IndexChange.Changed)

        val timeDiff = IndexChangeDetector.detect(
            known = listOf(known(modified = 1_000)),
            remote = listOf(remote(modified = 2_000))
        )
        assertTrue(timeDiff.changes.single() is IndexChange.Changed)
    }

    @Test
    fun renameWithSameBytesUpdatesTheIndex() {
        val diff = IndexChangeDetector.detect(
            known = listOf(known(name = "old.pdf", path = "Reports/old.pdf")),
            remote = listOf(remote(name = "new.pdf", path = "Reports/new.pdf"))
        )

        assertTrue(diff.changes.single() is IndexChange.Changed)
    }

    @Test
    fun missingFileIsRemoved() {
        val diff = IndexChangeDetector.detect(
            known = listOf(known(uri = "content://docs/gone")),
            remote = emptyList()
        )

        assertEquals(1, diff.removedCount)
        assertTrue(diff.changes.single() is IndexChange.Removed)
    }

    @Test
    fun equalFilesAreUnchanged() {
        val diff = IndexChangeDetector.detect(
            known = listOf(known()),
            remote = listOf(remote())
        )

        assertEquals(0, diff.changes.size)
        assertEquals(1, diff.unchanged.size)
    }

    @Test
    fun zeroTimestampFromAProviderDoesNotReindexEveryScan() {
        val diff = IndexChangeDetector.detect(
            known = listOf(known(modified = 0)),
            remote = listOf(remote(modified = 0))
        )

        assertEquals(0, diff.changes.size)
        assertEquals(1, diff.unchanged.size)
    }

    @Test
    fun anIncompleteScanNeverDeletesMissingFiles() {
        val diff = IndexChangeDetector.detect(
            known = listOf(known(uri = "content://docs/gone")),
            remote = emptyList()
        )

        assertTrue(IndexChangeDetector.effectiveChanges(diff, completeScan = true).single() is IndexChange.Removed)
        assertTrue(IndexChangeDetector.effectiveChanges(diff, completeScan = false).isEmpty())

        val withNewFile = IndexChangeDetector.detect(
            known = listOf(known(uri = "content://docs/gone")),
            remote = listOf(remote(uri = "content://docs/new"))
        )
        val incomplete = IndexChangeDetector.effectiveChanges(withNewFile, completeScan = false)
        assertEquals(1, incomplete.size)
        assertTrue(incomplete.single() is IndexChange.New)
    }

    @Test
    fun duplicateRemoteUriIsOnlyConsideredOnce() {
        val diff = IndexChangeDetector.detect(
            known = emptyList(),
            remote = listOf(remote(), remote(size = 999))
        )

        assertEquals(1, diff.changes.size)
    }
}
