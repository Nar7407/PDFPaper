package com.pdfpaper.app.index

/**
 * Task T50: the local search index. A folder is a SAF tree the user granted;
 * a file is one PDF found by walking that tree. Nothing here touches the
 * network and nothing is copied out of the app-private database.
 */
data class IndexFolder(
    val id: Long,
    val treeUri: String,
    val displayName: String,
    val addedAt: Long,
    val lastScanAt: Long? = null,
    val lastError: String? = null
)

enum class IndexedFileStatus {
    /** Every page has an extractable text layer. */
    INDEXED,

    /** The document is encrypted and cannot be indexed without its password. */
    LOCKED,

    /** The document opened, but at least one page has no text layer. */
    NEEDS_OCR,

    /** Reading or parsing the document failed. */
    ERROR,

    /** The file is larger than the per-file indexing limit. */
    TOO_LARGE
}

data class IndexedFile(
    val id: Long,
    val folderId: Long,
    val documentUri: String,
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val status: IndexedFileStatus,
    val pageCount: Int,
    val pagesWithoutText: Int,
    val indexedAt: Long,
    val error: String? = null
)

/** A PDF discovered by [DocumentTreeWalker]. */
data class RemotePdf(
    val documentUri: String,
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long
)

/** The change-detection input for one previously indexed file. */
data class KnownPdf(
    val documentUri: String,
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long
)

sealed interface IndexChange {
    data class New(val remote: RemotePdf) : IndexChange
    data class Changed(val remote: RemotePdf) : IndexChange
    data class Removed(val known: KnownPdf) : IndexChange
}

data class IndexDiff(
    val changes: List<IndexChange>,
    val unchanged: List<RemotePdf>
) {
    val newCount: Int get() = changes.count { it is IndexChange.New }
    val changedCount: Int get() = changes.count { it is IndexChange.Changed }
    val removedCount: Int get() = changes.count { it is IndexChange.Removed }
}

data class IndexStats(
    val folderCount: Int,
    val fileCount: Int,
    val pageCount: Int,
    val indexedCount: Int,
    val lockedCount: Int,
    val needsOcrCount: Int,
    val errorCount: Int,
    val databaseBytes: Long
) {
    val totalCount: Int get() = indexedCount + lockedCount + needsOcrCount + errorCount
}

/** One page hit from the FTS table, with FTS4's snippet markers intact. */
data class SearchHit(
    val fileId: Long,
    val documentUri: String,
    val displayName: String,
    val relativePath: String,
    val folderId: Long,
    val folderName: String,
    val lastModified: Long,
    val pageIndex: Int,
    val markedSnippet: String
)

/** Hits grouped by document for the result list. */
data class SearchResultGroup(
    val fileId: Long,
    val documentUri: String,
    val displayName: String,
    val relativePath: String,
    val folderId: Long,
    val folderName: String,
    val lastModified: Long,
    val hits: List<SearchHit>
)

enum class DateFilter(val label: String) {
    ANY("Any date"),
    LAST_7_DAYS("Last 7 days"),
    LAST_30_DAYS("Last 30 days"),
    THIS_YEAR("This year")
}

data class SearchFilters(
    val folderId: Long? = null,
    val date: DateFilter = DateFilter.ANY,
    val fileNameOnly: Boolean = false
)

/** A status row shown under "Locked" and "Needs OCR". */
data class IndexStatusFile(
    val fileId: Long,
    val documentUri: String,
    val displayName: String,
    val relativePath: String,
    val status: IndexedFileStatus,
    val pageCount: Int,
    val pagesWithoutText: Int,
    val error: String? = null
)
