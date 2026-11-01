package com.pdfpaper.app.data

import android.content.Context
import com.pdfpaper.app.ui.viewer.speech.SpeechVoiceKey
import com.pdfpaper.app.ui.search.DEFAULT_REGEX_FLAGS
import com.pdfpaper.engine.text.RegexFlag

/**
 * Small, app-private settings store (task UI-2). The author name is written
 * into comments, replies and dynamic stamps, so it must survive restarts but
 * never leaves the device.
 */
/** What a tap on an internal link or reference does (T61). */
enum class LinkTapBehavior {
    /** Show the floating preview card first (default). */
    PREVIEW_FIRST,

    /** Jump to the target immediately. */
    JUMP_DIRECTLY
}

object AppSettings {

    const val DEFAULT_AUTHOR = "You"

    private const val PREFS = "pdfpaper_settings"
    private const val KEY_AUTHOR = "author_name"
    private const val KEY_LINK_TAP = "link_tap_behavior"
    private const val MAX_AUTHOR_LENGTH = 80

    // Regex search (T65)
    private const val KEY_REGEX_SAVED = "regex.saved"
    private const val KEY_REGEX_FLAGS = "regex.flags"

    // Read aloud (T64)
    private const val KEY_SPEECH_SPEED = "speech.speed"
    private const val KEY_SPEECH_VOICE = "speech.voice"
    private const val DEFAULT_SPEECH_SPEED = 1f
    private const val MIN_SPEECH_SPEED = 0.5f
    private const val MAX_SPEECH_SPEED = 2f

    // The mark tools (T44).
    private const val KEY_WATERMARK_TEXT = "mark.watermark.text"
    private const val KEY_WATERMARK_USE_IMAGE = "mark.watermark.use_image"
    private const val KEY_WATERMARK_FONT_SIZE = "mark.watermark.font_size"
    private const val KEY_WATERMARK_OPACITY = "mark.watermark.opacity"
    private const val KEY_WATERMARK_ROTATION = "mark.watermark.rotation"
    private const val KEY_WATERMARK_COLOR = "mark.watermark.color"
    private const val KEY_WATERMARK_POSITION = "mark.watermark.position"
    private const val KEY_WATERMARK_BEHIND = "mark.watermark.behind_content"
    private const val KEY_WATERMARK_RANGE_MODE = "mark.watermark.range_mode"
    private const val KEY_WATERMARK_RANGE_TEXT = "mark.watermark.range_text"

    private const val KEY_NUMBER_HEADER = "mark.numbers.header"
    private const val KEY_NUMBER_FOOTER = "mark.numbers.footer"
    private const val KEY_NUMBER_FORMAT = "mark.numbers.format"
    private const val KEY_NUMBER_ALIGN = "mark.numbers.align"
    private const val KEY_NUMBER_FONT_SIZE = "mark.numbers.font_size"
    private const val KEY_NUMBER_MARGIN_TOP = "mark.numbers.margin_top"
    private const val KEY_NUMBER_MARGIN_BOTTOM = "mark.numbers.margin_bottom"
    private const val KEY_NUMBER_MARGIN_LEFT = "mark.numbers.margin_left"
    private const val KEY_NUMBER_MARGIN_RIGHT = "mark.numbers.margin_right"
    private const val KEY_NUMBER_START = "mark.numbers.start_number"
    private const val KEY_NUMBER_SKIP_FIRST = "mark.numbers.skip_first_page"
    private const val KEY_NUMBER_RANGE_MODE = "mark.numbers.range_mode"
    private const val KEY_NUMBER_RANGE_TEXT = "mark.numbers.range_text"

    private const val KEY_BATES_PREFIX = "mark.bates.prefix"
    private const val KEY_BATES_SUFFIX = "mark.bates.suffix"
    private const val KEY_BATES_DIGITS = "mark.bates.digits"
    private const val KEY_BATES_START = "mark.bates.start_number"
    private const val KEY_BATES_PLACEMENT = "mark.bates.placement"
    private const val KEY_BATES_FONT_SIZE = "mark.bates.font_size"
    private const val KEY_BATES_RANGE_MODE = "mark.bates.range_mode"
    private const val KEY_BATES_RANGE_TEXT = "mark.bates.range_text"

