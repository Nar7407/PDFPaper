package com.pdfpaper.app.data

import com.pdfpaper.engine.eink.EinkDither
import com.pdfpaper.engine.eink.EinkProfile
import com.pdfpaper.engine.eink.EinkSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task T63: which profile a document actually gets, and why. */
class EinkDisplayTest {

    private val device = EinkSettings(
        profile = EinkProfile.TEXT_BITONAL,
        threshold = 0.65,
        dither = EinkDither.FLOYD_STEINBERG
    )

    @Test
    fun theDeviceSwitchOffMeansOffWhateverTheDocumentWants() {
        val resolved = EinkResolution.resolve(
            device = device,
            enabled = false,
            documentProfile = EinkProfile.FULL_BITONAL
        )
        assertFalse(resolved.enabled)
        assertFalse(resolved.active)
        assertEquals(EinkSettings.OFF, resolved.settings)
        assertFalse(resolved.fromDocument)
    }

    @Test
    fun anEnabledDeviceUsesItsProfile() {
        val resolved = EinkResolution.resolve(device = device, enabled = true)
        assertTrue(resolved.enabled)
        assertEquals(EinkProfile.TEXT_BITONAL, resolved.settings.profile)
        assertFalse(resolved.fromDocument)
    }

    @Test
    fun aDocumentOverrideWinsOverTheDeviceProfile() {
        val resolved = EinkResolution.resolve(
            device = device,
            enabled = true,
            documentProfile = EinkProfile.GRAYSCALE
        )
        assertEquals(EinkProfile.GRAYSCALE, resolved.settings.profile)
        assertTrue(resolved.fromDocument)
    }

    @Test
    fun anExplicitOffOnOneDocumentStaysOff() {
        val resolved = EinkResolution.resolve(
            device = device,
            enabled = true,
            documentProfile = EinkProfile.OFF
        )
        assertTrue(resolved.enabled)
        assertFalse(resolved.settings.active)
        assertTrue(resolved.fromDocument)
    }

    @Test
    fun thePanelSettingsStayDeviceWide() {
        val resolved = EinkResolution.resolve(
            device = device,
            enabled = true,
            documentProfile = EinkProfile.FULL_BITONAL
        )
        assertEquals(0.65, resolved.settings.threshold, 0.0)
        assertEquals(EinkDither.FLOYD_STEINBERG, resolved.settings.dither)
    }

    @Test
    fun aStoredProfileIsNormalised() {
        val resolved = EinkResolution.resolve(
            device = device.copy(threshold = 9.0),
            enabled = true
        )
        assertEquals(EinkSettings.MAX_THRESHOLD, resolved.settings.threshold, 0.0)
    }
}
