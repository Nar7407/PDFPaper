# T68b plan — annotation layers, user-facing integration

## Scope and starting point

Implement only the app-side integration of T68 on branch `wip/T68b`, based on
commit `84a0533`.  That commit already provides and tests:

* `AnnotationFilter` and `PdfPage.buildAnnotationDisplayList(filter, decoder)`;
* `PdfDocument.annotationFilterReport(filter)` including non-filterable
  PDFPaper page-content marks;
* `AnnotationLayersState`, group/color commands, `toEngineFilter()`, and
  `AnnotationOutputMode`;
* `AnnotationLayerStore`, keyed by the document content fingerprint and storing
  no document text, author, or path.

Do not change those engine or state/store APIs.  No new dependency is needed
(therefore no license, size, or network use to declare).  T68b must be worked
in a `wip/T68b` worktree; the currently shared checkout is `wip/T66` with
unrelated modifications and must not be switched, reset, cleaned, or used for
implementation work.

## Rendering contract

`PageBitmapRenderer` must make layer filtering a regular render input:

1. Paint `page.buildDisplayList(decoder)` exactly as today.
2. Build `page.buildAnnotationDisplayList(annotationFilter, decoder)` and replay
   it using the **same** canvas transform, `AndroidRenderer`, theme, transform,
   E-Ink settings, and graphics-state boundaries.
3. Return combined non-fatal warnings in source order.  Do not alter the PDF,
   parser objects, or page-content display list.

Add an optional `AnnotationFilter = AnnotationFilter.SHOW_ALL` parameter to
each public `PageBitmapRenderer.render` overload.  This preserves existing
callers and yields the current all-visible behaviour unless a caller explicitly
supplies the document's `AnnotationLayersState.toEngineFilter()`.

`AndroidPageRasterizer` is presently a global object with no render policy.
Refactor only the app adapter as needed into a small configured `PageRasterizer`
factory/value (for example `AndroidPageRasterizer(filter)`), retaining the
existing all-visible default for unrelated tools such as crop/fit detection.
This is not an engine API change.  It allows PageExport, content-fit, and
column-flow to receive the correct bitmap policy without global mutable state.

## Exact propagation paths

* **Viewer:** `ViewerViewModel` owns `AnnotationLayersState`, loads it once the
  existing content fingerprint is resolved, saves each user command through
  `AnnotationLayerStore`, obtains the engine report from the opened document,
  and passes `state.toEngineFilter()` to every `PageBitmapRenderer.render` call
  used for page, zoom, preview, and look rendering.  Changing a layer increments
  the existing render generation and cancels/invalidates stale page jobs/cache
  entries before requesting the visible pages again.
* **Thumbnails:** `PagesViewModel` must load the same fingerprint-keyed state
  and pass its filter to its thumbnail calls.  Its cache key/generation must
  include a stable layer-state token so a thumbnail cannot reuse an all-visible
  bitmap after a filter update.  If `PagesViewModel` cannot observe viewer
  changes while both screens are alive, reload from `AnnotationLayerStore` on
  resume/entry rather than introduce a second state source.
* **Reflow and column flow (T33):** the `ViewerViewModel` reflow/flow region
  calls at the page-bitmap renderer must receive the same filter.  For analysis
  rasterizers (`ContentFit`, `ColumnFlow`) use the all-visible default unless
  the raster itself becomes user-visible; filtering must not make text/figure
  detection incorrect.  Include layer-state identity in `REFLOW_PAGE_CACHE`,
  `FLOW_PAGE_CACHE`, and `FLOW_BITMAP_CACHE` keys (or clear those caches on a
  layer change) because their visible region bitmaps are filtered output.
* **Presentation (T53):** presentation is supplied with viewer page bitmaps;
  update the viewer's presentation-page render request (the existing
  `PageBitmapRenderer` call) to use the active filter.  `PresentViewModel`
  remains session/control-only and must not duplicate persistence or filter
  logic.  External/presenter surfaces therefore show the same bitmap.
* **Export:** `ExportImagesViewModel` must obtain the fingerprint, load the
  persisted state, and create a page-export rasterizer for the selected
  `AnnotationOutputMode`: `ALL → SHOW_ALL`, `VISIBLE_ONLY → saved filter`,
  `NONE → a filter that matches no annotation`.  This applies to page-image
  export only; embedded-image extraction does not paint annotations, so it
  either omits the prompt or explains that the choice has no effect rather than
  pretending to filter raw extracted image bytes.
