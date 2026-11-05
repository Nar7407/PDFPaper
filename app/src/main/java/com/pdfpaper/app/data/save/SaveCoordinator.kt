package com.pdfpaper.app.data.save

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.annot.annotations
import com.pdfpaper.engine.page.pages
import java.io.File
import java.security.MessageDigest

/** How a verified edit is written back (T55). */
enum class SaveWriteMode {
    /** Append the new revision to the original bytes (§7.5.6). */
    INCREMENTAL,

    /** Replace the whole file with a verified rewrite. */
    FULL_REWRITE
}

/** The size and last-modified time captured when a file was opened (T55). */
data class DocumentFingerprint(
    val sizeBytes: Long,
    val lastModified: Long?
)

/** What the written file must contain after the save. */
data class SaveExpectation(
    val pageCount: Int,
    val minimumAnnotations: Int = 0,
    val expectedBytes: Int? = null
)

/** The result of one write-back, kept for the UI message and tests. */
data class SaveReport(
    val writtenBytes: Long,
    val usedMode: SaveWriteMode,
    val fallbackToRewrite: Boolean = false,
    val versionRecorded: Boolean = false
)

/** The file changed on disk since it was opened. */
class FileChangedException :
    IllegalStateException("This file changed elsewhere. Reload it, save a copy, or overwrite anyway.")

