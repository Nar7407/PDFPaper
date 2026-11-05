# T64 - Read aloud in the background

**Goal:** a document keeps being read aloud with the screen off. A foreground
service owns the reading; the notification and the lock screen carry the
transport controls; the viewer shows the bar and the spoken word.

**Authority:** `docs/superpowers/specs/2026-11-01-t64-background-read-aloud.md`.

**Why this plan exists:** T34 built the pure state machine
(`ReadAloudController`) and the model, but its Tasks 6-7 (the TTS adapter and
the viewer wiring) were never written, and nothing kept reading with the
screen off. T64 folds the essential parts of T34's Tasks 6-7 in and adds the
background service.

**Rules for the whole plan:**
- No new dependency. The platform's media APIs are used (`MediaSession`,
  `Notification.MediaStyle`, `AudioFocusRequest`).
- `:engine` is not touched. The PDF is only ever read; the bytes on disk never
  change.
- Offline voices only. A network voice is never offered.
- TDD: watch the test fail, then make it pass, then commit - one commit per
  task, with only that task's files.
- The shared files (`AppSettings.kt`, `AndroidManifest.xml`, `ViewerScreen.kt`,
  `ViewerViewModel.kt`, `ViewerToolsSheet.kt`, `ToolRegistry.kt`, `UiTags.kt`,
  `ToolRegistryTest.kt`) carry other tasks' uncommitted work. They are staged
  as "HEAD's file + only the T64 lines", never as the working copy.

**Build on this machine:** `$env:JAVA_HOME = "C:\Tools\jdk-17"` before every
Gradle run, plus `-Pkotlin.incremental=false
-Dkotlin.compiler.execution.strategy=in-process`. The wrapper ignores
`gradle.java.home`, and the Android Studio JBR (OpenJDK 25.0.2) is rejected by
Gradle 8.7.

---

### Task 1: The speech-engine seam and the voice list

**Files:**
- Create: `app/src/main/java/com/pdfpaper/app/ui/viewer/speech/SpeechSynthesis.kt`
- Create: `app/src/main/java/com/pdfpaper/app/ui/viewer/speech/AndroidSpeechSynthesis.kt`
- Modify: `app/src/main/java/com/pdfpaper/app/data/AppSettings.kt`
- Test: `app/src/test/java/com/pdfpaper/app/ui/viewer/speech/SpeechVoiceListTest.kt`

**Interfaces:**
- Consumes: T34's `SpeechVoiceKey` (`app/src/main/java/com/pdfpaper/app/ui/viewer/speech/SpeechModel.kt`).
- Produces:

```kotlin
// SpeechSynthesis.kt
data class SpeechVoice(val key: SpeechVoiceKey, val label: String, val offline: Boolean)

interface SpeechListener {
    fun onReady(ok: Boolean, message: String?)
    fun onRangeStart(utteranceId: String, start: Int, end: Int)
    fun onDone(utteranceId: String)
    fun onError(utteranceId: String, message: String)
}

interface SpeechSynthesis {
    fun speak(text: String, utteranceId: String, rate: Float)
    fun stop()
    fun shutdown()
}

object SpeechVoices {
    /** The offline voices of the device, sorted by locale then name. */
    fun list(context: Context): List<SpeechVoice>
    /** The voice with this key, or null when the device no longer has it. */
    fun resolve(context: Context, key: SpeechVoiceKey?): SpeechVoice?
    /** The pure filter: offline only, sorted by locale then name. */
    internal fun usable(voices: List<SpeechVoice>): List<SpeechVoice>
}

// AndroidSpeechSynthesis.kt
class AndroidSpeechSynthesis(
    context: Context,
    listener: SpeechListener,
    voice: SpeechVoiceKey? = null
) : SpeechSynthesis

// AppSettings.kt
fun speechSpeed(context: Context): Float          // default 1.0, clamped to 0.5..2.0
fun setSpeechSpeed(context: Context, value: Float)
fun speechVoice(context: Context): SpeechVoiceKey?
fun setSpeechVoice(context: Context, key: SpeechVoiceKey?)
```

