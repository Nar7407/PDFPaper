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

/** Copies a SAF document into the app cache, ready for the engine's file API. */
suspend fun cachePdfFromUri(
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
    fallbackName: String? = null
): CachedPdf {
    val cached = cachePdfFromUri(context, uri, fallbackName)
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
    RecentStore(context).add(uri.toString(), cached.displayName)
    return cached
}
