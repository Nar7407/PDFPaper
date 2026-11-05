package com.pdfpaper.app.index

/**
 * Pure incremental-indexing rules (T50).
 *
 * A file is "changed" when either the provider's size or last-modified value
 * differs. The relative path and display name are compared too, so a rename
 * updates the index without a second extraction when the bytes are the same.
 * A provider that reports 0 for lastModified is handled conservatively: equal
 * sizes with 0 timestamps are unchanged rather than re-indexed on every scan.
 */
object IndexChangeDetector {

    fun detect(known: List<KnownPdf>, remote: List<RemotePdf>): IndexDiff {
        val knownByUri = LinkedHashMap<String, KnownPdf>(known.size)
        for (item in known) {
            knownByUri[item.documentUri] = item
        }
        val seen = HashSet<String>(remote.size)
        val changes = ArrayList<IndexChange>()
        val unchanged = ArrayList<RemotePdf>()

        for (item in remote) {
            if (!seen.add(item.documentUri)) continue
            val old = knownByUri[item.documentUri]
            when {
                old == null -> changes += IndexChange.New(item)
                isChanged(old, item) -> changes += IndexChange.Changed(item)
                else -> unchanged += item
            }
        }

        for ((uri, old) in knownByUri) {
            if (uri !in seen) changes += IndexChange.Removed(old)
        }
        return IndexDiff(changes = changes, unchanged = unchanged)
    }

    /**
     * A scan that was truncated or hit an unreadable subfolder has an
     * incomplete listing. A file missing from it may still exist, so removals
     * are suppressed; new and changed files are still safe to process.
     */
    fun effectiveChanges(diff: IndexDiff, completeScan: Boolean): List<IndexChange> =
        if (completeScan) diff.changes else diff.changes.filterNot { it is IndexChange.Removed }

    private fun isChanged(old: KnownPdf, new: RemotePdf): Boolean =
        old.sizeBytes != new.sizeBytes ||
            old.lastModified != new.lastModified ||
            old.relativePath != new.relativePath ||
            old.displayName != new.displayName
}