/** A write failed; [originalRestored] says whether the old bytes are back. */
class SaveWriteException(
    message: String,
    val originalRestored: Boolean,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/**
 * The smallest seam over a writable document (T55). Android backs this with a
 * `content://` URI; tests use an in-memory implementation that can fail after
 * a chosen number of bytes.
 */
interface SaveTarget {
    fun read(): Result<ByteArray>
    fun fingerprint(): Result<DocumentFingerprint>
    fun append(bytes: ByteArray): Result<Unit>
    fun replace(bytes: ByteArray): Result<Unit>
}

/** A place that keeps pre-save versions (the app uses app-private storage). */
interface VersionSink {
    fun record(bytes: ByteArray): Result<Unit>
}

/**
 * Writes an edited PDF back to its source without ever leaving a half-written
 * file (T55).
 *
 * The order is deliberate:
 *  1. compare the opened fingerprint with the file now;
 *  2. write the new bytes to an app-private temporary file;
 *  3. reopen that file with the engine and verify page count and content;
 *  4. keep the old bytes as a version;
 *  5. try append mode for an incremental update, then verify by reading back;
 *  6. fall back to a full rewrite (or restore the original on failure).
 *
 * Every step returns a [Result]; no failure is allowed to escape as an
 * exception and no step writes to the source before step 3 has passed.
 */
class SaveCoordinator(
    private val tempDirectory: File,
    private val engineVerify: (ByteArray, SaveExpectation) -> Result<Unit> = ::verifyWithEngine
) {

    fun save(
        target: SaveTarget,
        originalBytes: ByteArray,
        editedBytes: ByteArray,
        expectation: SaveExpectation,
        mode: SaveWriteMode,
        versions: VersionSink? = null,
        overwriteConflict: Boolean = false,
        openedFingerprint: DocumentFingerprint? = null
    ): Result<SaveReport> = runCatching {
        val current = target.fingerprint().getOrThrow()
        if (!overwriteConflict) {
            if (changedSinceOpen(current, originalBytes, openedFingerprint)) {
                throw FileChangedException()
            }
            // Some providers keep a stale size and timestamp after another app
            // rewrote the file. An incremental edit must still extend the bytes
            // it was derived from, so compare them when the caller knows which
            // revision was opened.
            if (openedFingerprint != null && mode == SaveWriteMode.INCREMENTAL) {
                val onDisk = target.read().getOrThrow()
                if (!onDisk.contentEquals(originalBytes)) throw FileChangedException()
            }
        }
        require(editedBytes.isNotEmpty()) { "The edited document is empty" }
        require(originalBytes.isNotEmpty()) { "The original document is empty" }

        verifyInTempFile(editedBytes, expectation)

        val recorded = versions?.record(originalBytes)?.isSuccess == true
        val report = when (mode) {
            SaveWriteMode.INCREMENTAL -> writeIncremental(
                target,
                originalBytes,
                editedBytes,
                recorded
            )
            SaveWriteMode.FULL_REWRITE -> writeFull(
                target,
                originalBytes,
                editedBytes,
                recorded,
                fallback = false
            )
        }
        report
    }

    private fun writeIncremental(
        target: SaveTarget,
        originalBytes: ByteArray,
        editedBytes: ByteArray,
        recorded: Boolean
    ): SaveReport {
        // "wa" appends to the existing bytes, so only the new revision is sent.
        val prefixKept = editedBytes.size >= originalBytes.size &&
            editedBytes.copyOf(originalBytes.size).contentEquals(originalBytes)
        if (!prefixKept) {
            return writeFull(target, originalBytes, editedBytes, recorded, fallback = true)
        }
        val appended = editedBytes.copyOfRange(originalBytes.size, editedBytes.size)
        val attempted = runCatching { target.append(appended).getOrThrow() }
        if (attempted.isSuccess) {
            val readBack = target.read()
            if (readBack.isSuccess && readBack.getOrThrow().contentEquals(editedBytes)) {
                return SaveReport(editedBytes.size.toLong(), SaveWriteMode.INCREMENTAL, false, recorded)
            }
        }
        // Append mode is unreliable across Android providers. The failed
        // attempt may have left a partial tail (or a new file), so put the
        // original back before trying a complete replacement.
        restoreOriginal(target, originalBytes)
        return writeFull(target, originalBytes, editedBytes, recorded, fallback = true)
    }

    private fun writeFull(
        target: SaveTarget,
        originalBytes: ByteArray,
        editedBytes: ByteArray,
        recorded: Boolean,
        fallback: Boolean
    ): SaveReport {
        val written = runCatching { target.replace(editedBytes).getOrThrow() }
        if (written.isSuccess) {
            val readBack = target.read()
            if (readBack.isSuccess && readBack.getOrThrow().contentEquals(editedBytes)) {
                return SaveReport(editedBytes.size.toLong(), SaveWriteMode.FULL_REWRITE, fallback, recorded)
            }
        }
        val restored = restoreOriginal(target, originalBytes)
        throw SaveWriteException(
            if (restored) {
                "The file could not be written. The original was restored."
            } else {
                "The file could not be written, and the original could not be restored. " +
                    "A previous version is kept in Version history."
            },
            restored,
            written.exceptionOrNull()
        )
    }

    /**
     * True when the file no longer matches what the viewer opened: a different
     * size, or (when both sides report one) a different last-modified time.
     * A provider that omits the timestamp leaves only the size to compare.
     */
    private fun changedSinceOpen(
        current: DocumentFingerprint,
        originalBytes: ByteArray,
        opened: DocumentFingerprint?
    ): Boolean {
        val expectedSize = opened?.sizeBytes ?: originalBytes.size.toLong()
        if (current.sizeBytes != expectedSize) return true
        val openedModified = opened?.lastModified
        val currentModified = current.lastModified
        return openedModified != null && currentModified != null && openedModified != currentModified
    }

    private fun restoreOriginal(target: SaveTarget, originalBytes: ByteArray): Boolean {
        val replaced = target.replace(originalBytes)
        if (replaced.isFailure) return false
        return target.read().getOrNull()?.contentEquals(originalBytes) == true
    }

    private fun verifyInTempFile(bytes: ByteArray, expectation: SaveExpectation) {
        tempDirectory.mkdirs()
        val file = File(tempDirectory, "save-${System.nanoTime()}.pdf")
        try {
            file.writeBytes(bytes)
            val readBack = file.readBytes()
            require(readBack.contentEquals(bytes)) { "The temporary save was incomplete" }
            engineVerify(readBack, expectation).getOrThrow()
        } finally {
            runCatching { file.delete() }
        }
    }

    companion object {
        /** Engine-side verification used by the app and by unit tests. */
        fun verifyWithEngine(bytes: ByteArray, expectation: SaveExpectation): Result<Unit> = runCatching {
            PdfDocument.open(ByteArraySource(bytes)).getOrThrow().use { document ->
                val pages = document.pages()
                require(pages.size == expectation.pageCount) {
                    "The saved file has ${pages.size} pages, expected ${expectation.pageCount}"
                }
                val annotations = pages.sumOf { it.annotations().size }
                require(annotations >= expectation.minimumAnnotations) {
                    "The saved file has $annotations annotations, expected at least " +
                        expectation.minimumAnnotations
                }
            }
            if (expectation.expectedBytes != null) {
                require(bytes.size == expectation.expectedBytes) {
                    "The saved file is ${bytes.size} bytes, expected ${expectation.expectedBytes}"
                }
            }
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
    }
}
