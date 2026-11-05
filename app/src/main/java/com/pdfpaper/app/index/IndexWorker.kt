package com.pdfpaper.app.index

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pdfpaper.app.annot.AnnotationDocumentInput
import com.pdfpaper.app.annot.AnnotationRepository
import com.pdfpaper.app.annot.ExtractedAnnotation
import com.pdfpaper.app.data.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Incremental indexing (T50): one PDF at a time, one folder scan at a time,
 * with a per-file time and size limit. A cancelled worker leaves the database
 * consistent because each file is written in one Room transaction.
 */
class IndexWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runMutex.withLock {
        if (AppSettings.indexPaused(applicationContext)) return@withLock Result.success()
        val repository = IndexRepository.get(applicationContext)
        val folders = repository.folders()
        if (folders.isEmpty()) return Result.success()

        val walker = DocumentTreeWalker(applicationContext)
        val extractor = PdfIndexExtractor(applicationContext)
        val annotationRepository = AnnotationRepository.get(applicationContext)
        var processed = 0

        for (folder in folders) {
            if (isStopped) return Result.retry()
            setProgress(workDataOf(KEY_PHASE to "Scanning ${folder.displayName}"))
            val treeUri = runCatching { Uri.parse(folder.treeUri) }.getOrNull()
            if (treeUri == null) {
                repository.updateFolderScan(folder.id, System.currentTimeMillis(), "Invalid folder URI")
                continue
            }
            val walkResult = walker.walk(treeUri)
            if (walkResult.isFailure) {
                // Keep the previous rows: a transient provider failure must not
                // look like every file in the folder was deleted.
                val error = walkResult.exceptionOrNull()
                repository.updateFolderScan(
                    folder.id,
                    System.currentTimeMillis(),
                    "Could not read this folder: " +
                        (error?.message ?: "permission denied")
                )
                continue
            }
            val walked = walkResult.getOrThrow()
            val known = repository.filesInFolder(folder.id)
            val diff = IndexChangeDetector.detect(
                known.map { it.toKnown() },
                walked.files
            )
            // A truncated walk or an unreadable subfolder means the listing is
            // incomplete: a file missing from it may still exist, so never
            // delete rows in that case. New and changed files are still safe.
            val completeScan = !walked.truncated && walked.warnings.isEmpty()
            val changes = IndexChangeDetector.effectiveChanges(diff, completeScan)
            val total = changes.size
            setProgress(
                workDataOf(
                    KEY_PHASE to folder.displayName,
                    KEY_DONE to 0,
                    KEY_TOTAL to total,
                    KEY_NEW to changes.count { it is IndexChange.New },
                    KEY_CHANGED to changes.count { it is IndexChange.Changed },
                    KEY_REMOVED to changes.count { it is IndexChange.Removed }
                )
            )

            val knownById = known.associateBy { it.documentUri }
            var folderProcessed = 0
            for (change in changes) {
                if (isStopped) return Result.retry()
                if (change is IndexChange.Removed) {
                    knownById[change.known.documentUri]?.let { repository.deleteFile(it.id) }
                    folderProcessed++
                    processed++
                    setProgress(
                        progress(
                            folder.displayName,
                            folderProcessed,
                            total,
                            change.known.displayName
                        )
                    )
                    continue
                }
                val remote = when (change) {
                    is IndexChange.New -> change.remote
                    is IndexChange.Changed -> change.remote
                    is IndexChange.Removed -> continue
                }
                val existing = if (change is IndexChange.Changed) {
                    knownById[remote.documentUri]
                } else {
                    null
                }
                setProgress(
                    workDataOf(
                        KEY_PHASE to "Indexing ${remote.displayName}",
                        KEY_DONE to folderProcessed,
                        KEY_TOTAL to total,
                        KEY_FILE to remote.displayName
                    )
                )
                val completed = runCatching {
                    indexFile(
                        repository,
                        annotationRepository,
                        extractor,
                        folder,
                        remote,
                        existing
                    )
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Indexing ${remote.displayName} failed", error)
                    // A database or provider failure for one file must not
                    // abort the whole library.
                    true
                }
                if (!completed) return Result.retry()
                folderProcessed++
                processed++
                setProgress(progress(folder.displayName, folderProcessed, total, remote.displayName))
            }

            val warning = when {
                walked.truncated -> "Stopped after the folder scan limit"
                walked.warnings.isNotEmpty() -> walked.warnings.take(3).joinToString("; ")
                else -> null
            }
            repository.updateFolderScan(folder.id, System.currentTimeMillis(), warning)
        }

