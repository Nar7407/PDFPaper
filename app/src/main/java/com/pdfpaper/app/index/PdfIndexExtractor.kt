package com.pdfpaper.app.index

import android.content.Context
import com.pdfpaper.app.annot.AnnotationFingerprint
import com.pdfpaper.app.annot.AnnotationKind
import com.pdfpaper.app.annot.AnnotationColors
import com.pdfpaper.app.annot.ExtractedAnnotation
import com.pdfpaper.app.annot.annotationKindOf
import com.pdfpaper.app.annot.formatBounds
import com.pdfpaper.app.data.DocumentAccess
import com.pdfpaper.engine.annot.PdfAnnotation
import com.pdfpaper.engine.annot.annotations
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.security.PdfPasswordException
import com.pdfpaper.engine.text.PageLayout
import com.pdfpaper.engine.text.TextRect
import com.pdfpaper.engine.text.layout

/**
 * Reads one PDF for the index (T50, T56). It uses the engine's reading-order
 * text extraction, which already includes an invisible OCR layer, and collects
 * the annotations and bookmarks the hub lists. The document is closed before
 * the next file starts, and one file's stored text is capped so a pathological
 * page cannot grow the heap without bound.
 */
class PdfIndexExtractor(private val context: Context) {

    sealed interface Outcome {
        data class Text(
            val pageCount: Int,
            val pagesWithoutText: Int,
            val pages: List<PageText>,
            val annotations: List<ExtractedAnnotation>,
            val fingerprint: String
        ) : Outcome

        data object Locked : Outcome
        data object TooLarge : Outcome
        data object TimedOut : Outcome
        data object Cancelled : Outcome
        data class Failed(val message: String) : Outcome
    }

    data class PageText(val pageIndex: Int, val text: String)

    fun extract(
        documentUri: String,
        reportedSize: Long,
        deadlineNanos: Long = Long.MAX_VALUE,
        cancelled: () -> Boolean = { false }
    ): Outcome {
        if (reportedSize > MAX_FILE_BYTES) return Outcome.TooLarge
        if (cancelled()) return Outcome.Cancelled
        val opened = DocumentAccess.openSource(context, documentUri, MAX_FILE_BYTES)
            .getOrElse { error ->
                return if (error.message?.contains("larger than", ignoreCase = true) == true) {
                    Outcome.TooLarge
                } else {
                    Outcome.Failed(error.message ?: "The document could not be opened")
                }
            }
        return opened.use outer@{ source ->
            // The fingerprint decides which document the annotations belong to,
            // so it is computed from the bytes before the parser touches them.
            val fingerprint = runCatching { AnnotationFingerprint.of(source) }
                .getOrElse { "size:${source.size}" }
            val openedDocument = PdfDocument.open(source)
            val document = openedDocument.getOrElse { error ->
                return@outer if (error is PdfPasswordException) {
                    Outcome.Locked
                } else {
                    Outcome.Failed(error.message ?: "The PDF could not be read")
                }
            }
            document.use { open ->
                if (open.isEncrypted) {
                    Outcome.Locked
                } else {
                    extractPages(open, fingerprint, deadlineNanos, cancelled)
                }
            }
        }
    }

    private fun extractPages(
        document: PdfDocument,
        fingerprint: String,
        deadlineNanos: Long,
        cancelled: () -> Boolean
    ): Outcome {
        val pages = document.pages()
        if (pages.isEmpty()) return Outcome.Failed("The document has no pages")
        val texts = ArrayList<PageText>()
        val annotations = ArrayList<ExtractedAnnotation>()
        var pagesWithoutText = 0
        var storedChars = 0
        val fallbackTime = System.currentTimeMillis()

        for (page in pages) {
            if (cancelled()) return Outcome.Cancelled
            if (System.nanoTime() > deadlineNanos) return Outcome.TimedOut
            val layout = runCatching { page.layout() }.getOrNull()
            val text = IndexTextPolicy.normalize(layout?.text.orEmpty())
            if (!IndexTextPolicy.hasText(text)) {
                pagesWithoutText++
            } else if (storedChars < MAX_FILE_TEXT_CHARS) {
                // Always count text pages, but stop storing once one file's cap is
                // reached. The status stays INDEXED; the cap only bounds memory.
                val room = MAX_FILE_TEXT_CHARS - storedChars
                val kept = if (text.length > room) text.take(room) else text
                if (kept.isNotEmpty()) {
                    texts += PageText(page.index, kept)
                    storedChars += kept.length
                }
            }
            if (annotations.size < MAX_FILE_ANNOTATIONS) {
                runCatching { page.annotations() }.getOrDefault(emptyList()).forEach { annotation ->
                    if (annotations.size >= MAX_FILE_ANNOTATIONS) return@forEach
                    extractAnnotation(annotation, page.index, layout, fallbackTime)
                        ?.let { annotations += it }
                }
            }
        }
        return Outcome.Text(
            pageCount = pages.size,
            pagesWithoutText = pagesWithoutText,
            pages = texts,
            annotations = annotations,
            fingerprint = fingerprint
        )
    }

    /**
     * One annotation as the hub sees it, or null when it is not a comment the
     * user wrote (a link, a form widget, a popup window or a hidden review-state
     * helper from T22).
     */
    private fun extractAnnotation(
        annotation: PdfAnnotation,
        pageIndex: Int,
        layout: PageLayout?,
        fallbackTime: Long
    ): ExtractedAnnotation? {
        val kind = annotationKindOf(annotation) ?: return null
        if (annotation.hidden) return null
        if (annotation.state != null) return null
        val quads = annotation.quadBounds()
        val highlighted = quads.isNotEmpty() && kind != AnnotationKind.NOTE &&
            kind != AnnotationKind.INK && kind != AnnotationKind.STAMP &&
            kind != AnnotationKind.FREE_TEXT
        val quoted = if (highlighted && layout != null) {
            runCatching {
                layout.textInRects(
                    quads.map { TextRect(it.left, it.bottom, it.right, it.top) }
                )
            }
                .getOrDefault("")
                .trim()
                .take(MAX_QUOTED_CHARS)
                .ifBlank { null }
        } else {
            null
        }
        val box = if (quads.isNotEmpty()) PdfRect.union(quads) else annotation.rect
        val created = annotation.createdAtMillis
            ?: annotation.modifiedAtMillis
            ?: fallbackTime
        return ExtractedAnnotation(
            kind = kind,
            pageIndex = pageIndex,
            color = AnnotationColors.of(annotation.color),
            author = annotation.title?.take(MAX_AUTHOR_CHARS),
            createdAt = created,
            modifiedAt = annotation.modifiedAtMillis,
            quotedText = quoted,
            noteText = annotation.contents?.trim()?.take(MAX_NOTE_CHARS)?.ifBlank { null },
            bounds = formatBounds(box),
            objectNumber = annotation.ref?.objNum
        )
    }

    companion object {
        /** Per-file cap for both the reported size and a non-seekable copy. */
        const val MAX_FILE_BYTES: Long = 128L * 1024L * 1024L

        /** Per-file cap on the text held before the transaction writes it. */
        const val MAX_FILE_TEXT_CHARS: Int = 4_000_000

        /** One file may take this long before the worker gives up on it. */
        const val MAX_FILE_MILLIS: Long = 60_000L

        /** A document with more annotations than this keeps the first ones. */
        const val MAX_FILE_ANNOTATIONS: Int = 5000

        const val MAX_QUOTED_CHARS: Int = 1000
        const val MAX_NOTE_CHARS: Int = 4000
        const val MAX_AUTHOR_CHARS: Int = 120
    }
}