- [x] **Step 1: Write the failing test**

`SpeechVoiceListTest.kt` (JVM, no Android objects - the filter is pure):

```kotlin
@Test fun aNetworkVoiceIsNeverOffered()
@Test fun voicesAreSortedByLocaleThenName()
@Test fun resolvingAMissingVoiceReturnsNull()
```

`usable` keeps only `offline == true` and sorts by `key.locale` then `key.name`; `resolve` returns null for a key that is not in the list.

- [x] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*SpeechVoiceListTest" --console=plain`
Expected: FAIL - `SpeechVoice`/`SpeechVoices` do not exist.

- [x] **Step 3: Implement**

`SpeechSynthesis.kt`: the data class, the listener, the interface and `SpeechVoices` (`list` reads `TextToSpeech` voices through a short-lived engine and filters with `usable`; `resolve` looks the key up in `list`).

`AndroidSpeechSynthesis.kt`: `TextToSpeech(context, OnInitListener)`; `onInit` calls `listener.onReady(ok, message)` and applies the voice when `SpeechVoices.resolve` finds it, otherwise reports "The saved voice is gone, so the default voice is used"; `setOnUtteranceProgressListener` forwards `onRangeStart`/`onDone`/`onError`; `speak` uses `QUEUE_FLUSH` with the utterance id and clamps the rate to 0.5..2.0; `stop`/`shutdown` are idempotent.

`AppSettings.kt`: `speech.speed` (float, default 1.0) and `speech.voice` (the `SpeechVoiceKey.encode()` string), following the existing `prefs(context)` pattern.

- [x] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*SpeechVoiceListTest" --console=plain`
Expected: PASS (3 tests).

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/pdfpaper/app/ui/viewer/speech/SpeechSynthesis.kt app/src/main/java/com/pdfpaper/app/ui/viewer/speech/AndroidSpeechSynthesis.kt app/src/main/java/com/pdfpaper/app/data/AppSettings.kt app/src/test/java/com/pdfpaper/app/ui/viewer/speech/SpeechVoiceListTest.kt
git commit -m "feat(app): offline speech-engine seam and the stored voice"
```

---

### Task 2: The playback executor and the focus rules

**Files:**
- Create: `app/src/main/java/com/pdfpaper/app/ui/viewer/speech/SpeechPageSource.kt`
- Create: `app/src/main/java/com/pdfpaper/app/ui/viewer/speech/ReadAloudSession.kt`
- Create: `app/src/main/java/com/pdfpaper/app/ui/viewer/speech/AudioFocusPolicy.kt`
- Test: `app/src/test/java/com/pdfpaper/app/ui/viewer/speech/ReadAloudSessionTest.kt`
- Test: `app/src/test/java/com/pdfpaper/app/ui/viewer/speech/AudioFocusPolicyTest.kt`

**Interfaces:**
- Consumes: Task 1's `SpeechSynthesis`/`SpeechListener`; T34's `ReadAloudController`, `SpeechAction`, `SpeechPosition`, `SpeechChapterList`, `SpeechState`; the engine's `SpeechPage`, `SpeechChapter`, `TextRect`.
- Produces:

```kotlin
// SpeechPageSource.kt
interface SpeechPageSource {
    /** The page, extracted on demand; null when the page does not exist. */
    suspend fun page(index: Int): SpeechPage?
    /** The chapters known so far (the outline plus the headings seen). */
    suspend fun chapters(): SpeechChapterList
    /** The document's page count. */
    fun pageCount(): Int
}

