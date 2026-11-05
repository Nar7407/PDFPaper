package com.pdfpaper.app.data

import android.content.Context
import com.pdfpaper.app.present.PresentTransition
import com.pdfpaper.app.ui.crop.CropMargins
import com.pdfpaper.app.ui.viewer.fit.FitContentMode
import com.pdfpaper.app.ui.viewer.flow.ColumnFlowSettings
import com.pdfpaper.app.ui.viewer.flow.FlowCorrectionCodec
import com.pdfpaper.app.ui.viewer.ink.StylusInputPolicy
import com.pdfpaper.app.ui.viewer.ink.StylusSettings
import com.pdfpaper.app.ui.viewer.reflow.ReflowAlign
import com.pdfpaper.app.ui.viewer.reflow.ReflowFont
import com.pdfpaper.app.ui.viewer.reflow.ReflowSettings
import com.pdfpaper.app.ui.search.DEFAULT_REGEX_FLAGS
import com.pdfpaper.app.ui.snip.MergedCells
import com.pdfpaper.app.ui.viewer.speech.SleepOption
import com.pdfpaper.app.ui.viewer.speech.SpeechVoiceKey
import com.pdfpaper.app.ui.snip.SnipFormat
import com.pdfpaper.app.ui.snip.SnipOptions
import com.pdfpaper.app.ui.snip.SnipSettings
import com.pdfpaper.app.ui.theme.CodePalettes
import com.pdfpaper.app.ui.viewer.turn.TurnAction
import com.pdfpaper.app.ui.viewer.turn.TurnAnimation
import com.pdfpaper.app.ui.viewer.turn.TurnMode
import com.pdfpaper.app.ui.viewer.turn.TurnSettings
import com.pdfpaper.engine.eink.EinkDither
import com.pdfpaper.engine.eink.EinkProfile
import com.pdfpaper.engine.eink.EinkSettings
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.render.ColorTransform
import com.pdfpaper.engine.render.PageTheme
import com.pdfpaper.engine.render.PageThemeMode
import com.pdfpaper.engine.table.CsvDelimiter
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

/** How edits are saved back (T55). */
enum class AutosaveMode {
    /** Save in place automatically when the source is writable. */
    IN_PLACE,

    /** Never save automatically; the user chooses each time. */
    ASK,

    /** Save only as a new copy; no automatic copy is created. */
    COPY
}

object AppSettings {

    const val DEFAULT_AUTHOR = "You"

    private const val PREFS = "pdfpaper_settings"
    private const val KEY_AUTHOR = "author_name"
    private const val KEY_LINK_TAP = "link_tap_behavior"
    private const val KEY_SHOW_LAUNCHER_NAMES = "launcher.show_file_names"
    private const val KEY_BLOCK_DOCUMENT_ACTIONS = "safety.block_document_actions"
    private const val KEY_SHOW_SAFETY_BANNER = "safety.show_banner"
    private const val KEY_INDEX_PAUSED = "index.paused"
    private const val KEY_INDEX_CHARGING = "index.only_while_charging"
    private const val MAX_AUTHOR_LENGTH = 80

    // Saving in place and version history (T55).
    private const val KEY_AUTOSAVE_MODE = "save.autosave_mode"
    private const val KEY_VERSION_HISTORY = "save.version_history"
    private const val KEY_COPY_CHOICE = "save.copy_choice."
    private const val KEY_BOOKMARK_OUTLINE = "annot.bookmark_outline"
    private const val KEY_ANNOTATION_GROUPING = "annot.grouping"

    // Fitting the content to the screen (T57).
    private const val KEY_FIT_MODE = "view.fit.mode."
    private const val KEY_FIT_MARGINS = "view.fit.margins."
    private const val KEY_FIT_IGNORE_HEADS = "view.fit.ignore_headers"
    private const val KEY_THEME_MODE = "view.theme.mode"
    private const val KEY_THEME_INK = "view.theme.ink"
    private const val KEY_THEME_DIM = "view.theme.dim"
    private const val KEY_THEME_FOLLOW_SYSTEM = "view.theme.follow_system"
    private const val KEY_THEME_DOC_MODE = "view.theme.doc."
    private const val KEY_LOOK_DEFAULT = "view.look.default"

