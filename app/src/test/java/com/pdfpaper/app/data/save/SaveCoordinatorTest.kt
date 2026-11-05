package com.pdfpaper.app.data.save

import com.pdfpaper.engine.annot.NoteSpec
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.write.PdfPageOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** T55: write-back verification, append fallback and original restoration. */
class SaveCoordinatorTest {

    @org.junit.Rule
    @JvmField
    val temporary = TemporaryFolder()

    private fun original(): ByteArray =
        PdfBuilder(PageSize.A4).apply { addPage() }.build()

    private fun edited(original: ByteArray): ByteArray =
        PdfDocument.open(ByteArraySource(original)).getOrThrow().use { document ->
            PdfPageOps.editIncremental(
                document,
                original,
                listOf(NoteSpec(0, PdfRect(20.0, 20.0, 40.0, 40.0), "note"))
            ).getOrThrow()
        }

    private fun coordinator() = SaveCoordinator(temporary.newFolder("tmp"))

    @Test
    fun incrementalAppendKeepsTheOriginalPrefix() {
        val original = original()
        val updated = edited(original)
        val target = MemoryTarget(original)
        var recorded: ByteArray? = null

        val report = coordinator().save(
            target,
            original,
            updated,
            SaveExpectation(pageCount = 1),
            SaveWriteMode.INCREMENTAL,
            versions = object : VersionSink {
                override fun record(bytes: ByteArray): Result<Unit> {
                    recorded = bytes
                    return Result.success(Unit)
                }
            }
        ).getOrThrow()

        assertEquals(SaveWriteMode.INCREMENTAL, report.usedMode)
        assertFalse(report.fallbackToRewrite)
        assertTrue(target.bytes.copyOf(original.size).contentEquals(original))
        assertTrue(target.bytes.contentEquals(updated))
        assertTrue(recorded!!.contentEquals(original))
    }

    @Test
    fun appendThatThrowsAfterAPartialWriteFallsBackToARewrite() {
        val original = original()
        val updated = edited(original)
        val target = MemoryTarget(original).apply { failAppendAfter = 40 }

        val report = coordinator().save(
            target,
            original,
            updated,
            SaveExpectation(pageCount = 1),
            SaveWriteMode.INCREMENTAL
        ).getOrThrow()

        assertTrue(report.fallbackToRewrite)
        assertTrue(target.bytes.contentEquals(updated))
        assertEquals(1, target.partialWrites)
    }

    @Test
    fun providerThatIgnoresAppendModeFallsBackToARewrite() {
        val original = original()
        val updated = edited(original)
        val target = MemoryTarget(original).apply { appendSupported = false }

        val report = coordinator().save(
            target,
            original,
            updated,
            SaveExpectation(pageCount = 1),
            SaveWriteMode.INCREMENTAL
        ).getOrThrow()

        assertTrue(report.fallbackToRewrite)
        assertTrue(target.bytes.contentEquals(updated))
    }

    @Test
    fun failedRewriteRestoresTheOriginal() {
        val original = original()
        val updated = edited(original)
        val target = MemoryTarget(original).apply { failReplaceAfter = 25 }

        val error = assertThrows(SaveWriteException::class.java) {
            coordinator().save(
                target,
                original,
                updated,
                SaveExpectation(pageCount = 1),
                SaveWriteMode.FULL_REWRITE
            ).getOrThrow()
        }

        assertTrue(error.originalRestored)
        assertTrue(target.bytes.contentEquals(original))
    }

    @Test
    fun restoreFailureIsReportedAndLeavesTheVersionForRecovery() {
        val original = original()
        val updated = edited(original)
        val target = MemoryTarget(original).apply {
            failReplaceAfter = 25
            allowRestore = false
        }

        val error = assertThrows(SaveWriteException::class.java) {
            coordinator().save(
                target,
                original,
                updated,
                SaveExpectation(pageCount = 1),
                SaveWriteMode.FULL_REWRITE
            ).getOrThrow()
        }

        assertFalse(error.originalRestored)
        assertTrue(error.message!!.contains("Version history"))
    }

    @Test
    fun conflictCheckRefusesToWriteWhenTheFileChangedElsewhere() {
        val original = original()
        val updated = edited(original)
        val target = MemoryTarget(original + byteArrayOf(10, 20))
        var writes = 0
        target.onWrite = { writes++ }

        assertThrows(FileChangedException::class.java) {
            coordinator().save(
                target,
                original,
                updated,
                SaveExpectation(pageCount = 1),
                SaveWriteMode.INCREMENTAL
            ).getOrThrow()
        }
        assertEquals(0, writes)
    }

