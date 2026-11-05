package com.pdfpaper.app.office

import com.pdfpaper.convert.DocxToPdf
import com.pdfpaper.convert.DocxToPdfException
import com.pdfpaper.convert.PptxToPdf
import com.pdfpaper.convert.PptxToPdfException
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The PDF produced from an office file, plus every loss the converter reported. */
data class OfficeConversion(val pdf: ByteArray, val warnings: List<String>)

/** Why an office file could not be turned into a PDF. */
sealed class OfficeConversionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /** The file is not a Word or PowerPoint document the converter supports. */
    class Unsupported(message: String) : OfficeConversionException(message)

    /** The right kind of document, but damaged or unreadable. */
    class Corrupt(message: String, cause: Throwable? = null) :
        OfficeConversionException(message, cause)

    /** Larger than the app will load into memory. */
    class TooLarge(message: String) : OfficeConversionException(message)
}

/**
 * Turns a .docx or .pptx into PDF bytes for the viewer.
 *
 * The conversion runs on its own daemon thread with a hard timeout, and the
 * API never throws: callers get a [Result] whose failure is an
 * [OfficeConversionException] they can show as-is.
 */
object OfficeToPdf {

    /** Documents above this size are refused instead of loaded into memory. */
    const val MAX_BYTES: Long = 64L * 1024 * 1024

    /** A conversion that takes longer than this is abandoned. */
    const val TIMEOUT_MS: Long = 30_000

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "office-to-pdf").apply { isDaemon = true }
    }

    fun convert(
        file: File,
        kind: OfficeKind,
        maxBytes: Long = MAX_BYTES,
        timeoutMs: Long = TIMEOUT_MS
    ): Result<OfficeConversion> = try {
        Result.success(convertOrThrow(file, kind, maxBytes, timeoutMs))
    } catch (e: OfficeConversionException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(OfficeConversionException.Corrupt(messageOf(e), e))
    }

    private fun convertOrThrow(
        file: File,
        kind: OfficeKind,
        maxBytes: Long,
        timeoutMs: Long
    ): OfficeConversion {
        if (!file.isFile) {
            throw OfficeConversionException.Corrupt("The document is no longer available")
        }
        if (file.length() > maxBytes) {
            throw OfficeConversionException.TooLarge(
                "This ${kind.label.lowercase()} is ${megabytes(file.length())} MB; " +
                    "the limit is ${megabytes(maxBytes)} MB."
            )
        }

        val future = worker.submit<OfficeConversion> { convertNow(file, kind) }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            future.cancel(true)
            throw OfficeConversionException.Corrupt(
                "This document did not finish converting in " +
                    "${timeoutMs / 1000} seconds and was stopped. " +
                    "Try a smaller document or one with fewer pages."
            )
        } catch (failed: ExecutionException) {
            throw asConversionError(failed.cause)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw OfficeConversionException.Corrupt("The conversion was interrupted")
        }
    }

    private fun convertNow(file: File, kind: OfficeKind): OfficeConversion {
        val bytes = file.readBytes()
        return when (kind) {
            OfficeKind.WORD -> {
                val result = DocxToPdf.convert(bytes, sourceName = file.name)
                OfficeConversion(
                    result.bytes,
                    warnings(result.report.notes, result.report.replacedCharacters)
                )
            }
            OfficeKind.POWERPOINT -> {
                val result = PptxToPdf.convert(bytes, sourceName = file.name)
                OfficeConversion(
                    result.bytes,
                    warnings(result.report.notes, result.report.replacedCharacters)
                )
            }
        }
    }

    private fun warnings(notes: List<String>, replacedCharacters: Int): List<String> {
        if (replacedCharacters <= 0) return notes
        return notes + "$replacedCharacters character(s) have no equivalent in the PDF " +
            "fonts and are drawn as '?'"
    }

    private fun asConversionError(cause: Throwable?): OfficeConversionException = when (cause) {
        is OfficeConversionException -> cause
        is DocxToPdfException ->
            OfficeConversionException.Corrupt(cause.message ?: "not a readable .docx document", cause)
        is PptxToPdfException ->
            OfficeConversionException.Corrupt(cause.message ?: "not a readable .pptx presentation", cause)
        else -> OfficeConversionException.Corrupt(messageOf(cause), cause)
    }

    private fun messageOf(error: Throwable?): String =
        error?.message?.takeIf { it.isNotBlank() } ?: "The document could not be converted"

    private fun megabytes(bytes: Long): Long = (bytes + 1024 * 1024 - 1) / (1024 * 1024)
}
