# T68 Tests Specification & Status

## Overview

Task T68 adds non-destructive annotation layers and visibility filtering:
- Engine: `AnnotationFilter`, `annotationFilterReport`, filtered `buildAnnotationDisplayList`.
- App: `AnnotationLayersModel`, `AnnotationLayerStore`, Layers bottom sheet, toolbar indicator, print/export mode selection.

In accordance with the test writer role guidelines:
1. All fixtures are built using the project's own writer and existing test helpers (`buildTestPdf`, `PdfPageOps.applyAnnotations`, `PdfMarks.applyWatermark`). No object numbers or references are hand-coded.
2. The fixture validation test `T68FixtureValidTest.mixedAnnotationFixtureIsValid` passes immediately.
3. Minimal stubs with `TODO("T68")` are provided in main source code so tests compile cleanly with zero compilation errors.
4. All behaviour tests fail with `kotlin.NotImplementedError` because the implementation is not yet provided.

---

## Test Inventory

### 1. Fixture Validation Tests

| Test | Class | What it checks | Fixture | Result |
| --- | --- | --- | --- | --- |
| `mixedAnnotationFixtureIsValid` | `T68FixtureValidTest` | Asserts fixture parses, has 3 pages, extracts text ("P1", "P2", "P3"), has outlines ("Book One", "Book Three"), has mixed annotations (highlights, underlines, ink, notes, stamps, form field widget, link), and PDFPaper watermark | `T68Fixtures.createMixedAnnotationDoc()` | **PASS** |

### 2. Engine Model Tests (`AnnotationFilterTest`)

| Test | Class | What it checks | Fixture | Expected Failure |
| --- | --- | --- | --- | --- |
| `defaultShowAllMatchesAllAnnotations` | `AnnotationFilterTest` | `AnnotationFilter.SHOW_ALL` matches any annotation | In-memory `sampleAnnotation` | `NotImplementedError: T68` |
| `filterByTypeMatchesOnlySelectedTypes` | `AnnotationFilterTest` | `AnnotationFilter(types)` allows matching types and rejects non-matching types | In-memory `sampleAnnotation` | `NotImplementedError: T68` |
| `filterByColorMatchesNormalizedRgb` | `AnnotationFilterTest` | `AnnotationFilter(colors)` matches quantized RGB and rejects other colors | In-memory `sampleAnnotation` | `NotImplementedError: T68` |
| `filterByColorDistinguishesAbsentColorFromBlack` | `AnnotationFilterTest` | Filter distinguishes absent `/C` (null) from true black (`PdfColor.BLACK`) | In-memory `sampleAnnotation` | `NotImplementedError: T68` |
| `filterByAuthorMatchesExactOrSet` | `AnnotationFilterTest` | `AnnotationFilter(authors)` matches author title (`/T`) | In-memory `sampleAnnotation` | `NotImplementedError: T68` |
| `filterByDateRangeMatchesCreationAndModifiedDates` | `AnnotationFilterTest` | Filter matches timestamp within inclusive range and rejects outside | In-memory `sampleAnnotation` | `NotImplementedError: T68` |
| `filterByDateToleratesMalformedOrMissingDate` | `AnnotationFilterTest` | Filter does not throw on null or malformed date strings, safely rejects | In-memory `sampleAnnotation` | `NotImplementedError: T68` |
| `filterByDateInclusiveBoundaries` | `AnnotationFilterTest` | Filter inclusively matches exact boundary timestamps in `dateRange` | In-memory `sampleAnnotation` | `NotImplementedError: T68` |
| `pdfVisibilityFlagsSuppressAnnotationRegardlessOfFilter` | `AnnotationFilterTest` | `INVISIBLE`, `HIDDEN`, or `NO_VIEW` flags suppress matching regardless of filter | In-memory `sampleAnnotation` | `NotImplementedError: T68` |
| `combinedFilterMatchesOnlyWhenAllDimensionsMatch` | `AnnotationFilterTest` | Conjunction of type, author, color, and date must all match | In-memory `sampleAnnotation` | `NotImplementedError: T68` |
| `unknownSubtypeToleratedAndFilteredNormally` | `AnnotationFilterTest` | `AnnotationType.UNKNOWN` can be explicitly matched or excluded without throwing | In-memory `sampleAnnotation` | `NotImplementedError: T68` |

### 3. Engine Report Tests (`AnnotationFilterTest`)

