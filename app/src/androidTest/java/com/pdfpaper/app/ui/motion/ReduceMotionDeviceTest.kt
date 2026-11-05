package com.pdfpaper.app.ui.motion

import android.app.Application
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.material.ripple.LocalRippleTheme
import androidx.compose.material.ripple.RippleTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.ui.theme.AppTheme
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T63 acceptance: with reduce motion on, the app root exposes the flag, swaps
 * the click indication and the Material ripple for no-op versions, and the
 * page-turn rules are pinned to instant whole-page snaps (covered on the JVM by
 * PageTurnModelTest and MotionTest). This is the wiring test that makes those
 * pure rules reach the real UI.
 */
@RunWith(AndroidJUnit4::class)
class ReduceMotionDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Application =
        ApplicationProvider.getApplicationContext<Application>()

    private var originalReduce = false
    private var originalSunlight = false

    @Before
    fun rememberSettings() {
        originalReduce = AppSettings.reduceMotion(context)
        originalSunlight = AppSettings.sunlightMode(context)
    }

    @After
    fun restoreSettings() {
        AppSettings.setReduceMotion(context, originalReduce)
        AppSettings.setSunlightMode(context, originalSunlight)
    }

    @Test
    fun theAppRootExposesReduceMotionAndRemovesTheRipple() {
        AppSettings.setReduceMotion(context, true)
        AppSettings.setSunlightMode(context, false)

        var reduce = false
        var indication: Indication? = null
        var ripple: RippleTheme? = null
        compose.setContent {
            AppTheme {
                reduce = LocalReduceMotion.current
                indication = LocalIndication.current
                ripple = LocalRippleTheme.current
                Text(if (reduce) "reduced" else "motion")
            }
        }
        compose.waitForIdle()

        assertTrue("the root must provide the reduce-motion flag", reduce)
        assertSame(NoIndication, indication)
        assertSame(NoRippleTheme, ripple)
        compose.onNodeWithText("reduced").assertExists()
    }

    @Test
    fun aSwitchInSettingsTakesEffectWithoutReopeningTheApp() {
        AppSettings.setReduceMotion(context, false)
        AppSettings.setSunlightMode(context, false)

        var reduce = false
        var sunlight = false
        compose.setContent {
            AppTheme {
                reduce = LocalReduceMotion.current
                sunlight = com.pdfpaper.app.ui.theme.LocalSunlightMode.current
                Text("live")
            }
        }
        compose.waitForIdle()
        org.junit.Assert.assertFalse(reduce)
        org.junit.Assert.assertFalse(sunlight)

        // Settings is a destination in this activity, so returning from it
        // does not fire ON_START. The settings listener must update the root
        // theme on the spot.
        compose.runOnUiThread {
            AppSettings.setReduceMotion(context, true)
            AppSettings.setSunlightMode(context, true)
        }
        compose.waitUntil(3_000) { reduce && sunlight }
        assertTrue(reduce)
        assertTrue(sunlight)
    }

    @Test
    fun sunlightPressesTheLightHighContrastTheme() {
        AppSettings.setReduceMotion(context, false)
        AppSettings.setSunlightMode(context, true)

        var sunlight = false
        var background = Color.Unspecified
        compose.setContent {
            AppTheme {
                sunlight = com.pdfpaper.app.ui.theme.LocalSunlightMode.current
                background = MaterialTheme.colorScheme.background
                Text(if (sunlight) "sunlight" else "normal")
            }
        }
        compose.waitForIdle()

        assertTrue("sunlight must be provided to the viewer", sunlight)
        compose.onNodeWithText("sunlight").assertExists()
        // The app root forces a white, high-contrast palette.
        org.junit.Assert.assertEquals(Color.White, background)
    }
}
