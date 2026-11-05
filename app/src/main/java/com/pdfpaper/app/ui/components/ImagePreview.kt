package com.pdfpaper.app.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes a content/file URI to an [ImageBitmap] off the main thread. Null
 * while loading and on unreadable input; callers show a placeholder instead.
 */
@Composable
fun rememberUriImageBitmap(uri: Uri?): ImageBitmap? {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = if (uri == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val stream = when (uri.scheme) {
                        "file" -> uri.path?.let { java.io.FileInputStream(it) }
                        else -> resolver.openInputStream(uri)
                    }
                    stream?.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    return bitmap
}

/** Decodes encoded JPEG/PNG bytes to an [ImageBitmap] off the main thread. */
@Composable
fun rememberEncodedImageBitmap(bytes: ByteArray?): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, bytes) {
        value = if (bytes == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    return bitmap
}
