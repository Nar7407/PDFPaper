package com.pdfpaper.app.data

import android.content.Intent
import android.net.Uri
import android.os.Build

/** Which external action started PDFPaper. */
enum class IncomingAction { VIEW, SEND, SEND_MULTIPLE }

/**
 * One untrusted request from another app (task T47).
 *
 * [uris] is a copy of the incoming URIs; [error] is set when the intent was
 * malformed or asked for something PDFPaper cannot do. Nothing here reads a
 * file - the open flow or the share chooser inspects the bytes later.
 */
data class IncomingRequest(
    val id: Long,
    val action: IncomingAction,
    val uris: List<Uri> = emptyList(),
    val mimeType: String? = null,
    val error: String? = null
)

object IncomingIntents {

    const val MAX_FILES: Int = 50

    /** Returns null for an ordinary launcher intent. */
    fun parse(intent: Intent?): IncomingRequest? {
        if (intent == null) return null
        val action = when (intent.action) {
            Intent.ACTION_VIEW -> IncomingAction.VIEW
            Intent.ACTION_SEND -> IncomingAction.SEND
            Intent.ACTION_SEND_MULTIPLE -> IncomingAction.SEND_MULTIPLE
            else -> return null
        }
        val raw = when (action) {
            IncomingAction.VIEW -> listOfNotNull(intent.data)
            IncomingAction.SEND -> streamUris(intent)
            IncomingAction.SEND_MULTIPLE -> multipleStreamUris(intent)
        }
        val uris = raw.asSequence()
            .filter { it.scheme.equals("content", true) || it.scheme.equals("file", true) }
            .distinctBy { it.toString() }
            .toList()
        val error = when {
            raw.isEmpty() -> "No file was shared with PDFPaper."
            uris.isEmpty() -> "This share contains no local file. PDFPaper has no network permission."
            uris.size > MAX_FILES -> "Too many files were shared. PDFPaper can open at most $MAX_FILES at once."
            else -> null
        }
        return IncomingRequest(
            id = System.nanoTime(),
            action = action,
            uris = uris.take(MAX_FILES),
            mimeType = intent.type,
            error = error
        )
    }

    @Suppress("DEPRECATION")
    private fun streamUris(intent: Intent): List<Uri> {
        val direct = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        if (direct != null) return listOf(direct)
        return clipUris(intent)
    }

    @Suppress("DEPRECATION")
    private fun multipleStreamUris(intent: Intent): List<Uri> {
        val list = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }
        if (!list.isNullOrEmpty()) return list
        // Some senders use SEND_MULTIPLE but put a single URI in the extra.
        val single = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        if (single != null) return listOf(single)
        return clipUris(intent)
    }

    private fun clipUris(intent: Intent): List<Uri> {
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { index ->
            clip.getItemAt(index).uri
        }
    }
}
