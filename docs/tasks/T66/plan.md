# T66 plan — Split a document by table of contents

## Scope and starting point

T66 extends the existing Split entry in the organizer.  The existing engine
path is `PdfPageOps.split`/`PdfDocumentAssembler`: it already rebuilds each
selected page set and preserves reachable outlines, named destinations,
internal link annotations, `/Info`, metadata, and AcroForm fields.  It
currently drops `/PageLabels`, returns all output byte arrays at once, and does
not expose outline diagnostics or output names.  T66 will add a streaming
chapter-split layer above that assembler and make page labels a first-class
preservation responsibility.

The existing `DocumentOutline` + `DestinationResolver` already resolve
explicit destinations, named destinations, action `/D` values, and indirect
references.  T66 needs a diagnostic outline walk rather than weakening those
safe, null-on-malformed-input rules.  Heading detection is available through
T33's `SpeechExtractor`/`ReflowExtractor`; it is only the fallback offered
when no usable outline exists.

No dependency will be added.  The Android work will reuse `ExportFolder`,
`SafExportFolder`, `CacheExportFolder`, and the ZIP/FileProvider conventions
used by Export images.

## Files

Paths marked **change** were verified to exist.  Paths marked **new** do not
yet exist; their parent source/test directories were verified to exist.  This
plan intentionally does not modify the already-dirty product files while it
is being written.

### Engine and CLI

- **new** `engine/src/main/java/com/pdfpaper/engine/split/TocSplit.kt` — public
  chapter-plan model, outline/heading planning, filename sanitisation,
  diagnostics, and streaming writer.
- **change** `engine/src/main/java/com/pdfpaper/engine/outline/DocumentOutline.kt`
  — expose a bounded diagnostic traversal (including unresolved title/depth
  records) shared by the splitter while keeping the reader's existing
  resolved-entry API stable.
- **change** `engine/src/main/java/com/pdfpaper/engine/write/PdfPageOps.kt` —
  add a range-at-a-time assembly entry point or an assembler option used by
  `TocSplit`; retain the existing `split` methods for compatibility.
- **change** `engine/src/main/java/com/pdfpaper/engine/write/PdfDocumentAssembler.kt`
  — import and rebase the selected `/PageLabels` number-tree entries, and
  surface dropped cross-part link counts/details to the split report without
  changing existing callers' behavior.
- **new** `engine/src/main/java/com/pdfpaper/engine/tools/PdfSplitTocCli.kt`
  — `split-toc <book.pdf> --level N --out-dir <parts/>` command, reporting
  warnings and refusing existing output files unless an explicit overwrite
  option is designed and documented.
- **change** `engine/build.gradle.kts` — register `:engine:split-toc` with
  `PdfSplitTocCliKt` and the repository-root working directory.
- **change** `cli` — document and dispatch `split-toc` to that Gradle task.
- **new** `engine/src/test/java/com/pdfpaper/engine/split/TocSplitTest.kt` —
  planner, naming, warnings, preservation, and streaming tests.
- **new** `engine/src/test/java/com/pdfpaper/engine/tools/PdfSplitTocCliTest.kt`
  — command parsing, files written, and no-outline/error behavior.
- **change** `engine/src/test/java/com/pdfpaper/engine/write/PdfPageOpsTest.kt`
  — focused page-label and split-assembly regression fixtures if they need
  existing `WriteFixtures` helpers.

### Android app

- **new** `app/src/main/java/com/pdfpaper/app/ui/pages/TocSplitModel.kt` — pure
  preview-edit model: checked chapters, rename, merge/split range validation,
  estimate formatting, output-name uniqueness, and UI-specific errors.
- **new** `app/src/main/java/com/pdfpaper/app/ui/pages/TocSplitViewModel.kt` —
  opens one document read-only, requests a plan, performs one part at a time,
  checks conflicts, tracks progress/cancellation, and writes to an
  `ExportFolder` or cache ZIP.
- **new** `app/src/main/java/com/pdfpaper/app/ui/pages/TocSplitScreen.kt` —
  “Split by chapters” screen: level picker, shared-page choice, diagnostics and
  fallback choices, editable preview, folder/ZIP actions, overwrite dialog,
  progress/cancel, and result summary.