// ReadAloudSession.kt
data class ReadAloudPlaybackState(
    val active: Boolean = false,
    val speaking: Boolean = false,
    val documentName: String = "",
    val pageIndex: Int = 0,
    val paragraphIndex: Int = 0,
    val chapterTitle: String? = null,
    val paragraphText: String = "",
    val wordPageIndex: Int = -1,
    val wordRects: List<TextRect> = emptyList(),
    val paragraphOnly: Boolean = false,
    val message: String? = null,
    val finished: Boolean = false,
    val scrollPageIndex: Int = -1,
    val scrollFraction: Double = 0.0,
    /** Bumped on every scroll request, so the viewer consumes it once. */
    val scrollToken: Long = 0L
)

class ReadAloudSession(
    synthesis: SpeechSynthesis,
    pages: SpeechPageSource,
    scope: CoroutineScope,
    initialSpeed: Float = 1f
) {
    val state: StateFlow<ReadAloudPlaybackState>
    suspend fun start(documentName: String, position: SpeechPosition)
    fun pause(message: String? = null)
    fun resume()
    fun stop()
    fun nextParagraph()
    fun previousParagraph()
    fun nextChapter()
    fun previousChapter()
    fun setSpeed(rate: Float)
    fun onRangeStart(utteranceId: String, start: Int)
    fun onUtteranceDone(utteranceId: String)
    fun onUtteranceError(utteranceId: String, message: String)
    fun onFocusEvent(event: FocusEvent)
}

// AudioFocusPolicy.kt
enum class FocusEvent { GAIN, LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK, DUCK, NOISY }
enum class FocusAction { NONE, PAUSE, RESUME }
object AudioFocusPolicy {
    fun action(event: FocusEvent): FocusAction
}
```

- [x] **Step 1: Write the failing tests**

`ReadAloudSessionTest.kt` (JVM; a fake `SpeechSynthesis` records the spoken text and a fake `SpeechPageSource` serves two pages):

```kotlin
@Test fun startingSpeaksTheFirstParagraph()
@Test fun finishingAParagraphMovesToTheNext()
@Test fun aWordRangePublishesTheWordBoxes()
@Test fun aPageWithoutTextIsReportedAndSkipped()
@Test fun aMissingPageIsFetchedAndTheReadingContinues()
@Test fun pausingStopsTheSpeechAndResumingSpeaksAgain()
@Test fun focusLossPausesAndTheGainResumes()
@Test fun theNoiseEventPausesWithoutResuming()
@Test fun stoppingClearsTheWordBoxes()
```

`AudioFocusPolicyTest.kt`:

```kotlin
@Test fun aPermanentLossPauses()
@Test fun aTransientLossPausesAndTheGainResumes()
@Test fun aDuckIsIgnored()
@Test fun theNoiseEventPauses()
```

Pinned: a transient loss is `PAUSE` and the following `GAIN` is `RESUME`; `LOSS_TRANSIENT_CAN_DUCK` and `DUCK` are `NONE`; `NOISY` is `PAUSE` and the next `GAIN` is `NONE` (a headphone unplug is not a focus event).

- [x] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*ReadAloudSessionTest" --tests "*AudioFocusPolicyTest" --console=plain`
Expected: FAIL - the classes do not exist.

- [x] **Step 3: Implement**

`ReadAloudSession` owns a `ReadAloudController` and runs its actions:

| Controller action | The session does |
| --- | --- |
| `Speak` | `synthesis.speak(text, utteranceId, rate)` |
| `Highlight` | publishes `wordPageIndex`/`wordRects`/`paragraphOnly` (an empty rectangle list clears the highlight) |
| `ScrollTo` | publishes `scrollPageIndex`, `scrollFraction` and a `scrollToken` one higher |
| `NeedPage` | launches `pages.page(index)` in `scope` and calls `controller.onPageReady(page)`; a null page ends the reading with "End of the document" |
| `Paused` | publishes the message, `speaking = false` and calls `synthesis.stop()` |
| `Finished` | publishes `finished = true`, `speaking = false` and calls `synthesis.stop()` |

