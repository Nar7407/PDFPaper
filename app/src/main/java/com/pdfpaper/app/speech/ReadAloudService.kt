package com.pdfpaper.app.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pdfpaper.app.R
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.DocumentAccess
import com.pdfpaper.app.ui.viewer.speech.AndroidSpeechSynthesis
import com.pdfpaper.app.ui.viewer.speech.FocusEvent
import com.pdfpaper.app.ui.viewer.speech.ReadAloudPlaybackState
import com.pdfpaper.app.ui.viewer.speech.ReadAloudSession
import com.pdfpaper.app.ui.viewer.speech.SleepOption
import com.pdfpaper.app.ui.viewer.speech.SpeechChapterList
import com.pdfpaper.app.ui.viewer.speech.SpeechSpeed
import com.pdfpaper.app.ui.viewer.speech.SpeechListener
import com.pdfpaper.app.ui.viewer.speech.SpeechPageSource
import com.pdfpaper.app.ui.viewer.speech.SpeechPosition
import com.pdfpaper.app.ui.viewer.speech.SpeechVoiceKey
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.speech.ChapterSource
import com.pdfpaper.engine.speech.SpeechChapter
import com.pdfpaper.engine.speech.SpeechExtractor
import com.pdfpaper.engine.speech.SpeechPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * The background reader (T64).
 *
 * It keeps reading with the screen off: a foreground service of the type
 * "media playback", a notification with the media style (so the lock screen
 * shows the usual transport controls) and a media session (so the headset
 * buttons work). The service owns the document and the speech engine, so the
 * viewer and the service can never disagree about what is being read; the
 * viewer only sends commands and watches [ReadAloudHub].
 *
 * The document is opened by the service itself, from the location the viewer
 * hands over. The PDF is only ever read - the bytes on disk never change.
 */
class ReadAloudService : Service() {

    companion object {
        const val ACTION_START = "com.pdfpaper.app.speech.START"
        const val ACTION_STOP = "com.pdfpaper.app.speech.STOP"
        const val ACTION_PAUSE = "com.pdfpaper.app.speech.PAUSE"
        const val ACTION_RESUME = "com.pdfpaper.app.speech.RESUME"
        const val ACTION_NEXT_PARAGRAPH = "com.pdfpaper.app.speech.NEXT_PARAGRAPH"
        const val ACTION_PREVIOUS_PARAGRAPH = "com.pdfpaper.app.speech.PREVIOUS_PARAGRAPH"
        const val ACTION_NEXT_CHAPTER = "com.pdfpaper.app.speech.NEXT_CHAPTER"
        const val ACTION_PREVIOUS_CHAPTER = "com.pdfpaper.app.speech.PREVIOUS_CHAPTER"

        const val EXTRA_LOCATION = "location"
        const val EXTRA_DISPLAY_NAME = "displayName"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_PAGE = "page"
        const val EXTRA_PARAGRAPH = "paragraph"

        /** The notification's id, so the platform and the tests can find it. */
        const val NOTIFICATION_ID = 4201

        private const val CHANNEL_ID = "read_aloud"

        /** How long the speech engine may take to answer. */
        private const val ENGINE_TIMEOUT_MS = 15_000L

        /** The intent that starts reading a document. */
        fun startIntent(
            context: Context,
            location: String,
            displayName: String,
            password: String,
            position: SpeechPosition
        ): Intent = Intent(context, ReadAloudService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_LOCATION, location)
            putExtra(EXTRA_DISPLAY_NAME, displayName)
            putExtra(EXTRA_PASSWORD, password)
            putExtra(EXTRA_PAGE, position.pageIndex)
            putExtra(EXTRA_PARAGRAPH, position.paragraphIndex)
        }

        /** The intent for a transport command (pause, resume, next, stop). */
        fun commandIntent(context: Context, action: String): Intent =
            Intent(context, ReadAloudService::class.java).apply { this.action = action }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var session: ReadAloudSession? = null
    private var synthesis: AndroidSpeechSynthesis? = null
    private var mediaSession: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var noisyReceiver: BroadcastReceiver? = null

    /** The job that publishes the session's state; cancelled with the session. */
    private var publisher: Job? = null

