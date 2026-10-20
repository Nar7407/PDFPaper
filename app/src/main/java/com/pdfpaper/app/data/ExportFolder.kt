package com.pdfpaper.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** Where exported page images go. Implemented for SAF trees and for tests. */
interface ExportFolder {

    /** File names already present, so the caller can ask before overwriting. */
    fun existingNames(): Set<String>

    /**
     * Writes [bytes] as [name]. Returns the file's display location for the
     * report, or null when the provider refused to create the file.
     */
    fun write(name: String, mimeType: String, bytes: ByteArray): String?

    /** Human-readable location shown in the summary. */
    val displayPath: String
}

/**
 * A folder the user picked with `ACTION_OPEN_DOCUMENT_TREE`
 * ([android.content.Intent.ACTION_OPEN_DOCUMENT_TREE]).
 *
 * Uses `DocumentsContract` directly: the app has no `documentfile` dependency,
 * and creating one file is a single provider call. Writes go through
 * `openOutputStream`; the provider chooses a unique name if a file with the
 * same name exists, but the export checks [existingNames] first and asks the
 * user instead of letting duplicates appear.
 */
class SafExportFolder(
    private val context: Context,
    private val treeUri: Uri
) : ExportFolder {

    private val documentUri: Uri by lazy {
        val id = DocumentsContract.getTreeDocumentId(treeUri)
        DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
    }

    override val displayPath: String
        get() = runCatching {
            documentUri.lastPathSegment?.substringAfterLast(':')?.ifEmpty { "the chosen folder" }
        }.getOrNull() ?: "the chosen folder"

    override fun existingNames(): Set<String> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        val names = LinkedHashSet<String>()
        runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { names += it }
                }
            }
        }
        return names
    }

    override fun write(name: String, mimeType: String, bytes: ByteArray): String? {
        val created = runCatching {
            DocumentsContract.createDocument(
                context.contentResolver,
                documentUri,
                mimeType,
                name
            )
        }.getOrNull() ?: return null
        return try {
            context.contentResolver.openOutputStream(created, "w")?.use { it.write(bytes) }
                ?: return null
            created.toString()
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, created) }
            null
        }
    }
}

/**
 * A plain directory (app cache, used for sharing and by tests). Names are
 * written verbatim, so the overwrite check behaves the same as SAF.
 */
class CacheExportFolder(private val directory: File) : ExportFolder {

    override val displayPath: String get() = directory.path

    override fun existingNames(): Set<String> =
        directory.listFiles()?.map { it.name }?.toSet() ?: emptySet()

    override fun write(name: String, mimeType: String, bytes: ByteArray): String? {
        if (!directory.exists() && !directory.mkdirs()) return null
        return try {
            val file = File(directory, name)
            file.writeBytes(bytes)
            file.path
        } catch (error: Exception) {
            null
        }
    }
}
