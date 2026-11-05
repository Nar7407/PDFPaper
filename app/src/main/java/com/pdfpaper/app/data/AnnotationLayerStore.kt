package com.pdfpaper.app.data

import android.content.Context
import com.pdfpaper.app.ui.viewer.AnnotationLayerGroup
import com.pdfpaper.app.ui.viewer.AnnotationLayersState

/**
 * Versioned, privacy-preserving codec for serializing [AnnotationLayersState] (T68).
 * Only saves group and color selections; never saves authors, texts, or note contents.
 */
object AnnotationLayerCodec {
    const val VERSION = 1

    private const val SEPARATOR = '|'

    fun encode(state: AnnotationLayersState): String = listOf(
        VERSION.toString(),
        state.enabledGroups.joinToString(",") { it.name },
        state.enabledColors.joinToString(",") { hexOf(it) },
        state.disabledColors.joinToString(",") { hexOf(it) }
    ).joinToString(SEPARATOR.toString())

    fun decode(raw: String?): AnnotationLayersState {
        if (raw.isNullOrBlank()) return AnnotationLayersState.SHOW_ALL
        // limit keeps trailing empty fields ("1||" is hide-all, not garbage).
        val fields = raw.split(SEPARATOR, limit = 4)
        if (fields.size < 3 || fields[0].toIntOrNull() != VERSION) {
            return AnnotationLayersState.SHOW_ALL
        }
        val groups = fields[1].split(',').mapNotNull { token ->
            AnnotationLayerGroup.values().firstOrNull { it.name == token }
        }.toSet()
        return AnnotationLayersState(
            enabledGroups = groups,
            enabledColors = fields[2].colorsOf(),
            disabledColors = fields.getOrNull(3)?.colorsOf() ?: emptySet()
        )
    }

    private fun hexOf(rgb: Int): String =
        String.format(java.util.Locale.ROOT, "0x%06X", rgb and 0xFFFFFF)

    private fun String.colorsOf(): Set<Int> = split(',').mapNotNull { token ->
        token.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.and(0xFFFFFF)
    }.toSet()
}

/**
 * Fingerprint-keyed store for remembering layer choices per document (T68 / T38).
 */
class AnnotationLayerStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(fingerprint: String?): AnnotationLayersState =
        AnnotationLayerCodec.decode(fingerprint?.let { prefs.getString(KEY_PREFIX + it, null) })

    fun save(fingerprint: String?, state: AnnotationLayersState) {
        if (fingerprint.isNullOrBlank()) return
        prefs.edit().putString(KEY_PREFIX + fingerprint, AnnotationLayerCodec.encode(state)).apply()
    }

    fun forget(fingerprint: String?) {
        if (fingerprint.isNullOrBlank()) return
        prefs.edit().remove(KEY_PREFIX + fingerprint).apply()
    }

    private companion object {
        const val PREFS = "pdfpaper_reading"
        const val KEY_PREFIX = "layers."
    }
}
