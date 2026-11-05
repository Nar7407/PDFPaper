# T68 implementation plan — annotation layers

## Scope and non-goals

Add a non-destructive, per-document annotation visibility filter.  It controls
only annotations painted over a page's content; it never edits, saves, flattens,
or otherwise changes the PDF.  Content that was permanently painted into a page
stream remains visible and is reported as not filterable.

Implementation must be done on `wip/T68` in its own worktree.  This planning
session found the shared workspace on `wip/T66` with unrelated uncommitted work,
so it must not be switched in place.

No dependency is required: this uses the existing pure-Kotlin engine, Compose,
SharedPreferences, and Android print/export paths.  Thus there is no new
license, size, or network use to declare.

## Existing integration points

* `engine/.../annot/PdfAnnotation.kt` already exposes subtype, `/C`, `/T`,
  parsed creation/modification dates, `/F` flags, and page annotation order.
* `engine/.../annot/AnnotationRender.kt` calls
  `DisplayListBuilder.buildAnnotations(page.annotations())`; page content and
  annotations are deliberately separate display lists.
* `engine/.../graphics/DisplayListBuilder.kt` paints `/AP /N` when available
  and synthetic appearances otherwise, skipping PDF-hidden annotations.
* `app/.../render/PageBitmapRenderer.kt` is the central bitmap path.  It is
  already used by `ViewerViewModel`, `PagesViewModel` thumbnails,
  `AndroidPageRasterizer`/export, and presentation/reflow helpers.
* `app/.../data/DocumentLook.kt` and `DocumentLookStore` provide the existing
  content-fingerprint-keyed, app-private persistence pattern to reuse for the
  T38 per-document choice.

## Public interfaces and data rules

Create `engine/src/main/java/com/pdfpaper/engine/annot/AnnotationFilter.kt`:

* `data class AnnotationFilter(...)` is immutable and defaults to `SHOW_ALL`.
  It evaluates one `PdfAnnotation` using type, normalized RGB color, author
  (`/T`), selected date field/range, and `/F` flags.  A predicate is explicit
  rather than leaking UI-specific categories into the renderer.
* `fun PdfPage.buildAnnotationDisplayList(filter: AnnotationFilter =
  AnnotationFilter.SHOW_ALL, decoder: ExternalImageDecoder? = null):
  DisplayList` passes only matching annotations to `DisplayListBuilder`.
  Preserve the existing overload/source compatibility if callers exist.
* `data class AnnotationFilterReport(...)` contains document-wide totals by
  annotation type and normalized color, matching/hidden totals, and a
  `notFilterable` count/list of detected page-content marks.  The report must
  distinguish an absent `/C` from an actual black color.
* `fun PdfDocument.annotationFilterReport(...): AnnotationFilterReport` walks
  pages defensively.  It does not mutate parser objects and it tolerates bad
  annotation dictionaries/dates.
* Formalize the PDF visibility interaction: the filter is an additional
  constraint; annotations already suppressed by `INVISIBLE`, `HIDDEN`, or
  `NO_VIEW` remain suppressed.  Do not reinterpret `PRINT`, `READ_ONLY`, or
  `LOCKED` as visibility filters.

The engine's content-mark detector must be conservative.  It may report only
PDFPaper-owned flattened highlight/markup signatures that can be identified
reliably (for example the existing private marked-content convention), and
must report an unknown/unavailable result rather than claiming arbitrary page
paint is a highlight.  Such marks cannot be hidden.

Create app-owned, pure model/storage code, preferably
`app/src/main/java/com/pdfpaper/app/ui/viewer/AnnotationLayersModel.kt` and
`app/src/main/java/com/pdfpaper/app/data/AnnotationLayerStore.kt` (or extend
the fingerprint-keyed `DocumentLookStore` only if it keeps look and layer keys
independently decodable):

* Map engine types to stable UI groups: highlights; underlines; strikeouts;
  ink/drawings; sticky notes/comments; stamps; form fields (`WIDGET`).  Keep
  unsupported/unknown annotations visible by default and show them in an
  “Other”/report-safe row if a hidden default would otherwise make them vanish.
