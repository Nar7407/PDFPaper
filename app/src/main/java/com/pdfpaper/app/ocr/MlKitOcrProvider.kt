package com.pdfpaper.app.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pdfpaper.engine.ocr.OcrBounds
import com.pdfpaper.engine.ocr.OcrImage
import com.pdfpaper.engine.ocr.OcrProvider
import com.pdfpaper.engine.ocr.OcrWord
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * On-device OCR through ML Kit Text Recognition v2 (task UI-2, chosen by the
 * owner). Latin maps to English and Devanagari to Hindi. The unbundled models
 * are downloaded by Google Play services on first use; inference stays local.
 *
 * The engine calls [recognize] from one background thread. [cancel] is safe to
 * call from the UI thread: the next page stops immediately, and the engine's
 * per-page exception handling copies that page unchanged.
 */
class MlKitOcrProvider : OcrProvider, AutoCloseable {

    private val recognizers = HashMap<String, TextRecognizer>()

    @Volatile
    var cancelled: Boolean = false
        private set

    /** Number of page images handed to ML Kit so far (for the progress bar). */
    @Volatile
    var processed: Int = 0
        private set

    var onProcessed: ((Int) -> Unit)? = null

    fun cancel() {
        cancelled = true
    }

    override fun recognize(image: OcrImage, language: String): List<OcrWord> {
        if (cancelled) throw CancellationException("OCR cancelled")
        val bitmap = decode(image)
        if (bitmap == null) {
            countProcessed()
            return emptyList()
        }
        try {
            val recognizer = recognizerFor(language)
            val input = InputImage.fromBitmap(bitmap, 0)
            val text = try {
                Tasks.await(recognizer.process(input), TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("OCR cancelled")
            }
            val words = ArrayList<OcrWord>()
            for (block in text.textBlocks) {
                for (line in block.lines) {
                    for (element in line.elements) {
                        val box = element.boundingBox ?: continue
                        val word = element.text.trim()
                        if (word.isEmpty() || box.width() <= 0 || box.height() <= 0) continue
                        words += OcrWord(
                            text = word,
                            bounds = OcrBounds(
                                left = box.left.toDouble(),
                                top = box.top.toDouble(),
                                right = box.right.toDouble(),
                                bottom = box.bottom.toDouble()
                            ),
                            confidence = element.confidence.toDouble().coerceIn(0.0, 1.0)
                        )
                    }
                }
            }
            countProcessed()
            return words
        } finally {
            bitmap.recycle()
        }
    }

    /** Recognizes [language] ("eng"/"en" or "hin"/"hi"). */
    private fun recognizerFor(language: String): TextRecognizer {
        val hindi = language.split('+', '-', '_')
            .any { it.trim().equals("hin", true) || it.trim().equals("hi", true) }
        val key = if (hindi) "hin" else "eng"
        return synchronized(recognizers) {
            recognizers.getOrPut(key) {
                if (hindi) {
                    TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                } else {
                    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                }
            }
        }
    }

    private fun countProcessed() {
        processed += 1
        onProcessed?.invoke(processed)
    }

    /**
     * The engine normally hands over decoded RGBA, but a JPEG/JPX image the
     * pure-JVM engine could not decode arrives as encoded bytes.
     */
    private fun decode(image: OcrImage): Bitmap? {
        val pixels = image.pixels
        if (pixels != null) {
            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0 || pixels.size < width * height * 4) return null
            val argb = IntArray(width * height)
            for (i in argb.indices) {
                val o = i * 4
                val r = pixels[o].toInt() and 0xFF
                val g = pixels[o + 1].toInt() and 0xFF
                val b = pixels[o + 2].toInt() and 0xFF
                val a = pixels[o + 3].toInt() and 0xFF
                argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            return runCatching {
                Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
            }.getOrNull()
        }
        val encoded = image.encodedData ?: return null
        return runCatching {
            BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        }.getOrNull()
    }

    override fun close() {
        synchronized(recognizers) {
            recognizers.values.forEach { runCatching { it.close() } }
            recognizers.clear()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60L
    }
}