| Test | Class | What it checks | Fixture | Expected Failure |
| --- | --- | --- | --- | --- |
| `reportOnDocumentWithoutAnnotationsReturnsEmptyTotals` | `AnnotationFilterTest` | Report on document with 0 annotations returns 0 counts and empty maps | Synthesized blank PDF | `NotImplementedError: T68` |
| `reportCalculatesDocumentWideTotalsByType` | `AnnotationFilterTest` | Aggregates document-wide totals across all pages per `AnnotationType` | `T68Fixtures.createMixedAnnotationDoc()` | `NotImplementedError: T68` |
| `reportCalculatesDocumentWideTotalsByColor` | `AnnotationFilterTest` | Aggregates document-wide totals across pages grouped by quantized RGB color | `T68Fixtures.createMixedAnnotationDoc()` | `NotImplementedError: T68` |
| `reportDistinguishesAbsentColorFromBlackColor` | `AnnotationFilterTest` | Report records absent `/C` under key `null` separate from RGB `0x000000` | `T68Fixtures.createMixedAnnotationDoc()` | `NotImplementedError: T68` |
| `reportCalculatesMatchingAndHiddenTotals` | `AnnotationFilterTest` | Report calculates `matchingCount` and `hiddenCount` for the given filter | `T68Fixtures.createMixedAnnotationDoc()` | `NotImplementedError: T68` |
| `reportDetectsConservativeNotFilterablePageMarks` | `AnnotationFilterTest` | Conservative detection identifies PDFPaper marked-content watermarks as `notFilterable` | `T68Fixtures.createMixedAnnotationDoc()` | `NotImplementedError: T68` |
| `reportDoesNotMutateDocumentOrParserObjects` | `AnnotationFilterTest` | Walks pages defensively without altering parser objects or annotation lists | `T68Fixtures.createMixedAnnotationDoc()` | `NotImplementedError: T68` |
| `reportToleratesMalformedAnnotationDictionary` | `AnnotationFilterTest` | Tolerates invalid `/Annots` entries and malformed dicts without uncaught exceptions | Corrupt annot dict fixture | `NotImplementedError: T68` |

### 4. Engine Renderer & DisplayList Tests (`AnnotationFilterTest`)

| Test | Class | What it checks | Fixture | Expected Failure |
| --- | --- | --- | --- | --- |
| `buildAnnotationDisplayListWithShowAllIsIdenticalToDefault` | `AnnotationFilterTest` | `buildAnnotationDisplayList(SHOW_ALL)` commands match existing default display list | `T68Fixtures.createMixedAnnotationDoc()` | `NotImplementedError: T68` |
| `buildAnnotationDisplayListSuppressesFilteredAnnotations` | `AnnotationFilterTest` | Suppressing all annotations results in an empty annotation display list | `T68Fixtures.createMixedAnnotationDoc()` | `NotImplementedError: T68` |
| `pageContentDisplayListIsUnchangedWhenAnnotationsAreFiltered` | `AnnotationFilterTest` | Page content display list is completely independent and untouched by annotation filter | `T68Fixtures.createMixedAnnotationDoc()` | `NotImplementedError: T68` |
| `filteredDisplayListRetainsApAppearanceWhenVisible` | `AnnotationFilterTest` | Visible annotation with `/AP /N` stream retains its appearance commands | `T68Fixtures.createMixedAnnotationDoc()` | `NotImplementedError: T68` |
| `filteredDisplayListRetainsSyntheticAppearanceWhenVisible` | `AnnotationFilterTest` | Visible annotation without `/AP` retains its synthetic appearance commands | `T68Fixtures.createMixedAnnotationDoc()` | `NotImplementedError: T68` |
| `annotationsSuppressedByPdfFlagsRemainHiddenRegardlessOfFilter` | `AnnotationFilterTest` | Annotation with `HIDDEN` flag is omitted from display list even under `SHOW_ALL` | Fixture with `/F 2` | `NotImplementedError: T68` |
| `annotationDisplayListPreservesZOrderForRemainingAnnotations` | `AnnotationFilterTest` | Remaining visible annotations retain their relative z-order in the display list | `T68Fixtures.createMixedAnnotationDoc()` | `NotImplementedError: T68` |

### 5. App Layers Model Tests (`AnnotationLayersModelTest`)

