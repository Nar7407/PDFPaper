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
- Last session: 2026-10-17 — T6 done. :engine page tree (nested trees, inheritance, tolerant traversal) + content-stream parser with inline images; 28 new tests (92 total), corpus 10/10 with page counts (12 pages), build green.
- Done: T0, T1, T2, T3, T4, T5, T6
- Broken / known issues: none yet
- Next task: T7

## Milestone checklist
- [x] T0  Project skeleton compiles
- [x] T1  App shell + file picker
- [x] T2  Engine: file structure + xref
- [x] T3  Engine: corpus test harness
- [x] T4  Engine: object model + streams
- [x] T5  Engine: filters (Flate, ASCIIHex, ASCII85, LZW, RunLength)
- [x] T6  Engine: page tree + content streams
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
- 2026-10-16 (T1): added navigation-compose 2.7.7 (Apache 2.0, 2-screen navigation) and lifecycle-viewmodel-compose 2.7.0 (Apache 2.0, ViewModel per screen); no new test deps (JUnit4 + TemporaryFolder only).
- 2026-10-16 (T1): copy helper (PdfFileCache) is pure JVM taking InputStream so it is unit-testable without Robolectric; thin ContentResolver querying stays in HomeViewModel.
- 2026-10-16 (T2): no new dependencies (stdlib/JDK only). Xref sections capped at a 4 MB parse window (larger falls back to rebuild scan); filtered xref streams return null and fall back to rebuild until T5 adds filter decoding. Failed opens close the source; caller closes PdfFile on success.
- 2026-10-16 (T3): no new dependencies (Python stdlib generator, engine-only harness). corpusCheck is a JavaExec task that always exits 0; failures are recorded in the report, never fail the build. Harness opens only *.pdf so README/generate.py are ignored.
- 2026-10-16 (T3 fix): .gitattributes marks *.pdf/*.jar/*.bin binary; git had normalized crlf-endings.pdf CRLF->LF on add (329 vs 346 bytes), re-added byte-exact.
- 2026-10-16 (T4): no new dependencies. PdfDocument caches resolved objects (incl. misses); cycles resolve to null. Filtered object streams resolve to null until T5. Stream data tokens are skipped by byte position after parsing; stray ')'/lone '>' tokenize as words so binary stream data survives tokenizing.
- 2026-10-16 (T5): no new dependencies (JDK java.util.zip for Flate). Decode is lazy (streams stay encoded until decoded()) with a 256 MB output guard. Corrupt input returns the decoded prefix + warnings, never throws. LZW width rule verified against the PDFBox reference: decoder bumps on table-size + EarlyChange, encoder one entry earlier; PNG rows always carry a filter-type byte for predictors 10-15; predictors support 8 BPC only.
- 2026-10-17 (T6): no new dependencies. Page tree traversal is purely walk-based (/Count is ignored), so missing or wrong counts never matter. Content streams get their own lazy tokenizer (a whole-file tokenizer cannot survive binary inline-image data); BI/ID/EI collapse into one "ID" command holding a PdfInlineImage. PdfInlineImage lives in the model package because the sealed PdfObject hierarchy only admits same-package inheritors. Rebuilt files (broken/missing xref) recover /Root by scanning for "/Type /Catalog"; resolveRoot now uses the typed trailer root so recovery applies everywhere. Inline images keep exactly one whitespace byte as delimiter after ID and one before EI (byte-exact data capture).

## Test corpus
Folder: /testdata (real-world PDFs). Metric: files that open without crash / total.
Current result: 10/10 (2026-10-17, all self-made; 3 opened via rebuild scan).
Pages: 12 total (every file reports its page count; rebuilt files found
their catalog via the rebuild /Root recovery).
Streams decoded: 1/1 (only content-reachable streams are indexed; the
unfiltered xref-stream object itself is not in any xref table).
