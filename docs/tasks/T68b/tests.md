# T68b Tests Documentation

## Overview
This document records the test suite written for Task T68b (annotation layer filtering, user-facing half).
The test suite spans:
1. **Fixture Validation Tests (`app/src/test`):** Verifies the test fixture `t68b-mixed.pdf` parses cleanly with `:engine` and produces expected annotation counts, types, and engine report structure.
2. **Privacy Verification Tests (`app/src/test`):** Verifies compliance with T30 privacy constraints (no author names, note contents, or file URIs stored or leaked in layer preferences).
3. **Renderer & Rasterizer Behaviour Tests (`app/src/test`):** Verifies layer filter integration with `PageBitmapRenderer` and configured `AndroidPageRasterizer`.
4. **Thumbnail Behaviour Tests (`app/src/test`):** Verifies layer filter integration with `PagesViewModel`.
5. **Export & Print Behaviour Tests (`app/src/test`):** Verifies output mode handling in `ExportImagesViewModel` and `PdfPrintAdapter`.
6. **Device & UI Tests (`app/src/androidTest`):** Verifies `ViewerViewModel` lifecycle, commands, and immutability of the source PDF, as well as `LayersSheet` Compose rendering.

---

## Test Table

| Test Class | Test Method | What it checks | Fixture Used | Initial Status |
|---|---|---|---|---|
| `T68bFixtureValidTest` | `fixtureOpensAndHasExpectedPagesAndOutline` | Validates `t68b-mixed.pdf` parses cleanly with `PdfReader`, has 2 pages and outline entries | `t68b-mixed.pdf` | **PASS** |
| `T68bFixtureValidTest` | `fixtureHasExpectedAnnotationsAndTypes` | Validates page 1 has 9 annotations (3 highlights, 1 underline, 1 strikeout, 2 ink, 1 note, 1 stamp) and page 2 has 1 form field | `t68b-mixed.pdf` | **PASS** |
| `T68bFixtureValidTest` | `fixtureMatchesAnnotationFilterReport` | Validates `annotationFilterReport` returns exact expected group/color counts and 1 non-filterable watermark | `t68b-mixed.pdf` | **PASS** |
| `AnnotationLayersPrivacyTest` | `layerStateSerializationNeverContainsAuthorOrContents` | Validates `AnnotationLayerCodec` and serialized preferences never leak author names, note text, or document URIs (T30) | None | **PASS** |
| `PageBitmapRendererFilterTest` | `renderWithHighlightGroupFilterShowsOnlyHighlights` | Calls `PageBitmapRenderer.render` with non-default filter; verifies filter propagation | `t68b-mixed.pdf` | **FAIL (`NotImplementedError`)** |
| `PageBitmapRendererFilterTest` | `renderWithMatchNoneFilterHidesAnnotationsAndKeepsContent` | Verifies match-none filter renders page content while omitting annotation display lists | `t68b-mixed.pdf` | **FAIL (`NotImplementedError`)** |
| `PageBitmapRendererFilterTest` | `renderWithColorFilterHidesOtherColors` | Verifies color-specific predicate hides other annotation colors | `t68b-mixed.pdf` | **FAIL (`NotImplementedError`)** |
| `PageBitmapRendererFilterTest` | `renderComposesBaseAndAnnotationWarningsInOrder` | Verifies warnings from page content and annotations are preserved in source order | `t68b-mixed.pdf` | **FAIL (`NotImplementedError`)** |
| `PageBitmapRendererFilterTest` | `androidPageRasterizerConfiguredWithFilter` | Verifies `AndroidPageRasterizer(filter)` factory produces configured rasterizer | `t68b-mixed.pdf` | **FAIL (`NotImplementedError`)** |
| `PagesThumbnailLayersTest` | `thumbnailRenderingUsesFingerprintLayerFilter` | Verifies `PagesViewModel.layerFilter` property accesses fingerprint-keyed state | None | **FAIL (`NotImplementedError`)** |
| `PagesThumbnailLayersTest` | `layerFilterChangeInvalidatesThumbnailCache` | Verifies `refreshThumbnailLayers()` refreshes thumbnail cache upon layer filter changes | None | **FAIL (`NotImplementedError`)** |
| `ExportAnnotationOutputModeTest` | `exportAnnotationOutputModeDefaultsToAll` | Verifies `ExportImagesViewModel.annotationOutputMode` defaults to `ALL` | None | **FAIL (`NotImplementedError`)** |
| `ExportAnnotationOutputModeTest` | `setAnnotationOutputModeVisibleOnlyUsesSavedFilter` | Verifies `VISIBLE_ONLY` uses document's saved filter | None | **FAIL (`NotImplementedError`)** |
| `ExportAnnotationOutputModeTest` | `setAnnotationOutputModeNoneUsesEmptyFilter` | Verifies `NONE` mode uses match-none filter | None | **FAIL (`NotImplementedError`)** |
| `ExportAnnotationOutputModeTest` | `resolvePageRasterizerForOutputMode` | Verifies rasterizer resolution according to selected output mode | None | **FAIL (`NotImplementedError`)** |
| `ExportAnnotationOutputModeTest` | `pdfPrintAdapterResolvesFilterPerOutputMode` | Verifies `PdfPrintAdapter.resolveFilter()` resolves engine filter for output mode | None | **FAIL (`NotImplementedError`)** |
| `ViewerLayersDeviceTest` | `loadsPersistedLayerStateOnFingerprint` | Verifies `ViewerViewModel` restores persisted state once content fingerprint is resolved | `t68b-mixed.pdf` | *Device Test* |
| `ViewerLayersDeviceTest` | `toggleLayerGroupUpdatesStateAndSaves` | Verifies `toggleLayerGroup` updates state and persists via `AnnotationLayerStore` | `t68b-mixed.pdf` | *Device Test* |
| `ViewerLayersDeviceTest` | `toggleLayerColorUpdatesState` | Verifies `toggleLayerColor` updates active filter colors | `t68b-mixed.pdf` | *Device Test* |
| `ViewerLayersDeviceTest` | `soloColorEnablesOnlySpecifiedGroupAndColor` | Verifies `soloLayerColor` enables only single group and color | `t68b-mixed.pdf` | *Device Test* |
| `ViewerLayersDeviceTest` | `showAllResetsToDefaultState` | Verifies `showAllLayers` resets filter to `SHOW_ALL` | `t68b-mixed.pdf` | *Device Test* |
| `ViewerLayersDeviceTest` | `hideAllDisablesAllAnnotationGroups` | Verifies `hideAllLayers` disables all annotation groups | `t68b-mixed.pdf` | *Device Test* |
| `ViewerLayersDeviceTest` | `layersSheetVisibilityControls` | Verifies `openLayersSheet` / `closeLayersSheet` UI state toggling | None | *Device Test* |
| `ViewerLayersDeviceTest` | `annotationFilterReportEmitsDocumentCounts` | Verifies report is exposed for open document | `t68b-mixed.pdf` | *Device Test* |
| `ViewerLayersDeviceTest` | `togglingFilterLeavesPdfFileByteIdentical` | Verifies original PDF bytes and SHA-256 hash remain completely untouched after filter operations | `t68b-mixed.pdf` | *Device Test* |
| `LayersSheetDeviceTest` | `layersSheetRendersOrThrowsNotImplementedError` | Verifies `LayersSheet` Compose rendering and interaction tags | None | *Device Test* |

---

## Open Questions & Notes
1. **Print Tool Integration:** In the current codebase, the `print` tool in `ToolRegistry` is registered as a viewer tool. `PdfPrintAdapter` provides the print document adapter; when wired in session 5, `resolveFilter()` will configure the document rasterizer for printing.
2. **ViewModel Testing Pattern:** In accordance with the codebase pattern (`ViewerEditingTest`, `ViewerRegexModelTest`, `ReflowModelTest`, etc.), pure business models and unit-testable facades run in JVM unit tests, while full Android ViewModels depending on `Application` and `Dispatchers.Main` live in `androidTest` instrumented tests.
