package com.pdfpaper.app.ui.viewer

import com.pdfpaper.engine.annot.AnnotationFilter
import com.pdfpaper.engine.annot.AnnotationType

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
fun AnnotationType.toLayerGroup(): AnnotationLayerGroup = TODO("T68")

/**
 * Quantizes an RGB tuple (or engine color) into a canonical 24-bit 0xRRGGBB integer (T68).
 */
fun quantizeRgb(r: Double, g: Double, b: Double): Int = TODO("T68")

/**
 * Output mode for printing and exporting (T68).
 */
enum class AnnotationOutputMode {
    ALL,
    VISIBLE_ONLY,
    NONE
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
    val isFilterActive: Boolean get() = TODO("T68")

    fun showAll(): AnnotationLayersState = TODO("T68")
    fun hideAll(): AnnotationLayersState = TODO("T68")
    fun soloColor(group: AnnotationLayerGroup, rgb: Int): AnnotationLayersState = TODO("T68")
    fun toggleGroup(group: AnnotationLayerGroup): AnnotationLayersState = TODO("T68")
    fun toggleColor(rgb: Int): AnnotationLayersState = TODO("T68")

    fun toEngineFilter(): AnnotationFilter = TODO("T68")

    companion object {
        val SHOW_ALL = AnnotationLayersState()
    }
}
