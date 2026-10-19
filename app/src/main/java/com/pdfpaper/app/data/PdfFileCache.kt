package com.pdfpaper.app.data

import java.io.File
import java.io.InputStream

/**
 * A PDF copied into the app cache, ready for the Viewer screen.
 *
 * @property file the cached copy; its absolute path is passed to the Viewer.
 * @property displayName the provider's display name (sanitized, no path separators).
 * @property sizeBytes size of the cached copy.
 */
data class CachedPdf(
    val file: File,
    val displayName: String,
    val sizeBytes: Long
)

/**
 * Strips path separators and parent traversals from a provider-supplied name.
 * Falls back to "document.pdf" for null/blank/empty-after-sanitizing input.
 *
 * Pure JVM (no Android imports) so it is unit-testable.
 */
fun sanitizeDisplayName(raw: String?): String {
    var name = raw?.trim().orEmpty()
    name = name.replace('/', '_').replace('\\', '_')
    while (".." in name) {
        name = name.replace("..", "")
    }
    name = name.trim()
    if (name.isBlank()) return "document.pdf"
    return name
}

/**
 * Copies [source] into `<cacheDir>/pdfs/` under a sanitized, collision-free name.
 * Pure JVM (no Android imports) so it is unit-testable; Android callers pass
 * `contentResolver.openInputStream(uri)` as [source].
 */
fun copyPdfToCache(source: InputStream, displayName: String?, cacheDir: File): CachedPdf {
    val safeName = sanitizeDisplayName(displayName)
    val dir = File(cacheDir, "pdfs")
    require(dir.mkdirs() || dir.isDirectory) { "Unable to create cache directory: $dir" }
    val target = uniqueFile(dir, safeName)
    source.use { input ->
        target.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    return CachedPdf(target, safeName, target.length())
}

internal fun uniqueFile(dir: File, name: String): File {
    var candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "")
    val hasExt = ext.isNotEmpty() && ext != name
    var index = 1
    while (candidate.exists()) {
        candidate = if (hasExt) {
            File(dir, "$base ($index).$ext")
        } else {
            File(dir, "$base ($index)")
        }
        index++
    }
    return candidate
}