- **change** `app/src/main/java/com/pdfpaper/app/ui/navigation/AppNav.kt` — add
  a dedicated `split-toc` route from the existing Split tool, leaving generic
  page-organizer routes intact.
- **change** `app/src/main/java/com/pdfpaper/app/ui/tools/ToolRegistry.kt` —
  revise the Split hint/description so it opens chapter splitting rather than
  silently splitting every five pages.
- **change** `app/src/main/java/com/pdfpaper/app/ui/pages/PagesScreen.kt` and
  `app/src/main/java/com/pdfpaper/app/ui/pages/PagesViewModel.kt` — either
  route Split to the new screen or remove the fixed-five-page action only for
  the Split tool; retain ordinary organizer save/extract behavior.
- **change** `app/src/main/java/com/pdfpaper/app/data/ExportFolder.kt` — only
  if needed to support a preflight write plan atomically enough to avoid
  accidental provider-generated duplicate filenames; reuse its existing
  conflict policy otherwise.
- **change** `app/src/main/java/com/pdfpaper/app/ui/UiTags.kt` — tags for
  chapter preview, level, fallback, destination, conflict, progress, and
  cancellation tests.
- **new** `app/src/test/java/com/pdfpaper/app/ui/pages/TocSplitModelTest.kt` —
  pure preview edits, estimates, validation, names, and conflict planning.
- **new** `app/src/test/java/com/pdfpaper/app/ui/pages/TocSplitViewModelTest.kt`
  — state transitions, cancellation, no-overwrite preflight, and folder/ZIP
  writing using a fake `ExportFolder`/document source seam.
- **new** `app/src/androidTest/java/com/pdfpaper/app/ui/pages/TocSplitDeviceTest.kt`
  — end-to-end Split UI acceptance on device/emulator.
- **new** `app/src/androidTest/assets/t66/` — generated acceptance PDFs:
  nested valid outline, out-of-order/invalid outline, and no-outline headings.
- **new** `app/tools/t66_acceptance_pdfs.py` — deterministic fixture generator
  for those device assets.

## Interfaces and data types

Keep the public engine API in a new `com.pdfpaper.engine.split` package rather
than coupling the app to `PdfDocumentAssembler`.

- `TocSplitSource` = `OUTLINE` or `HEADINGS`.
- `TocSplitOptions(level: Int, duplicateBoundaryPage: Boolean, source: TocSplitSource)`;
  `level` is one-based for users and validated as positive.
- `TocSplitEntry(title, sourcePageIndex, depth, source)` is the resolved
  candidate before the requested-level filter.  Outline depth 0 maps to UI
  level 1.
- `TocSplitPart(id, title, pages: IntRange, fileName, checked = true)` uses
  zero-based inclusive source pages; UI/CLI presentation is always one-based.
- `TocSplitIssue` sealed types: `NoOutline`, `NoUsableEntries`,
  `InvalidDestination(title, depth)`, `OutOfOrder(title, originalPage,
  sortedPage)`, `SamePage(title, page)`, `EmptyRange(title)`,
  `DroppedExternalLink(sourcePage, targetPage)`, and `OutputNameAdjusted`.
- `TocSplitPlan(source, requestedLevel, parts, issues, pageCount)` is a
  read-only preview.  `planOutline(document, options)` never throws for a
  malformed outline; it resolves destinations through `DestinationResolver`,
  skips unusable entries, stable-sorts entries by page then outline order, and
  reports every correction.
- `TocSplitWriter.write(document, parts, sink, isCancelled, onProgress)` calls
  the sink once per completed part and does not retain prior part bytes.  The
  sink receives `TocSplitOutput(part, bytes, report)`; `report` includes
  preserved/drop counts and emitted link-drop issues.  Cancellation stops
  before the next part and returns a completed-part count, never an uncaught
  cancellation/error.
- `TocSplitFileNames` sanitizes Windows/Android-hostile characters and reserved
  device names, trims trailing dots/spaces, collapses blank titles to
  `Chapter`, applies a documented UTF-8/code-point limit before `.pdf`, and
  assigns stable numeric prefixes and ` (2)`, ` (3)` suffixes for collisions.
