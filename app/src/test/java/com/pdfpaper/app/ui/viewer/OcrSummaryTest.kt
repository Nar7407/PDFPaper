package com.pdfpaper.app.ui.viewer

import com.pdfpaper.engine.ocr.OcrPageReport
import com.pdfpaper.engine.ocr.OcrPageStatus
import com.pdfpaper.engine.ocr.SearchableResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T33b: the line shown when an OCR run adds no text. The provider failure and
 * the "recognizer read nothing" cases must not share one message: the first is
 * the device's model, the second is the scan (found during T33 acceptance, when
 * an emulator without the ML Kit text module said "try a sharper scan").
 */
class OcrSummaryTest {

    private fun result(vararg statuses: OcrPageStatus): SearchableResult =
        SearchableResult(
            ByteArray(0),
            statuses.mapIndexed { index, status -> OcrPageReport(index, status) }
        )

    @Test
    fun aProviderFailureBlamesTheModelNotTheScan() {
        val text = ocrNoTextSummary(result(OcrPageStatus.PROVIDER_FAILED))
        assertTrue(text.startsWith("OCR could not run"))
        assertTrue(text.contains("model is unavailable"))
        assertTrue("a failed run must not send the reader to the scan", !text.contains("sharper scan"))
    }

    @Test
    fun noWordsBlamesTheScan() {
        val text = ocrNoTextSummary(result(OcrPageStatus.NO_WORDS))
        assertTrue(text.contains("No searchable words were recognized"))
        assertTrue(text.contains("sharper scan"))
    }

    @Test
    fun aPageThatAlreadyHasTextSaysSo() {
        assertEquals(
            "No new text was added: 1 page already has searchable text. " +
                "Choose \"This page\" to run OCR anyway.",
            ocrNoTextSummary(result(OcrPageStatus.SKIPPED_HAS_TEXT))
        )
        assertTrue(
            ocrNoTextSummary(
                result(OcrPageStatus.SKIPPED_HAS_TEXT, OcrPageStatus.SKIPPED_HAS_TEXT)
            ).contains("2 pages already have")
        )
    }

    @Test
    fun aPageWithNoImageSaysSo() {
        assertEquals(
            "No new text was added: no page images were found to read.",
            ocrNoTextSummary(result(OcrPageStatus.NO_IMAGE))
        )
    }

    @Test
    fun aFailureOutranksTheOtherReasons() {
        // One page of a multi-page run could not reach the model at all; that is
        // the reason the reader can act on, not the page that had no picture.
        val text = ocrNoTextSummary(
            result(OcrPageStatus.NO_IMAGE, OcrPageStatus.PROVIDER_FAILED)
        )
        assertTrue(text.startsWith("OCR could not run"))
    }
}