    fun authorName(context: Context): String =
        prefs(context).getString(KEY_AUTHOR, null)
            ?.trim()
            ?.take(MAX_AUTHOR_LENGTH)
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_AUTHOR

    /** Stores [name]; a blank name falls back to [DEFAULT_AUTHOR] when read. */
    fun setAuthorName(context: Context, name: String) {
        prefs(context).edit()
            .putString(KEY_AUTHOR, name.trim().take(MAX_AUTHOR_LENGTH))
            .apply()
    }

    /** Defaults to [LinkTapBehavior.PREVIEW_FIRST] for existing installs. */
    fun linkTapBehavior(context: Context): LinkTapBehavior {
        val raw = prefs(context).getString(KEY_LINK_TAP, null)
        return LinkTapBehavior.entries.firstOrNull { it.name == raw }
            ?: LinkTapBehavior.PREVIEW_FIRST
    }

    fun setLinkTapBehavior(context: Context, behavior: LinkTapBehavior) {
        prefs(context).edit()
            .putString(KEY_LINK_TAP, behavior.name)
            .apply()
    }

    // ---- The mark tools (T44): the options each screen remembers ---------

    /** The last used options of the watermark screen. */
    fun watermarkPreset(context: Context): WatermarkPreset {
        val p = prefs(context)
        val defaults = WatermarkPreset()
        return WatermarkPreset(
            text = p.getString(KEY_WATERMARK_TEXT, defaults.text) ?: defaults.text,
            useImage = p.getBoolean(KEY_WATERMARK_USE_IMAGE, defaults.useImage),
            fontSize = p.getFloat(KEY_WATERMARK_FONT_SIZE, defaults.fontSize.toFloat()).toDouble(),
            opacity = p.getFloat(KEY_WATERMARK_OPACITY, defaults.opacity.toFloat()).toDouble(),
            rotation = p.getFloat(KEY_WATERMARK_ROTATION, defaults.rotation.toFloat()).toDouble(),
            color = p.getString(KEY_WATERMARK_COLOR, defaults.color) ?: defaults.color,
            position = p.getString(KEY_WATERMARK_POSITION, defaults.position) ?: defaults.position,
            behindContent = p.getBoolean(KEY_WATERMARK_BEHIND, defaults.behindContent),
            rangeMode = p.getString(KEY_WATERMARK_RANGE_MODE, defaults.rangeMode) ?: defaults.rangeMode,
            rangeText = p.getString(KEY_WATERMARK_RANGE_TEXT, defaults.rangeText) ?: defaults.rangeText
        )
    }

    /** Stores the options of the watermark screen. */
    fun setWatermarkPreset(context: Context, preset: WatermarkPreset) {
        prefs(context).edit()
            .putString(KEY_WATERMARK_TEXT, preset.text)
            .putBoolean(KEY_WATERMARK_USE_IMAGE, preset.useImage)
            .putFloat(KEY_WATERMARK_FONT_SIZE, preset.fontSize.toFloat())
            .putFloat(KEY_WATERMARK_OPACITY, preset.opacity.toFloat())
            .putFloat(KEY_WATERMARK_ROTATION, preset.rotation.toFloat())
            .putString(KEY_WATERMARK_COLOR, preset.color)
            .putString(KEY_WATERMARK_POSITION, preset.position)
            .putBoolean(KEY_WATERMARK_BEHIND, preset.behindContent)
            .putString(KEY_WATERMARK_RANGE_MODE, preset.rangeMode)
            .putString(KEY_WATERMARK_RANGE_TEXT, preset.rangeText)
            .apply()
    }

