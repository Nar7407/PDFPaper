package com.pdfpaper.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class PdfEngineTest {

    @Test
    fun versionReturnsT0() {
        assertEquals("T0", PdfEngine().version())
    }
}