- The assembler gains an internal preservation report/callback instead of a
  new global mutable state.  It must map each selected source page to its
  output page index, rebuild `/PageLabels` for the selected ranges, and report
  only link annotations dropped because their resolved target is outside the
  current part.  Existing generic extract/merge callers may ignore it.
- App state uses immutable `TocSplitUiState` (load/preview/error/diagnostics,
  destination/conflicts, `done`/`total`, cancellation/summary) and
  `EditableTocSplitPart` (checked, title, range, estimate, computed filename).
  UI edits operate on this pure model before opening an output destination.

## Implementation order

Each numbered step ends with a compiling state; run its focused tests before
moving on.  Do not begin app work until the engine plan contract is stable.

1. Add `TocSplit` models and unit tests for level selection, destination
   resolution through named/direct/indirect forms, stable out-of-order sorting,
   same-page starts, invalid entries, no outline, and filename rules.  Reuse
   `DocumentOutline` resolution logic via a new diagnostic traversal rather
   than duplicate PDF destination parsing.  Compile with `:engine:test`.
2. Implement range construction.  Default ranges end at the page before the
   next retained start; when duplicate-boundary is selected, only the boundary
   page is included in both adjacent nonempty parts.  Treat same-page entries
   deterministically (report then merge/skip the zero-length predecessor per
   the preview rule).  Add heading fallback by lazily extracting pages with
   T33 and collecting level 1–2 headings only when outline planning has no
   usable parts.  Compile with `:engine:test`.
3. Extend `PdfDocumentAssembler` to preserve/rebase `/PageLabels` and return
   precise dropped-cross-part-link diagnostics while retaining current link,
   outline, metadata, and AcroForm behavior.  Add page-label/link/form/
   bookmark regression tests; compile with `:engine:test`.
4. Implement `TocSplitWriter` as a sequential producer: build one part, pass
   it to the sink, discard its bytes, check cancellation, then proceed.  Keep
   only the outline plan and one output part in memory.  Add a 1,000-page
   synthetic test with a recording sink that proves ordering, bounded retained
   outputs, cancellation, and correct first/last-page text.  Compile with
   `:engine:test`.
5. Add the CLI class, Gradle task, shell dispatch, and CLI tests.  Validate
   level/out-dir before opening the document; preflight all target names,
   reject existing files without changing any; create/write parts sequentially;
   print ranges, warnings, and a clear fallback/no-outline message.  Compile
   with `:engine:test` and exercise `./cli split-toc ...` on generated fixtures.
6. Add the pure Android preview model and tests.  Support uncheck, rename,
   merge adjacent checked ranges, split a selected range at a valid page,
   duplicate-boundary choice, estimates from page count/source size, and a
   clear fallback choice for fixed-N versus heading detection.  Compile with
   `:app:testDebugUnitTest`.
7. Add the split ViewModel and screen as a dedicated route.  Reuse
   `SafExportFolder` conflict preflight and cache+`ZipOutputStream` sharing;
   write parts serially, expose completed count, honor cancellation, and leave
   already-written files reported rather than deleted.  Keep files in the
   chosen tree or ZIP cache only; never overwrite until confirmation.  Compile
   with `:app:testDebugUnitTest`.
8. Route the existing Split tool to the chapter screen, update its copy/tags,
   add fixture generation and device tests, then run the full required
   verification: `./gradlew :engine:test`, `./gradlew :app:testDebugUnitTest`,
   and `./gradlew build`.  On an attached emulator/device run the new device
   class and manually open generated parts in two independent PDF viewers to
   confirm bookmarks and links.

## Tests

### Engine unit and CLI

- Nested bookmarks at levels 1–3: each selected level produces expected
  one-based ranges, and extracted first/last-page text matches its source.
- Explicit, named, action `/D`, and indirect destinations all resolve to the
  expected start page; invalid/cyclic/missing destinations are skipped and
  reported without throwing.
- Out-of-order entries stable-sort with an `OutOfOrder` issue; multiple entries
  on one page produce deterministic preview behavior and a `SamePage` issue.
- Empty outline produces `NoOutline` and no output; an outline with only bad
  targets produces `NoUsableEntries`; heading fallback finds only T33 level
  1–2 starts when requested.
