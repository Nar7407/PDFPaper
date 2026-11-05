package com.pdfpaper.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Reads a provider display name, or null when the provider does not expose one. */
fun queryDisplayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index)
        }
    }
    return null
}

/**
 * Makes the provider's grant survive the activity, when the provider allows it.
 * Read+write is preferred so a document received through ACTION_VIEW can still
 * be saved in place after the transient grant is gone; a provider that only
 * persists reads falls back to the read grant. Best-effort: a URI opened
 * through ACTION_VIEW may carry a transient grant only, and asking for
 * persistence there is not an error.
 */
fun rememberUriPermission(context: Context, uri: Uri) {
    val resolver = context.contentResolver
    val readWrite = runCatching {
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }
    if (readWrite.isSuccess) return
    runCatching {
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
}

/**
 * Copies a picked document into the app cache, whatever its format. The open
 * flow looks at the bytes afterwards, so no name or MIME type has to be
 * trusted here.
 */
suspend fun cacheDocumentFromUri(
    context: Context,
    uri: Uri,
    fallbackName: String? = null
): CachedPdf = withContext(Dispatchers.IO) {
    val name = queryDisplayName(context, uri) ?: fallbackName
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IOException("Unable to open $uri")
    copyPdfToCache(input, name, context.cacheDir)
}

/**
 * Copies the document and remembers it in the recent list. The URI permission
 * is made persistable when the provider supports it, so Recent can reopen the
 * file after the activity's transient grant is gone.
 */
suspend fun cachePdfAndRemember(
    context: Context,
    uri: Uri,
    fallbackName: String? = null,
    kind: String = RecentFile.KIND_PDF
): CachedPdf {
    val cached = cacheDocumentFromUri(context, uri, fallbackName)
    rememberUriPermission(context, uri)
    RecentStore(context).add(uri.toString(), cached.displayName, kind)
    return cached
}
