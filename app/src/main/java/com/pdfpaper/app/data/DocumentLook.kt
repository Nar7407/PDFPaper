package com.pdfpaper.app.data

import android.content.Context
import com.pdfpaper.app.annot.AnnotationFingerprint
import com.pdfpaper.engine.eink.EinkProfile
import com.pdfpaper.engine.render.ColorTransform
import com.pdfpaper.engine.render.PageFilter
import com.pdfpaper.engine.render.PageTheme
import com.pdfpaper.engine.render.PageThemeMode

/**
 * T62: one document's look, remembered by the document's own fingerprint.
 *
 * The look is app data. It is never written into the PDF, so a file that is
 * opened again in another reader shows exactly what its author drew.
 */
data class ResolvedLook(
    val transform: ColorTransform,
    val theme: PageTheme,
    /** True when this document has a look of its own (not just the default). */
    val fromDocument: Boolean
) {
    /** The per-document override chip state of T32b: null when it inherits. */
    val documentMode: PageThemeMode? get() = if (fromDocument) theme.mode else null
}

/**
 * How a document's look is chosen (T62). Pure, so the order is unit-tested:
 *
 *  1. a look saved for this document;
 *  2. the T32b per-document theme override (a chip from before T62), mapped to
 *     the equivalent filter;
 *  3. the global default for new documents;
 *  4. the T32 global page theme, which is what "no look" has always meant.
 *
 * "Follow the system dark theme" only ever *removes* the dark look from a
 * document that has no look of its own: a reader who picked Sepia for a book
 * keeps it when the system turns light.
 */
object LookResolution {

    fun resolve(
        baseTheme: PageTheme,
        documentLook: ColorTransform? = null,
        legacyMode: PageThemeMode? = null,
        defaultLook: ColorTransform? = null,
        followSystem: Boolean = false,
        systemDark: Boolean = true
    ): ResolvedLook {
        val fromDocument = documentLook != null || legacyMode != null
        val explicit = documentLook ?: legacyMode?.let { ColorTransform(filter = PageFilter.of(it)) }
        val chosen = explicit ?: defaultLook ?: ColorTransform(filter = PageFilter.of(baseTheme.mode))
        val forcedLight = followSystem && !systemDark && !fromDocument
        val transform = if (forcedLight) chosen.copy(filter = PageFilter.NORMAL) else chosen
        val mode = when {
            fromDocument || defaultLook != null -> transform.filter.themeMode
            forcedLight -> PageThemeMode.OFF
            else -> baseTheme.mode
        }
        val theme = transform.pageTheme(baseTheme.copy(mode = mode))
        return ResolvedLook(transform = transform, theme = theme, fromDocument = fromDocument)
    }
}

/**
 * The content fingerprint a look is keyed by (T62). It is the same value the
 * annotation index uses (SHA-1 over the size, the first 8 KB and the last
 * 8 KB), so the two views of "the same document" can never disagree. Returns
 * null when the file cannot be read; a document without a fingerprint simply
 * follows the default look.
 */
object DocumentFingerprints {

    fun of(context: Context, location: String): String? = runCatching {
        DocumentAccess.openSource(context, location).getOrThrow()
            .use { AnnotationFingerprint.of(it) }
    }.getOrNull()
}

/**
 * Storage format for one look, pure JVM so it is unit-testable. Fields are
 * positional and separated by a `|`; a record from another version decodes to
 * null rather than to a wrong look.
 */
object DocumentLookCodec {

    private const val VERSION = 1
    private const val SEPARATOR = '|'
    private const val FIELD_COUNT = 8

    fun encode(transform: ColorTransform): String {
        val look = transform.normalized()
        return listOf(
            VERSION.toString(),
            look.filter.name,
            look.warmth.toString(),
            rgbOf(look.tint).toString(),
            look.brightness.toString(),
            look.contrast.toString(),
            look.gamma.toString(),
            // Reserved for a future field, kept so the record shape is stable.
            "0"
        ).joinToString(SEPARATOR.toString())
    }

