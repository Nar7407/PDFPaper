# AGENTS.md: PDFPaper

## Project goal
PDFPaper is a native Android PDF app with its own PDF engine (no third-party
PDF library for core parsing/rendering). Long-term goal: similar feature set to
PdfCraft (view, organize, merge/split, annotate, forms, encryption), designed
for touch. Do not copy PdfCraft/ArtCraft code, logos or branding.

## Tech decisions (change only when the owner approves)
- Language: Kotlin (engine in a pure-Kotlin module with no Android imports)
- UI: Jetpack Compose + Material 3, MVVM
- Build: Gradle Kotlin DSL; minSdk 26, targetSdk/compileSdk 34
- Modules: :app (UI), :engine (PDF engine, pure Kotlin/JVM), :engine-tests data in /testdata
- Reference: ISO 32000-1 (PDF 1.7). Cite the section number in code comments.

## Working rules
1. One task per session. Do exactly what the task says, nothing extra.
2. Write tests before or together with the code. Never delete or weaken a test
   to make it pass. If a test is wrong, explain why and ask first.
3. The build must be green (`./gradlew build` and `./gradlew :engine:test`)
   before you say a task is done. Paste the real command output.
4. Never invent file paths, APIs or results. If unsure, read the code or say so.
5. Never crash on bad input: the engine returns errors/results, it does not throw
   uncaught exceptions on malformed PDFs.
6. Keep files small and focused. Prefer clear names over comments.
7. Do not add dependencies without asking. State license and reason.
8. Never run destructive commands (delete, force push, reset --hard) without asking.
9. Stop and report when blocked. Do not guess your way around a blocker.

## Commands
- Build: ./gradlew build
- Engine tests: ./gradlew :engine:test
- Run app: build in Android Studio (Run on device/emulator)
- Corpus check: ./gradlew :engine:corpusCheck (added in task T3)

## Current status
(Updated at the end of every session. Newest at the top.)
- Last session: 2026-10-16 — T0 done. Skeleton with :app (shows "PDFPaper") + :engine (PdfEngine + 1 test) builds green.
- Done: T0
- Broken / known issues: none yet
- Next task: T1

## Milestone checklist
- [x] T0  Project skeleton compiles
- [ ] T1  App shell + file picker
- [ ] T2  Engine: file structure + xref
- [ ] T3  Engine: corpus test harness
- [ ] T4  Engine: object model + streams
- [ ] T5  Engine: filters (Flate, ASCIIHex, ASCII85, LZW, RunLength)
- [ ] T6  Engine: page tree + content streams
- [ ] T7  Engine: fonts + text
- [ ] T8  Engine: graphics + images
- [ ] T9  Rendering to bitmap + viewer in app
- [ ] T10 Page operations (rotate/delete/reorder/merge/split) + save
- [ ] T11 Text search + selection
- [ ] T12 Annotations + forms
- [ ] T13 Encryption (standard security handler)
- [ ] T14 Performance, memory, release APK

## Decisions log
(Date, decision, reason. Add a line whenever something is decided.)
- 2026-10-16: Build with Microsoft JDK 17 at C:\Tools\jdk-17 (Gradle 8.7 does not run on Android Studio's JBR 25).
- 2026-10-16: Manifest has no android:icon (no launcher icon asset yet; avoids missing-resource build error).
- 2026-10-16: T0 :app screen shows only "PDFPaper"; the earlier PdfRenderer viewer was removed and returns incrementally (T1 file picker, T9 engine-based viewer).

## Test corpus
Folder: /testdata (real-world PDFs). Metric: files that open without crash / total.
Current result: not measured yet.
