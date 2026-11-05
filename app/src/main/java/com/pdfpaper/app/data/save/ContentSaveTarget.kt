package com.pdfpaper.app.data.save

import android.content.Context
import android.net.Uri
import com.pdfpaper.app.data.DocumentAccess
import java.io.File
import java.io.IOException

/**
 * A writable document backed by a SAF `content://` URI (T55). Append mode
 * (`"wa"`) is a request, not a guarantee: a provider may ignore it, so the
 * coordinator always reads the file back and falls back to a rewrite.
 */
class ContentSaveTarget(
    private val context: Context,
    private val location: String
) : SaveTarget {

    private val uri: Uri = Uri.parse(location)

    override fun read(): Result<ByteArray> =
        DocumentAccess.readBytes(context, location)

    override fun fingerprint(): Result<DocumentFingerprint> =
        DocumentAccess.fingerprint(context, location)

    override fun append(bytes: ByteArray): Result<Unit> = runCatching {
        write(bytes, "wa")
    }

    override fun replace(bytes: ByteArray): Result<Unit> = runCatching {
        write(bytes, "wt")
    }

    private fun write(bytes: ByteArray, mode: String) {
        val stream = try {
            context.contentResolver.openOutputStream(uri, mode)
        } catch (error: Exception) {
            throw IOException("The provider refused ${if (mode == "wa") "append" else "write"} access", error)
        } ?: throw IOException("Could not open the original file for writing")
        stream.use { output ->
            output.write(bytes)
            output.flush()
        }
    }
}

/** A local file target, used by tests and by future local-file editing. */
class FileSaveTarget(private val file: File) : SaveTarget {
    override fun read(): Result<ByteArray> = runCatching { file.readBytes() }

    override fun fingerprint(): Result<DocumentFingerprint> = runCatching {
        DocumentFingerprint(file.length(), file.lastModified().takeIf { it > 0 })
    }

    override fun append(bytes: ByteArray): Result<Unit> = runCatching {
        file.appendBytes(bytes)
    }

    override fun replace(bytes: ByteArray): Result<Unit> = runCatching {
        file.writeBytes(bytes)
    }
}