`start` loads `pages.chapters()`, builds the controller, calls `controller.start(position)` and runs the returned actions; the session's `onRangeStart`/`onUtteranceDone`/`onUtteranceError` forward to the controller, and the controller's `Highlight`/`StateChanged` actions are turned into the published state. `pause` remembers whether the pause came from a focus event (`onFocusEvent(LOSS_TRANSIENT)`), so `GAIN` resumes it; `NOISY` pauses without that flag.

`AudioFocusPolicy.action` maps: `GAIN` -> `RESUME`, `LOSS` -> `PAUSE`, `LOSS_TRANSIENT` -> `PAUSE`, `LOSS_TRANSIENT_CAN_DUCK`/`DUCK` -> `NONE`, `NOISY` -> `PAUSE`.

- [x] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*ReadAloudSessionTest" --tests "*AudioFocusPolicyTest" --console=plain`
Expected: PASS (13 tests).

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/pdfpaper/app/ui/viewer/speech/SpeechPageSource.kt app/src/main/java/com/pdfpaper/app/ui/viewer/speech/ReadAloudSession.kt app/src/main/java/com/pdfpaper/app/ui/viewer/speech/AudioFocusPolicy.kt app/src/test/java/com/pdfpaper/app/ui/viewer/speech/ReadAloudSessionTest.kt app/src/test/java/com/pdfpaper/app/ui/viewer/speech/AudioFocusPolicyTest.kt
git commit -m "feat(app): the read-aloud playback executor and the audio-focus rules"
```

---

### Task 3: The foreground service, the notification and the hub

**Files:**
- Create: `app/src/main/java/com/pdfpaper/app/speech/ReadAloudService.kt`
- Create: `app/src/main/java/com/pdfpaper/app/speech/ReadAloudHub.kt`
- Create: `app/src/main/java/com/pdfpaper/app/speech/ReadAloudMediaButtonReceiver.kt`
- Create: `app/src/main/res/drawable/ic_read_aloud.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/androidTest/java/com/pdfpaper/app/speech/ReadAloudServiceDeviceTest.kt`

**Interfaces:**
- Consumes: Task 1's `SpeechSynthesis`/`AndroidSpeechSynthesis`; Task 2's `ReadAloudSession`, `ReadAloudPlaybackState`, `SpeechPageSource`, `FocusEvent`, `AudioFocusPolicy`; T34's `SpeechPosition`; the engine's `SpeechExtractor`; `DocumentAccess.open`.
- Produces:

```kotlin
// ReadAloudHub.kt
object ReadAloudHub {
    val state: StateFlow<ReadAloudPlaybackState>
    fun publish(state: ReadAloudPlaybackState)
    fun clear()
    /** The commands the viewer sends; the service registers them on create. */
    interface Commands {
        fun start(location: String, displayName: String, password: String, position: SpeechPosition)
        fun pause()
        fun resume()
        fun stop()
        fun nextParagraph()
        fun previousParagraph()
        fun nextChapter()
        fun previousChapter()
    }
    var commands: Commands?
}

// ReadAloudService.kt
class ReadAloudService : Service() {
    companion object {
        const val ACTION_START = "com.pdfpaper.app.speech.START"
        const val ACTION_STOP = "com.pdfpaper.app.speech.STOP"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_DISPLAY_NAME = "displayName"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_PAGE = "page"
        const val EXTRA_PARAGRAPH = "paragraph"
        fun startIntent(context: Context, location: String, displayName: String, password: String, position: SpeechPosition): Intent
    }
}
```

- [ ] **Step 1: Write the failing device test**

`ReadAloudServiceDeviceTest.kt` (on `paperpilot34`):

```kotlin
@Test fun startingTheServicePostsAMediaNotificationAndAnActiveSession()
@Test fun aDocumentTheServiceCannotOpenReportsTheReason()
@Test fun theNoiseEventPausesTheReading()
```

The test builds a PDF in the app cache, starts the service with `startIntent`, waits for `ReadAloudHub.state.value.active`, and asserts that the service is running, that a notification with the media style exists (`NotificationManager.activeNotifications`), that the `MediaSession` is active, and that the original file's bytes are unchanged.

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.pdfpaper.app.speech.ReadAloudServiceDeviceTest" --console=plain`
Expected: FAIL - the service does not exist.