    // Reflow reading (T33).
    private const val KEY_REFLOW_FONT_SIZE = "reflow.font_size"
    private const val KEY_REFLOW_LINE_SPACING = "reflow.line_spacing"
    private const val KEY_REFLOW_PARAGRAPH_SPACING = "reflow.paragraph_spacing"
    private const val KEY_REFLOW_MARGIN = "reflow.margin"
    private const val KEY_REFLOW_ALIGN = "reflow.align"
    private const val KEY_REFLOW_FONT = "reflow.font"
    private const val KEY_REFLOW_HIDE_PAGE_NUMBERS = "reflow.hide_page_numbers"
    private const val KEY_REFLOW_HIDE_FOOTNOTES = "reflow.hide_footnotes"
    private const val KEY_REFLOW_HINT = "reflow.hint_dismissed."

    // Snip table (T60)
    private const val KEY_SNIP_FORMAT = "snip.format"
    private const val KEY_SNIP_HEADER = "snip.header_row"
    private const val KEY_SNIP_MERGED = "snip.merged"
    private const val KEY_SNIP_TRIM = "snip.trim"
    private const val KEY_SNIP_DELIMITER = "snip.delimiter"

    // Regex search (T65)
    private const val KEY_REGEX_SAVED = "regex.saved"
    private const val KEY_REGEX_FLAGS = "regex.flags"

    // Read aloud (T64)
    private const val KEY_SPEECH_SPEED = "speech.speed"
    private const val KEY_SPEECH_VOICE = "speech.voice"
    private const val KEY_SPEECH_SLEEP = "speech.sleep"
    private const val KEY_SPEECH_AUTO_SCROLL = "speech.auto_scroll"
    private const val DEFAULT_SPEECH_SPEED = 1f
    private const val MIN_SPEECH_SPEED = 0.5f
    private const val MAX_SPEECH_SPEED = 2f

    // Column flow (T59)
    private const val KEY_FLOW_SIDE_BY_SIDE = "flow.side_by_side"
    private const val KEY_FLOW_ANIMATE = "flow.animate"
    private const val KEY_FLOW_MINIMAP = "flow.minimap"
    private const val KEY_FLOW_CORRECTIONS = "flow.correct."

    // The reading session (T38b).
    private const val KEY_ALWAYS_PAGE_ONE = "reading.always_page_one"

    // The presentation mode (T53).
    private const val KEY_PRESENT_TRANSITION = "present.transition"
    private const val KEY_PRESENT_TIMER_MINUTES = "present.timer_minutes"
    private const val KEY_PRESENT_LAST_PAGE = "present.last_page."
    private const val DEFAULT_PRESENT_TIMER_MINUTES = 15

    // Reading and page turning (T58).
    private const val KEY_TURN_VOLUME_KEYS = "turn.volume_keys"
    private const val KEY_TURN_INVERT_VOLUME = "turn.invert_volume"
    private const val KEY_TURN_EDGE_TAPS = "turn.edge_taps"
    private const val KEY_TURN_LEFT_HANDED = "turn.left_handed"
    private const val KEY_TURN_VERTICAL_ZONES = "turn.vertical_zones"
    private const val KEY_TURN_ACTION = "turn.action"
    private const val KEY_TURN_ZONE_PERCENT = "turn.zone_percent"
    private const val KEY_TURN_OVERLAP_LINES = "turn.overlap_lines"
    private const val KEY_TURN_MODE = "turn.mode"
    private const val KEY_TURN_ANIMATION = "turn.animation"
    private const val KEY_TURN_HAPTIC = "turn.haptic"
    private const val KEY_TURN_VOLUME_HINT = "turn.volume_hint_shown"

    // E-Ink / reflex display (T63).
    private const val KEY_EINK_ENABLED = "eink.enabled"
    private const val KEY_EINK_PROFILE = "eink.profile"
    private const val KEY_EINK_THRESHOLD = "eink.threshold"
    private const val KEY_EINK_DITHER = "eink.dither"
    private const val KEY_EINK_MIN_LINE = "eink.min_line_px"
    private const val KEY_EINK_AUTO_CLEAR = "eink.auto_clear_turns"
    private const val KEY_REDUCE_MOTION = "motion.reduce"
    private const val KEY_SUNLIGHT = "display.sunlight"

    // The app chrome theme (Settings → Appearance). Stored locally; the page
    // filters (T32) stay a separate setting.
    private const val KEY_APPEARANCE_CHOICE = "appearance.choice"
    private const val KEY_APPEARANCE_CHANGE_EACH_LAUNCH = "appearance.code.each_launch"
    private const val KEY_APPEARANCE_ROTATION = "appearance.code.rotation"
    private const val KEY_APPEARANCE_PALETTE = "appearance.code.palette"
    private const val KEY_APPEARANCE_LAST_PALETTE = "appearance.code.last_palette"

    // Stylus input (T54b).
    private const val KEY_STYLUS_MODE = "stylus.mode"
    private const val KEY_STYLUS_PRIMARY = "stylus.button.primary"
    private const val KEY_STYLUS_SECONDARY = "stylus.button.secondary"

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

