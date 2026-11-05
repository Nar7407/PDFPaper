package com.pdfpaper.app.annot

import android.content.Context
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.DocumentAccess
import com.pdfpaper.app.data.save.ContentSaveTarget
import com.pdfpaper.app.data.save.FileSaveTarget
import com.pdfpaper.app.data.save.SaveCoordinator
import com.pdfpaper.app.data.save.SaveExpectation
import com.pdfpaper.app.data.save.SaveTarget
import com.pdfpaper.app.data.save.SaveWriteMode
import com.pdfpaper.app.data.save.VersionStore
import com.pdfpaper.engine.annot.AnnotationStyle
import com.pdfpaper.engine.annot.annotations
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.outline.PdfOutlines
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.write.PdfPageOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * T56: edits a hub item for real. A PDF annotation is patched through
 * [AnnotationStyle] (or removed through the T55 incremental page write) and the
 * file is written back with [SaveCoordinator], so version history, conflict
 * detection and the "original is never left half-written" rule apply exactly
 * as they do in the viewer. A bookmark only lives in the app database; when the
 * user asked for bookmarks in the PDF outline, the outline is updated with the
 * same write path.
 */
class AnnotationEditor(
    context: Context,
    repository: AnnotationRepository = AnnotationRepository.get(context)
) {

    private val context = context.applicationContext
    private val repository = repository
    private val coordinator = SaveCoordinator(File(this.context.cacheDir, "annotation-save"))

    /**
     * Changes an item's note, colour, label or tag. Returns a short message for
     * the hub; a read-only file fails before anything is written.
     */
    suspend fun update(
        item: AnnotationItem,
        noteText: String? = null,
        color: String? = null,
        label: String? = null,
        tag: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (item.kind.isBookmark) {
                val changed = repository.updateFields(
                    id = item.id,
                    color = color,
                    label = label,
                    tag = tag
                )
                require(changed) { "That bookmark no longer exists" }
                val outline = syncOutlineIfEnabled(item.documentId)
                if (outline.isSuccess && outline.getOrDefault(false)) {
                    "Bookmark updated and the PDF outline was written"
                } else if (outline.isFailure) {
                    "Bookmark updated; the PDF outline could not be written: " +
                        (outline.exceptionOrNull()?.message ?: "unknown error")
                } else {
                    "Bookmark updated"
                }
            } else {
                val objectNumber = item.objectNumber
                    ?: error("This annotation has no object number and cannot be edited")
                val bytes = readOriginal(item.documentUri)
                val fingerprint = DocumentAccess.fingerprint(context, item.documentUri)
                    .getOrThrow()
                val (edited, pageCount) = openDocument(bytes) { document ->
                    val page = document.pages().getOrNull(item.pageIndex)
                        ?: error("Page ${item.pageIndex + 1} is not in this document")
                    val existing = page.annotations()
                    require(existing.any { it.ref?.objNum == objectNumber }) {
                        "That annotation is no longer in the file; reopen the document"
                    }
                    val colorValue = color?.let { hex ->
                        AnnotationColors.fromHex(hex) ?: error("Unknown colour")
                    }
                    val updated = AnnotationStyle.applyIncremental(
                        document,
                        bytes,
                        listOf(
                            AnnotationStyle.Update(
                                pageIndex = item.pageIndex,
                                objectNumber = objectNumber,
                                contents = noteText,
                                color = colorValue
                            )
                        )
                    ).getOrThrow()
                    // Verify the new text before the file is touched.
                    PdfDocument.open(ByteArraySource(updated)).getOrThrow().use { check ->
                        val checkPage = check.pages()[item.pageIndex]
                        val changed = checkPage.annotations().firstOrNull {
                            it.ref?.objNum == objectNumber
                        } ?: error("The edited annotation disappeared from the new revision")
                        if (noteText != null) {
                            require(changed.contents == noteText) {
                                "The new revision does not contain the edited note"
                            }
                        }
                    }
                    updated to document.pages().size
                }
                writeBack(
                    item = item,
                    originalBytes = bytes,
                    editedBytes = edited,
                    pageCount = pageCount,
                    expectation = SaveExpectation(pageCount = pageCount),
                    mode = SaveWriteMode.INCREMENTAL,
                    fingerprint = fingerprint
                )
                repository.updateFields(
                    id = item.id,
                    noteText = noteText ?: item.noteText,
                    color = color ?: item.color,
                    tag = tag ?: item.tag
                )
                "Annotation saved"
            }
        }
    }

    /** Deletes an item from the PDF (or the app bookmark) and the index. */
    suspend fun delete(item: AnnotationItem): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (item.kind.isBookmark) {
                repository.delete(item.id)
                val outline = syncOutlineIfEnabled(item.documentId)
                if (outline.isFailure) {
                    "Bookmark deleted; the PDF outline could not be updated: " +
                        (outline.exceptionOrNull()?.message ?: "unknown error")
                } else {
                    "Bookmark deleted"
                }
            } else {
                val objectNumber = item.objectNumber
                    ?: error("This annotation has no object number and cannot be deleted")
                val bytes = readOriginal(item.documentUri)
                val fingerprint = DocumentAccess.fingerprint(context, item.documentUri)
                    .getOrThrow()
                val incremental = startXrefOf(bytes) != null
                val (edited, pageCount) = openDocument(bytes) { document ->
                    val page = document.pages().getOrNull(item.pageIndex)
                        ?: error("Page ${item.pageIndex + 1} is not in this document")
                    require(page.annotations().any { it.ref?.objNum == objectNumber }) {
                        "That annotation is no longer in the file; reopen the document"
                    }
                    val updated = if (incremental) {
                        PdfPageOps.editIncremental(
                            document,
                            bytes,
                            droppedAnnots = setOf(objectNumber)
                        ).getOrThrow()
                    } else {
                        PdfPageOps.edit(
                            document,
                            droppedAnnots = setOf(objectNumber)
                        ).getOrThrow()
                    }
                    PdfDocument.open(ByteArraySource(updated)).getOrThrow().use { check ->
                        val stillThere = check.pages().any { changed ->
                            changed.annotations().any { it.ref?.objNum == objectNumber }
                        }
                        require(!stillThere) {
                            "The annotation is still in the new revision; nothing was written"
                        }
                    }
                    updated to document.pages().size
                }
                writeBack(
                    item = item,
                    originalBytes = bytes,
                    editedBytes = edited,
                    pageCount = pageCount,
                    expectation = SaveExpectation(pageCount = pageCount),
                    mode = if (incremental) SaveWriteMode.INCREMENTAL else SaveWriteMode.FULL_REWRITE,
                    fingerprint = fingerprint
                )
                repository.delete(item.id)
                "Annotation deleted"
            }
        }
    }

    /**
     * Rewrites the PDF outline from the app bookmarks. Runs only when the
     * setting is on. Returns true when the file was written.
     */
    suspend fun syncBookmarks(documentId: Long): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching { syncOutline(documentId) }
    }

    private suspend fun syncOutlineIfEnabled(documentId: Long): Result<Boolean> {
        if (!AppSettings.bookmarkOutlineEnabled(context)) return Result.success(false)
        return runCatching { syncOutline(documentId) }
    }

    private suspend fun syncOutline(documentId: Long): Boolean {
        val document = repository.document(documentId)
            ?: return false
        val location = document.documentUri
        if (!isWritable(location)) {
            throw IllegalStateException(
                "This file is read-only. Open it in the viewer and save a copy to change its outline."
            )
        }
        val bookmarks = repository.bookmarks(documentId)
            .sortedWith(compareBy({ it.pageIndex }, { it.createdAt }))
        val entries = bookmarks.map {
            PdfOutlines.Entry(
                title = it.label?.takeIf { label -> label.isNotBlank() }
                    ?: "Page ${it.pageIndex + 1}",
                pageIndex = it.pageIndex
            )
        }
        val bytes = readOriginal(location)
        if (startXrefOf(bytes) == null) {
            throw IllegalStateException("This file has no cross-reference table to append to")
        }
        val fingerprint = DocumentAccess.fingerprint(context, location).getOrThrow()
        val (edited, pageCount) = openDocument(bytes) { open ->
            require(!open.isEncrypted) { "An encrypted document cannot be saved" }
            val updated = PdfOutlines.applyIncremental(open, bytes, entries)
                .getOrElse { error ->
                    if (entries.isEmpty() &&
                        error.message?.contains("no bookmarks", ignoreCase = true) == true
                    ) {
                        return@openDocument null
                    }
                    throw error
                }
            updated to open.pages().size
        } ?: return false
        writeBack(
            location = location,
            displayName = document.displayName,
            documentId = documentId,
            originalBytes = bytes,
            editedBytes = edited,
            pageCount = pageCount,
            expectation = SaveExpectation(pageCount = pageCount),
            mode = SaveWriteMode.INCREMENTAL,
            fingerprint = fingerprint
        )
        return true
    }

    private suspend fun writeBack(
        item: AnnotationItem,
        originalBytes: ByteArray,
        editedBytes: ByteArray,
        pageCount: Int,
        expectation: SaveExpectation,
        mode: SaveWriteMode,
        fingerprint: com.pdfpaper.app.data.save.DocumentFingerprint
    ) {
        writeBack(
            location = item.documentUri,
            displayName = item.displayName,
            documentId = item.documentId,
            originalBytes = originalBytes,
            editedBytes = editedBytes,
            pageCount = pageCount,
            expectation = expectation,
            mode = mode,
            fingerprint = fingerprint
        )
    }

    private suspend fun writeBack(
        location: String,
        displayName: String,
        documentId: Long,
        originalBytes: ByteArray,
        editedBytes: ByteArray,
        pageCount: Int,
        expectation: SaveExpectation,
        mode: SaveWriteMode,
        fingerprint: com.pdfpaper.app.data.save.DocumentFingerprint
    ) {
        require(isWritable(location)) {
            "This file is read-only. Open it in the viewer and save a copy to edit annotations."
        }
        val target = targetFor(location)
        coordinator.save(
            target = target,
            originalBytes = originalBytes,
            editedBytes = editedBytes,
            expectation = expectation,
            mode = mode,
            versions = VersionStore(context, location),
            openedFingerprint = fingerprint
        ).getOrThrow()
        val after = DocumentAccess.fingerprint(context, location).getOrNull()
        if (after != null) {
            // The row now describes the saved revision, not the one we opened.
            repository.recordSaved(documentId, after.sizeBytes, after.lastModified ?: 0L)
        }
    }

    private fun isWritable(location: String): Boolean =
        if (DocumentAccess.isContentLocation(location)) {
            DocumentAccess.canWriteInPlace(context, location)
        } else {
            runCatching { File(location).canWrite() }.getOrDefault(false)
        }

    private fun targetFor(location: String): SaveTarget =
        if (DocumentAccess.isContentLocation(location)) {
            ContentSaveTarget(context, location)
        } else {
            FileSaveTarget(File(location))
        }

    private fun readOriginal(location: String): ByteArray =
        DocumentAccess.readBytes(context, location).getOrThrow()

    private fun startXrefOf(bytes: ByteArray): Long? =
        PdfDocument.open(ByteArraySource(bytes)).getOrNull()?.use { it.file.startXrefOffset }

    private fun <T> openDocument(bytes: ByteArray, block: (PdfDocument) -> T): T =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow().use(block)
}