    @Test
    fun overwriteAnywaySkipsTheConflictCheck() {
        val original = original()
        val updated = edited(original)
        val target = MemoryTarget(original + byteArrayOf(10, 20))

        val report = coordinator().save(
            target,
            original,
            updated,
            SaveExpectation(pageCount = 1),
            SaveWriteMode.FULL_REWRITE,
            overwriteConflict = true
        ).getOrThrow()

        assertEquals(SaveWriteMode.FULL_REWRITE, report.usedMode)
        assertTrue(target.bytes.contentEquals(updated))
    }

    @Test
    fun aNewerTimestampIsTreatedAsAnExternalChange() {
        val original = original()
        val updated = edited(original)
        val target = MemoryTarget(original).apply { lastModified = 5_000L }

        assertThrows(FileChangedException::class.java) {
            coordinator().save(
                target,
                original,
                updated,
                SaveExpectation(pageCount = 1),
                SaveWriteMode.INCREMENTAL,
                openedFingerprint = DocumentFingerprint(original.size.toLong(), 1_000L)
            ).getOrThrow()
        }
        assertTrue(target.bytes.contentEquals(original))
    }

    @Test
    fun anUnknownTimestampFallsBackToTheSizeCheck() {
        val original = original()
        val updated = edited(original)
        val target = MemoryTarget(original).apply { lastModified = null }

        val report = coordinator().save(
            target,
            original,
            updated,
            SaveExpectation(pageCount = 1),
            SaveWriteMode.INCREMENTAL,
            openedFingerprint = DocumentFingerprint(original.size.toLong(), 1_000L)
        ).getOrThrow()

        assertEquals(SaveWriteMode.INCREMENTAL, report.usedMode)
        assertTrue(target.bytes.contentEquals(updated))
    }

    @Test
    fun aBadTempVerificationLeavesTheSourceUntouched() {
        val original = original()
        val updated = edited(original)
        val target = MemoryTarget(original)
        var writes = 0
        target.onWrite = { writes++ }

        assertThrows(IllegalArgumentException::class.java) {
            coordinator().save(
                target,
                original,
                updated,
                SaveExpectation(pageCount = 2),
                SaveWriteMode.FULL_REWRITE
            ).getOrThrow()
        }
        assertEquals(0, writes)
        assertTrue(target.bytes.contentEquals(original))
    }

    @Test
    fun versionFailureDoesNotBlockTheSave() {
        val original = original()
        val updated = edited(original)
        val target = MemoryTarget(original)

        val report = coordinator().save(
            target,
            original,
            updated,
            SaveExpectation(pageCount = 1),
            SaveWriteMode.INCREMENTAL,
            versions = object : VersionSink {
                override fun record(bytes: ByteArray): Result<Unit> =
                    Result.failure(IOException("full"))
            }
        ).getOrThrow()

        assertFalse(report.versionRecorded)
        assertTrue(target.bytes.contentEquals(updated))
    }

    private class MemoryTarget(
        var bytes: ByteArray,
        var failAppendAfter: Int? = null,
        var failReplaceAfter: Int? = null,
        var appendSupported: Boolean = true,
        var allowRestore: Boolean = true,
        var lastModified: Long? = null
    ) : SaveTarget {
        var onWrite: () -> Unit = {}
        var partialWrites = 0

        override fun read(): Result<ByteArray> = Result.success(bytes)

        override fun fingerprint(): Result<DocumentFingerprint> =
            Result.success(DocumentFingerprint(bytes.size.toLong(), lastModified))

        override fun append(bytes: ByteArray): Result<Unit> {
            onWrite()
            if (!appendSupported) return Result.success(Unit)
            val limit = failAppendAfter
            if (limit != null) {
                this.bytes = this.bytes.copyOf(this.bytes.size + limit)
                partialWrites++
                failAppendAfter = null
                return Result.failure(IOException("stream killed after $limit bytes"))
            }
            this.bytes += bytes
            return Result.success(Unit)
        }

        override fun replace(bytes: ByteArray): Result<Unit> {
            onWrite()
            val limit = failReplaceAfter
            if (!allowRestore && limit == null) {
                return Result.failure(IOException("restore refused"))
            }
            if (limit != null) {
                this.bytes = bytes.copyOf(limit)
                partialWrites++
                failReplaceAfter = null
                if (!allowRestore) return Result.failure(IOException("stream killed after $limit bytes"))
                return Result.failure(IOException("stream killed after $limit bytes"))
            }
            this.bytes = bytes
            return Result.success(Unit)
        }
    }
}
