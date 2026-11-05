# T68 status — annotation layers

## Implementer

Branch `wip/T68` (worktree). Tests were committed by the test writer; this file
tracks implementation progress.

## 2026-11-05 — implemented

Engine (`:engine`):

- `annot/AnnotationFilter.kt`: `AnnotationFilter.matches` conjunctive
  type/color/author/date-range/flags-mask/predicate evaluation. PDF visibility
  flags (`INVISIBLE|HIDDEN|NO_VIEW`, ISO 32000-1 §12.5.3 Table 165) always win.
  Dates are parsed with `PdfDate.millis` from `/CreationDate` and `/M` (absent or
  malformed → no match when a date range is set). `PdfColor.rgbInt()` gives the
  canonical `0xRRGGBB` key; an absent `/C` stays `null` (distinct from black).
- `PdfDocument.annotationFilterReport`: type counts, color counts (including the
  `null` key), total/matching/hidden counts, and one `NotFilterableMark` per page
  per `/OC /PDFPaperWatermark|HeaderFooter|Bates BDC` marker found in the decoded
  page content (PDFPaper-written marks cannot be filtered by an annotation
  filter). Read-only.
- `annot/AnnotationRender.kt`: `PdfPage.buildAnnotationDisplayList(filter, decoder)`
  paints only matching annotations; the existing unfiltered overload is unchanged.

App (`:app`):

- `ui/viewer/AnnotationLayersModel.kt`: `AnnotationType.toLayerGroup`,
  `quantizeRgb`, `AnnotationLayersState` with `isFilterActive`, `showAll`,
  `hideAll`, `soloColor`, `toggleGroup`, `toggleColor`, `toEngineFilter`
  (enabled groups → engine type set, OTHER = the remaining types; color
  predicate excludes disabled colors and, when enabled colors are set, requires
  membership).
- `data/AnnotationLayerStore.kt`: `AnnotationLayerCodec`
  (`1|groupsCsv|enabledColorsCsv|disabledColorsCsv`; three-field records decode
  as enabled colors only; unknown version → `SHOW_ALL`; unknown group tokens and
  unparsable colors are skipped) and a fingerprint-keyed store over app-private
  `pdfpaper_reading` prefs. Persisted state contains no authors, note text or
  file paths (T30).

## Tests (real output)

- `:engine:test` — 646 tests, 0 failures, 0 errors.
- `:app:testDebugUnitTest` — 575 tests, 0 failures, 0 errors.
- `./gradlew build` — `BUILD SUCCESSFUL` (lint + release APK green). The full
  build ran before one final one-line change in `AnnotationFilter.matches`
  (dates now parsed by `PdfDate.millis` on `/CreationDate` and `/M` directly,
  instead of the `createdAtMillis`/`modifiedAtMillis` helpers that belong to
  another task's uncommitted work); the engine and app suites above were re-run
  green after that change.

Note: this branch's HEAD already carries committed tests from other tasks
(TocSplit, RegexSearch, ...) whose implementations and test fixtures are still
uncommitted in the shared working tree, so the branch does not compile in an
isolated checkout. Verification was therefore run in the shared worktree, as in
previous sessions.

## Not done / known gaps

- The Layers bottom sheet, viewer toolbar indicator, and print/export output-mode
  selection are not wired in this task's committed tests; the model and store
  they would use are implemented above.
- `PageBitmapRenderer` paints the page content display list only, so app
  annotations are not part of the rendered bitmap today. Rendering-side layer
  visibility (`buildAnnotationDisplayList(filter)`) is ready but not yet called
  by the viewer.
