package com.pdfpaper.app.ui.viewer

import com.pdfpaper.engine.ocr.OcrPageStatus
import com.pdfpaper.engine.ocr.SearchableResult

/**
 * The line shown when an OCR run added no text (T25, T33b).
 *
 * A provider failure and a picture the recognizer could not read are different
 * problems: the first is the device (ML Kit's optional text module is not
 * downloaded, so no model ever ran), the second is the scan. Reporting both as
 * "no searchable words were recognized" sent the T33 acceptance run looking for
 * a sharper scan while the emulator had logged "Waiting for the text optional
 * module to be downloaded" — so the two cases are told apart here.
 *
 * Pure: the caller passes the engine's report, the clock and the device are not
 * involved, so this is an ordinary JVM unit test.
 */
fun ocrNoTextSummary(result: SearchableResult): String {
    val failed = result.pages.count { it.status == OcrPageStatus.PROVIDER_FAILED }
    val skipped = result.pages.count { it.status == OcrPageStatus.SKIPPED_HAS_TEXT }
    val noImage = result.pages.count { it.status == OcrPageStatus.NO_IMAGE }
    return when {
        failed > 0 ->
            "OCR could not run: the text-recognition model is unavailable on this " +
                "device. Check that Google Play services can download it, then try again."
        skipped > 0 -> "No new text was added: $skipped page" +
            (if (skipped == 1) " already has" else "s already have") +
            " searchable text. Choose \"This page\" to run OCR anyway."
        noImage > 0 -> "No new text was added: no page images were found to read."
        else -> "No searchable words were recognized. " +
            "Try a sharper scan or a different language."
    }
}
