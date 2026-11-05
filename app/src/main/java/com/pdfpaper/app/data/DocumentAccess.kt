package com.pdfpaper.app.data

import android.content.Context
import android.net.Uri
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.io.RandomAccessSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.app.data.save.DocumentFingerprint
import java.io.File
import java.io.IOException
import java.io.InputStream

/** A location the viewer can open: a local path or a content URI. */
data class DocumentLocation(
    val value: String,
    val displayName: String,
    val canWriteInPlace: Boolean = false,
    val isContent: Boolean = value.startsWith("content://"),
    /** Size at open time, used to detect an external change (T55). */
    val sizeBytes: Long = 0L,
    /** Provider/file last-modified time at open time, when known (T55). */
    val lastModified: Long? = null
)

/** One inspected incoming document. */
data class DocumentInspection(
    val displayName: String,
    val sizeBytes: Long,
    val kind: IncomingKind,
    /** Set when the bytes are already local (a file, or a fallback cache copy). */
    val localFile: File?,
    /** Set when a seekable content URI can be opened without copying. */
    val directLocation: String?,
    val canWriteInPlace: Boolean
) {
    val viewerLocation: String?
        get() = directLocation ?: localFile?.absolutePath
}

/** An opened document together with the location it was read from. */
data class OpenedDocument(
    val document: PdfDocument,
    val location: DocumentLocation
)

/**
 * All access to incoming documents goes through this object (task T47).
 *
 * Rules:
 *  - a seekable `content://` PDF is opened through [ParcelFileDescriptorSource]
 *    and never copied;
 *  - a pipe, or a file larger than [MAX_DOCUMENT_BYTES], is copied through a
 *    bounded stream into the app cache (pipes are not seekable, so the engine
 *    cannot read them directly);
 *  - Office files are always copied because the converter reads a [File];
 *  - every failure is an exception carried in a [Result], never a crash.
 */
object DocumentAccess {

    /** Refuse a document larger than this before doing any heavy work. */
    const val MAX_DOCUMENT_BYTES: Long = 512L * 1024L * 1024L

    /** A single shared image is bounded separately so ten photos cannot blow the heap. */
    const val MAX_IMAGE_BYTES: Long = 64L * 1024L * 1024L

    /** Total bytes accepted for one multi-image share. */
    const val MAX_SHARE_BYTES: Long = 256L * 1024L * 1024L

    const val MAX_HEADER_BYTES: Int = 1024

    fun isContentLocation(location: String): Boolean =
        location.startsWith("content://", ignoreCase = true)

    /** Reads the display name without trusting it for anything but a label. */
    fun displayName(context: Context, location: String, fallback: String? = null): String {
        val uri = runCatching { Uri.parse(location) }.getOrNull()
        val fromProvider = if (uri != null && isContentLocation(location)) {
            queryDisplayName(context, uri)
        } else {
            null
        }
        return sanitizeDisplayName(fromProvider ?: fallback ?: fileNameOf(uri))
    }