    /** Checks the sleep countdown once a second while a reading runs. */
    private var sleepTicker: Job? = null

    /** The engine's first answer, awaited before the reading starts. */
    private var engineReady: CompletableDeferred<Pair<Boolean, String?>>? = null

    private var documentName = ""

    // --------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        createChannel()
        mediaSession = MediaSession(this, "ReadAloud").apply {
            setCallback(mediaCallback)
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
            // The platform sends the media keys to this receiver; it turns them
            // into the same commands the notification sends. The broadcast
            // receiver form exists from API 31; on 26-30 the notification's
            // media actions remain the only entry point (and this call would
            // otherwise fail at runtime on those devices).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setMediaButtonBroadcastReceiver(
                    ComponentName(this@ReadAloudService, ReadAloudMediaButtonReceiver::class.java)
                )
            }
            isActive = true
        }
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        noisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                session?.onFocusEvent(FocusEvent.NOISY)
            }
        }
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ReadAloudHub.commands = hubCommands
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            null -> return START_NOT_STICKY
            ACTION_START -> {
                // The foreground notification has to be up right away; the
                // document is opened afterwards and the notification is then
                // updated with the real title.
                startForegroundNow("Preparing the document", "")
                startReading(intent)
            }
            ACTION_STOP -> stopReading()
            else -> {
                // A media button can arrive while the app is in the
                // background; the platform would refuse the start then, so the
                // service goes back to the foreground before the command runs.
                startForegroundNow(
                    ReadAloudHub.state.value.documentName.ifBlank { documentName },
                    notificationText(ReadAloudHub.state.value)
                )
                dispatch(intent.action)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        noisyReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        noisyReceiver = null
        if (ReadAloudHub.commands === hubCommands) ReadAloudHub.commands = null
        publisher?.cancel()
        publisher = null
        session?.shutdown()
        session = null
        synthesis?.shutdown()
        synthesis = null
        abandonFocus()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        // The state is kept (only marked as not reading): the message about a
        // document that could not be opened must survive the service.
        val current = ReadAloudHub.state.value
        ReadAloudHub.publish(current.copy(active = false, speaking = false))
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- reading

    /** Opens the document and starts the session that reads it. */
    private fun startReading(intent: Intent) {
        val location = intent.getStringExtra(EXTRA_LOCATION).orEmpty()
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty()
        val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        val position = SpeechPosition(
            pageIndex = intent.getIntExtra(EXTRA_PAGE, 0),
            paragraphIndex = intent.getIntExtra(EXTRA_PARAGRAPH, 0)
        )
        stopCurrentReading()
        scope.launch {
            val opened = withContext(Dispatchers.IO) {
                DocumentAccess.open(this@ReadAloudService, location, password)
            }
            val document = opened.getOrElse { error ->
                failToStart("This document could not be opened: ${reason(error)}")
                null
            } ?: return@launch
            val source = withContext(Dispatchers.IO) { ServicePageSource(document.document) }
            documentName = displayName.ifBlank { document.location.displayName }

            // The engine is asked before the reading starts: speaking into an
            // engine that is not ready yet would only produce an error.
            val ready = CompletableDeferred<Pair<Boolean, String?>>()
            engineReady = ready
            val engine = AndroidSpeechSynthesis(
                this@ReadAloudService,
                speechListener,
                AppSettings.speechVoice(this@ReadAloudService)
            )
            synthesis = engine
            val answer = withTimeoutOrNull(ENGINE_TIMEOUT_MS) { ready.await() }
                ?: (false to "The speech engine did not start")
            engineReady = null
            if (!answer.first) {
                failToStart(answer.second ?: "This device has no speech engine")
                return@launch
            }

            val created = ReadAloudSession(
                synthesis = engine,
                pages = source,
                scope = scope,
                initialSpeed = AppSettings.speechSpeed(this@ReadAloudService),
                initialSleep = AppSettings.speechSleep(this@ReadAloudService)
            )
            session = created
            publisher = scope.launch {
                created.state.collect { state ->
                    ReadAloudHub.publish(state)
                    updateNotification(state)
                    updateMediaSession(state)
                    if (state.finished) finishReading()
                }
            }
            requestFocus()
            created.start(documentName, position)
            startSleepTicker()
            // The voice message comes after the start, which resets the state.
            created.onReady(true, answer.second)
        }
    }

    /** Runs one transport command. */
    private fun dispatch(action: String?) {
        when (action) {
            ACTION_PAUSE -> session?.pause()
            ACTION_RESUME -> session?.resume()
            ACTION_NEXT_PARAGRAPH -> session?.nextParagraph()
            ACTION_PREVIOUS_PARAGRAPH -> session?.previousParagraph()
            ACTION_NEXT_CHAPTER -> session?.nextChapter()
            ACTION_PREVIOUS_CHAPTER -> session?.previousChapter()
            else -> Unit
        }
    }

    /** Stops the reading but keeps the service (and its notification) alive. */
    private fun stopCurrentReading() {
        sleepTicker?.cancel()
        sleepTicker = null
        publisher?.cancel()
        publisher = null
        session?.stop()
        session = null
        synthesis?.shutdown()
        synthesis = null
    }

    /** The reading could not start: say why and stop the service. */
    private fun failToStart(message: String) {
        ReadAloudHub.publish(
            ReadAloudPlaybackState(active = false, speaking = false, message = message)
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** The reader pressed stop: nothing is read any more. */
    private fun stopReading() {
        stopCurrentReading()
        abandonFocus()
        ReadAloudHub.clear()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** The document ended: the service goes away, the message stays. */
    private fun finishReading() {
        stopCurrentReading()
        abandonFocus()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun reason(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: "unknown reason"

    // ----------------------------------------------------------------- sound

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val event = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> FocusEvent.GAIN
            AudioManager.AUDIOFOCUS_LOSS -> FocusEvent.LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusEvent.LOSS_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusEvent.LOSS_TRANSIENT_CAN_DUCK
            else -> return@OnAudioFocusChangeListener
        }
        session?.onFocusEvent(event)
    }

    private fun requestFocus() {
        val manager = getSystemService(AudioManager::class.java) ?: return
        val request = focusRequest ?: return
        manager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        val manager = getSystemService(AudioManager::class.java) ?: return
        val request = focusRequest ?: return
        manager.abandonAudioFocusRequest(request)
    }

    // ----------------------------------------------------------- speech bridge

    private val speechListener = object : SpeechListener {
        override fun onReady(ok: Boolean, message: String?) {
            val pending = engineReady
            if (pending != null) {
                engineReady = null
                pending.complete(ok to message)
                return
            }
            session?.onReady(ok, message)
        }

        override fun onRangeStart(utteranceId: String, start: Int, end: Int) {
            session?.onRangeStart(utteranceId, start)
        }

        override fun onDone(utteranceId: String) {
            session?.onUtteranceDone(utteranceId)
        }

        override fun onError(utteranceId: String, message: String) {
            session?.onUtteranceError(utteranceId, message)
        }
    }

    // ------------------------------------------------------------- the bridge

    private val hubCommands = object : ReadAloudHub.Commands {
        override fun start(
            location: String,
            displayName: String,
            password: String,
            position: SpeechPosition
        ) {
            // The service may already be running; the intent starts the
            // reading again, which is what the reader asked for.
            ContextCompat.startForegroundService(
                this@ReadAloudService,
                startIntent(this@ReadAloudService, location, displayName, password, position)
            )
        }

        override fun pause() {
            session?.pause()
        }

        override fun resume() {
            session?.resume()
        }

        override fun stop() {
            stopReading()
        }

        override fun nextParagraph() {
            session?.nextParagraph()
        }

        override fun previousParagraph() {
            session?.previousParagraph()
        }

        override fun nextChapter() {
            session?.nextChapter()
        }

        override fun previousChapter() {
            session?.previousChapter()
        }

        override fun setSpeed(rate: Float) {
            val safe = SpeechSpeed.nearest(rate)
            AppSettings.setSpeechSpeed(this@ReadAloudService, safe)
            session?.setSpeed(safe)
        }

        override fun setSleep(option: SleepOption) {
            AppSettings.setSpeechSleep(this@ReadAloudService, option)
            session?.setSleep(option)
        }

        override fun setVoice(key: SpeechVoiceKey?) {
            AppSettings.setSpeechVoice(this@ReadAloudService, key)
            val changed = session?.setVoice(key) ?: return
            if (!changed) {
                session?.showMessage("That voice is gone; the current voice is kept")
            }
        }
    }

    /**
     * The sleep timer is a wall-clock countdown, and the controller only checks
     * it when asked; this asks once a second while the service is reading.
     */
    private fun startSleepTicker() {
        sleepTicker?.cancel()
        sleepTicker = scope.launch {
            while (isActive) {
                delay(1_000L)
                session?.tick()
            }
        }
    }

    // -------------------------------------------------------- the media session

    private val mediaCallback = object : MediaSession.Callback() {
        override fun onPlay() {
            session?.resume()
        }

        override fun onPause() {
            session?.pause()
        }

        override fun onStop() {
            stopReading()
        }

        override fun onSkipToNext() {
            session?.nextParagraph()
        }

        override fun onSkipToPrevious() {
            session?.previousParagraph()
        }
    }

    private fun updateMediaSession(state: ReadAloudPlaybackState) {
        val session = mediaSession ?: return
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (state.speaking) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    0L,
                    1f
                )
                .build()
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.documentName)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, notificationText(state))
                .build()
        )
    }

    // --------------------------------------------------------- the notification

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Read aloud",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "The document that is read aloud"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundNow(title: String, text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(title, text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    private fun updateNotification(state: ReadAloudPlaybackState) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(
                state.documentName.ifBlank { documentName },
                notificationText(state)
            )
        )
    }

    /** The one line under the title: where the reading is. */
    private fun notificationText(state: ReadAloudPlaybackState): String {
        val where = state.chapterTitle ?: "Page ${state.pageIndex + 1}"
        val text = state.paragraphText
        return if (text.isBlank()) where else "$where - $text"
    }

    /**
     * The media notification.
     *
     * The platform's own media style is used (not the AndroidX one, which no
     * longer exists in the current AndroidX core): it is what puts the
     * transport controls on the lock screen.
     */
    private fun buildNotification(title: String, text: String): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_read_aloud)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_play),
                "Play",
                servicePendingIntent(ACTION_RESUME, 1)
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_next),
                "Next paragraph",
                servicePendingIntent(ACTION_NEXT_PARAGRAPH, 2)
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                "Previous paragraph",
                servicePendingIntent(ACTION_PREVIOUS_PARAGRAPH, 3)
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                "Pause",
                servicePendingIntent(ACTION_PAUSE, 4)
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Stop",
                servicePendingIntent(ACTION_STOP, 5)
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_next),
                "Next chapter",
                servicePendingIntent(ACTION_NEXT_CHAPTER, 6)
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                "Previous chapter",
                servicePendingIntent(ACTION_PREVIOUS_CHAPTER, 7)
            ).build()
        )
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            commandIntent(this, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    // ------------------------------------------------------------- page source

    /**
     * The pages of the open document, extracted one at a time.
     *
     * The chapters are the document's own outline; when it has none, the
     * headings of the pages already seen are used instead. Only the pages that
     * were really needed are extracted, so a long document costs no more than
     * the reading itself.
     */
    private class ServicePageSource(document: PdfDocument) : SpeechPageSource {

        private val pages = document.pages()
        private val chapters = AtomicReference(SpeechExtractor.chapters(document))
        private val source: ChapterSource =
            if (chapters.get().isEmpty()) ChapterSource.HEADING else ChapterSource.OUTLINE

        override suspend fun page(index: Int): SpeechPage? = withContext(Dispatchers.IO) {
            val page = pages.getOrNull(index) ?: return@withContext null
            val extracted = SpeechExtractor.extract(page).getOrNull() ?: return@withContext null
            chapters.set(SpeechExtractor.mergeHeadings(chapters.get(), extracted))
            extracted
        }

        override suspend fun chapters(): SpeechChapterList = withContext(Dispatchers.IO) {
            SpeechChapterList(chapters.get(), source)
        }

        override fun pageCount(): Int = pages.size
    }
}