- [ ] **Step 3: Implement**

`ReadAloudHub`: a `MutableStateFlow` the service publishes into and the viewer reads, plus the `Commands` slot.

`ReadAloudService`:
- `onCreate` creates the notification channel (`media` importance, low), the `MediaSession` (with a `Callback` that maps the media keys to the session's commands), the `AudioFocusRequest` and its listener, registers the `ACTION_AUDIO_BECOMING_NOISY` receiver, and registers itself in `ReadAloudHub.commands`.
- `onStartCommand(ACTION_START)` opens the document with `DocumentAccess.open(this, location, password)` on its own single-threaded dispatcher; a failure publishes the message ("This document could not be opened: ...") and stops the service. On success it builds the page source (at most three cached pages, chapters from the outline plus the headings of the pages already seen), creates the `ReadAloudSession`, starts it and calls `startForeground` with the media notification.
- The notification: `Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_read_aloud).setContentTitle(documentName).setContentText(chapter or page).setStyle(Notification.MediaStyle().setMediaSession(sessionToken))` with actions for play/pause, stop, next/previous paragraph and next/previous chapter, each a `PendingIntent.getService` with the matching action.
- `onStartCommand(ACTION_STOP)` and the controller's `Finished` stop the foreground notification and `stopSelf()`.
- The audio-focus listener maps the platform constants to `FocusEvent` and calls `session.onFocusEvent(event)`; the session's state changes update the session's playback state and the notification.
- `ReadAloudMediaButtonReceiver` forwards `KeyEvent`s from `Intent.EXTRA_KEY_EVENT` to the session (play/pause, next/previous, stop).

`AndroidManifest.xml`: the service with `android:foregroundServiceType="mediaPlayback"` and `android:exported="false"`, the media-button receiver (exported, the `MEDIA_BUTTON` action), the permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and `POST_NOTIFICATIONS`, and a `<queries>` entry for `android.intent.action.TTS_SERVICE`.

`ic_read_aloud.xml`: a small own vector (a speaker with waves), 24 dp, white on transparent.

- [ ] **Step 4: Run the device test**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.pdfpaper.app.speech.ReadAloudServiceDeviceTest" --console=plain`
Expected: PASS (3 tests) on `paperpilot34`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pdfpaper/app/speech/ReadAloudService.kt app/src/main/java/com/pdfpaper/app/speech/ReadAloudHub.kt app/src/main/java/com/pdfpaper/app/speech/ReadAloudMediaButtonReceiver.kt app/src/main/res/drawable/ic_read_aloud.xml app/src/main/AndroidManifest.xml app/src/androidTest/java/com/pdfpaper/app/speech/ReadAloudServiceDeviceTest.kt
git commit -m "feat(app): foreground read-aloud service with notification and lock-screen controls"
```

---

### Task 4: The viewer - the bar, the word highlight, the entry point

**Files:**
- Create: `app/src/main/java/com/pdfpaper/app/ui/viewer/speech/ReadAloudBar.kt`
- Modify: `app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerViewModel.kt`
- Modify: `app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerScreen.kt`
- Modify: `app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerToolsSheet.kt`
- Modify: `app/src/main/java/com/pdfpaper/app/ui/tools/ToolRegistry.kt`
- Modify: `app/src/main/java/com/pdfpaper/app/ui/UiTags.kt`
- Test: `app/src/test/java/com/pdfpaper/app/ui/tools/ToolRegistryTest.kt`
- Test: `app/src/androidTest/java/com/pdfpaper/app/ui/viewer/speech/ReadAloudViewerDeviceTest.kt`

**Interfaces:**
- Consumes: Task 3's `ReadAloudHub`, `ReadAloudService.startIntent`; Task 2's `ReadAloudPlaybackState`.
- Produces: a read-aloud bar in the viewer, an amber highlight on the spoken word, and a "Read aloud" entry point in the tools sheet and the tool registry.

- [ ] **Step 1: Write the failing tests**

`ToolRegistryTest.kt` gains the read-aloud card in the full tool-id order assertion.

`ReadAloudViewerDeviceTest.kt` (on `paperpilot34`): the viewer shows the bar once the hub is active, the bar carries the transport buttons, and the spoken word is highlighted.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*ToolRegistryTest" --console=plain` and the device test.
Expected: FAIL - the read-aloud card and the bar do not exist.

- [ ] **Step 3: Implement**

`ReadAloudBar.kt`: a bar with play/pause, previous/next paragraph, stop and the chapter/paragraph line. It reads `ReadAloudHub.state` and sends commands through `ReadAloudHub.commands`.

`ViewerViewModel`: keeps the document's location, display name and the in-memory password so the service can open the document; exposes `startReadAloud()`, `pauseReadAloud()`, `resumeReadAloud()`, `stopReadAloud()`.

`ViewerScreen`: shows the bar when the hub state is active, highlights the spoken word in amber (`HighlightOverlay`), and scrolls to the spoken paragraph (consuming `scrollToken` once per request).

`ViewerToolsSheet`: a "Read aloud" row. `ToolRegistry`: a read-aloud card. `UiTags`: the bar's test tag.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*ToolRegistryTest" --console=plain` and the device test.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pdfpaper/app/ui/viewer/speech/ReadAloudBar.kt app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerViewModel.kt app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerScreen.kt app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerToolsSheet.kt app/src/main/java/com/pdfpaper/app/ui/tools/ToolRegistry.kt app/src/main/java/com/pdfpaper/app/ui/UiTags.kt app/src/test/java/com/pdfpaper/app/ui/tools/ToolRegistryTest.kt app/src/androidTest/java/com/pdfpaper/app/ui/viewer/speech/ReadAloudViewerDeviceTest.kt
git commit -m "feat(app): the read-aloud bar, the spoken-word highlight and the viewer entry point"
```

---

### Task 5: Acceptance, screenshots, documentation

**Files:**
- Create: `app/tools/t64_acceptance_pdfs.py`
- Create: `screenshots/t64-bar.png`, `screenshots/t64-notification.png`, `screenshots/t64-lock-screen.png`
- Modify: `AGENTS.md`

- [ ] **Step 1: Build the acceptance PDFs**

`app/tools/t64_acceptance_pdfs.py` writes a few readable PDFs (a text page, a two-page document, a document with an outline) into the app's cache.

- [ ] **Step 2: Run the acceptance on the emulator**

Start the service from the viewer, pull the notification down, lock the screen, and take the three screenshots.

- [ ] **Step 3: Run the whole build**

Run: `.\gradlew.bat build` and `.\gradlew.bat :engine:test`.
Expected: PASS.

- [ ] **Step 4: Update `AGENTS.md`**

Record T64's status and what was verified.

- [ ] **Step 5: Commit**

```bash
git add app/tools/t64_acceptance_pdfs.py screenshots/t64-bar.png screenshots/t64-notification.png screenshots/t64-lock-screen.png AGENTS.md
git commit -m "docs(app): T64 acceptance - the background reader on the emulator"
```

---

## Out of scope

- T34's rich read-aloud UI: the speed/sleep/voice sheet, the settings card and
  the saved speech position. T64 delivers the bar, the transport controls and
  the background reading; the rest is a follow-up.
- A bound service. The bridge is in-process (`ReadAloudHub`); process death
  kills the service, so there is never a stale "still reading" state.
- Media3. The platform media APIs are enough and add no dependency.