- Normal and duplicate-boundary policies produce correct ranges, including the
  final part ending at document end and no invalid/empty page ranges.
- Filename tests cover illegal characters, reserved names, blank/long titles,
  Unicode-safe truncation, numeric prefix width, and duplicates.
- Each output reopens with this engine and asserts rebased outline destinations,
  retained same-part link destinations, omitted cross-part links/report, page
  labels, `/Info`/metadata, and surviving AcroForm widgets/values.
- 1,000-page sequential sink test proves at-most-one output byte array is held
  by the writer, ordered progress, and clean cancellation between parts.
- CLI test writes `01 - Introduction.pdf`-style output to a temporary
  directory; invalid level, missing `--out-dir`, existing target, and no
  usable outline return clear nonzero/usage results without partial overwrite.

### App unit and device

- Preview model: level changes, checkbox filtering, rename sanitisation,
  merge/split constraints, duplicate-boundary ranges, size estimates, and
  diagnostics/fallback wording.
- ViewModel: folder conflicts block writes until confirmation, ZIP contains
  the selected names/bytes, one-at-a-time progress updates, cancellation leaves
  only completed outputs and a truthful summary, and document-open errors are
  displayed rather than crashing.
- Device acceptance with the nested-bookmark book: user selects “Split by
  chapters,” chooses level 1, adjusts one preview item, exports, and sees
  progress/result.  Reopen each result and assert first/last page text and
  usable bookmark navigation.
- Device acceptance with the out-of-order book: warning is visible; preview is
  page-sorted and exports correct ranges.
- Device acceptance with the no-outline book: clear message offers fixed-N and
  detected-headings fallback; heading choice previews/exports expected parts.
- Device overwrite test: target tree with a planned name shows the confirmation
  dialog and does not write until Replace is tapped.
- Cross-viewer acceptance (manual, recorded with viewer names/versions): open
  every generated part in two external viewers, expand bookmarks, follow an
  internal link, and confirm no cross-part link remains clickable.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Existing assembler deliberately drops `/PageLabels`; rebasing PDF number trees is subtle. | Add a dedicated page-label parser/rewriter with sparse-number-tree fixtures before wiring UI. Preserve the source rule at each selected output-page boundary and rebuild a valid sorted tree. |
| `DocumentOutline.read` intentionally hides malformed entries, so it cannot itself report why a chapter was skipped. | Add a bounded diagnostic traversal beside the existing reader API; keep `DocumentOutline.read` behavior unchanged for T34. |
| Duplicate-boundary semantics conflict with a simple one-to-one source-page map. | Permit duplicate source pages only in T66 selections and ensure each independently assembled part maps its own page refs/labels/outlines. Test links and fields in both outputs. |
| Full rewrite normally materializes each part as `ByteArray`. | Make the orchestration sequential and discard each completed array immediately; do not return `List<ByteArray>`. Document that a single part still needs existing assembler memory until a stream-capable writer is introduced. |
| SAF providers can race or silently choose a duplicate display name. | Preflight exact planned names, ask first, re-check immediately before write where possible, and report provider failures; never promise atomic multi-file replacement. |
| Heading extraction is CPU-intensive and heuristic. | Invoke only after no usable outline, scan lazily with cancellation/progress, and label it as detected headings rather than authoritative contents. |
| The workspace is already dirty, including `PagesScreen`, `PagesViewModel`, and navigation. | Implement from the current worktree deliberately, restrict edits to T66 hunks, and review diff ownership before committing; do not revert unrelated changes. |

## Out of scope

- Splitting at an exact bookmark Y-coordinate; outputs are page-granular. The
  shared-page option controls only which output(s) receive that whole page.
- Repairing or rewriting a malformed source outline beyond skipping/reporting
  invalid entries.
- New PDF encryption, digital-signature preservation, structure-tree/tagging,
  embedded files, or arbitrary non-destination name trees; these remain the
  assembler's documented limitations unless separately tasked.
- A general external-link policy change (URI/GoToR links retain existing
  behavior); T66 reports only internal links dropped because their page is in a
  different part.
- Cloud storage, background/WorkManager execution, OCR, or new dependencies.
- Automatically invoking or certifying third-party viewers; that remains a
  documented manual acceptance check.
