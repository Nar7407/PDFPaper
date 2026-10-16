# PDFPaper: Agent Pack

Contents:
1. AGENTS.md (put in the project root)
2. Session START prompt (paste at the beginning of every session)
3. Session END prompt (paste at the end of every session, this updates AGENTS.md)
4. Task prompts T0 to T14 (give one at a time, in order)

Notes before you start:
- The prompts do not depend on a specific model. They work with Muse Spark 1.3 or any other coding model.
- The pack assumes the engine is written in Kotlin (simplest with Android Studio). If you choose Rust or C++, change the "Language" lines in AGENTS.md and T0.
- Never give two tasks at once. Wait for a green build and a commit before the next one.

---------------------------------------------------------------------

## 1. AGENTS.md

```markdown
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
- Last session: none yet
- Done: nothing yet
- Broken / known issues: none yet
- Next task: T0

## Milestone checklist
- [ ] T0  Project skeleton compiles
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

## Test corpus
Folder: /testdata (real-world PDFs). Metric: files that open without crash / total.
Current result: not measured yet.
```

---------------------------------------------------------------------

## 2. Session START prompt

```
Read AGENTS.md fully. Then:
1. Run `git status` and `git log --oneline -10`.
2. Tell me in 5 lines or fewer: the current status, the next task, and anything
   that looks inconsistent between AGENTS.md and the actual repo.
Do not change any file. Wait for my task prompt.
```

---------------------------------------------------------------------

## 3. Session END prompt (updates AGENTS.md)

```
The session is ending. Do these steps in order:
1. Run `./gradlew build` and `./gradlew :engine:test` and show the real result.
2. Update AGENTS.md:
   - "Current status": set Last session (today's date + one line), Done,
     Broken / known issues, Next task.
   - Tick the milestone checklist only for tasks that fully passed their
     acceptance criteria with green tests.
   - Add any new decision to the Decisions log with the reason.
   - Update the Test corpus result if it changed.
   - Keep newest information at the top and remove outdated lines.
3. Show me the AGENTS.md diff.
4. If the build is green, run `git add -A` and `git commit -m "<task id>: <summary>"`.
   If it is not green, do NOT commit; list exactly what is failing.
Do not start any new work.
```

---------------------------------------------------------------------

## 4. Task prompts

Use this wrapper at the top of every task prompt (it repeats the key rules):

```
Follow AGENTS.md. Do only this task. Write tests. Build must be green. Show real
command output. Do not add dependencies without asking. Stop when the acceptance
criteria are met.
```

### T0: Project skeleton

```
Task T0: create the project skeleton in the current folder.
- Gradle Kotlin DSL, modules :app (Compose, Material 3) and :engine (pure Kotlin/JVM, no Android imports).
- Versions: AGP 8.5.2, Kotlin 1.9.24, Compose compiler 1.5.14, Gradle 8.7, minSdk 26, compileSdk/targetSdk 34.
- Package com.pdfpaper.app, app name PDFPaper.
- :app shows a screen with the text "PDFPaper". :engine has one trivial class + one passing unit test.
- Add .gitignore, init git, first commit.
Acceptance: `./gradlew build` and `./gradlew :engine:test` pass; app installs and shows the text.
```

### T1: App shell and file picker

```
Task T1: app shell.
- Navigation with 2 screens: Home and Viewer (placeholder).
- Home has an "Open PDF" button using Storage Access Framework (OpenDocument, application/pdf).
- Copy the chosen file to the app cache, keep its display name, pass the path to the Viewer screen.
- ViewModel per screen. Handle cancel and errors with a visible message.
Acceptance: picking a PDF navigates to Viewer showing file name and size. Tests for the file-copy helper.
```

### T2: Engine, file structure and xref

```
Task T2: in :engine implement low-level PDF file reading (ISO 32000-1 sections 7.5.x).
- Read header, find startxref, parse the classic xref table AND xref streams, follow /Prev chains.
- If xref is broken or missing, rebuild it by scanning the file for "N G obj".
- API: PdfFile.open(bytes or random-access source) returns Result with the trailer and an object lookup by number.
- Large files must not be fully loaded into memory: use a random-access source.
Acceptance: unit tests with small hand-made PDFs (valid, truncated, bad xref, incremental update) all return a result and never throw.
```

### T3: Corpus test harness

```
Task T3: build a corpus test harness.
- Gradle task :engine:corpusCheck that opens every PDF in /testdata with the current engine.
- Output: total files, opened OK, failed (with file name + short reason). Save as build/corpus-report.txt.
- Add 10 small public-domain or self-made PDFs to /testdata (no copyrighted files; ask me where to get more).
- A crash or uncaught exception counts as a failure of the harness: catch per file and report.
Acceptance: task runs, report is produced, number recorded in AGENTS.md.
```

### T4: Object model and streams

```
Task T4: implement the PDF object model (sections 7.3.x): null, boolean, integer, real, string
(literal + hex, escapes), name (#xx escapes), array, dictionary, stream, indirect reference.
- Tokenizer + parser that is tolerant of real-world errors (missing endobj, wrong /Length).
- Resolve indirect references lazily with a cache and cycle protection.
- Object streams (/Type /ObjStm) support.
Acceptance: tests for every object type, malformed inputs, and a reference cycle. Corpus pass count does not drop.
```

