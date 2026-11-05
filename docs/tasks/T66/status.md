# T66 status — split a document by table of contents

Branch: `wip/T66`. Engine, CLI and app are implemented; the engine and app unit
suites pass. The device class currently fails with a Compose-test environment
error on the headless emulator (details under "Verification").

## What is done

### Engine (plan steps 1–5)

- `engine/.../split/TocSplit.kt` (new): `TocSplitSource`, `TocSplitOptions`
  (level, duplicate boundary page, source), `TocSplitPart`, `TocSplitPlan`,
  the `TocSplitIssue` diagnostics (`NoOutline`, `NoUsableEntries`,
  `InvalidDestination`, `OutOfOrder`, `SamePage`, `EmptyRange`,
  `DroppedExternalLink`, `OutputNameAdjusted`), `TocSplit.planOutline`,
  `TocSplit.planHeadings` (T33 detector, level 1–2 blocks, one chapter per
  page), `TocSplitFileNames` (illegal characters, trailing dots/spaces, blank
  titles, 150-character limit before `.pdf`, `01 - ` prefix whose width follows
  the part count, ` (2)`/` (3)` duplicates, reserved names made harmless by the
  prefix) and `TocSplitWriter` (one part at a time into a sink, progress,
  cancellation between parts, never retains earlier bytes).
- `engine/.../outline/DocumentOutline.kt`: new bounded `diagnose()` traversal
  returning `OutlineDiagnostic(title, pageIndex, depth)` including unresolved
  entries; `read()` is unchanged for T34.
- `engine/.../write/PdfDocumentAssembler.kt`: opt-in `rebasePageLabels` rebuilds
  the `/PageLabels` number tree for the selected pages (`/S`, `/P` kept, `/St`
  moved with the first selected page of the rule), and
  `onDroppedInternalLink(sourcePage, targetPage)` reports internal links whose
  target page is in another part. Existing callers are unchanged.
- `engine/.../model/PdfDocument.kt`: new `trailer()` accessor returning the
  trailer as objects. `TocSplitTest` reads `/Info` through it; the reader itself
  keeps using `file.trailer`.
- `engine/.../tools/PdfSplitTocCli.kt` (new): `split-toc <book.pdf> --level N
  --out-dir <dir> [--duplicate-boundary] [--overwrite]`. Validates level and
  out-dir before opening the document, falls back to detected headings when the
  outline is empty, preflights every target name and refuses existing files
  without `--overwrite`, writes parts sequentially, prints ranges and warnings,
  exit 0/1/2. Registered as `:engine:split-toc` and dispatched by `cli`
  (the `.bat` wrapper still needs the `split-toc` case; see "Left").
- `engine/build.gradle.kts`: the two test fixture files moved to
  `src/testFixtures` (built-in `java-test-fixtures` plugin, nothing downloaded)
  so the app tests can build the same tiny PDFs instead of duplicating them.

### App (plan steps 6–8)

- `app/.../ui/pages/TocSplitModel.kt` (new): `EditableTocSplitPart` and the
  pure preview edits — check/uncheck, rename with renumbered names, merge
  adjacent chapters only, split a chapter at a page inside it, duplicate
  boundary policy, size estimates, conflicts, and the warning wording.
- `app/.../ui/pages/TocSplitViewModel.kt` (new): opens the document read-only
  for the preview and again for each export run, level and heading fallback,
  fixed-N fallback, preview edits, conflict preflight (nothing is written until
  the user confirms Replace), one part at a time progress, cancellation that
  leaves completed files, and `exportToZipSync()` for Share as ZIP.
- `app/.../ui/pages/TocSplitScreen.kt` (new): `TocSplitRoute` + the screen —
  level chips 1–3, shared-boundary switch, warning banner, chapter rows with
  page range and estimate, Rename / Merge forward / Split, folder picker
  (`ACTION_OPEN_DOCUMENT_TREE`), Share as ZIP, progress with Cancel, and the
  no-outline, conflict, rename and split dialogs.
- `app/.../ui/UiTags.kt`, `ui/navigation/AppNav.kt`, `ui/pages/PagesScreen.kt`,
  `ui/tools/ToolRegistry.kt`: tags for the screen and dialogs, the
  `split-toc?path=…` route, the Split tool's Apply now opens the chapter screen
  instead of silently splitting every five pages, and its copy says so.
- `app/build.gradle.kts`: `testImplementation`/`androidTestImplementation` of
  the engine's test fixtures (no new external dependency); the
  `unitTests.isReturnDefaultValues` line was added earlier by the fixture commit.

### Test-harness notes (no test file was edited)

- `TocSplitViewModelTest` constructs `Application()` and calls
  `android.net.Uri.parse(...)`. On the JVM both are `android.jar` stubs: the
  constructor throws without `isReturnDefaultValues`, and `parse` returns null
  with it. The ViewModel therefore takes the destination as `Uri?` and its
  folder factory owns resolution (the default factory refuses a missing tree),
  so the test's `Uri.parse` result can be null without touching the test.
- The engine's `buildTestPdf` fixture is shared through Gradle test fixtures
  rather than copied into the app.

## Verification (real output)

- `./gradlew :engine:test --tests TocSplitTest --tests PdfSplitTocCliTest`
  — 33 tests, 1 failed (CLI ignored `--duplicate-boundary`); fixed, suite green.
- `./gradlew :app:compileDebugKotlin` — `BUILD SUCCESSFUL in 6m 23s`.
- `./gradlew :app:testDebugUnitTest --tests TocSplitModelTest
  --tests TocSplitViewModelTest` — `BUILD SUCCESSFUL in 1m 48s` (25 tests).
- `./gradlew :engine:test :app:testDebugUnitTest` — `BUILD SUCCESSFUL in 1m 12s`.
- `./gradlew :app:connectedDebugAndroidTest -P…class=TocSplitDeviceTest` —
  **FAILED**: all four tests die on their first assertion with
  `IllegalStateException: No compose hierarchies found in the app`, with
  `SurfaceComposerClient: Sync transaction timed out waiting for commit
  callback` in logcat. The AVD was started `-no-window`; the compose window
  never attached, so this is a harness/environment failure, not an assertion.

## Left

- Re-run `TocSplitDeviceTest` on the same AVD with a window (in progress).
- Full `./gradlew build` after the device run.
- Add the missing `split-toc` case to `cli.bat` (the `cli` sh wrapper and the
  Gradle task are done).
- Manual cross-viewer acceptance (two external viewers, bookmarks expanded) is
  not automated and has not been recorded yet.
- App acceptance screenshots are not captured; the device class asserts the
  same flow without them.
