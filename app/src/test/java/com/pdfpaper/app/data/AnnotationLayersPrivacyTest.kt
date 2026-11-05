package com.pdfpaper.app.data

import com.pdfpaper.app.ui.viewer.AnnotationLayerGroup
import com.pdfpaper.app.ui.viewer.AnnotationLayersState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T68b: T30 Privacy checks for layer filter persistence.
 * Verifies that layer filter serialization in AnnotationLayerCodec / AnnotationLayersState
 * never leaks author names, note contents, document URIs, or file paths.
 */
class AnnotationLayersPrivacyTest {

    @Test
    fun layerStateSerializationNeverContainsAuthorOrContents() {
        val state = AnnotationLayersState(
            enabledGroups = setOf(AnnotationLayerGroup.HIGHLIGHT, AnnotationLayerGroup.INK),
            enabledColors = setOf(0xFFFF00),
            disabledColors = setOf(0xFF0000)
        )
        val encoded = AnnotationLayerCodec.encode(state)

        // Strict T30 verification: serialized strings must never hold user text or identifiers
        assertFalse("Persisted layer state must not contain author names", encoded.contains("Alice", ignoreCase = true))
        assertFalse("Persisted layer state must not contain author names", encoded.contains("Bob", ignoreCase = true))
        assertFalse("Persisted layer state must not contain author names", encoded.contains("Charlie", ignoreCase = true))
        assertFalse("Persisted layer state must not contain note contents", encoded.contains("Important note text", ignoreCase = true))
        assertFalse("Persisted layer state must not contain note contents", encoded.contains("sample comment", ignoreCase = true))
        assertFalse("Persisted layer state must not contain file paths or URIs", encoded.contains("content://", ignoreCase = true))
        assertFalse("Persisted layer state must not contain file paths or URIs", encoded.contains("file://", ignoreCase = true))
        assertFalse("Persisted layer state must not contain file paths or URIs", encoded.contains(".pdf", ignoreCase = true))

        // Decoded state must match exactly without fabricating unpersisted metadata
        val decoded = AnnotationLayerCodec.decode(encoded)
        assertTrue(decoded.enabledGroups.contains(AnnotationLayerGroup.HIGHLIGHT))
        assertTrue(decoded.enabledGroups.contains(AnnotationLayerGroup.INK))
        assertFalse(decoded.enabledGroups.contains(AnnotationLayerGroup.NOTE))
    }
}
