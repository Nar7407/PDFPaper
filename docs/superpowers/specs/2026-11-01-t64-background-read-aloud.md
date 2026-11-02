# T64 - Read aloud in the background

Status: implemented.

## The problem

T34 built the pure read-aloud state machine (`ReadAloudController`) and its
model (`SpeechModel.kt`), but its Tasks 6-7 - the text-to-speech adapter and the
viewer wiring - were never written, and nothing in the app kept reading once the
screen went off. The reader could not start a reading, could not see where the
reading was, and could not control it from the lock screen or a headset button.

## What T64 delivers

1. A reading that survives the screen going off.
2. A notification and a lock-screen control strip with play/pause, previous and
   next paragraph, previous and next chapter, and stop.
3. Headset and media-key control.
4. Audio focus, so another app's sound stops or lowers the reading instead of
   talking over it.
5. The spoken word highlighted on the page in the viewer.
6. A "Read aloud" entry point in the viewer's tools sheet and in the tool
   registry.

## The shape

- **The service owns the reading.** `ReadAloudService` (a foreground service of
  the type `mediaPlayback`) opens the document itself, owns the speech engine
  and the `ReadAloudSession`, and publishes what it is doing. The viewer never
  binds to it: it reads `ReadAloudHub.state` and sends commands through
  `ReadAloudHub.commands`. Two owners of one reading would drift; one owner
  cannot.
- **The bridge is in-process.** `ReadAloudHub` is an object with a `StateFlow`
  and a `Commands` slot. Process death kills the service with the app, so there
  is never a stale "still reading" state.
- **The platform's own media APIs, no new dependency.** `android.media.session.MediaSession`
  for the session and the media keys, `android.app.Notification.MediaStyle` for
  the notification, `AudioFocusRequest` for the focus. Media3 was considered and
  rejected: it would add a dependency for a transport strip the platform already
  draws.
- **Offline only.** The app has no network permission; the voice list keeps only
  voices that do not need the network, and the reading never asks for one.
- **The PDF is never written.** The service opens the document read-only through
  `DocumentAccess`; the bytes on disk never change.

## The pieces

| Piece | File | Role |
| --- | --- | --- |
| The speech seam | `ui/viewer/speech/SpeechSynthesis.kt` | `SpeechSynthesis`, `SpeechListener`, `SpeechVoice`, `SpeechVoices` |
| The engine adapter | `ui/viewer/speech/AndroidSpeechSynthesis.kt` | Wraps the platform `TextToSpeech`; reports word ranges |
| The page feed | `ui/viewer/speech/SpeechPageSource.kt` | The pages and chapters the reading needs |
| The executor | `ui/viewer/speech/ReadAloudSession.kt` | Runs the controller's actions against the engine and publishes the state |
| The focus rules | `ui/viewer/speech/AudioFocusPolicy.kt` | Which focus event pauses, resumes or is ignored |
| The service | `speech/ReadAloudService.kt` | Opens the document, owns the reading, the notification and the session |
| The bridge | `speech/ReadAloudHub.kt` | State and commands between the viewer and the service |
| The media keys | `speech/ReadAloudMediaButtonReceiver.kt` | Headset and lock-screen buttons to commands |
| The bar | `ui/viewer/speech/ReadAloudBar.kt` | The in-viewer transport and the chapter/paragraph line |

## The rules that are pinned

- A permanent focus loss pauses. A transient loss pauses and the following gain
  resumes. A duck is ignored. An unplugged headset pauses and does **not**
  resume by itself - that is not a focus event.
- The reading waits for the speech engine to report readiness (15 s) before it
  starts. Speaking into an engine that is not ready only produces an error.
- A document the service cannot open reports the reason ("This document could
  not be opened: ...") and the message survives the service going away.
- The service stops itself when the document ends or the reader stops it. The
  state is marked not-reading; only an explicit stop clears it.
- The notification, the lock screen and the viewer always show the same
  transport actions, because they all send the same commands.

## Not in T64

- T34's rich read-aloud UI: the speed/sleep/voice sheet, the settings card, and
  the saved speech position across sessions. T64 ships the bar, the transport
  and the background reading; those follow.
- A bound service. The in-process hub is deliberate.
- Speech that survives the app process being killed. The service dies with the
  process, which is what the reader expects from "stop the app".

## How it was verified

- JVM tests: the voice filter, the playback executor, the focus rules, the
  bar's word highlight and the tool-registry card.
- Device test (`ReadAloudServiceDeviceTest`, emulator API 34): the service posts
  a foreground notification that carries the media-session token for the
  lock-screen transport, a missing document reports the reason, a headset key
  pauses a reading that is genuinely speaking (and it stays paused), and the
  document's bytes are unchanged after a reading.
- Device test (`ReadAloudViewerDeviceTest`): the bar appears when the reading is
  active, carries its transport actions, and the spoken word is highlighted.
- The emulator acceptance run with screenshots of the bar, the notification and
  the lock screen.

Two paths cannot be driven from a test on API 34 and were checked by reading the
code instead: `AUDIO_BECOMING_NOISY` is a protected broadcast (even `adb shell`
gets `SecurityException`), and `MediaSessionManager.getMediaKeyEventSessionPackageName()`
needs `MEDIA_CONTENT_CONTROL`, which no ordinary app holds. The headset-key test
covers the same receiver the unplug path uses, and the media key routing itself
is proven by a real key press reaching it.
