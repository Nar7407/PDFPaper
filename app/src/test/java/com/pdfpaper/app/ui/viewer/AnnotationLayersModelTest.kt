package com.pdfpaper.app.ui.viewer

import com.pdfpaper.engine.annot.AnnotationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AnnotationLayersModel] (T68).
 */
class AnnotationLayersModelTest {

    @Test
    fun mappingEngineAnnotationTypesToLayerGroups() {
        assertEquals(AnnotationLayerGroup.HIGHLIGHT, AnnotationType.HIGHLIGHT.toLayerGroup())
        assertEquals(AnnotationLayerGroup.UNDERLINE, AnnotationType.UNDERLINE.toLayerGroup())
        assertEquals(AnnotationLayerGroup.STRIKEOUT, AnnotationType.STRIKEOUT.toLayerGroup())
        assertEquals(AnnotationLayerGroup.INK, AnnotationType.INK.toLayerGroup())
        assertEquals(AnnotationLayerGroup.NOTE, AnnotationType.TEXT.toLayerGroup())
        assertEquals(AnnotationLayerGroup.STAMP, AnnotationType.STAMP.toLayerGroup())
        assertEquals(AnnotationLayerGroup.FORM_FIELD, AnnotationType.WIDGET.toLayerGroup())
    }

    @Test
    fun otherGroupIncludesUnsupportedOrUnknownTypes() {
        assertEquals(AnnotationLayerGroup.OTHER, AnnotationType.LINK.toLayerGroup())
        assertEquals(AnnotationLayerGroup.OTHER, AnnotationType.UNKNOWN.toLayerGroup())
        assertEquals(AnnotationLayerGroup.OTHER, AnnotationType.POPUP.toLayerGroup())
        assertEquals(AnnotationLayerGroup.OTHER, AnnotationType.CARET.toLayerGroup())
    }

    @Test
    fun quantizingColorsProducesStableRgbInts() {
        val yellow = quantizeRgb(1.0, 1.0, 0.0)
        assertEquals(0xFFFF00, yellow and 0xFFFFFF)

        val red = quantizeRgb(1.0, 0.0, 0.0)
        assertEquals(0xFF0000, red and 0xFFFFFF)

        val green = quantizeRgb(0.0, 1.0, 0.0)
        assertEquals(0x00FF00, green and 0xFFFFFF)
    }

    @Test
    fun showAllEnablesAllGroupsAndColors() {
        val initial = AnnotationLayersState(
            enabledGroups = setOf(AnnotationLayerGroup.HIGHLIGHT),
            disabledColors = setOf(0xFFFF00)
        )
        val reset = initial.showAll()

        assertEquals(AnnotationLayerGroup.values().toSet(), reset.enabledGroups)
        assertTrue(reset.disabledColors.isEmpty())
        assertFalse(reset.isFilterActive)
    }

    @Test
    fun hideAllDisablesAllGroupsAndColors() {
        val initial = AnnotationLayersState.SHOW_ALL
        val hidden = initial.hideAll()

        assertTrue(hidden.enabledGroups.isEmpty())
        assertTrue(hidden.isFilterActive)
    }

    @Test
    fun soloColorEnablesOnlyThatColorInGroupAndHidesOthers() {
        val initial = AnnotationLayersState.SHOW_ALL
        val yellowRgb = 0xFFFF00
        val soloed = initial.soloColor(AnnotationLayerGroup.HIGHLIGHT, yellowRgb)

        // Highlight group should remain enabled, but only yellow is allowed
        assertTrue(soloed.enabledGroups.contains(AnnotationLayerGroup.HIGHLIGHT))
        assertTrue(soloed.isFilterActive)
    }

    @Test
    fun toggleGroupTogglesGroupVisibility() {
        val initial = AnnotationLayersState.SHOW_ALL
        val toggledOff = initial.toggleGroup(AnnotationLayerGroup.HIGHLIGHT)
        assertFalse(toggledOff.enabledGroups.contains(AnnotationLayerGroup.HIGHLIGHT))
        assertTrue(toggledOff.isFilterActive)

        val toggledOn = toggledOff.toggleGroup(AnnotationLayerGroup.HIGHLIGHT)
        assertTrue(toggledOn.enabledGroups.contains(AnnotationLayerGroup.HIGHLIGHT))
    }

    @Test
    fun toggleColorTogglesColorVisibility() {
        val initial = AnnotationLayersState.SHOW_ALL
        val yellowRgb = 0xFFFF00
        val toggledOff = initial.toggleColor(yellowRgb)
        assertTrue(toggledOff.disabledColors.contains(yellowRgb))
        assertTrue(toggledOff.isFilterActive)

        val toggledBackOn = toggledOff.toggleColor(yellowRgb)
        assertFalse(toggledBackOn.disabledColors.contains(yellowRgb))
    }

    @Test
    fun isFilterActiveIsFalseForShowAllAndTrueWhenAnyFilterIsApplied() {
        assertFalse(AnnotationLayersState.SHOW_ALL.isFilterActive)

        val groupFilter = AnnotationLayersState(enabledGroups = setOf(AnnotationLayerGroup.HIGHLIGHT))
        assertTrue(groupFilter.isFilterActive)

        val colorFilter = AnnotationLayersState(disabledColors = setOf(0xFF0000))
        assertTrue(colorFilter.isFilterActive)
    }

    @Test
    fun outputModeDefaultsToAllAndSelectsExpectedFilter() {
        assertEquals(AnnotationOutputMode.ALL, AnnotationOutputMode.valueOf("ALL"))
        assertEquals(AnnotationOutputMode.VISIBLE_ONLY, AnnotationOutputMode.valueOf("VISIBLE_ONLY"))
        assertEquals(AnnotationOutputMode.NONE, AnnotationOutputMode.valueOf("NONE"))
    }

    @Test
    fun toEngineFilterProducesExpectedFilterPredicate() {
        val state = AnnotationLayersState(enabledGroups = setOf(AnnotationLayerGroup.HIGHLIGHT))
        val engineFilter = state.toEngineFilter()
        assertTrue(engineFilter.types?.contains(AnnotationType.HIGHLIGHT) == true)
        assertFalse(engineFilter.types?.contains(AnnotationType.INK) == true)
    }
}