    /** The last used options of the page-number screen. */
    fun pageNumbersPreset(context: Context): PageNumbersPreset {
        val p = prefs(context)
        val defaults = PageNumbersPreset()
        return PageNumbersPreset(
            header = p.getString(KEY_NUMBER_HEADER, defaults.header) ?: defaults.header,
            footer = p.getString(KEY_NUMBER_FOOTER, defaults.footer) ?: defaults.footer,
            format = p.getString(KEY_NUMBER_FORMAT, defaults.format) ?: defaults.format,
            align = p.getString(KEY_NUMBER_ALIGN, defaults.align) ?: defaults.align,
            fontSize = p.getFloat(KEY_NUMBER_FONT_SIZE, defaults.fontSize.toFloat()).toDouble(),
            marginTop = p.getFloat(KEY_NUMBER_MARGIN_TOP, defaults.marginTop.toFloat()).toDouble(),
            marginBottom = p.getFloat(KEY_NUMBER_MARGIN_BOTTOM, defaults.marginBottom.toFloat()).toDouble(),
            marginLeft = p.getFloat(KEY_NUMBER_MARGIN_LEFT, defaults.marginLeft.toFloat()).toDouble(),
            marginRight = p.getFloat(KEY_NUMBER_MARGIN_RIGHT, defaults.marginRight.toFloat()).toDouble(),
            startNumber = p.getInt(KEY_NUMBER_START, defaults.startNumber),
            skipFirstPage = p.getBoolean(KEY_NUMBER_SKIP_FIRST, defaults.skipFirstPage),
            rangeMode = p.getString(KEY_NUMBER_RANGE_MODE, defaults.rangeMode) ?: defaults.rangeMode,
            rangeText = p.getString(KEY_NUMBER_RANGE_TEXT, defaults.rangeText) ?: defaults.rangeText
        )
    }

    /** Stores the options of the page-number screen. */
    fun setPageNumbersPreset(context: Context, preset: PageNumbersPreset) {
        prefs(context).edit()
            .putString(KEY_NUMBER_HEADER, preset.header)
            .putString(KEY_NUMBER_FOOTER, preset.footer)
            .putString(KEY_NUMBER_FORMAT, preset.format)
            .putString(KEY_NUMBER_ALIGN, preset.align)
            .putFloat(KEY_NUMBER_FONT_SIZE, preset.fontSize.toFloat())
            .putFloat(KEY_NUMBER_MARGIN_TOP, preset.marginTop.toFloat())
            .putFloat(KEY_NUMBER_MARGIN_BOTTOM, preset.marginBottom.toFloat())
            .putFloat(KEY_NUMBER_MARGIN_LEFT, preset.marginLeft.toFloat())
            .putFloat(KEY_NUMBER_MARGIN_RIGHT, preset.marginRight.toFloat())
            .putInt(KEY_NUMBER_START, preset.startNumber)
            .putBoolean(KEY_NUMBER_SKIP_FIRST, preset.skipFirstPage)
            .putString(KEY_NUMBER_RANGE_MODE, preset.rangeMode)
            .putString(KEY_NUMBER_RANGE_TEXT, preset.rangeText)
            .apply()
    }

    /** The last used options of the Bates-number screen. */
    fun batesPreset(context: Context): BatesPreset {
        val p = prefs(context)
        val defaults = BatesPreset()
        return BatesPreset(
            prefix = p.getString(KEY_BATES_PREFIX, defaults.prefix) ?: defaults.prefix,
            suffix = p.getString(KEY_BATES_SUFFIX, defaults.suffix) ?: defaults.suffix,
            digits = p.getInt(KEY_BATES_DIGITS, defaults.digits),
            startNumber = p.getInt(KEY_BATES_START, defaults.startNumber),
            placement = p.getString(KEY_BATES_PLACEMENT, defaults.placement) ?: defaults.placement,
            fontSize = p.getFloat(KEY_BATES_FONT_SIZE, defaults.fontSize.toFloat()).toDouble(),
            rangeMode = p.getString(KEY_BATES_RANGE_MODE, defaults.rangeMode) ?: defaults.rangeMode,
            rangeText = p.getString(KEY_BATES_RANGE_TEXT, defaults.rangeText) ?: defaults.rangeText
        )
    }

