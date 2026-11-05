package com.pdfpaper.app.ui.mark

import android.content.Context
import com.pdfpaper.engine.edit.StandardFont
import com.pdfpaper.engine.mark.MarkFont

/**
 * Chooses the font for the text the user adds to a document.
 *
 * Latin text is written with one of the standard 14 fonts: every reader has
 * them, so nothing has to be embedded and the file stays small. Text in any
 * other script (Devanagari, for instance) is written with the bundled
 * Noto Sans Devanagari font, which is embedded into the file as a Type0 font
 * so the text travels with the document (SIL Open Font License 1.1; the
 * licence text sits next to the font in the assets).
 */
object MarkFontProvider {

    /** The bundled font, as it sits in the app's assets. */
    const val ASSET_PATH = "fonts/NotoSansDevanagari-Regular.ttf"

    /** The name the embedded font gets in the PDF. */
    const val ASSET_BASE_NAME = "NotoSansDevanagari"

    /** The app the font is read from; set when the font is first needed. */
    @Volatile
    private var appContext: Context? = null

    /** The font file, read once and kept for the life of the process. */
    private val fontFile: Lazy<ByteArray?> = lazy {
        runCatching {
            appContext?.assets?.open(ASSET_PATH)?.use { it.readBytes() }
        }.getOrNull()
    }

    /**
     * The font for [text]: the standard font for Latin text, the bundled
     * font for everything else. When the bundled font cannot be read the
     * standard font is used, so the text is still written.
     */
    fun forText(
        context: Context,
        text: String,
        standard: StandardFont = StandardFont.HELVETICA
    ): MarkFont = if (isLatinText(text)) {
        MarkFont.Standard(standard)
    } else {
        embedded(context) ?: MarkFont.Standard(standard)
    }

    /** The bundled font, embedded into the PDF; null when it cannot be read. */
    fun embedded(context: Context): MarkFont.Embedded? {
        val bytes = fontBytes(context) ?: return null
        return MarkFont.Embedded(bytes, ASSET_BASE_NAME)
    }

    /** The bytes of the bundled font; read once and then kept. */
    fun fontBytes(context: Context): ByteArray? {
        if (appContext == null) appContext = context.applicationContext
        return fontFile.value
    }
}
