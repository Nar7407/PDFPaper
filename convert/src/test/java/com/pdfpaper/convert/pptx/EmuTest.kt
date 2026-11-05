package com.pdfpaper.convert.pptx

import kotlin.test.Test
import kotlin.test.assertEquals

class EmuTest {

    @Test
    fun `72 points is one inch`() {
        assertEquals(EMU_PER_INCH, Emu.ofPoints(72.0))
        assertEquals(EMU_PER_INCH, Emu.ofInches(1.0))
    }

    @Test
    fun `point conversion rounds to the nearest EMU`() {
        assertEquals(12700L, Emu.ofPoints(1.0))
        assertEquals(6350L, Emu.ofPoints(0.5))
        assertEquals(304800L, Emu.ofPoints(24.0))
    }

    @Test
    fun `inch conversion matches points`() {
        assertEquals(Emu.ofPoints(36.0), Emu.ofInches(0.5))
    }
}
