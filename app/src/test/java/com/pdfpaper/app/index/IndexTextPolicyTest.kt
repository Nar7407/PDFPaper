package com.pdfpaper.app.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexTextPolicyTest {

    @Test
    fun whitespaceIsNormalised() {
        assertEquals("one two three", IndexTextPolicy.normalize("  one\n\ntwo\t three  "))
    }

    @Test
    fun blankPagesAreRecognised() {
        assertFalse(IndexTextPolicy.hasText("   \n\t"))
        assertTrue(IndexTextPolicy.hasText("42"))
        assertTrue(IndexTextPolicy.hasText("नमस्ते"))
    }

    @Test
    fun onePageIsCapped() {
        val long = "word ".repeat(IndexTextPolicy.MAX_PAGE_CHARS)
        val normalized = IndexTextPolicy.normalize(long)

        assertTrue(normalized.length <= IndexTextPolicy.MAX_PAGE_CHARS)
        assertTrue(normalized.startsWith("word word"))
    }
}
