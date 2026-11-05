# T68b status

Branch: `wip/T68b`. Implementer session, 2026-11-05.

## Step 1 — renderer composition and configured rasterizer: done

### Implemented

* `app/src/main/java/com/pdfpaper/app/render/PageBitmapRenderer.kt`
  * Builds `page.buildAnnotationDisplayList(annotationFilter, decoder)` and
    replays it after the page-content display list, inside the same
    `canvas.save()` / `canvas.concat(plan.matrix)` scope and with one
    `AndroidRenderer` (same theme, transform, E-Ink settings, font dir, scale).
  * Returns `displayList.warnings + annotations.warnings` (content first).
    `AnnotationFilter.SHOW_ALL` is still the default argument, so existing
    callers and the all-visible analysis paths are unchanged.
* `app/src/main/java/com/pdfpaper/app/render/AndroidPageRasterizer.kt`
  * `AndroidPageRasterizer(filter)` returns a `PageRasterizer` configured with
    the filter (the plain object for `SHOW_ALL`) through one shared
    `rasterize(page, plan, filter)`.

### Test placement decision (owner-approved)

`PageBitmapRendererFilterTest` lived in `app/src/test` but calls the real
renderer. `:app` sets `testOptions.unitTests.isReturnDefaultValues = true`
(`app/build.gradle.kts`) and has no Robolectric, so on the JVM
`Bitmap.createBitmap(...)` returns `null` and four tests failed with
`NullPointerException: createBitmap(...) must not be null` before any
assertion. There is no production-side fix; `android.graphics.Bitmap` cannot
exist in a plain JVM unit test.

Owner chose: move the four pixel cases to `app/src/androidTest` with the same
fixture and assertions; keep the JVM-safe factory test in `app/src/test`.

* `app/src/androidTest/java/com/pdfpaper/app/render/PageBitmapRendererFilterDeviceTest.kt`
  (new, `@RunWith(AndroidJUnit4::class)`) holds the four cases verbatim.
* `app/src/test/java/com/pdfpaper/app/render/PageBitmapRendererFilterTest.kt`
  holds only `androidPageRasterizerConfiguredWithFilter`.
* `app/src/sharedTest/java/com/pdfpaper/app/render/T68bFixtures.kt` (moved from
  `app/src/test`) is shared by both test sets; `app/build.gradle.kts` adds
  `src/sharedTest/java` to the `test` and `androidTest` source sets. No
  dependency was added.

### Unrelated compile fix needed by the move

`app/src/androidTest/.../speech/ReadAloudViewerDeviceTest.kt`'s
`RecordingCommands` did not implement the `ReadAloudHub.Commands` members
`setSpeed`, `setSleep`, `setVoice`, so the whole androidTest source set did not
compile (pre-existing on `wip/T66` too; the file is identical there). Added
three no-op overrides. No assertion or existing behaviour was changed.

### Real command output

```
> .\gradlew.bat :app:testDebugUnitTest --tests "com.pdfpaper.app.render.PageBitmapRendererFilterTest" :app:compileDebugAndroidTestKotlin
> Task :app:testDebugUnitTest
> Task :app:compileDebugAndroidTestKotlin
BUILD SUCCESSFUL in 1m 6s
```

Full JVM suite after the move (the remaining failures are the untouched T68b
stubs for steps 3 and 5):

```
> .\gradlew.bat :app:testDebugUnitTest
585 tests completed, 7 failed
* com.pdfpaper.app.ui.export.ExportAnnotationOutputModeTest: 5 failed (NotImplementedError: T68b)
* com.pdfpaper.app.ui.pages.PagesThumbnailLayersTest: 2 failed (NotImplementedError: T68b)
BUILD FAILED in 23s
```

## Step 2 — viewer layer state: done

### Implemented

`app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerViewModel.kt` only.

* State: `_annotationLayers` (`AnnotationLayersState.SHOW_ALL`), `_annotationFilterReport`
  (`null`), `_isLayersSheetOpen` (`false`), exposed as `StateFlow`s through the
  existing public names. The store is a lazy `AnnotationLayerStore`.
* `openLayersSheet()` / `closeLayersSheet()` flip the flag.
* `toggleLayerGroup`, `toggleLayerColor`, `soloLayerColor`, `showAllLayers`,
  `hideAllLayers` all go through one `updateLayers(change)`: an equal state is a
  no-op, otherwise the immutable model change is published, saved through the
  fingerprint-keyed store (which no-ops without a fingerprint) and every cache
  above a page render is dropped (`presentCache`, `linkPreviews`,
  `reflowRegionCache`, flow bitmaps + order, then `invalidateRenders()`).
* Restore: on open success, right after the fingerprint is known and before
  `_state.value = ViewerUiState(...)`, the saved state is loaded, the sheet is
  closed and the report is requested.
* Report: `loadAnnotationReport()` runs `PdfDocument.annotationFilterReport()`
  once on the document dispatcher with `AnnotationFilter.SHOW_ALL` (counts are
  filter-independent), ignores a failure, and only publishes when the same
  document is still open.
* Reset: `closeCurrentDocument()` cancels the report job, clears the report and
  returns the layers to `SHOW_ALL` with the sheet closed.
* Nothing is written to the PDF; `AnnotationFilter.toEngineFilter()` is still the
  only bridge between the UI state and the engine.

## Step 3 — filter threaded through visible render paths: done

### Implemented

**`app/src/main/java/com/pdfpaper/app/ui/pages/PagesViewModel.kt`**
* `annotationLayerStore` (lazy), `layers` (`SHOW_ALL`), `layerFilter` returning
  `layers.toEngineFilter()`.
* Restore: after the fingerprint is resolved in `openPrimary()`, `layers` is
  loaded from the store and the thumbnail `cache` is cleared (stale bitmaps are
  keyed by position and must not survive across documents).
* `refreshThumbnailLayers()`: loads by fingerprint, no-ops when unchanged,
  otherwise drops render jobs, the `LruCache`, the thumbs map and bumps
  `_themeGeneration` so grid cells ask again.
* The grid cell (`onThumbVisible`) paints with `annotationFilter = layerFilter`.
* `PagesScreen.kt` calls `refreshThumbnailLayers()` on the look/theme effect.

**`app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerViewModel.kt`** — six
user-visible render sites now pass
`annotationFilter = _annotationLayers.value.toEngineFilter()`:
`renderFitPreview`, the reflow-region render, `onPageVisible`,
`presentCache` render, the column-flow segment render and the flow-presenter
render. The look-hint render stays `SHOW_ALL` (detection, not painting).

**`app/src/main/java/com/pdfpaper/app/ui/pages/PagesScreen.kt`**
* Added the call into the theme/look `LaunchedEffect` so the thumb filter is
  refreshed when the screen resumes and its fingerprint exists.

No engine, store, model or test file was changed in this step.

### Real command output

```
> .\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
585 tests completed, 5 failed
* com.pdfpaper.app.ui.export.ExportAnnotationOutputModeTest: 5 failed
  (NotImplementedError: T68b — step 5, untouched stubs)
BUILD FAILED in 1m 23s

> .\gradlew.bat :app:compileDebugAndroidTestKotlin
BUILD SUCCESSFUL in 4s
```