    /**
     * Whether document names may appear on the launcher and the home-screen
     * widget (T48). Hidden by default: anyone who can see the home screen can
     * otherwise read what the user has been working on.
     */
    fun showLauncherNames(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_LAUNCHER_NAMES, LauncherLabels.DEFAULT_SHOW_NAMES)

    fun setShowLauncherNames(context: Context, show: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SHOW_LAUNCHER_NAMES, show)
            .apply()
    }

    /**
     * Task T49: the user's declaration that document actions and scripts are
     * blocked. The app never executes them anyway; the default is ON and the
     * safety screen reports it, so the promise is visible rather than implicit.
     */
    fun blockDocumentActions(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCK_DOCUMENT_ACTIONS, true)

    fun setBlockDocumentActions(context: Context, block: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_BLOCK_DOCUMENT_ACTIONS, block)
            .apply()
    }

    /** Whether a medium/high safety finding shows the viewer banner (T49). */
    fun showSafetyBanner(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_SAFETY_BANNER, true)

    fun setShowSafetyBanner(context: Context, show: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SHOW_SAFETY_BANNER, show)
            .apply()
    }

    /**
     * Task T50: pause is persistent. A periodic run must not quietly restart
     * indexing after the user pressed Pause.
     */
    fun indexPaused(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INDEX_PAUSED, false)

    fun setIndexPaused(context: Context, paused: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_INDEX_PAUSED, paused)
            .apply()
    }

    /** Optional WorkManager constraint; off by default. */
    fun indexOnlyWhileCharging(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INDEX_CHARGING, false)

    fun setIndexOnlyWhileCharging(context: Context, onlyCharging: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_INDEX_CHARGING, onlyCharging)
            .apply()
    }

    // ---- Saving in place, autosave and version history (T55) -------------

    /** The user's autosave choice; saving in place is the default. */
    fun autosaveMode(context: Context): AutosaveMode {
        val raw = prefs(context).getString(KEY_AUTOSAVE_MODE, AutosaveMode.IN_PLACE.name)
        return runCatching { AutosaveMode.valueOf(raw ?: "") }.getOrDefault(AutosaveMode.IN_PLACE)
    }

    fun setAutosaveMode(context: Context, mode: AutosaveMode) {
        prefs(context).edit().putString(KEY_AUTOSAVE_MODE, mode.name).apply()
    }

    /** Keeps up to three pre-save copies by default. */
    fun versionHistoryEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VERSION_HISTORY, true)

    fun setVersionHistoryEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VERSION_HISTORY, enabled).apply()
    }

    fun versionHistoryLimit(context: Context): Int = 3

    /**
     * The copy destination the user chose once for a read-only document. Only
     * the URI is stored (hashed key, no name); the app writes later saves there
     * instead of asking for a new file every time.
     */
    fun saveCopyChoice(context: Context, path: String): String? {
        if (path.isBlank()) return null
        return prefs(context).getString(KEY_COPY_CHOICE + copyPathKey(path), null)
    }

    fun setSaveCopyChoice(context: Context, path: String, uri: String?) {
        if (path.isBlank()) return
        val editor = prefs(context).edit()
        val key = KEY_COPY_CHOICE + copyPathKey(path)
        if (uri.isNullOrBlank()) editor.remove(key) else editor.putString(key, uri)
        editor.apply()
    }

    private fun copyPathKey(path: String): String =
        path.hashCode().toUInt().toString(16)

    // ---- Annotations hub (T56) -------------------------------------------

    /** Writes app bookmarks into the document outline when a save happens. */
    fun bookmarkOutlineEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BOOKMARK_OUTLINE, true)

    fun setBookmarkOutlineEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BOOKMARK_OUTLINE, enabled).apply()
    }

    /** How the hub groups its list; by document unless the user chose dates. */
    fun annotationGrouping(context: Context): String =
        prefs(context).getString(KEY_ANNOTATION_GROUPING, "DOCUMENT") ?: "DOCUMENT"

    fun setAnnotationGrouping(context: Context, grouping: String) {
        prefs(context).edit().putString(KEY_ANNOTATION_GROUPING, grouping).apply()
    }

    // ---- Fit content (T57) -----------------------------------------------

    /**
     * How [path] was last framed (T38: remembered per document). Only the
     * mode and the manual margins are stored; the detected boxes are cheap to
     * recompute and never written to the settings file.
     */
    fun fitContentMode(context: Context, path: String): FitContentMode {
        if (path.isBlank()) return FitContentMode.OFF
        val raw = prefs(context).getString(KEY_FIT_MODE + copyPathKey(path), null) ?: return FitContentMode.OFF
        return runCatching { FitContentMode.valueOf(raw) }.getOrDefault(FitContentMode.OFF)
    }

    fun setFitContentMode(context: Context, path: String, mode: FitContentMode) {
        if (path.isBlank()) return
        prefs(context).edit()
            .putString(KEY_FIT_MODE + copyPathKey(path), mode.name)
            .apply()
    }

    /** The manual fit margins of [path], shared by every page. */
    fun fitMargins(context: Context, path: String): CropMargins {
        if (path.isBlank()) return CropMargins.ZERO
        val p = prefs(context)
        val key = KEY_FIT_MARGINS + copyPathKey(path)
        if (!p.contains(key)) return CropMargins.ZERO
        return CropMargins(
            p.getFloat(key + ".l", 0f).toDouble(),
            p.getFloat(key + ".b", 0f).toDouble(),
            p.getFloat(key + ".r", 0f).toDouble(),
            p.getFloat(key + ".t", 0f).toDouble()
        )
    }

    fun setFitMargins(context: Context, path: String, margins: CropMargins) {
        if (path.isBlank()) return
        val key = KEY_FIT_MARGINS + copyPathKey(path)
        prefs(context).edit()
            .putFloat(key + ".l", margins.left.toFloat())
            .putFloat(key + ".b", margins.bottom.toFloat())
            .putFloat(key + ".r", margins.right.toFloat())
            .putFloat(key + ".t", margins.top.toFloat())
            .apply()
    }

    /** Whether running heads and page numbers are left out of the fit (T57). */
    fun fitIgnoreHeaderFooter(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FIT_IGNORE_HEADS, true)

    fun setFitIgnoreHeaderFooter(context: Context, ignore: Boolean) {
        prefs(context).edit().putBoolean(KEY_FIT_IGNORE_HEADS, ignore).apply()
    }

    // ---- Page theme (T32 content-aware dark mode, T32b true black) --------

    /**
     * The global page theme: mode, ink colour and raster dim. Only the mode is
     * a document-level override; ink and dim are global, so two documents never
     * disagree about how the reader's eyes are treated.
     */
    fun pageTheme(context: Context): PageTheme {
        val p = prefs(context)
        val mode = PageThemeMode.fromName(p.getString(KEY_THEME_MODE, PageThemeMode.OFF.name))
        val ink = p.getInt(KEY_THEME_INK, DEFAULT_THEME_INK)
        val dim = p.getFloat(KEY_THEME_DIM, 1f).toDouble()
        return PageTheme(mode, pdfColorOf(ink), dim)
    }

    fun setPageTheme(context: Context, theme: PageTheme) {
        prefs(context).edit()
            .putString(KEY_THEME_MODE, theme.mode.name)
            .putInt(KEY_THEME_INK, rgbOf(theme.ink))
            .putFloat(KEY_THEME_DIM, theme.dim.toFloat())
            .apply()
    }

    /** "Follow system dark theme": light system → the page theme is off. */
    fun pageThemeFollowsSystem(context: Context): Boolean =
        prefs(context).getBoolean(KEY_THEME_FOLLOW_SYSTEM, false)

    fun setPageThemeFollowsSystem(context: Context, follow: Boolean) {
        prefs(context).edit().putBoolean(KEY_THEME_FOLLOW_SYSTEM, follow).apply()
    }

    /** The per-document override, or null when the document inherits (T32b). */
    fun documentThemeMode(context: Context, path: String): PageThemeMode? {
        if (path.isBlank()) return null
        val raw = prefs(context).getString(KEY_THEME_DOC_MODE + copyPathKey(path), null) ?: return null
        return runCatching { PageThemeMode.valueOf(raw) }.getOrNull()
    }

    fun setDocumentThemeMode(context: Context, path: String, mode: PageThemeMode?) {
        if (path.isBlank()) return
        val key = KEY_THEME_DOC_MODE + copyPathKey(path)
        val edit = prefs(context).edit()
        if (mode == null) edit.remove(key) else edit.putString(key, mode.name)
        edit.apply()
    }

    /** Resolves the theme for [path] from the global settings and the system. */
    fun resolvedPageTheme(context: Context, path: String, systemDark: Boolean): PageTheme =
        PageTheme.resolve(
            global = pageTheme(context),
            perDocument = documentThemeMode(context, path),
            followSystem = pageThemeFollowsSystem(context),
            systemDark = systemDark
        )

    // ---- The look of documents that have none of their own (T62) ---------

    /**
     * The look a document without a saved one opens with, or null when the T32
     * page theme above is still the default. Setting it also mirrors its filter
     * into the page-theme card, so the two settings can never disagree.
     */
    fun defaultColorTransform(context: Context): ColorTransform? =
        DocumentLookCodec.decode(prefs(context).getString(KEY_LOOK_DEFAULT, null))

    fun setDefaultColorTransform(context: Context, transform: ColorTransform?) {
        val edit = prefs(context).edit()
        if (transform == null) {
            edit.remove(KEY_LOOK_DEFAULT)
        } else {
            val look = transform.normalized()
            edit.putString(KEY_LOOK_DEFAULT, DocumentLookCodec.encode(look))
            edit.putString(KEY_THEME_MODE, look.filter.themeMode.name)
        }
        edit.apply()
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

    // ---- Presentation mode (T53) -----------------------------------------

    /** Page animation between slides; fades by default. */
    fun presenterTransition(context: Context): PresentTransition =
        PresentTransition.fromName(prefs(context).getString(KEY_PRESENT_TRANSITION, null))

    fun setPresenterTransition(context: Context, transition: PresentTransition) {
        prefs(context).edit()
            .putString(KEY_PRESENT_TRANSITION, transition.name)
            .apply()
    }

    /** Timer target in minutes; 0 means no limit, so the clock only counts up. */
    fun presenterTimerMinutes(context: Context): Int =
        prefs(context).getInt(KEY_PRESENT_TIMER_MINUTES, DEFAULT_PRESENT_TIMER_MINUTES)
            .coerceIn(0, 240)

    fun setPresenterTimerMinutes(context: Context, minutes: Int) {
        prefs(context).edit()
            .putInt(KEY_PRESENT_TIMER_MINUTES, minutes.coerceIn(0, 240))
            .apply()
    }

    /** The zero-based slide the last presentation on [path] stopped on. */
    fun presenterLastPage(context: Context, path: String): Int? {
        val key = KEY_PRESENT_LAST_PAGE + presenterPathKey(path)
        val p = prefs(context)
        return if (p.contains(key)) p.getInt(key, 0).coerceAtLeast(0) else null
    }

    fun setPresenterLastPage(context: Context, path: String, pageIndex: Int) {
        if (path.isBlank()) return
        prefs(context).edit()
            .putInt(KEY_PRESENT_LAST_PAGE + presenterPathKey(path), pageIndex.coerceAtLeast(0))
            .apply()
    }

    /**
     * A stable, short preferences key for a document path. Only the hash is
     * stored: the settings file never contains document names or locations.
     */
    private fun presenterPathKey(path: String): String =
        path.hashCode().toUInt().toString(16)

    // ---- Reading and page turning (T58) ----------------------------------

    /**
     * Every reading preference at once. Volume keys stay OFF until the user
     * says yes, because claiming them takes the phone's volume control away.
     */
    fun turnSettings(context: Context): TurnSettings {
        val p = prefs(context)
        val d = TurnSettings.DEFAULT
        return TurnSettings(
            volumeKeys = p.getBoolean(KEY_TURN_VOLUME_KEYS, d.volumeKeys),
            invertVolume = p.getBoolean(KEY_TURN_INVERT_VOLUME, d.invertVolume),
            edgeTaps = p.getBoolean(KEY_TURN_EDGE_TAPS, d.edgeTaps),
            leftHanded = p.getBoolean(KEY_TURN_LEFT_HANDED, d.leftHanded),
            verticalZones = p.getBoolean(KEY_TURN_VERTICAL_ZONES, d.verticalZones),
            action = enumOrDefault(p.getString(KEY_TURN_ACTION, null), d.action),
            zonePercent = p.getInt(KEY_TURN_ZONE_PERCENT, d.zonePercent),
            overlapLines = p.getInt(KEY_TURN_OVERLAP_LINES, d.overlapLines),
            mode = enumOrDefault(p.getString(KEY_TURN_MODE, null), d.mode),
            animation = enumOrDefault(p.getString(KEY_TURN_ANIMATION, null), d.animation),
            hapticTick = p.getBoolean(KEY_TURN_HAPTIC, d.hapticTick)
        ).normalised()
    }

    fun setTurnSettings(context: Context, settings: TurnSettings) {
        val s = settings.normalised()
        prefs(context).edit()
            .putBoolean(KEY_TURN_VOLUME_KEYS, s.volumeKeys)
            .putBoolean(KEY_TURN_INVERT_VOLUME, s.invertVolume)
            .putBoolean(KEY_TURN_EDGE_TAPS, s.edgeTaps)
            .putBoolean(KEY_TURN_LEFT_HANDED, s.leftHanded)
            .putBoolean(KEY_TURN_VERTICAL_ZONES, s.verticalZones)
            .putString(KEY_TURN_ACTION, s.action.name)
            .putInt(KEY_TURN_ZONE_PERCENT, s.zonePercent)
            .putInt(KEY_TURN_OVERLAP_LINES, s.overlapLines)
            .putString(KEY_TURN_MODE, s.mode.name)
            .putString(KEY_TURN_ANIMATION, s.animation.name)
            .putBoolean(KEY_TURN_HAPTIC, s.hapticTick)
            .apply()
    }

    /**
     * Whether the one-time "use the volume keys to turn pages" offer has been
     * shown. It is shown once per install, never again after a dismissal.
     */
    fun turnVolumeHintShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TURN_VOLUME_HINT, false)

    fun setTurnVolumeHintShown(context: Context, shown: Boolean) {
        prefs(context).edit().putBoolean(KEY_TURN_VOLUME_HINT, shown).apply()
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T =
        raw?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: fallback

    // ---- Reflow reading (T33) --------------------------------------------

    /** The reader's reflow type settings; they apply to every document. */
    fun reflowSettings(context: Context): ReflowSettings {
        val p = prefs(context)
        val d = ReflowSettings.DEFAULT
        return ReflowSettings(
            fontSizeSp = p.getFloat(KEY_REFLOW_FONT_SIZE, d.fontSizeSp),
            lineSpacing = p.getFloat(KEY_REFLOW_LINE_SPACING, d.lineSpacing),
            paragraphSpacingDp = p.getFloat(KEY_REFLOW_PARAGRAPH_SPACING, d.paragraphSpacingDp),
            marginDp = p.getFloat(KEY_REFLOW_MARGIN, d.marginDp),
            align = enumOrDefault(p.getString(KEY_REFLOW_ALIGN, null), d.align),
            font = enumOrDefault(p.getString(KEY_REFLOW_FONT, null), d.font),
            hidePageNumbers = p.getBoolean(KEY_REFLOW_HIDE_PAGE_NUMBERS, d.hidePageNumbers),
            hideFootnotes = p.getBoolean(KEY_REFLOW_HIDE_FOOTNOTES, d.hideFootnotes)
        ).normalised()
    }

    fun setReflowSettings(context: Context, settings: ReflowSettings) {
        val s = settings.normalised()
        prefs(context).edit()
            .putFloat(KEY_REFLOW_FONT_SIZE, s.fontSizeSp)
            .putFloat(KEY_REFLOW_LINE_SPACING, s.lineSpacing)
            .putFloat(KEY_REFLOW_PARAGRAPH_SPACING, s.paragraphSpacingDp)
            .putFloat(KEY_REFLOW_MARGIN, s.marginDp)
            .putString(KEY_REFLOW_ALIGN, s.align.name)
            .putString(KEY_REFLOW_FONT, s.font.name)
            .putBoolean(KEY_REFLOW_HIDE_PAGE_NUMBERS, s.hidePageNumbers)
            .putBoolean(KEY_REFLOW_HIDE_FOOTNOTES, s.hideFootnotes)
            .apply()
    }

    /**
     * Whether "Text is small. Try Reflow" was dismissed for [path]. The hint is
     * per document, so a book the reader already chose a mode for stays quiet.
     */
    fun reflowHintDismissed(context: Context, path: String): Boolean {
        if (path.isBlank()) return false
        return prefs(context).getBoolean(KEY_REFLOW_HINT + presenterPathKey(path), false)
    }

    fun setReflowHintDismissed(context: Context, path: String, dismissed: Boolean) {
        if (path.isBlank()) return
        prefs(context).edit()
            .putBoolean(KEY_REFLOW_HINT + presenterPathKey(path), dismissed)
            .apply()
    }

    // ---- Column flow (T59) ------------------------------------------------

    /** How column flow presents a page; the choice applies to every document. */
    fun flowSettings(context: Context): ColumnFlowSettings {
        val p = prefs(context)
        val d = ColumnFlowSettings.DEFAULT
        return ColumnFlowSettings(
            sideBySide = p.getBoolean(KEY_FLOW_SIDE_BY_SIDE, d.sideBySide),
            animate = p.getBoolean(KEY_FLOW_ANIMATE, d.animate),
            showMiniMap = p.getBoolean(KEY_FLOW_MINIMAP, d.showMiniMap)
        )
    }

    fun setFlowSettings(context: Context, settings: ColumnFlowSettings) {
        prefs(context).edit()
            .putBoolean(KEY_FLOW_SIDE_BY_SIDE, settings.sideBySide)
            .putBoolean(KEY_FLOW_ANIMATE, settings.animate)
            .putBoolean(KEY_FLOW_MINIMAP, settings.showMiniMap)
            .apply()
    }

    /**
     * The reader's manual reading-order corrections for [path], keyed by page.
     * They are app data: a correction changes what PDFPaper shows, never the
     * file, so a copy of the PDF opened elsewhere reads normally.
     */
    fun flowCorrections(context: Context, path: String): Map<Int, List<PdfRect>> {
        if (path.isBlank()) return emptyMap()
        return FlowCorrectionCodec.decode(
            prefs(context).getString(KEY_FLOW_CORRECTIONS + presenterPathKey(path), null)
        )
    }

    fun setFlowCorrections(
        context: Context,
        path: String,
        corrections: Map<Int, List<PdfRect>>
    ) {
        if (path.isBlank()) return
        val key = KEY_FLOW_CORRECTIONS + presenterPathKey(path)
        val editor = prefs(context).edit()
        if (corrections.isEmpty()) {
            editor.remove(key)
        } else {
            editor.putString(key, FlowCorrectionCodec.encode(corrections))
        }
        editor.apply()
    }

    // ---- Snip table (T60) -------------------------------------------------

    /** The last snip format and options; they apply to every document. */
    fun snipSettings(context: Context): SnipSettings {
        val p = prefs(context)
        return SnipSettings(
            format = SnipFormat.fromName(p.getString(KEY_SNIP_FORMAT, null)),
            options = SnipOptions(
                headerRow = p.getBoolean(KEY_SNIP_HEADER, true),
                merged = MergedCells.fromName(p.getString(KEY_SNIP_MERGED, null)),
                trimSpaces = p.getBoolean(KEY_SNIP_TRIM, true),
                delimiter = enumOrDefault(p.getString(KEY_SNIP_DELIMITER, null), CsvDelimiter.COMMA)
            )
        )
    }

    fun setSnipSettings(context: Context, settings: SnipSettings) {
        prefs(context).edit()
            .putString(KEY_SNIP_FORMAT, settings.format.name)
            .putBoolean(KEY_SNIP_HEADER, settings.options.headerRow)
            .putString(KEY_SNIP_MERGED, settings.options.merged.name)
            .putBoolean(KEY_SNIP_TRIM, settings.options.trimSpaces)
            .putString(KEY_SNIP_DELIMITER, settings.options.delimiter.name)
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

    // ---- The reading session (T38b) --------------------------------------
    /**
     * When on, every document opens at page 1 even though its page, zoom and
     * mode are still remembered. It is a reader preference, not a document one.
     */
    fun alwaysStartAtPageOne(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALWAYS_PAGE_ONE, false)

    fun setAlwaysStartAtPageOne(context: Context, always: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALWAYS_PAGE_ONE, always).apply()
    }

    // ---- E-Ink / reflex display (T63) -------------------------------------

    /** The device-wide E-Ink switch; a document may still override the profile. */
    fun einkEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EINK_ENABLED, false)

    fun setEinkEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EINK_ENABLED, enabled).apply()
    }

    /**
     * The device profile and its panel settings. The profile is what an
     * enabled viewer uses unless a document overrides it (T63); the threshold
     * and dither belong to the panel and are always device-wide.
     */
    fun einkSettings(context: Context): EinkSettings {
        val p = prefs(context)
        return EinkSettings(
            profile = EinkProfile.fromName(
                p.getString(KEY_EINK_PROFILE, EinkProfile.TEXT_BITONAL.name)
            ),
            threshold = p.getFloat(KEY_EINK_THRESHOLD, EinkSettings.DEFAULT_THRESHOLD.toFloat())
                .toDouble(),
            dither = runCatching {
                EinkDither.valueOf(
                    p.getString(KEY_EINK_DITHER, EinkDither.ORDERED.name)
                        ?: EinkDither.ORDERED.name
                )
            }.getOrDefault(EinkDither.ORDERED),
            minLineWidthPx = p.getFloat(
                KEY_EINK_MIN_LINE,
                EinkSettings.DEFAULT_MIN_LINE_WIDTH_PX.toFloat()
            ).toDouble()
        ).normalized()
    }

    fun setEinkSettings(context: Context, settings: EinkSettings) {
        val normalised = settings.normalized()
        prefs(context).edit()
            .putString(KEY_EINK_PROFILE, normalised.profile.name)
            .putFloat(KEY_EINK_THRESHOLD, normalised.threshold.toFloat())
            .putString(KEY_EINK_DITHER, normalised.dither.name)
            .putFloat(KEY_EINK_MIN_LINE, normalised.minLineWidthPx.toFloat())
            .apply()
    }

    /** 0 = never flash automatically; otherwise a clear every N page turns. */
    fun einkAutoClearTurns(context: Context): Int =
        prefs(context).getInt(KEY_EINK_AUTO_CLEAR, 0).coerceIn(0, MAX_AUTO_CLEAR_TURNS)

    fun setEinkAutoClearTurns(context: Context, turns: Int) {
        prefs(context).edit()
            .putInt(KEY_EINK_AUTO_CLEAR, turns.coerceIn(0, MAX_AUTO_CLEAR_TURNS))
            .apply()
    }

    /**
     * The app-wide "reduce motion" flag (T63). It never changes the document;
     * it makes the UI switch instantly and removes decorative motion.
     */
    fun reduceMotion(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REDUCE_MOTION, false)

    fun setReduceMotion(context: Context, reduce: Boolean) {
        prefs(context).edit().putBoolean(KEY_REDUCE_MOTION, reduce).apply()
    }

    /** Direct-sunlight reading: a light high-contrast theme and full brightness. */
    fun sunlightMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SUNLIGHT, false)

    fun setSunlightMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SUNLIGHT, enabled).apply()
    }

    // ---- The app chrome theme (Settings → Appearance) ----------------------

    /** Everything the shell theme needs, read in one pass. */
    fun appearancePrefs(context: Context): AppearancePrefs {
        val p = prefs(context)
        return AppearancePrefCodec.decode(
            choice = p.getString(KEY_APPEARANCE_CHOICE, null),
            changeEachLaunch = if (p.contains(KEY_APPEARANCE_CHANGE_EACH_LAUNCH)) {
                p.getBoolean(KEY_APPEARANCE_CHANGE_EACH_LAUNCH, true)
            } else {
                null
            },
            rotation = p.getString(KEY_APPEARANCE_ROTATION, null),
            paletteIndex = p.getInt(KEY_APPEARANCE_PALETTE, 0),
            lastPaletteIndex = p.getInt(
                KEY_APPEARANCE_LAST_PALETTE,
                CodePalettes.NO_LAST_INDEX
            )
        )
    }

    /**
     * Writes the reader-facing appearance fields. The last-used palette is
     * owned by the app theme (it rotates once per launch), so it is not touched
     * here.
     */
    fun setAppearancePrefs(context: Context, prefs: AppearancePrefs) {
        val safeIndex = prefs.paletteIndex.coerceIn(0, CodePalettes.ALL.size - 1)
        prefs(context).edit()
            .putString(KEY_APPEARANCE_CHOICE, prefs.choice.name)
            .putBoolean(KEY_APPEARANCE_CHANGE_EACH_LAUNCH, prefs.changePaletteEachLaunch)
            .putString(KEY_APPEARANCE_ROTATION, prefs.rotation.name)
            .putInt(KEY_APPEARANCE_PALETTE, safeIndex)
            .apply()
    }

    /** Written once per launch by the app theme so the rotation keeps its place. */
    fun setLastCodePaletteIndex(context: Context, index: Int) {
        val safe = index.coerceIn(0, CodePalettes.ALL.size - 1)
        prefs(context).edit().putInt(KEY_APPEARANCE_LAST_PALETTE, safe).apply()
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

    /**
     * The sleep timer the next reading starts with (T34). Sleep is remembered
     * because a reader who wants 15 minutes tonight usually wants it tomorrow
     * too; the running countdown still starts fresh with each reading.
     */
    fun speechSleep(context: Context): SleepOption {
        val name = prefs(context).getString(KEY_SPEECH_SLEEP, null) ?: return SleepOption.OFF
        return SleepOption.entries.firstOrNull { it.name == name } ?: SleepOption.OFF
    }

    fun setSpeechSleep(context: Context, option: SleepOption) {
        prefs(context).edit().putString(KEY_SPEECH_SLEEP, option.name).apply()
    }

    /** Whether the viewer follows the spoken paragraph with the page (T34). */
    fun speechAutoScroll(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPEECH_AUTO_SCROLL, true)

    fun setSpeechAutoScroll(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEECH_AUTO_SCROLL, enabled).apply()
    }

    /**
     * The app's settings file (T63). The root theme listens for changes so a
     * reduce-motion or direct-sunlight switch takes effect on the next frame,
     * not only after the app has been backgrounded and reopened.
     */
    fun preferences(context: Context): android.content.SharedPreferences =
        prefs(context)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The largest "clear screen every N turns" the settings offer. */
    const val MAX_AUTO_CLEAR_TURNS = 100
}