        setProgress(workDataOf(KEY_PHASE to "Up to date", KEY_DONE to processed))
        return Result.success()
    }

    private suspend fun indexFile(
        repository: IndexRepository,
        annotationRepository: AnnotationRepository,
        extractor: PdfIndexExtractor,
        folder: IndexFolder,
        remote: RemotePdf,
        existing: IndexedFile?
    ): Boolean {
        val outcome = withContext(Dispatchers.IO) {
            extractor.extract(
                documentUri = remote.documentUri,
                reportedSize = remote.sizeBytes,
                deadlineNanos = System.nanoTime() +
                    PdfIndexExtractor.MAX_FILE_MILLIS * 1_000_000L,
                cancelled = { isStopped }
            )
        }
        if (outcome is PdfIndexExtractor.Outcome.Cancelled) return false

        val now = System.currentTimeMillis()
        val status: IndexedFileStatus
        val pageCount: Int
        val pagesWithoutText: Int
        val pages: List<PdfIndexExtractor.PageText>
        val annotations: List<ExtractedAnnotation>
        val fingerprint: String
        val error: String?
        when (outcome) {
            is PdfIndexExtractor.Outcome.Text -> {
                pageCount = outcome.pageCount
                pagesWithoutText = outcome.pagesWithoutText
                pages = outcome.pages
                annotations = outcome.annotations
                fingerprint = outcome.fingerprint
                error = null
                status = if (outcome.pagesWithoutText > 0) {
                    IndexedFileStatus.NEEDS_OCR
                } else {
                    IndexedFileStatus.INDEXED
                }
            }
            PdfIndexExtractor.Outcome.Locked -> {
                pageCount = 0
                pagesWithoutText = 0
                pages = emptyList()
                annotations = emptyList()
                fingerprint = ""
                error = "This PDF is encrypted; unlock it in the viewer to index it."
                status = IndexedFileStatus.LOCKED
            }
            PdfIndexExtractor.Outcome.TooLarge -> {
                pageCount = 0
                pagesWithoutText = 0
                pages = emptyList()
                annotations = emptyList()
                fingerprint = ""
                error = "Larger than the 128 MB indexing limit."
                status = IndexedFileStatus.TOO_LARGE
            }
            PdfIndexExtractor.Outcome.TimedOut -> {
                pageCount = 0
                pagesWithoutText = 0
                pages = emptyList()
                annotations = emptyList()
                fingerprint = ""
                error = "Indexing this file took longer than 60 seconds."
                status = IndexedFileStatus.ERROR
            }
            PdfIndexExtractor.Outcome.Cancelled -> return false
            is PdfIndexExtractor.Outcome.Failed -> {
                pageCount = 0
                pagesWithoutText = 0
                pages = emptyList()
                annotations = emptyList()
                fingerprint = ""
                error = outcome.message.take(300)
                status = IndexedFileStatus.ERROR
            }
        }

        val entity = IndexedFileEntity(
            id = existing?.id ?: 0,
            folderId = folder.id,
            documentUri = remote.documentUri,
            relativePath = remote.relativePath,
            displayName = remote.displayName,
            sizeBytes = remote.sizeBytes,
            lastModified = remote.lastModified,
            status = status.name,
            pageCount = pageCount,
            pagesWithoutText = pagesWithoutText,
            indexedAt = now,
            error = error
        )
        val dao = repository.database.dao()
        repository.database.withTransaction {
            val fileId = if (existing == null) {
                dao.insertFile(entity)
            } else {
                dao.deletePageTextForFile(existing.id)
                dao.deletePageMetaForFile(existing.id)
                dao.updateFile(entity)
                existing.id
            }
            var rowId = dao.nextPageRowId()
            for (page in pages) {
                dao.insertPageText(PageTextEntity(rowId, page.text))
                dao.insertPageMeta(PageMetaEntity(rowId, fileId, page.pageIndex))
                rowId++
            }
            // T56: the annotations are committed with the file, so the hub can
            // never show rows for a file the page index does not have.
            if (fingerprint.isNotEmpty()) {
                annotationRepository.storeInTransaction(
                    AnnotationDocumentInput(
                        fingerprint = fingerprint,
                        documentUri = remote.documentUri,
                        displayName = remote.displayName,
                        sizeBytes = remote.sizeBytes,
                        lastModified = remote.lastModified,
                        pageCount = pageCount
                    ),
                    annotations,
                    AnnotationRepository.SOURCE_FOLDER
                )
            }
        }
        return true
    }

    private fun progress(
        folderName: String,
        done: Int,
        total: Int,
        fileName: String
    ) = workDataOf(
        KEY_PHASE to folderName,
        KEY_DONE to done,
        KEY_TOTAL to total,
        KEY_FILE to fileName
    )

    private fun IndexedFile.toKnown() = KnownPdf(
        documentUri = documentUri,
        relativePath = relativePath,
        displayName = displayName,
        sizeBytes = sizeBytes,
        lastModified = lastModified
    )

    companion object {
        /**
         * The one-time and periodic work use different WorkManager names, so
         * WorkManager alone can run them together. This mutex keeps the "one
         * file at a time" promise inside the process; the database transaction
         * is the second line of defence if a worker is started elsewhere.
         */
        internal val runMutex = Mutex()

        private const val TAG = "IndexWorker"
        const val KEY_PHASE = "phase"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FILE = "file"
        const val KEY_NEW = "new"
        const val KEY_CHANGED = "changed"
        const val KEY_REMOVED = "removed"
    }
}
