package com.pdfpaper.app.data

import com.pdfpaper.app.ui.viewer.AnnotationLayerGroup
import com.pdfpaper.app.ui.viewer.AnnotationLayersState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AnnotationLayerCodec] and storage contract (T68).
 */
class AnnotationLayerStoreTest {

    @Test
    fun encodeAndDecodeRoundTrip() {
        val state = AnnotationLayersState(
            enabledGroups = setOf(AnnotationLayerGroup.HIGHLIGHT, AnnotationLayerGroup.INK),
            enabledColors = setOf(0xFFFF00),
            disabledColors = setOf(0xFF0000)
        )
        val encoded = AnnotationLayerCodec.encode(state)
        val decoded = AnnotationLayerCodec.decode(encoded)

        assertEquals(state.enabledGroups, decoded.enabledGroups)
        assertEquals(state.enabledColors, decoded.enabledColors)
        assertEquals(state.disabledColors, decoded.disabledColors)
    }

    @Test
    fun corruptOrMalformedSavedStateFallsBackToShowAll() {
        assertEquals(AnnotationLayersState.SHOW_ALL, AnnotationLayerCodec.decode("gibberish;not-valid"))
        assertEquals(AnnotationLayersState.SHOW_ALL, AnnotationLayerCodec.decode(""))
        assertEquals(AnnotationLayersState.SHOW_ALL, AnnotationLayerCodec.decode(null))
    }

    @Test
    fun unknownVersionRejectedToShowAll() {
        val futureVersionEncoded = "999|HIGHLIGHT|0xFFFF00"
        val decoded = AnnotationLayerCodec.decode(futureVersionEncoded)
        assertEquals(AnnotationLayersState.SHOW_ALL, decoded)
    }

    @Test
    fun privacyPreservedNoAuthorsOrAnnotationTextAreStored() {
        val state = AnnotationLayersState(
            enabledGroups = setOf(AnnotationLayerGroup.HIGHLIGHT),
            enabledColors = setOf(0xFFFF00)
        )
        val encoded = AnnotationLayerCodec.encode(state)

        // Must not contain any author names, note contents, or document paths
        assertFalse(encoded.contains("Alice", ignoreCase = true))
        assertFalse(encoded.contains("Bob", ignoreCase = true))
        assertFalse(encoded.contains("content://", ignoreCase = true))
        assertFalse(encoded.contains(".pdf", ignoreCase = true))
    }

    @Test
    fun defaultStateEncodesAndDecodesCleanly() {
        val defaultEncoded = AnnotationLayerCodec.encode(AnnotationLayersState.SHOW_ALL)
        val decoded = AnnotationLayerCodec.decode(defaultEncoded)

        assertEquals(AnnotationLayersState.SHOW_ALL, decoded)
        assertFalse(decoded.isFilterActive)
    }

    @Test
    fun unknownGroupOrGarbageColorIgnoredGracefully() {
        val rawWithUnknowns = "1|HIGHLIGHT,FUTURE_GROUP|0xFFFF00,not_a_color"
        val decoded = AnnotationLayerCodec.decode(rawWithUnknowns)

        // HIGHLIGHT should be preserved, unknown group ignored
        assertTrue(decoded.enabledGroups.contains(AnnotationLayerGroup.HIGHLIGHT))
        assertFalse(decoded.enabledGroups.any { it.name == "FUTURE_GROUP" })
    }
}
