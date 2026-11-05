package com.pdfpaper.app.data.save

import android.content.Context
import com.pdfpaper.app.data.AppSettings
import java.io.File
import java.security.MessageDigest

/** One pre-save copy in app-private storage. */
data class SavedVersion(
    val id: String,
    val savedAt: Long,
    val sizeBytes: Long,
    val file: File
)

/**
 * Keeps the last [MAX_VERSIONS] pre-save copies of each document (T55).
 *
 * The store lives under the app's private files directory, never on the SAF
 * provider, so the user's document folder is not littered with backups and a
 * read-only source still gets version history. A total byte cap bounds disk
 * use; older copies are deleted first. Turning the setting off deletes every
 * stored copy.
 */
class VersionStore(
    private val context: Context,
    private val documentKey: String
) : VersionSink {

    private val root: File
        get() = File(context.filesDir, "versions")

    override fun record(bytes: ByteArray): Result<Unit> = runCatching {
        if (!AppSettings.versionHistoryEnabled(context)) return@runCatching
        if (bytes.isEmpty() || bytes.size > MAX_VERSION_BYTES) return@runCatching
        val key = keyFor(documentKey)
        val dir = File(root, key).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val target = File(dir, "%013d.pdf".format(now))
        target.writeBytes(bytes)
        pruneDirectory(dir, keep = AppSettings.versionHistoryLimit(context))
        pruneTotal()
        Unit
    }

    /** Newest first. */
    fun list(): List<SavedVersion> {
        val dir = File(root, keyFor(documentKey))
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".pdf") }
            .orEmpty()
            .sortedByDescending { it.name }
            .map {
                SavedVersion(
                    id = it.name.removeSuffix(".pdf"),
                    savedAt = it.name.removeSuffix(".pdf").toLongOrNull() ?: it.lastModified(),
                    sizeBytes = it.length(),
                    file = it
                )
            }
    }

    fun read(id: String): Result<ByteArray> = runCatching {
        val safe = id.filter { it.isDigit() }
        require(safe == id && safe.isNotEmpty()) { "Invalid version id" }
        val file = File(File(root, keyFor(documentKey)), "$safe.pdf")
        require(file.isFile) { "That version is no longer available" }
        file.readBytes()
    }

    fun clear(): Result<Int> = runCatching {
        val dir = File(root, keyFor(documentKey))
        val files = dir.listFiles().orEmpty().filter { it.isFile }
        val count = files.size
        files.forEach { it.delete() }
        dir.delete()
        count
    }

    fun clearAll(): Int {
        val files = root.listFiles().orEmpty()
        var count = 0
        files.forEach { dir ->
            dir.listFiles().orEmpty().forEach { if (it.isFile) { it.delete(); count++ } }
            dir.delete()
        }
        return count
    }

    fun totalBytes(): Long =
        root.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().asList() }
            .filter { it.isFile }
            .sumOf { it.length() }

    fun count(): Int =
        root.listFiles().orEmpty().sumOf { dir -> dir.listFiles().orEmpty().count { it.isFile } }

    private fun pruneDirectory(dir: File, keep: Int) {
        dir.listFiles().orEmpty()
            .filter { it.isFile }
            .sortedByDescending { it.name }
            .drop(keep.coerceAtLeast(1))
            .forEach { it.delete() }
    }

    private fun pruneTotal() {
        val all = root.listFiles().orEmpty()
            .flatMap { dir -> dir.listFiles().orEmpty().asList() }
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
        var total = all.sumOf { it.length() }
        for (file in all) {
            if (total <= MAX_TOTAL_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun keyFor(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_VERSIONS = 3
        const val MAX_VERSION_BYTES = 64L * 1024L * 1024L
        const val MAX_TOTAL_BYTES = 256L * 1024L * 1024L
    }
}
