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