    /**
     * Reads only the header of [location] and reports its family. This never
     * copies a file, so a share sheet can classify fifty photos cheaply.
     */
    fun peekKind(context: Context, location: String): Result<IncomingKind> = runCatching {
        val uri = runCatching { Uri.parse(location) }.getOrNull()
        if (isContentLocation(location) && uri != null) {
            val descriptor = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (_: SecurityException) {
                null
            }
            if (descriptor != null) {
                val source = try {
                    descriptor.asSeekableSource()
                } catch (_: NonSeekableException) {
                    null
                }
                if (source != null) {
                    source.use { seekable ->
                        if (seekable.size > MAX_DOCUMENT_BYTES) throw tooLarge(MAX_DOCUMENT_BYTES)
                        val length = minOf(seekable.size, MAX_HEADER_BYTES.toLong()).toInt()
                        return@runCatching IncomingKindDetector.detect(seekable.read(0, length))
                    }
                }
            }
            val input = try {
                context.contentResolver.openInputStream(uri)
            } catch (_: SecurityException) {
                null
            } ?: throw IOException("The provider refused access to the shared file")
            input.use {
                val buffer = ByteArray(MAX_HEADER_BYTES)
                var total = 0
                while (total < buffer.size) {
                    val read = it.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
                return@runCatching IncomingKindDetector.detect(buffer.copyOf(total))
            }
        }
        val file = localFile(location)
        if (!file.isFile) throw IOException("The shared file could not be found")
        if (file.length() > MAX_DOCUMENT_BYTES) throw tooLarge(MAX_DOCUMENT_BYTES)
        IncomingKindDetector.detect(readHeader(file))
    }

    /** Inspects an incoming URI and decides what it is from its bytes. */
    fun inspect(
        context: Context,
        uri: Uri,
        fallbackName: String? = null
    ): Result<DocumentInspection> = runCatching {
        val name = sanitizeDisplayName(queryDisplayName(context, uri) ?: fallbackName)
        when {
            uri.scheme.equals("content", ignoreCase = true) ->
                inspectContent(context, uri, name)
            uri.scheme.equals("file", ignoreCase = true) ->
                inspectFile(File(requireNotNull(uri.path) { "file URI has no path" }), name)
            uri.scheme.isNullOrBlank() ->
                inspectFile(File(uri.toString()), name)
            else -> throw IOException(
                "Only local files and content:// documents can be opened; " +
                    "PDFPaper has no network permission."
            )
        }
    }

    /**
     * Opens [location] for the viewer. Content URIs are read directly when the
     * provider allows seeking; otherwise this transparently copies to cache
     * and returns the cache location in [OpenedDocument.location].
     */
    fun open(
        context: Context,
        location: String,
        password: String = ""
    ): Result<OpenedDocument> = runCatching {
        if (isContentLocation(location)) {
            openContent(context, Uri.parse(location), password)
        } else {
            val file = localFile(location)
            requireFileSize(file)
            val document = PdfDocument.open(FileSource(file), password).getOrThrow()
            OpenedDocument(
                document,
                DocumentLocation(
                    value = location,
                    displayName = sanitizeDisplayName(file.name),
                    canWriteInPlace = file.canWrite(),
                    isContent = false,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified().takeIf { it > 0 }
                )
            )
        }
    }

    /** Opens a fresh source for signature verification/devices that need raw bytes. */
    fun openSource(
        context: Context,
        location: String,
        maxBytes: Long = MAX_DOCUMENT_BYTES
    ): Result<RandomAccessSource> = runCatching {
        if (isContentLocation(location)) {
            val uri = Uri.parse(location)
            val descriptor = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (error: SecurityException) {
                null
            }
            if (descriptor != null) {
                val source = try {
                    descriptor.asSeekableSource()
                } catch (_: NonSeekableException) {
                    null
                }
                if (source != null) {
                    if (source.size > maxBytes) {
                        source.close()
                        throw tooLarge(maxBytes)
                    }
                    return@runCatching source
                }
            }
            val file = copyContent(
                context,
                uri,
                displayName(context, location),
                maxBytes
            )
            return@runCatching FileSource(file)
        }
        val file = localFile(location)
        if (file.length() > maxBytes) throw tooLarge(maxBytes)
        FileSource(file)
    }

    /** Reads a whole document with a hard byte cap (signing has to hash bytes). */
    fun readBytes(
        context: Context,
        location: String,
        maxBytes: Long = MAX_DOCUMENT_BYTES
    ): Result<ByteArray> = runCatching {
        openSource(context, location, maxBytes).getOrThrow().use { source ->
            if (source.size > maxBytes) throw tooLarge(maxBytes)
            if (source.size > Int.MAX_VALUE) throw tooLarge(maxBytes)
            source.read(0, source.size.toInt())
        }
    }

    /** Copies [location] to the app cache; used by editing tools that need a File. */
    fun materialize(
        context: Context,
        location: String,
        fallbackName: String? = null
    ): Result<File> = runCatching {
        if (!isContentLocation(location)) return@runCatching localFile(location)
        val uri = Uri.parse(location)
        val name = fallbackName ?: displayName(context, location)
        copyContent(context, uri, name, MAX_DOCUMENT_BYTES)
    }

    /** True when the provider accepts writes for this URI. */
    fun canWriteInPlace(context: Context, location: String): Boolean {
        if (!isContentLocation(location)) return false
        val uri = Uri.parse(location)
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
        }.getOrDefault(false)
    }