* Represent color chips by stable quantized engine RGB values, not Compose
  colors or localized labels.  “Solo this color” keeps that selected color
  visible within its group and hides the other filterable annotation groups as
  specified by the sheet; the model must make that policy testable.
* Persist a versioned, bounded codec under the document content fingerprint.
  Reject malformed/unknown saved state to `SHOW_ALL`; never persist authors,
  note contents, or other annotation text.  This preserves T30 privacy: state
  stays app-private and no document data is sent off device.
* Model an active-filter indicator from semantic difference from `SHOW_ALL`,
  not from whether the report currently finds a matching annotation.
* Define `AnnotationOutputMode { ALL, VISIBLE_ONLY, NONE }`, default `ALL`,
  so print and export share one prompt/result contract.  `NONE` renders page
  content only; `VISIBLE_ONLY` supplies the current engine filter; `ALL`
  supplies `SHOW_ALL`.

## Files expected to change

Engine:

* `engine/src/main/java/com/pdfpaper/engine/annot/AnnotationFilter.kt` — new
  filter, report, count aggregation, conservative flattened-mark report.
* `engine/src/main/java/com/pdfpaper/engine/annot/AnnotationRender.kt` —
  filtered display-list entry point.
* `engine/src/main/java/com/pdfpaper/engine/graphics/DisplayListBuilder.kt` —
  only if necessary to retain warning/order behavior while receiving the
  already-filtered annotation list.
* `engine/src/test/java/com/pdfpaper/engine/annot/AnnotationFilterTest.kt` —
  new focused unit/render coverage.

App:

* `app/src/main/java/com/pdfpaper/app/data/DocumentLook.kt` or new
  `AnnotationLayerStore.kt` — per-fingerprint persistence.
* `app/src/main/java/com/pdfpaper/app/ui/viewer/AnnotationLayersModel.kt` —
  new pure categories, commands, codec-facing state, counts, and output mode.
* `app/src/main/java/com/pdfpaper/app/ui/viewer/LayersSheet.kt` — new bottom
  sheet with group toggles, color chips, counts, Show all, Hide all, and Solo.
* `app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerViewModel.kt` and
  `ViewerScreen.kt` — load/store state, rerender/invalidate caches, toolbar
  indicator, launch sheet, and pass the filter to every viewer render call.
* `app/src/main/java/com/pdfpaper/app/render/PageBitmapRenderer.kt` and
  `AndroidPageRasterizer.kt` — add an optional `AnnotationFilter`/output mode,
  paint base content then the filtered annotation display list, and combine
  warnings without changing defaults.
* `app/src/main/java/com/pdfpaper/app/ui/pages/PagesViewModel.kt` — pass the
  current document filter for thumbnails.
* `app/src/main/java/com/pdfpaper/app/ui/viewer/reflow/*` and
  `app/src/main/java/com/pdfpaper/app/present/*`,
  `app/src/main/java/com/pdfpaper/app/ui/present/*` — thread the same render
  policy through reflow and presentation caches/frames; invalidate cache keys
  when it changes.
* `app/src/main/java/com/pdfpaper/app/ui/export/ExportImagesViewModel.kt` and
  `ExportImagesScreen.kt` — ask on export with All preselected and render using
  the selected output mode.
* Locate the existing Print route behind `ToolRegistry` and add the identical
  prompt/contract there; do not add a second print implementation.
* New app unit/device tests under the matching `app/src/test/.../viewer` and
  `app/src/androidTest/.../viewer` paths, plus acceptance asset generator such
  as `app/tools/t68_acceptance_pdfs.py` and `app/src/androidTest/assets/t68/`.

## Ordered session-sized steps

1. **Engine model and counts (one session).** Add the immutable filter,
   normalized color/count model, parser-safe report, and unit tests for type,
   color, author, date, and flags.  Include absent/malformed data and no
   annotations.

2. **Engine filtered painting (one session).** Thread the filter into
   annotation display-list construction without changing the page-content list.
   Add raster/image comparisons for each mixed annotation class, `/AP` and
   synthetic fallback, hidden PDF flags, and stable annotation z-order.  Add
   the conservative not-filterable report tests.

