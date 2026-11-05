package com.pdfpaper.app.launcher

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.RecentStore
import com.pdfpaper.app.widget.PdfPaperWidgetReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device checks for the launcher surfaces (T48). They use the real
 * ShortcutManager and AppWidgetManager; the widget itself is rendered by the
 * launcher and is checked by its provider registration here.
 */
@RunWith(AndroidJUnit4::class)
class LauncherSurfacesDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shortcuts = context.getSystemService(ShortcutManager::class.java)
    private val store = RecentStore(context)

    @Before
    fun reset() {
        AppSettings.setShowLauncherNames(context, false)
        store.clear()
        LauncherSurfaces.updateDynamicShortcuts(context)
        // RecentStore.clear starts the event-driven refresh on a background
        // coroutine; let it finish before the test inspects the manager.
        Thread.sleep(300)
        LauncherSurfaces.updateDynamicShortcuts(context)
    }

    @After
    fun cleanup() {
        AppSettings.setShowLauncherNames(context, false)
        store.clear()
        LauncherSurfaces.updateDynamicShortcuts(context)
    }

    @Test
    fun staticShortcutsAndTheWidgetProviderAreDeclared() {
        assertEquals(
            setOf("scan", "open_pdf", "merge_pdfs", "recent_files"),
            shortcuts.manifestShortcuts.map { it.id }.toSet()
        )

        val providers = AppWidgetManager.getInstance(context).installedProviders
        assertTrue(
            providers.any {
                it.provider.packageName == context.packageName &&
                    it.provider.className == PdfPaperWidgetReceiver::class.java.name
            }
        )
    }

    @Test
    fun dynamicShortcutsHideNamesUntilThePrivacySettingIsOn() {
        store.add("content://launcher-test/one", "Private acquisition plan.pdf", "pdf")
        store.add("content://launcher-test/two", "Salary review.pdf", "pdf")
        Thread.sleep(400)
        LauncherSurfaces.updateDynamicShortcuts(context)

        val hidden = shortcuts.dynamicShortcuts.associate {
            it.id to it.shortLabel?.toString().orEmpty()
        }
        assertEquals(2, hidden.size)
        assertEquals("Recent PDF 1", hidden["recent-1"])
        assertEquals("Recent PDF 2", hidden["recent-2"])
        assertTrue(hidden.values.none { it.contains("Private") })

        AppSettings.setShowLauncherNames(context, true)
        LauncherSurfaces.updateDynamicShortcuts(context)

        val visible = shortcuts.dynamicShortcuts.associate {
            it.id to it.shortLabel?.toString().orEmpty()
        }
        assertEquals("Salary review.pdf", visible["recent-1"])
        assertEquals("Private acquisition plan.pdf", visible["recent-2"])
    }

    @Test
    fun pinnedAndRecentActionsAreExplicitAndNeverTrustAnExtension() {
        assertEquals(
            LauncherAction.OPEN_PDF,
            LauncherActions.parse(Intent(LauncherActions.ACTION_OPEN_PDF))
        )
        assertEquals(
            LauncherAction.SCAN,
            LauncherActions.parse(Intent(LauncherActions.ACTION_SCAN))
        )
        assertEquals(
            LauncherAction.MERGE,
            LauncherActions.parse(Intent(LauncherActions.ACTION_MERGE))
        )
        assertEquals(
            LauncherAction.RECENT,
            LauncherActions.parse(Intent(LauncherActions.ACTION_RECENT))
        )
        assertNull(LauncherActions.parse(Intent(Intent.ACTION_MAIN)))
        assertNull(LauncherActions.parse(null))

        val file = com.pdfpaper.app.data.RecentFile(
            "content://launcher-test/one",
            "Private acquisition plan.pdf"
        )
        val hidden = LauncherSurfaces.recentIntent(context, file)
        assertEquals(Intent.ACTION_VIEW, hidden.action)
        assertEquals("content://launcher-test/one", hidden.data.toString())
        assertTrue(hidden.component?.className == "com.pdfpaper.app.MainActivity")
    }

    @Test
    fun thePrivacyChoiceIsStoredAndDefaultsOff() {
        AppSettings.setShowLauncherNames(context, false)
        assertFalse(AppSettings.showLauncherNames(context))
        AppSettings.setShowLauncherNames(context, true)
        assertTrue(AppSettings.showLauncherNames(context))
        AppSettings.setShowLauncherNames(context, false)
        assertFalse(AppSettings.showLauncherNames(context))
    }
}