    fun decode(raw: String?): ColorTransform? {
        if (raw.isNullOrBlank()) return null
        val fields = raw.split(SEPARATOR, limit = FIELD_COUNT)
        if (fields.size != FIELD_COUNT) return null
        if (fields[0].toIntOrNull() != VERSION) return null
        val filter = PageFilter.fromName(fields[1])
        val warmth = fields[2].toDoubleOrNull() ?: return null
        val tint = fields[3].toIntOrNull()?.let { pdfColorOf(it) } ?: return null
        val brightness = fields[4].toDoubleOrNull() ?: return null
        val contrast = fields[5].toDoubleOrNull() ?: return null
        val gamma = fields[6].toDoubleOrNull() ?: return null
        return ColorTransform(filter, warmth, tint, brightness, contrast, gamma).normalized()
    }
}

/**
 * Reads and writes one look per document (T62). The key is the document's
 * content fingerprint (the same SHA-1 of size, head and tail that the
 * annotation index uses), so a file that moved or was re-shared keeps its look,
 * while a different file with the same name does not inherit it.
 *
 * It lives in the T38 session preferences file: the look is part of "how this
 * document was last read", like the page, the zoom and the mode.
 */
class DocumentLookStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(fingerprint: String?): ColorTransform? {
        if (fingerprint.isNullOrBlank()) return null
        return DocumentLookCodec.decode(preferences.getString(key(fingerprint), null))
    }

    fun save(fingerprint: String?, transform: ColorTransform) {
        if (fingerprint.isNullOrBlank()) return
        preferences.edit()
            .putString(key(fingerprint), DocumentLookCodec.encode(transform))
            .apply()
    }

    /** Forgets the look (and the hint dismissal) of one document. */
    fun forget(fingerprint: String?) {
        if (fingerprint.isNullOrBlank()) return
        preferences.edit()
            .remove(key(fingerprint))
            .remove(hintKey(fingerprint))
            .apply()
    }

    // ---- E-Ink profile override (T63) ------------------------------------

    /**
     * The E-Ink profile this document asked for, or null to follow the device.
     * [EinkProfile.OFF] is a real choice ("this drawing stays as authored"), so
     * it is stored explicitly instead of being confused with "inherit".
     */
    fun loadEink(fingerprint: String?): EinkProfile? {
        if (fingerprint.isNullOrBlank()) return null
        val raw = preferences.getString(einkKey(fingerprint), null) ?: return null
        return EinkProfile.values().firstOrNull { it.name == raw } ?: EinkProfile.OFF
    }

    fun saveEink(fingerprint: String?, profile: EinkProfile) {
        if (fingerprint.isNullOrBlank()) return
        preferences.edit().putString(einkKey(fingerprint), profile.name).apply()
    }

    fun forgetEink(fingerprint: String?) {
        if (fingerprint.isNullOrBlank()) return
        preferences.edit().remove(einkKey(fingerprint)).apply()
    }

    /** True when the reader said "not now" to the look hint of this document. */
    fun hintDismissed(fingerprint: String?): Boolean {
        if (fingerprint.isNullOrBlank()) return true
        return preferences.getBoolean(hintKey(fingerprint), false)
    }

    fun setHintDismissed(fingerprint: String?, dismissed: Boolean) {
        if (fingerprint.isNullOrBlank()) return
        preferences.edit().putBoolean(hintKey(fingerprint), dismissed).apply()
    }

    private fun key(fingerprint: String): String = KEY_LOOK + fingerprint

    private fun hintKey(fingerprint: String): String = KEY_HINT + fingerprint

    private fun einkKey(fingerprint: String): String = KEY_EINK + fingerprint

    private companion object {
        const val PREFERENCES = "pdfpaper_reading"
        const val KEY_LOOK = "look."
        const val KEY_HINT = "look.hint."
        const val KEY_EINK = "eink."
    }
}