* **Print:** locate the current print action/adapter behind the `print` tool
  registration and route it through the same one-shot output-policy resolver.
  If the registration is currently a placeholder, add the minimum real print
  entry point there rather than a parallel printing implementation.  Its
  default is `ALL`; no saved per-document output preference is introduced.

## Layers sheet UI and states

Create `ui/viewer/LayersSheet.kt`, with all presentation state supplied by
`ViewerViewModel` (not duplicated in Compose local state):

* **Loading/unavailable report:** controls remain usable with `SHOW_ALL` / the
  restored state; counts show a neutral loading placeholder, never zero as a
  misleading count.  A report error does not alter the rendered filter.
* **Ready:** rows in this order: Highlights, Underlines, Strikeouts,
  Ink/drawings, Sticky notes and comments, Stamps, Form fields.  Each has an
  enabled switch/checkbox and its report-derived count.  The Highlights and Ink
  rows expand/show their distinct color chips; chips show the per-color count
  and call `toggleColor`.  A null `/C` item is counted but not assigned an
  invented color chip.
* **Empty group:** retain the group row with count 0 so the control is stable;
  chips are absent.  Unknown engine annotation types stay visible under the
  model's `OTHER` default and are not silently hidden by a group toggle.
* **Actions:** Show all calls `showAll`; Hide all calls `hideAll`; each chip has
  “Solo this color”, calling the existing `soloColor(group, rgb)`.  Hide all
  affects annotations only, not page content.
* **Not filterable:** when `report.notFilterableCount > 0`, show a clear,
  non-toggleable line beneath highlights: “N page-content mark(s) are not
  filterable,” with the report descriptions/pages available for accessibility.
* **Active indicator:** add a toolbar icon/badge only when
  `AnnotationLayersState.isFilterActive`; tapping it opens the sheet.  The main
  viewer tools/options path must also expose Layers when no filter is active,
  otherwise a user cannot start filtering.  Give icon, sheet, rows, chips, and
  actions stable `UiTags` for device tests.
* **Persistence:** restore before initial visible-page rendering whenever the
  fingerprint is available; write on every state command with `apply()` via the
  store.  Invalid/missing state is `SHOW_ALL`, exactly as the existing codec
  specifies.  Closing/dismissing the sheet never writes the PDF.

## Files expected to change

* `app/src/main/java/com/pdfpaper/app/render/PageBitmapRenderer.kt` — compose
  base and filtered annotation display lists and expose optional filter input.
* `app/src/main/java/com/pdfpaper/app/render/AndroidPageRasterizer.kt` — make
  render-policy configuration per caller possible with an all-visible default.
* `app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerViewModel.kt` — lifetime,
  report/state/store wiring, filtered viewer/reflow/flow/presentation rendering,
  cache invalidation, and UI commands.
* `app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerScreen.kt` and
  `app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerToolsSheet.kt` — launch
  Layers and display the active indicator.
* `app/src/main/java/com/pdfpaper/app/ui/viewer/LayersSheet.kt` — new sheet.
* `app/src/main/java/com/pdfpaper/app/ui/UiTags.kt` — T68b test tags.
* `app/src/main/java/com/pdfpaper/app/ui/pages/PagesViewModel.kt` — filtered
  thumbnail rendering and refresh policy.
* `app/src/main/java/com/pdfpaper/app/ui/export/ExportImagesViewModel.kt` and
  `ExportImagesScreen.kt` — three-choice page export prompt and rasterizer.
* The discovered existing print screen/action/adapter — use the shared output
  choice resolver after locating it; `ToolRegistry.kt` changes only if needed
  to connect the already registered Print tool.
* Existing reflow/flow cache model files only if their cache keys must carry a
  filter token; presentation UI files only if the bitmap request lives there.
* New focused unit/device/render tests and a deterministic mixed-annotation
  fixture generator/assets under `app/tools/t68b_*` and
  `app/src/androidTest/assets/t68b/`.

## Ordered implementation sessions

