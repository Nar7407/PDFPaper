package com.pdfpaper.app.data

import com.pdfpaper.engine.eink.EinkProfile
import com.pdfpaper.engine.eink.EinkSettings

/**
 * Task T63: the E-Ink state of one open document.
 *
 * [enabled] is the device switch; [settings] is what the renderer is handed
 * (OFF when the switch is off). [fromDocument] is true when this document has
 * its own profile instead of following the device, so "Reset to device" can be
 * enabled only when it has work to do.
 */
data class EinkDisplay(
    val settings: EinkSettings = EinkSettings.OFF,
    val enabled: Boolean = false,
    val fromDocument: Boolean = false
) {
    val active: Boolean get() = settings.active
}

/**
 * The one resolution rule of T63, pure so it is unit-tested:
 *
 *  - the device switch off means off, whatever the document wants;
 *  - otherwise a document override wins (including an explicit [EinkProfile.OFF] —
 *    a blueprint can stay as authored while the rest of the library is 1-bit);
 *  - otherwise the device profile is used.
 *
 * Threshold, dither and minimum line width are device-wide: they are how the
 * panel is driven, not a property of a file.
 */
object EinkResolution {

    fun resolve(
        device: EinkSettings,
        enabled: Boolean,
        documentProfile: EinkProfile? = null
    ): EinkDisplay {
        if (!enabled) return EinkDisplay(EinkSettings.OFF, enabled = false, fromDocument = false)
        val profile = documentProfile ?: device.profile
        return EinkDisplay(
            settings = device.copy(profile = profile).normalized(),
            enabled = true,
            fromDocument = documentProfile != null
        )
    }
}
