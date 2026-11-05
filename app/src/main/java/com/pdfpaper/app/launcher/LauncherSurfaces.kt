package com.pdfpaper.app.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import com.pdfpaper.app.MainActivity
import com.pdfpaper.app.R
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.LauncherLabels
import com.pdfpaper.app.data.RecentCodec
import com.pdfpaper.app.data.RecentFile
import com.pdfpaper.app.data.RecentStore
import com.pdfpaper.app.widget.PdfPaperWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps the launcher shortcuts and the home-screen widget in step with the
 * recent list (task T48).
 *
 * Nothing runs in the background: [onRecentsChanged] is called once when the
 * list is written, and [refresh] once when the privacy setting changes. The
 * widget itself has no update period (`updatePeriodMillis="0"`).
 */
object LauncherSurfaces {

    private const val MAX_DYNAMIC = 3
    private const val PINNED_PREFS = "pdfpaper_pinned_shortcuts"
    private const val PINNED_KEY = "shortcuts"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Called by [RecentStore] after a document is added or the list is cleared. */
    fun onRecentsChanged(context: Context) {
        refresh(context)
    }

    /** Update dynamic shortcuts, pinned labels and the widget in the background. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            updateDynamicShortcuts(app)
            updatePinnedShortcutLabels(app)
            updateWidget(app)
        }
    }

    /**
     * Rebuilds the three recent shortcuts. This is synchronous so a device test
     * can inspect the result; production callers go through [refresh].
     */
    fun updateDynamicShortcuts(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val showNames = AppSettings.showLauncherNames(context)
        val recents = RecentStore(context).list()
            .filter { it.uri.isNotBlank() }
            .take(minOf(MAX_DYNAMIC, manager.maxShortcutCountPerActivity.coerceAtLeast(0)))
        val shortcuts = recents.mapIndexed { index, file ->
            ShortcutInfo.Builder(context, "recent-${index + 1}")
                .setShortLabel(LauncherLabels.recentLabel(index, file.displayName, showNames))
                .setLongLabel(
                    LauncherLabels.recentLongLabel(index, file.displayName, showNames)
                )
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_document))
                .setIntent(recentIntent(context, file))
                .setRank(index)
                .build()
        }
        // A launcher may reject an update while the app is rate-limited; that
        // must never crash the open flow. The next recent change tries again.
        runCatching { manager.setDynamicShortcuts(shortcuts) }
    }

    /**
     * Pinned shortcuts are created by the user, so they are not removed when
     * the privacy setting changes; only their labels are rewritten.
     */
    fun updatePinnedShortcutLabels(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val existing = runCatching {
            manager.pinnedShortcuts.map { it.id }.toSet()
        }.getOrElse { return }
        if (existing.isEmpty()) return
        val showNames = AppSettings.showLauncherNames(context)
        val updates = PinnedShortcutStore(context).list()
            .filter { LauncherLabels.pinnedId(it.uri) in existing }
            .map { file ->
                pinnedShortcut(context, file, showNames)
            }
        if (updates.isEmpty()) return
        runCatching { manager.updateShortcuts(updates) }
    }

    /**
     * Asks the launcher to pin one document. Returns false when the launcher
     * does not support pinned shortcuts; the caller shows that instead of
     * pretending the shortcut exists.
     */
    fun requestPin(context: Context, file: RecentFile): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false
        val requested = runCatching {
            manager.requestPinShortcut(
                pinnedShortcut(context, file, AppSettings.showLauncherNames(context)),
                null
            )
        }.getOrDefault(false)
        if (requested) {
            PinnedShortcutStore(context).add(file)
        }
        return requested
    }

    /** Starts the app from the widget's Open/Scan buttons. */
    fun actionIntent(context: Context, action: LauncherAction): Intent =
        Intent(context, MainActivity::class.java).apply {
            this.action = when (action) {
                LauncherAction.OPEN_PDF -> LauncherActions.ACTION_OPEN_PDF
                LauncherAction.SCAN -> LauncherActions.ACTION_SCAN
                LauncherAction.MERGE -> LauncherActions.ACTION_MERGE
                LauncherAction.RECENT -> LauncherActions.ACTION_RECENT
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    /** Starts the app on one recent document. */
    fun recentIntent(context: Context, file: RecentFile): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(file.uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    suspend fun updateWidget(context: Context) {
        runCatching { PdfPaperWidget().updateAll(context.applicationContext) }
    }

    private fun pinnedShortcut(
        context: Context,
        file: RecentFile,
        showNames: Boolean
    ): ShortcutInfo =
        ShortcutInfo.Builder(context, LauncherLabels.pinnedId(file.uri))
            .setShortLabel(LauncherLabels.pinnedLabel(file.displayName, showNames))
            .setLongLabel(LauncherLabels.pinnedLongLabel(file.displayName, showNames))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_document))
            .setIntent(recentIntent(context, file))
            .build()

    /** The documents the user pinned, kept separately so labels can follow the setting. */
    private class PinnedShortcutStore(context: Context) {

        private val preferences = context.applicationContext
            .getSharedPreferences(PINNED_PREFS, Context.MODE_PRIVATE)

        fun list(): List<RecentFile> = RecentCodec.decode(preferences.getString(PINNED_KEY, null))

        fun add(file: RecentFile) {
            val next = RecentCodec.add(list(), file)
            preferences.edit().putString(PINNED_KEY, RecentCodec.encode(next)).apply()
        }
    }
}