1. **Renderer composition and regression test.** Add the optional filter to
   `PageBitmapRenderer`, replay the annotation display list after content, and
   create a configured rasterizer adapter.  Test base content remains visible,
   `SHOW_ALL` matches the old renderer image, and a match-none filter removes
   only annotation pixels.

2. **Viewer state lifecycle.** Wire the already-created store/model/report into
   `ViewerViewModel`: load after fingerprint, expose immutable UI state and
   commands, save commands, and invalidate page jobs/visible render caches.
   Test restored/malformed state and source-file SHA-256 equality before/after
   every UI command.

3. **All on-screen render paths.** Pass the active filter to normal viewer,
   zoom/preview, thumbnails, reflow, column flow, and presentation bitmap calls.
   Give filtered visible-bitmap caches a state token or clear them on a change;
   leave detection-only rasterization all-visible.  Add renderer/device tests
   proving the same group disappears in every visible mode.

4. **Layers sheet and indicator.** Implement the controlled sheet, report
   counts/color chips/non-filterable notice, Show all/Hide all/Solo operations,
   toolbar indicator, tools entry, and `UiTags`.  Add Compose/model tests and
   device tests for toggles, chip solo, disabled groups, active indicator, and
   report-loading state.

5. **Output policy.** Add a reusable one-shot output-mode selector with `ALL`
   preselected.  Use it for page-image export and the actual print path;
   configure the rasterizer per selection.  Test all/visible-only/none and
   cancellation; raw embedded-image extraction remains unchanged and clearly
   scoped.

6. **Acceptance and complete verification.** Produce a PDF with yellow,
   green, and red highlights; underline; strikeout; colored ink; notes/comments;
   stamp; widget; an appearance-stream annotation; and a PDFPaper marked-content
   watermark/header/Bates sample.  Verify image deltas, exact counts, restart
   restoration, export results, source-file hash equality, T30 privacy tests,
   `./gradlew :engine:test`, `./gradlew :app:testDebugUnitTest`, focused device
   tests (when an emulator/device is available), and `./gradlew build`.

## Test list

* Bitmap render tests: mixed fixture, all-visible baseline, match-none, each
  type group, each color chip, `/AP` appearance, hidden PDF flags, page-content
  pixels unchanged, and composed warnings retained.
* View-model/unit tests: initial restore keyed by fingerprint, command-to-filter
  mapping, report-to-group/color counts, render-generation/cache invalidation,
  and byte-identical original content after toggling.
* Persistence/T30 tests: restart restoration; document fingerprints isolated;
  serialized preference never contains author, contents, URI, or PDF bytes;
  existing T30 suite stays green.
* Device tests: Layers entry/indicator, all seven rows and counts, chips and
  Solo, non-filterable notice, viewer/thumbnail/reflow/presentation consistency,
  and no UI path offers a save/modify action.
* Output tests: dialog defaults to All; All renders all annotations;
  Visible-only uses restored active filter; None renders content only; cancel
  exports/prints nothing; embedded extraction remains byte-preserving.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Page renderer transform/theme/E-Ink treatment differs between base and annotations. | Replay the annotation display list inside the same canvas save/concat scope and use the same renderer configuration; protect with image comparisons. |
| Global rasterizer state leaks a filter into crop/fit or another document. | Use a per-operation configured adapter/value; keep the existing default all-visible instance for analysis paths. |
| Cached thumbnails/reflow/presentation frames remain stale after a toggle. | Include a stable filter-state token in visible bitmap keys or clear the exact caches and cancel their in-flight generation. |
| Toggling a group hides unknown annotation types accidentally. | Preserve `OTHER` as enabled by default and do not show it as an unsupported hidden category. |
| `/C` is absent or appearance ink differs from metadata color. | Use T68's canonical metadata color/count and filter before painting the entire appearance; do not infer colors from pixels. |
| Print entry point is not implemented despite tool registration. | Locate it before coding; add only the minimum adapter needed and document any discovered absence in `docs/tasks/T68b/status.md`. |
| Export of embedded images cannot contain annotation appearances. | Apply output mode to rendered page export only and communicate/omit an inapplicable selector for embedded extraction. |
| Filter settings could expose document data. | Reuse only `AnnotationLayerStore`; do not add logging, analytics, author/date/content persistence, or network access. |

