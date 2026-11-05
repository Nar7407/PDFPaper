package com.pdfpaper.app.ui.viewer

import com.pdfpaper.engine.annot.AnnotationFilter
import com.pdfpaper.engine.annot.AnnotationType
import kotlin.math.roundToInt

/**
 * High-level annotation groups displayed as toggles in the Layers bottom sheet (T68).
 */
enum class AnnotationLayerGroup {
    HIGHLIGHT,
    UNDERLINE,
    STRIKEOUT,
    INK,
    NOTE,
    STAMP,
    FORM_FIELD,
    OTHER
}

/**
 * Maps engine annotation subtypes to user-facing layer groups (T68).
 */
fun AnnotationType.toLayerGroup(): AnnotationLayerGroup = when (this) {
    AnnotationType.HIGHLIGHT -> AnnotationLayerGroup.HIGHLIGHT
    AnnotationType.UNDERLINE -> AnnotationLayerGroup.UNDERLINE
    AnnotationType.STRIKEOUT -> AnnotationLayerGroup.STRIKEOUT
    AnnotationType.INK -> AnnotationLayerGroup.INK
    AnnotationType.TEXT -> AnnotationLayerGroup.NOTE
    AnnotationType.STAMP -> AnnotationLayerGroup.STAMP
    AnnotationType.WIDGET -> AnnotationLayerGroup.FORM_FIELD
    else -> AnnotationLayerGroup.OTHER
}

/** Engine types each explicit group stands for; OTHER is the rest. */
private val GROUP_TYPES: Map<AnnotationLayerGroup, Set<AnnotationType>> =
    AnnotationLayerGroup.values().associateWith { group ->
        AnnotationType.values().filterTo(LinkedHashSet()) { it.toLayerGroup() == group }
    }

/**
 * Quantizes an RGB tuple (or engine color) into a canonical 24-bit 0xRRGGBB integer (T68).
 */
fun quantizeRgb(r: Double, g: Double, b: Double): Int {
    fun channel(value: Double): Int = (value.coerceIn(0.0, 1.0) * 255.0).roundToInt()
    return ((channel(r) shl 16) or (channel(g) shl 8) or channel(b)) and 0xFFFFFF
}

/**
 * Output mode for printing and exporting (T68).
 */
enum class AnnotationOutputMode {
    ALL,
    VISIBLE_ONLY,
    NONE
}

/**
 * The engine filter an output mode resolves to. [AnnotationOutputMode.ALL]
 * shows every annotation, [AnnotationOutputMode.NONE] shows none, and
 * [AnnotationOutputMode.VISIBLE_ONLY] keeps whatever the caller's current
 * layer selection already resolved to (passed in as [visibleOnly]).
 */
fun AnnotationOutputMode.toEngineFilter(
    visibleOnly: AnnotationFilter = AnnotationFilter.SHOW_ALL
): AnnotationFilter = when (this) {
    AnnotationOutputMode.ALL -> AnnotationFilter.SHOW_ALL
    AnnotationOutputMode.VISIBLE_ONLY -> visibleOnly
    AnnotationOutputMode.NONE -> AnnotationFilter(types = emptySet())
}

/**
 * User-selected layer filter state for a document (T68).
 */
data class AnnotationLayersState(
    val enabledGroups: Set<AnnotationLayerGroup> = AnnotationLayerGroup.values().toSet(),
    val enabledColors: Set<Int> = emptySet(),
    val disabledColors: Set<Int> = emptySet()
) {
    /** True if this filter differs from the default SHOW_ALL state. */
    val isFilterActive: Boolean
        get() = enabledGroups != AnnotationLayerGroup.values().toSet() ||
            enabledColors.isNotEmpty() || disabledColors.isNotEmpty()

    fun showAll(): AnnotationLayersState = AnnotationLayersState()

    fun hideAll(): AnnotationLayersState =
        AnnotationLayersState(enabledGroups = emptySet())

    fun soloColor(group: AnnotationLayerGroup, rgb: Int): AnnotationLayersState =
        AnnotationLayersState(
            enabledGroups = setOf(group),
            enabledColors = setOf(rgb)
        )

    fun toggleGroup(group: AnnotationLayerGroup): AnnotationLayersState {
        val groups = if (group in enabledGroups) enabledGroups - group else enabledGroups + group
        return copy(enabledGroups = groups)
    }

    fun toggleColor(rgb: Int): AnnotationLayersState {
        val disabled = if (rgb in disabledColors) disabledColors - rgb else disabledColors + rgb
        return copy(disabledColors = disabled)
    }

    fun toEngineFilter(): AnnotationFilter {
        if (!isFilterActive) return AnnotationFilter.SHOW_ALL
        val types = enabledGroups.flatMapTo(LinkedHashSet()) { GROUP_TYPES[it].orEmpty() }
        val enabled = enabledColors
        val disabled = disabledColors
        return AnnotationFilter(
            types = types,
            predicate = { annotation ->
                val rgb = annotation.color?.let { quantizeRgb(it.red, it.green, it.blue) }
                (enabled.isEmpty() || rgb in enabled) && rgb !in disabled
            }
        )
    }

    companion object {
        val SHOW_ALL = AnnotationLayersState()
    }
}