    /** Writes bytes back to the original content URI. */
    fun writeInPlace(context: Context, location: String, bytes: ByteArray): Result<Unit> =
        runCatching {
            require(isContentLocation(location)) { "Only content:// files support Save in place" }
            val uri = Uri.parse(location)
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw IOException("Could not open the original file for writing")
        }

    /**
     * Reads the current size and last-modified time (T55). Providers are
     * allowed to leave the timestamp out, so a null timestamp means "unknown"
     * and only the size is compared.
     */
    fun fingerprint(context: Context, location: String): Result<DocumentFingerprint> = runCatching {
        if (!isContentLocation(location)) {
            val file = localFile(location)
            require(file.isFile) { "The file could not be found" }
            return@runCatching DocumentFingerprint(
                file.length(),
                file.lastModified().takeIf { it > 0 }
            )
        }
        val uri = Uri.parse(location)
        var size: Long? = null
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) size = cursor.getLong(0)
            }
        }
        if (size == null) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    size = descriptor.statSize.takeIf { it >= 0 }
                }
            }
        }
        if (size == null || size == 0L) {
            size = readBytes(context, location).getOrNull()?.size?.toLong()
        }
        val modified = contentLastModified(context, uri)
        DocumentFingerprint(
            size ?: throw IOException("The provider did not report the file size"),
            modified
        )
    }

    /** Copies an input stream into a cache file, refusing to grow past [maxBytes]. */
    fun copyToCache(
        context: Context,
        input: InputStream,
        displayName: String?,
        maxBytes: Long = MAX_DOCUMENT_BYTES
    ): File {
        val directory = File(context.cacheDir, "incoming").apply { mkdirs() }
        val target = uniqueFile(directory, sanitizeDisplayName(displayName))
        var copied = 0L
        try {
            input.use { source ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > maxBytes) throw tooLarge(maxBytes)
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (t: Throwable) {
            runCatching { target.delete() }
            throw t
        }
        return target
    }

    private fun inspectContent(
        context: Context,
        uri: Uri,
        name: String
    ): DocumentInspection {
        val descriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (error: SecurityException) {
            null
        }
        if (descriptor != null) {
            val source = try {
                descriptor.asSeekableSource()
            } catch (_: NonSeekableException) {
                null
            }
            if (source != null) {
                val result = source.use { seekable ->
                    if (seekable.size > MAX_DOCUMENT_BYTES) throw tooLarge(MAX_DOCUMENT_BYTES)
                    val header = seekable.read(0, minOf(seekable.size, MAX_HEADER_BYTES.toLong()).toInt())
                    val kind = IncomingKindDetector.detect(header)
                    if (kind == IncomingKind.PDF) {
                        DocumentInspection(
                            displayName = name,
                            sizeBytes = seekable.size,
                            kind = kind,
                            localFile = null,
                            directLocation = uri.toString(),
                            canWriteInPlace = canWriteInPlace(context, uri.toString())
                        )
                    } else {
                        null
                    }
                }
                if (result != null) return result
                // A seekable non-PDF still needs a file for the Office sniffer
                // and converter. It is copied under the same size cap.
                val local = copyContent(context, uri, name, MAX_DOCUMENT_BYTES)
                return inspectFile(local, name)
            }
            // A pipe has no position to seek to. Fall through to a bounded copy.
        }
        val local = copyContent(context, uri, name, MAX_DOCUMENT_BYTES)
        return inspectFile(local, name)
    }

    private fun inspectFile(file: File, name: String): DocumentInspection {
        if (!file.isFile) {
            throw IOException("The document could not be read")
        }
        if (file.length() > MAX_DOCUMENT_BYTES) throw tooLarge(MAX_DOCUMENT_BYTES)
        val header = readHeader(file)
        val kind = IncomingKindDetector.detect(header)
        return DocumentInspection(
            displayName = name,
            sizeBytes = file.length(),
            kind = kind,
            localFile = file,
            directLocation = if (kind == IncomingKind.PDF) file.absolutePath else null,
            canWriteInPlace = false
        )
    }

    private fun openContent(
        context: Context,
        uri: Uri,
        password: String
    ): OpenedDocument {
        val name = displayName(context, uri.toString())
        val descriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: SecurityException) {
            null
        }
        if (descriptor != null) {
            val source = try {
                descriptor.asSeekableSource()
            } catch (_: NonSeekableException) {
                null
            }
            if (source != null) {
                if (source.size > MAX_DOCUMENT_BYTES) {
                    source.close()
                    throw tooLarge(MAX_DOCUMENT_BYTES)
                }
                val size = source.size
                val modified = contentLastModified(context, uri)
                val document = PdfDocument.open(source, password).getOrThrow()
                return OpenedDocument(
                    document,
                    DocumentLocation(
                        value = uri.toString(),
                        displayName = name,
                        canWriteInPlace = canWriteInPlace(context, uri.toString()),
                        isContent = true,
                        sizeBytes = size,
                        lastModified = modified
                    )
                )
            }
        }
        val local = copyContent(context, uri, name, MAX_DOCUMENT_BYTES)
        if (local.length() > MAX_DOCUMENT_BYTES) throw tooLarge(MAX_DOCUMENT_BYTES)
        val document = PdfDocument.open(FileSource(local), password).getOrThrow()
        return OpenedDocument(
            document,
            DocumentLocation(
                value = local.absolutePath,
                displayName = name,
                canWriteInPlace = false,
                isContent = false
            )
        )
    }

    private fun copyContent(
        context: Context,
        uri: Uri,
        name: String,
        maxBytes: Long
    ): File {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (error: SecurityException) {
            null
        } ?: throw IOException("The provider refused access to ${uri.lastPathSegment ?: "the file"}")
        return copyToCache(context, input, name, maxBytes)
    }

    private fun readHeader(file: File): ByteArray {
        val length = minOf(file.length(), MAX_HEADER_BYTES.toLong()).toInt()
        if (length <= 0) return ByteArray(0)
        file.inputStream().use { input ->
            val buffer = ByteArray(length)
            var total = 0
            while (total < length) {
                val read = input.read(buffer, total, length - total)
                if (read < 0) break
                total += read
            }
            return if (total == length) buffer else buffer.copyOf(total)
        }
    }

    private fun requireFileSize(file: File) {
        if (!file.isFile) throw IOException("The document could not be found")
        if (file.length() > MAX_DOCUMENT_BYTES) throw tooLarge(MAX_DOCUMENT_BYTES)
    }

    private fun localFile(location: String): File {
        val uri = runCatching { Uri.parse(location) }.getOrNull()
        return if (uri?.scheme.equals("file", ignoreCase = true)) {
            File(requireNotNull(uri?.path) { "file URI has no path" })
        } else {
            File(location)
        }
    }

    private fun fileNameOf(uri: Uri?): String? = when {
        uri == null -> null
        uri.scheme.equals("content", ignoreCase = true) -> uri.lastPathSegment
        else -> uri.lastPathSegment ?: uri.path?.substringAfterLast('/')
    }

    /** Provider last-modified time, or null when the provider does not expose one. */
    private fun contentLastModified(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()?.takeIf { it > 0 }

    private fun tooLarge(maxBytes: Long): IOException =
        IOException("This file is larger than PDFPaper's ${formatMegabytes(maxBytes)} limit")
}

private fun formatMegabytes(bytes: Long): String =
    if (bytes % (1024L * 1024L) == 0L) {
        "${bytes / (1024L * 1024L)} MB"
    } else {
        "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