### T5: Filters

```
Task T5: implement stream filters (section 7.4): FlateDecode (with predictors PNG + TIFF),
ASCIIHexDecode, ASCII85Decode, LZWDecode, RunLengthDecode, and filter chains.
- You may use java.util.zip for Flate. Make it tolerant of truncated/corrupt data (return what was decoded + a warning).
- Stream decoding must be lazy and bounded in memory.
Acceptance: tests for each filter with known inputs/outputs, chained filters, corrupt data. Corpus: report how many streams decode.
```

### T6: Page tree and content streams

```
Task T6: implement the document catalog and page tree (section 7.7), inherited attributes
(MediaBox, CropBox, Rotate, Resources), page count, and page access by index.
Then tokenize content streams (section 7.8) into operators + operands, including inline images (BI/ID/EI).
Acceptance: tests for nested page trees, inheritance, missing /Count, and a content stream with every operator category. Corpus must report page counts.
```

### T7: Fonts and text

```
Task T7: fonts and text extraction (section 9).
- Simple fonts: Type1 (standard 14), TrueType; encodings (WinAnsi, MacRoman, Standard, /Differences); /ToUnicode CMaps.
- Composite fonts: Type0, CID fonts, Identity-H/V, predefined CMaps (list what you support).
- Text operators (BT/ET, Tf, Td, TD, Tm, T*, Tj, TJ, ', "), text state, and reading-order text extraction per page.
- Do NOT render glyphs yet. Output: positioned Unicode text runs with font size and bounding boxes.
Acceptance: extracted text matches expected text for at least 5 test PDFs; unknown fonts fall back gracefully, never crash.
```

### T8: Graphics and images

```
Task T8: graphics model.
- Graphics state stack (q/Q/cm), paths (m l c v y h re), painting (S s f F f* B b n), clipping (W W*), line styles.
- Color spaces: DeviceGray/RGB/CMYK, CalGray/CalRGB, Indexed, ICCBased (fallback to alternate), Separation (tint transform).
- Images: decode to a pixel buffer for Flate/DCT (JPEG, may use platform decoder in :app via an interface), masks and soft masks.
- Output a display list (list of draw commands), not pixels.
Acceptance: tests that check the display list for known PDFs; corpus: no new crashes.
```

### T9: Rendering and viewer

```
Task T9: render the display list.
- Define a Renderer interface in :engine; implement it in :app using android.graphics.Canvas/Bitmap.
- Draw paths, text (use embedded font data when possible, otherwise system fonts), images, clipping, transparency basics.
- Viewer: LazyColumn of pages, render on background threads, LRU bitmap cache, cancel off-screen renders, pinch-to-zoom.
- Keep memory bounded for a 500-page PDF.
Acceptance: opens the corpus files in the app, screenshots compared by eye with another viewer; scroll stays smooth; no OOM on the largest test file.
```

### T10: Page operations and saving

```
Task T10: page operations + writer.
- PdfWriter: write a new file (and optional incremental save) with a correct xref and trailer.
- Operations: rotate, delete, reorder, insert, extract, merge multiple PDFs, split by range/every N pages.
- Preserve links, bookmarks and form fields when copying pages (list what is not preserved).
- Undo/redo in the ViewModel. UI: page grid screen with multi-select.
Acceptance: output files open correctly in two other PDF viewers; round-trip tests (open, save, reopen) pass on the corpus.
```

### T11: Search and selection

```
Task T11: text search (case-insensitive, highlights on page) and text selection following reading order.
Copy selection to clipboard. Use the text runs from T7.
Acceptance: tests on search results and bounding boxes; works on multi-column test PDF.
```

### T12: Annotations and forms

```
Task T12: annotations (section 12.5) and AcroForm fields (section 12.7).
- Read and render: highlight, underline, strikeout, text note, ink, link, stamp/free text.
- Create and save: highlight, ink, text note.
- Forms: text field, checkbox, radio, choice; fill and save with appearance streams.
Acceptance: files saved by the app show the annotations and filled values in two other viewers.
```

### T13: Encryption

```
Task T13: standard security handler (section 7.6): open RC4 40/128 and AES-128/256 (revisions 2 to 6)
with user/owner passwords; permissions flags; password prompt UI.
Writing encrypted files: implement only after reading is verified, and test that output opens in other viewers
(known issue in other projects: files must open in Acrobat/Reader).
Acceptance: tests with encrypted sample files for each revision; wrong password handled without crash.
```

### T14: Performance and release

```
Task T14: performance and release.
- Profile memory and time on the largest corpus files; fix the top 3 hotspots.
- R8 minification rules, signed release APK build instructions (do not create or commit keystores).
- Crash reporting plan and a corpus report in AGENTS.md.
Acceptance: release APK builds, installs, and opens the corpus without crashes.
```

---------------------------------------------------------------------

## How to use it each day
1. Paste the START prompt.
2. Paste one task prompt (wrapper + task).
3. Run and test the app yourself.
4. If it fails, paste the real error back. Do not move on until green.
5. Paste the END prompt. Check the AGENTS.md diff and the commit.
