package com.pdfpaper.app.index

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

data class TreeWalkResult(
    val files: List<RemotePdf>,
    val truncated: Boolean,
    val warnings: List<String>
)

/**
 * Walks a SAF tree with [DocumentsContract] (T50). It only follows real
 * directories, never trusts a MIME type alone for a PDF, and stops after a
 * bounded number of files/folders so a pathological provider cannot hang the
 * worker. A failure for one subfolder is a warning, not a failed scan.
 */
class DocumentTreeWalker(private val context: Context) {

    fun walk(treeUri: Uri): Result<TreeWalkResult> {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrElse { return Result.failure(it) }
        val files = ArrayList<RemotePdf>()
        val warnings = ArrayList<String>()
        val visited = HashSet<String>()
        var truncated = false

        fun childrenOf(documentId: String): List<Row> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            val rows = ArrayList<Row>()
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
                ?: throw IllegalStateException("The provider returned no folder listing")
            cursor.use {
                val idIndex = it.getColumnIndex(projection[0])
                val nameIndex = it.getColumnIndex(projection[1])
                val mimeIndex = it.getColumnIndex(projection[2])
                val sizeIndex = it.getColumnIndex(projection[3])
                val modifiedIndex = it.getColumnIndex(projection[4])
                // Without these three columns the listing is not trustworthy.
                // Failing the scan keeps the previous rows instead of treating
                // every indexed file as deleted.
                if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) {
                    throw IllegalStateException("The provider omitted required document columns")
                }
                while (it.moveToNext()) {
                    val id = if (idIndex >= 0) it.getString(idIndex) else null
                    if (id.isNullOrBlank()) continue
                    val name = if (nameIndex >= 0) it.getString(nameIndex) else null
                    val mime = if (mimeIndex >= 0) it.getString(mimeIndex) else null
                    rows += Row(
                        documentId = id,
                        name = name?.takeIf { it.isNotBlank() } ?: id,
                        mime = mime.orEmpty(),
                        size = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) {
                            it.getLong(sizeIndex)
                        } else {
                            0L
                        },
                        lastModified = if (modifiedIndex >= 0 && !it.isNull(modifiedIndex)) {
                            it.getLong(modifiedIndex)
                        } else {
                            0L
                        }
                    )
                }
            }
            return rows
        }

        fun visit(documentId: String, path: String, depth: Int) {
            if (truncated || documentId in visited) return
            if (depth > MAX_DEPTH) {
                // A skipped subtree is an incomplete listing: the worker must
                // not treat the files inside it as removed.
                truncated = true
                return
            }
            if (visited.size >= MAX_FOLDERS) {
                truncated = true
                return
            }
            visited.add(documentId)
            // A failure to read the root must fail the scan (so no indexed file
            // is mistaken for removed). A single unreadable subfolder is a
            // warning and the rest of the tree is still indexed.
            val rows = if (depth == 0) {
                childrenOf(documentId)
            } else {
                runCatching { childrenOf(documentId) }.getOrElse { error ->
                    warnings += "Could not read a folder: " +
                        (error.message ?: error.javaClass.simpleName)
                    return
                }
            }
            for (row in rows) {
                if (files.size >= MAX_FILES) {
                    truncated = true
                    return
                }
                val childPath = if (path.isBlank()) row.name else "$path/${row.name}"
                if (row.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    visit(row.documentId, childPath, depth + 1)
                } else if (looksLikePdf(row.name, row.mime)) {
                    val documentUri = runCatching {
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, row.documentId)
                    }.getOrNull() ?: continue
                    files += RemotePdf(
                        documentUri = documentUri.toString(),
                        relativePath = childPath,
                        displayName = row.name,
                        sizeBytes = row.size,
                        lastModified = row.lastModified
                    )
                }
            }
        }

        return runCatching {
            visit(rootId, "", 0)
            TreeWalkResult(files = files, truncated = truncated, warnings = warnings)
        }
    }

    private fun looksLikePdf(name: String, mime: String): Boolean =
        mime.equals("application/pdf", ignoreCase = true) ||
            name.endsWith(".pdf", ignoreCase = true)

    private data class Row(
        val documentId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val lastModified: Long
    )

    companion object {
        const val MAX_FILES = 5_000
        const val MAX_FOLDERS = 5_000
        const val MAX_DEPTH = 32

        /** A readable name for a tree URI, with a provider-independent fallback. */
        fun displayName(context: Context, treeUri: Uri): String {
            runCatching {
                context.contentResolver.query(
                    treeUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME
                        )
                        if (index >= 0) {
                            val name = cursor.getString(index)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            }
            val segment = treeUri.lastPathSegment.orEmpty()
            val tail = segment.substringAfterLast(':').substringAfterLast('/')
            return tail.ifBlank { "Folder" }
        }
    }
}