3. **App state and persistence (one session).** Implement the pure layers
   model and versioned, fingerprint-keyed storage; load it after the document
   fingerprint is available in `ViewerViewModel`.  Add unit tests for all
   commands, color solo semantics, active indicator, corrupt/missing values,
   state restore, and privacy/no-text storage.

4. **Shared rendering propagation (one session).** Extend
   `PageBitmapRenderer` and all viewer rendering call sites; content always
   renders, matching annotations render afterward.  Add the filter identity to
   thumbnail/reflow/presentation cache keys and invalidate existing entries on
   change.  Verify `SHOW_ALL` remains pixel-identical to today’s default.

5. **Layers UI (one session).** Add the bottom sheet and toolbar indicator.
   Bind row/chip counts to the engine report, expose Show all/Hide all/Solo,
   show a non-filterable notice, and make toggles immediately rerender all
   modes.  Add Compose/device semantics tests.

6. **Print and export policy (one session).** Implement the common three-way
   prompt, default `ALL`, and renderer policy for export and the existing print
   route.  Test every choice and cancel/no-selection behavior; no file save is
   involved in toggling or output-mode selection.

7. **Acceptance and regression run (one session).** Generate a mixed fixture
   with yellow/green/red highlights, underline, strikeout, ink, note/comment,
   stamp, widget, authors/dates, an `/AP` appearance, flags, and a reliably
   detectable flattened PDFPaper mark.  Run screenshot/render comparisons,
   restart restoration, byte-for-byte source equality, and T30 checks.  Run
   `./gradlew :engine:test`, `./gradlew :app:testDebugUnitTest`, the focused
   connected tests when a device is available, then `./gradlew build`.

## Test matrix

* Engine model: each predicate dimension independently and in combination;
  unknown subtype/color; null author/date; malformed PDF date; inclusive date
  boundaries; flags; all-hidden and all-visible defaults.
* Engine report: document totals by type/color, colors quantized consistently,
  no annotations, malformed annotation entries, filterable versus conservative
  not-filterable content marks.
* Engine renderer: mixed page images prove hiding a group/color/author/date
  removes only matching annotation pixels; page content is unchanged; `/AP`
  and synthetic render paths work; `SHOW_ALL` is image-identical.
* App model/store: group mapping, Show all, Hide all, Solo color, active state,
  version migration/rejection, fingerprint isolation, and restart restore.
* App rendering: viewer, thumbnails, reflow, and presentation all receive the
  same state and invalidate stale cache entries.
* Output: print/export dialog defaults to all and all/visible-only/none select
  correct render policy; cancel leaves the original document untouched.
* Acceptance: mixed fixture counts exactly match the sheet; each toggle changes
  only the expected layer; original bytes before/after UI operations are equal;
  filter survives process restart.  Keep the existing T30 privacy test suite
  green.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Existing bitmap rendering currently paints only content in some paths. | Make the annotation-list composition explicit in `PageBitmapRenderer`, retain `SHOW_ALL` default, and add default-image regression tests before changing callers. |
| Annotation appearance streams can paint arbitrary colors unrelated to `/C`. | Filter by annotation metadata before building its whole appearance; color chips describe `/C`, never attempt pixel/color inference. |
| Flattened highlights are indistinguishable from ordinary page content. | Detect only a reliable producer marker; otherwise report “not detectable/not filterable” and never promise to hide it. |
| Stale bitmap/reflow/presentation cache entries show the old filter. | Add a stable filter cache token to every relevant key and clear affected caches on change. |
| Saved settings accidentally include authors or content. | Persist only version, booleans, type enum names, and RGB chips; unit-test the serialized string for absence of author/content. |
| Print/export may bypass the app renderer. | Locate their actual render entry point first and route all three output modes through the shared policy; default remains ALL for backward-compatible output. |
| Dirty `wip/T66` workspace may be damaged by branch switching. | Create/use the requested `wip/T68` worktree; do not checkout, reset, or clean the shared directory. |

