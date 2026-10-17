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
- Last session: 2026-10-17 — T11 done. :engine text layout (TextLayout: reading-order column detection from vertical whitespace + line grouping with an inserted word space between gapped runs, TextRect, rectsFor selection rectangles by baseline interpolation, wordRangeAt/wordAt hit testing, per-page case-insensitive search; TextSearch: document-wide searchText returning page-ordered PageMatch). :app viewer gained search and selection: a Find bar with a debounced case-insensitive query, match count and next/prev that jumps to the page, yellow hit highlights and blue selection rectangles drawn over the bitmap through the render plan, tap-or-long-press-drag word selection following reading order, and Copy / Select all / Clear via the clipboard. 18 new engine tests (275 total), 13 app tests, corpus 10/10, `./gradlew build` green including lint.
- Previous session: 2026-10-17 — T10 done. :engine write path (PdfSerializer byte-exact object writing with /Length rewrite + hex for binary strings + `#XX` name escaping; PdfWriter full classic-xref file + incremental append with /Prev and sparse subsections; PdfDocumentBuilder; PdfObjectCopier deep copy with memo + known-remap/drop; PdfDocumentAssembler page import with materialised inherited attrs, destination-aware link/outline/named-dest/AcroForm filtering, /Info + /ID copy, encryption rejected; PdfPageOps rotate/delete/reorder/extract/insert/merge/split-by-range-and-every-N + rotateIncremental) and :app organizer (PagesViewModel with immutable plan + PagePlanHistory undo/redo, thumbnail grid with multi-select, rotate/delete/reorder, add-PDF merge, extract/save via SAF CreateDocument, split to cache). 41 new engine tests (257 total) + 13 app tests, corpus 10/10 including a new round-trip metric (10/10 open→save→reopen with the same page count), `./gradlew build` green including lint.
- Previous session: 2026-10-17 — T9 done. :engine render API (Renderer interface + display-list walker, PageGeometry crop-box→pixel maths with /Rotate, byte-bounded LruCache, SetBlendMode command) and :app rendering/viewer (Canvas-based AndroidRenderer for paths/text/images/clips/alpha/blend, PageBitmapRenderer, DCT/JPX through BitmapFactory, embedded TrueType/OpenType fonts, ViewerViewModel with one-at-a-time background renders + LRU bitmap cache + cancel-on-scroll, LazyColumn viewer with pinch-to-zoom). 37 new tests (216 total), corpus 10/10, `./gradlew build` green including lint.
- Done: T0, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11
- Broken / known issues: standard-14 AFM widths not embedded (missing /Widths → 0 advance); StandardEncoding approximated from WinAnsi; only Identity-H/V predefined CMaps implemented (all others are documented-degraded); Pattern colour spaces, shadings (sh), CCITT/JBIG2 images, ExtGState /SMask transparency groups and ExtGState /Font are recognised-but-unsupported; CalRGB/CalGray ignore the white point (approximation); sampled colour functions use nearest-neighbour (no interpolation); text is drawn with platform fonts stretched to the document advance (embedded Type1/CFF programs cannot be loaded by android.graphics, so they fall back to system fonts); only five PorterDuff blend modes are mapped from /BM; whole-page rendering only (no tiling), one page at a time. Writer: output is always classic-xref/plain objects; not preserved when copying pages — encryption (rejected, T13), xref/object streams (rewritten), signatures, XFA, `/PieceInfo`, article beads, embedded files and non-/Dests /Names sub-trees, `/StructTreeRoot`, `/PageLabels`; `split` writes parts to the app cache (SAF has no multi-file create); T11 reading order is column-major: a full-width heading that spans a column gutter keeps the page single-column, and the layout assumes the gutter is wider than the inter-line leading. Search and selection are covered by engine tests (the corpus files have no text, so no corpus metric).
- Next task: T12 (annotations + forms). T9/T10 visual acceptance and the T11 search/selection UI still need a device/emulator run (screenshots by eye, smooth scrolling, no OOM; the organizer grid's thumbnails; search highlights and tap selection).

## Milestone checklist
- [x] T0  Project skeleton compiles
- [x] T1  App shell + file picker
- [x] T2  Engine: file structure + xref
- [x] T3  Engine: corpus test harness
- [x] T4  Engine: object model + streams
- [x] T5  Engine: filters (Flate, ASCIIHex, ASCII85, LZW, RunLength)
- [x] T6  Engine: page tree + content streams
- [x] T7  Engine: fonts + text
- [x] T8  Engine: graphics + images
- [x] T9  Rendering to bitmap + viewer in app
- [x] T10 Page operations (rotate/delete/reorder/merge/split) + save
- [x] T11 Text search + selection
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
- 2026-10-17 (T7): no new dependencies. Encoding tables (CP1252/WinAnsi, MacRoman) generated from Python codecs cp1252/mac_roman and embedded as \uXXXX literals; StandardEncoding is approximated (WinAnsi + quoteright-at-0x27). Glyph-name table is a compact AGL subset (ASCII, Latin-1, common punctuation) plus uniXXXX/uXXXX patterns; unknown /Differences names decode to "" (dropped), never throw. Missing /Widths → 0 advance (full standard-14 AFM metrics are a T14 candidate); missing /Encoding → Standard (Type1) or WinAnsi (TrueType). ToUnicode CMap parser takes bfchar + both bfrange forms, masks 2-byte sources onto low-byte keys for simple fonts, caps output at 100 000 entries. Type0 fonts support only Identity-H/V; any /BaseFont CId encoding without ToUnicode reads W-array widths from the DescendantFonts dict (/DW default 1000) and CIDs as Unicode BMP (best effort).  Extraction emits one positioned Unicode run per text-showing operand, bbox = advance×fontSize quad mapped through (CTM∘Tm), axis-aligned. Td/TD/T* offsets are relative to the current LINE START (Tlm is not advanced by glyph ops — ISO §9.4.4 / mainstream extractor semantics); glyph advances scale by Tz/100 per glyph, Tw applies only to code 32, Tc applies per glyph. 
- 2026-10-17 (T8): no new dependencies. Display list commands each carry the CTM in effect and user-space geometry (not pre-transformed), so T9 renderers get exact stroke scaling, clipping and image placement from their own rasteriser; q/Q emit explicit Save/Restore commands and text state is saved/restored with them (§9.3). Mat became the public com.pdfpaper.engine.geom.PdfMatrix; the T7 text interpreter was extracted into TextMachine + PdfFontCache (public) so extraction and the display list share one implementation and cannot drift. Colour spaces resolve to RGB doubles in the engine (no platform types); indexed components are palette indices (not normalised); ICCBased uses /Alternate, else /N; Cal spaces ignore the white point (documented approximation); Pattern and shading 'sh' are recognised-but-unsupported. PDF functions: Type 2 exp, Type 3 stitch, Type 0 sampled with nearest-neighbour lookup; Type 4 (PostScript calculator) degrades to null. Images decode in-engine to RGBA8 for raw and Flate-style filter chains (1/2/4/8/16 bpc, /Decode, Indexed/Separation, /ImageMask stencils, /Mask colour key and mask images, /SMask kept as a separate RasterImage); DCT/JPX keep their encoded bytes for an ExternalImageDecoder hook that :app wires to android.graphics in T9 (tests use a JDK ImageIO decoder); CCITT/JBIG2 and ExtGState /SMask + /Font are unsupported with warnings. Filter-chain helpers (filterNamesOf/decodeParamsOf/decodeFilterChain) are public so the image decoder can stop before a trailing DCT/JPX stage; PdfStream.decoded is now built on them.
- 2026-10-17 (T9): dependencies added: `androidx.compose.foundation:foundation` (Apache 2.0 — LazyColumn, Image and gesture APIs for the viewer) and `project(":engine")` (internal module link, no third-party code — the app now parses and renders in-process). Nothing else added. The engine owns the renderer contract (`Renderer` + `renderDisplayList`) and all layout maths (`PageGeometry`, `LruCache`), so the platform only implements drawing primitives; :app's AndroidRenderer maps each command to Canvas inside a save/concat/restore bracket, which gives correct stroke scaling, dashes, clipping and image placement for free. Page bitmaps are laid out from the crop box with a y flip and /Rotate applied (§14.11.2) and are clamped to a 12 MP per-page budget. Rendering runs one page at a time on `Dispatchers.Default.limitedParallelism(1)` so document access is serialised and peak memory is bounded; off-screen pages are dropped from the visible map but stay in a byte-bounded LRU cache (heap/6, 16-64 MB), pending renders for scrolled-away pages are cancelled, and zoom changes re-request only visible pages at the new width. Text uses `Typeface.Builder(file)` on an embedded /FontFile2 or /FontFile3-OpenType program written to the app cache (android.graphics cannot load Type1/CFF buffers, so those fall back to system families) and glyphs are stretched to the run's PDF advance so widths match /Widths. ExtGState /BM is emitted as a `SetBlendMode` command from the gs operator (§11.6.3) and mapped to PorterDuff modes; unsupported modes and ExtGState /SMask transparency groups are documented-degraded.
- 2026-10-17 (T10): no new dependencies (JDK only). The engine writes a fresh classic-xref file (§7.5.4/7.5.5) from a set of indirect objects; streams keep their encoded bytes and only `/Length` is rewritten, so filters never round-trip. Incremental save (§7.5.6) copies the original bytes unchanged and appends a sparse classic xref with `/Prev` (PdfFile now exposes `startXrefOffset`). Copier memoises by source object number (sharing preserved, cycles break) and takes caller-provided "known remaps": each source page is mapped up front to its imported page, or to null when the operation drops it, so a reference to a dropped page never drags its subtree in. Pages are imported self-contained (inherited /MediaBox /CropBox /Rotate /Resources materialised). Destinations decide what survives: a link, bookmark or named destination whose target page is kept is carried over with the page reference remapped; one whose target is dropped is removed (and an AcroForm field whose only widget lived on a dropped page is dropped too). Named destinations are flattened from `/Dests` or the `/Names /Dests` tree into a single-leaf tree. Encrypted documents are refused rather than written blind (T13). Also fixed a T4 stub: `PdfDocument` now decodes filtered object streams through the filter chain instead of returning null. :app's organizer keeps an immutable page plan with a bounded undo/redo history (pure Kotlin, unit-tested) and exports through `PdfPageOps`; save/extract use SAF `CreateDocument`, split writes parts to the app cache (SAF cannot create several files at once).
- 2026-10-17 (T11): no new dependencies. Reading order is column-major: page columns come from vertical whitespace (a gap wider than 1.5 em with text at overlapping heights on both sides, so a wide word space on a single line is not mistaken for a column), then each column is read top to bottom and left to right. Line text inserts one space where the horizontal gap between two runs exceeds 0.2 em; a run's characters are placed by linear interpolation along its baseline, so search and selection rectangles line up with the glyphs the renderer stretches to the advance. Search is per line and case-insensitive (a match never spans a line break); the document search visits every page once. Text extraction shares the renderer's single-threaded dispatcher because rendering and text both read the same PdfDocument, and text layouts are cached with a bounded LRU like bitmap pages. The viewer draws highlights over the bitmap (rather than into it) using the page render plan, so zooming re-renders only the page and never bakes highlights into the bitmap cache.

## Test corpus
Folder: /testdata (real-world PDFs). Metric: files that open without crash / total.
Current result: 10/10 (2026-10-17, all self-made; 3 opened via rebuild scan).
Pages: 12 total (every file reports its page count; rebuilt files found
their catalog via the rebuild /Root recovery).
Streams decoded: 1/1 (only content-reachable streams are indexed; the
unfiltered xref-stream object itself is not in any xref table).
Text runs: 0 (T7 corpus files are structural fixtures with no text; the
harness runs page.extractText() on every page as a no-crash check).
Display list: 0 commands, 0 images (T8; the harness also builds every page's
display list as a no-crash check — the structural fixtures have no content).
Round-trip: 10/10 (T10; the harness re-saves every file through the writer and
reopens it, checking the page count survives and the xref is not rebuilt).
Rendering: not part of the corpus harness (T9's renderer needs Android
Bitmap/Canvas). Engine side, a synthetic 500-page document is opened, laid out
and pixel-budgeted in PageMemoryTest, and the bitmap cache is proven to stay
inside its byte budget while visiting every page.