    /** Stores the options of the Bates-number screen. */
    fun setBatesPreset(context: Context, preset: BatesPreset) {
        prefs(context).edit()
            .putString(KEY_BATES_PREFIX, preset.prefix)
            .putString(KEY_BATES_SUFFIX, preset.suffix)
            .putInt(KEY_BATES_DIGITS, preset.digits)
            .putInt(KEY_BATES_START, preset.startNumber)
            .putString(KEY_BATES_PLACEMENT, preset.placement)
            .putFloat(KEY_BATES_FONT_SIZE, preset.fontSize.toFloat())
            .putString(KEY_BATES_RANGE_MODE, preset.rangeMode)
            .putString(KEY_BATES_RANGE_TEXT, preset.rangeText)
            .apply()
    }

    // ---- Regex search (T65) ----------------------------------------------

    /** The saved-pattern records, or null when none were ever saved. */
    fun savedRegexPatternsRaw(context: Context): String? =
        prefs(context).getString(KEY_REGEX_SAVED, null)

    fun setSavedRegexPatternsRaw(context: Context, raw: String?) {
        val editor = prefs(context).edit()
        if (raw.isNullOrEmpty()) {
            editor.remove(KEY_REGEX_SAVED)
        } else {
            editor.putString(KEY_REGEX_SAVED, raw)
        }
        editor.apply()
    }

    /**
     * The chips the regex sheet opens with. An unknown name is dropped and an
     * empty result falls back to the shared default rather than to "no flags".
     */
    fun regexFlags(context: Context): Set<RegexFlag> {
        val raw = prefs(context).getString(KEY_REGEX_FLAGS, null)
        if (raw.isNullOrEmpty()) return DEFAULT_REGEX_FLAGS
        val flags = raw.split(',')
            .mapNotNull { name -> RegexFlag.values().firstOrNull { it.name == name.trim() } }
            .toSet()
        return flags.ifEmpty { DEFAULT_REGEX_FLAGS }
    }

    fun setRegexFlags(context: Context, flags: Set<RegexFlag>) {
        prefs(context).edit()
            .putString(KEY_REGEX_FLAGS, flags.joinToString(",") { it.name })
            .apply()
    }

    // ---- Read aloud (T64) --------------------------------------------------

    /**
     * The reading speed, as a multiple of the normal pace. A value that was
     * never set is 1x; a stored value outside the usable range is clamped, so a
     * hand-edited preferences file cannot make the voice inaudible.
     */
    fun speechSpeed(context: Context): Float {
        val stored = prefs(context).getFloat(KEY_SPEECH_SPEED, DEFAULT_SPEECH_SPEED)
        if (!stored.isFinite()) return DEFAULT_SPEECH_SPEED
        return stored.coerceIn(MIN_SPEECH_SPEED, MAX_SPEECH_SPEED)
    }

    fun setSpeechSpeed(context: Context, value: Float) {
        val safe = if (!value.isFinite()) DEFAULT_SPEECH_SPEED else value
        prefs(context).edit()
            .putFloat(KEY_SPEECH_SPEED, safe.coerceIn(MIN_SPEECH_SPEED, MAX_SPEECH_SPEED))
            .apply()
    }

    /** The voice the reader picked, or null when the default voice is used. */
    fun speechVoice(context: Context): SpeechVoiceKey? =
        SpeechVoiceKey.decode(prefs(context).getString(KEY_SPEECH_VOICE, null))

    fun setSpeechVoice(context: Context, key: SpeechVoiceKey?) {
        val editor = prefs(context).edit()
        if (key == null) editor.remove(KEY_SPEECH_VOICE)
        else editor.putString(KEY_SPEECH_VOICE, key.encode())
        editor.apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
