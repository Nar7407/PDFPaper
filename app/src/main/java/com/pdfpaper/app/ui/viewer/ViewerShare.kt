package com.pdfpaper.app.ui.viewer

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Sends the open PDF to another app. The document lives in the app cache, so it
 * is exposed through the app's [FileProvider] with a read grant rather than a
 * raw `file://` URI, which newer Android versions reject.
 *
 * Best-effort: if the file is missing or the provider is not configured, nothing
 * happens instead of crashing.
 */
internal fun sharePdf(context: Context, path: String, displayName: String?) {
    val file = File(path)
    if (!file.exists()) return
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return

    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, displayName ?: "Share PDF").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}

/**
 * Hands the open PDF to another app to *view* rather than share. The file
 * lives in the app cache, so it is exposed through the [FileProvider] with a
 * read grant. Best-effort: a missing file or an unconfigured provider does
 * nothing instead of crashing.
 */
internal fun openPdfInAnotherApp(context: Context, path: String, displayName: String?) {
    val file = File(path)
    if (!file.exists()) return
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return

    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(view, displayName ?: "Open PDF").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}