| Test | Class | What it checks | Fixture | Expected Failure |
| --- | --- | --- | --- | --- |
| `mappingEngineAnnotationTypesToLayerGroups` | `AnnotationLayersModelTest` | Maps `HIGHLIGHT`, `UNDERLINE`, `STRIKEOUT`, `INK`, `TEXT`, `STAMP`, `WIDGET` to respective groups | Pure unit test | `NotImplementedError: T68` |
| `otherGroupIncludesUnsupportedOrUnknownTypes` | `AnnotationLayersModelTest` | Maps unhandled or unknown annotation types to `AnnotationLayerGroup.OTHER` | Pure unit test | `NotImplementedError: T68` |
| `quantizingColorsProducesStableRgbInts` | `AnnotationLayersModelTest` | `quantizeRgb` maps unit float RGB to 24-bit 0xRRGGBB integer | Pure unit test | `NotImplementedError: T68` |
| `showAllEnablesAllGroupsAndColors` | `AnnotationLayersModelTest` | `showAll()` restores all groups to enabled, clears disabled colors, and clears `isFilterActive` | Pure unit test | `NotImplementedError: T68` |
| `hideAllDisablesAllGroupsAndColors` | `AnnotationLayersModelTest` | `hideAll()` sets `enabledGroups` to empty and marks `isFilterActive` as true | Pure unit test | `NotImplementedError: T68` |
| `soloColorEnablesOnlyThatColorInGroupAndHidesOthers` | `AnnotationLayersModelTest` | Solo color enables only that color in the group and hides other filterable groups | Pure unit test | `NotImplementedError: T68` |
| `toggleGroupTogglesGroupVisibility` | `AnnotationLayersModelTest` | Toggling a group flips its presence in `enabledGroups` | Pure unit test | `NotImplementedError: T68` |
| `toggleColorTogglesColorVisibility` | `AnnotationLayersModelTest` | Toggling a color flips its presence in `disabledColors` | Pure unit test | `NotImplementedError: T68` |
| `isFilterActiveIsFalseForShowAllAndTrueWhenAnyFilterIsApplied` | `AnnotationLayersModelTest` | `isFilterActive` is false if and only if semantic state equals `SHOW_ALL` | Pure unit test | `NotImplementedError: T68` |
| `outputModeDefaultsToAllAndSelectsExpectedFilter` | `AnnotationLayersModelTest` | Verifies `AnnotationOutputMode` values (`ALL`, `VISIBLE_ONLY`, `NONE`) | Pure unit test | **PASS** |
| `toEngineFilterProducesExpectedFilterPredicate` | `AnnotationLayersModelTest` | Converts `AnnotationLayersState` into an immutable `AnnotationFilter` | Pure unit test | `NotImplementedError: T68` |

### 6. App Layers Store & Codec Tests (`AnnotationLayerStoreTest`)

| Test | Class | What it checks | Fixture | Expected Failure |
| --- | --- | --- | --- | --- |
| `encodeAndDecodeRoundTrip` | `AnnotationLayerStoreTest` | Valid `AnnotationLayersState` serializes and deserializes identically | Pure unit test | `NotImplementedError: T68` |
| `corruptOrMalformedSavedStateFallsBackToShowAll` | `AnnotationLayerStoreTest` | Corrupt, empty, or null serialized string safely returns `SHOW_ALL` | Pure unit test | `NotImplementedError: T68` |
| `unknownVersionRejectedToShowAll` | `AnnotationLayerStoreTest` | Serialized record with newer/unknown version rejects safely to `SHOW_ALL` | Pure unit test | `NotImplementedError: T68` |
| `privacyPreservedNoAuthorsOrAnnotationTextAreStored` | `AnnotationLayerStoreTest` | Asserts serialized string never stores author names, note contents, or URIs (T30) | Pure unit test | `NotImplementedError: T68` |
| `defaultStateEncodesAndDecodesCleanly` | `AnnotationLayerStoreTest` | Default `SHOW_ALL` encodes and decodes as inactive | Pure unit test | `NotImplementedError: T68` |
| `unknownGroupOrGarbageColorIgnoredGracefully` | `AnnotationLayerStoreTest` | Unknown group tokens or malformed colors in serialized string are skipped gracefully | Pure unit test | `NotImplementedError: T68` |

---

## Open Questions & Notes

1. **Conservative Page Mark Detection**:
   `PdfMarks.applyWatermark` generates marked content tagged `/OC /PDFPaperWatermark BDC ... EMC` in the page content stream. For T68, `annotationFilterReport` inspects page content streams for this marked-content group to report it as non-filterable. Arbitrary page vector artwork or text that resembles highlights is left unflagged to avoid false positives, as specified in `plan.md`.
2. **Color Quantization**:
   Colors from `/C` entries can be represented as DeviceGray (1 number), DeviceRGB (3 numbers), or DeviceCMYK (4 numbers). The engine's `PdfColor` normalizes to RGB. The color quantization quantizes each channel to an 8-bit integer component, yielding a 24-bit integer `0xRRGGBB`. When `/C` is absent, the color key is `null`, which is counted separately from `0x000000` (black).
3. **Output Mode Defaults**:
   When exporting or printing, the prompt defaults to `AnnotationOutputMode.ALL` to preserve backward-compatible default behavior.
