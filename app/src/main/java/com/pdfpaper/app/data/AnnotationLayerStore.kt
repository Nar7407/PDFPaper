package com.pdfpaper.app.data

import android.content.Context
import com.pdfpaper.app.ui.viewer.AnnotationLayersState

/**
 * Versioned, privacy-preserving codec for serializing [AnnotationLayersState] (T68).
 * Only saves group and color selections; never saves authors, texts, or note contents.
 */
object AnnotationLayerCodec {
    const val VERSION = 1

    fun encode(state: AnnotationLayersState): String = TODO("T68")
    fun decode(raw: String?): AnnotationLayersState = TODO("T68")
}

/**
 * Fingerprint-keyed store for remembering layer choices per document (T68 / T38).
 */
class AnnotationLayerStore(context: Context) {

    fun load(fingerprint: String?): AnnotationLayersState = TODO("T68")
    fun save(fingerprint: String?, state: AnnotationLayersState): Unit = TODO("T68")
    fun forget(fingerprint: String?): Unit = TODO("T68")
}
