package com.pdfpaper.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.pdfpaper.app.data.IncomingIntents
import com.pdfpaper.app.data.IncomingRequest
import com.pdfpaper.app.index.IndexWork
import com.pdfpaper.app.input.ViewerKeyRouter
import com.pdfpaper.app.launcher.LauncherActions
import com.pdfpaper.app.launcher.LauncherLaunchRequest
import com.pdfpaper.app.present.PresentKeyRouter
import com.pdfpaper.app.ui.navigation.AppNav
import com.pdfpaper.app.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    /**
     * The untrusted request a VIEW/SEND intent asks the app to handle. Passed
     * into [AppNav], which routes it through the same careful open flow as the
     * picker. Cleared only after [AppNav] has consumed it.
     */
    private val incomingRequest = mutableStateOf<IncomingRequest?>(null)

    /**
     * A static shortcut or widget button waiting to be routed (T48). It is
     * carried separately from the launch intent so a configuration change does
     * not replay a picker or create a second scan.
     */
    private val launchRequest = mutableStateOf<LauncherLaunchRequest?>(null)
    private var nextLaunchRequestId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // T50: refresh the local search index once a day when the device lets
        // WorkManager run. With no folders this is a no-op.
        IndexWork.ensurePeriodic(this)
        incomingRequest.value = IncomingIntents.parse(intent)
        if (savedInstanceState == null) {
            launchRequest.value = LauncherActions.request(intent, ++nextLaunchRequestId)
        }
        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNav(
                        incomingRequest = incomingRequest.value,
                        onIncomingConsumed = { id ->
                            if (incomingRequest.value?.id == id) {
                                incomingRequest.value = null
                            }
                        },
                        launchRequest = launchRequest.value,
                        onLaunchConsumed = { id ->
                            if (launchRequest.value?.id == id) {
                                launchRequest.value = null
                            }
                        }
                    )
                }
            }
        }
    }

    /**
     * T53: while a presentation is running, its key handler gets clicker,
     * keyboard, media and volume keys before the platform. Consuming both the
     * press and the release keeps a mapped volume key from changing volume.
     *
     * AndroidX marks ComponentActivity.dispatchKeyEvent as library-restricted
     * even though Activity declares it public; overriding it is the documented
     * way to intercept keys before they reach the window, so suppress the
     * false positive rather than replacing it with onKeyDown (which runs after
     * the window has already handled the volume keys).
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Presentation first: while a deck is on screen the volume keys belong
        // to the presenter, and its handler is only installed in that mode.
        if (PresentKeyRouter.dispatch(event)) return true
        // Then the reading viewer (T58). Its handler is null unless a document
        // is in front with the volume-key setting on and no dialog open.
        if (ViewerKeyRouter.dispatch(event)) return true
        return super.dispatchKeyEvent(event)
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Do not replace the activity's launch intent: the activity keeps the
        // one it was started with, and each request is carried separately.
        IncomingIntents.parse(intent)?.let { incomingRequest.value = it }
        LauncherActions.request(intent, ++nextLaunchRequestId)?.let {
            launchRequest.value = it
        }
    }
}
