package com.pdfpaper.app.annot

import android.content.Context
import com.pdfpaper.app.data.DocumentAccess
import com.pdfpaper.app.index.PdfIndexExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What indexing one opened file produced. */
data class IndexedOpenedDocument(
    val documentId: Long,
    val annotations: Int
)

/**
 * T56: a file opened in the viewer is indexed for the Annotations hub straight
 * away, whether or not its folder is part of the T50 library. The work runs on
 * the IO dispatcher, one document at a time, and never propagates a failure:
 * the hub simply has nothing new for that file.
 */
object OpenedDocumentIndex {

    private val mutex = Mutex()

    /**
     * Re-reads [location] and replaces its PDF annotations in the index.
     * Returns the document row id and the number of annotations stored, or a
     * failure when the file could not be read (the viewer still works; only
     * the hub entry is stale).
     */
    suspend fun refresh(
        context: Context,
        location: String,
        displayName: String? = null
    ): Result<IndexedOpenedDocument> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val fingerprint = DocumentAccess.fingerprint(context, location).getOrThrow()
                val outcome = PdfIndexExtractor(context).extract(location, fingerprint.sizeBytes)
                if (outcome !is PdfIndexExtractor.Outcome.Text) {
                    return@runCatching IndexedOpenedDocument(0, 0)
                }
                val documentId = AnnotationRepository.get(context).store(
                    AnnotationDocumentInput(
                        fingerprint = outcome.fingerprint,
                        documentUri = location,
                        displayName = displayName
                            ?: DocumentAccess.displayName(context, location),
                        sizeBytes = fingerprint.sizeBytes,
                        lastModified = fingerprint.lastModified ?: 0L,
                        pageCount = outcome.pageCount
                    ),
                    outcome.annotations,
                    AnnotationRepository.SOURCE_OPENED
                )
                IndexedOpenedDocument(documentId, outcome.annotations.size)
            }
        }
    }

    /** Adds an app bookmark for a page of an already-indexed document. */
    suspend fun addBookmark(
        context: Context,
        documentId: Long,
        pageIndex: Int,
        label: String?,
        color: String?
    ): Long = AnnotationRepository.get(context)
        .addBookmark(documentId, pageIndex, label, color)
}
