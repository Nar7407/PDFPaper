# T66 review (read-only)

## Scope reviewed

- Plan: [plan.md](C:/Users/hp/Downloads/PDFPaper/docs/tasks/T66/plan.md)
- Claimed status: [status.md](C:/Users/hp/Downloads/PDFPaper/docs/tasks/T66/status.md)
- History requested by task: `git log -5 --stat` and `git diff` on branch `wip/T66`

## 1) Plan + acceptance coverage

### Engine criteria

- Outline resolution (explicit/named/action/indirect), level split, page-range construction, out-of-order/same-page/invalid target handling:
  - Covered by [TocSplitTest](C:/Users/hp/Downloads/PDFPaper/engine/src/test/java/com/pdfpaper/engine/split/TocSplitTest.kt) tests:
    - `destinationTypesExplicitNamedActionAndIndirectAllResolve` (line 302)
    - `outOfOrderEntriesAreSortedByPageAndReported` (line 382)
    - `multipleEntriesOnSamePageReportIssueAndDoNotProduceZeroPageParts` (line 422)
    - `invalidCyclicAndMissingDestinationsAreSkippedAndReported` (line 348)
    - `defaultBoundaryDoesNotDuplicateBoundaryPage` (line 479)
    - `duplicateBoundaryPageOptionIncludesBoundaryInBothParts` (line 496)
    - `lastRangeAlwaysEndsAtDocumentEnd` (line 513)
- Filename sanitation/prefix/duplicates:
  - Covered by [TocSplitTest](C:/Users/hp/Downloads/PDFPaper/engine/src/test/java/com/pdfpaper/engine/split/TocSplitTest.kt) tests at lines 529, 551, 560, 577, 586.
- Preservation (bookmarks, links, page labels, metadata, form fields):
  - Covered by [TocSplitTest](C:/Users/hp/Downloads/PDFPaper/engine/src/test/java/com/pdfpaper/engine/split/TocSplitTest.kt):
    - bookmarks rebased (line 596)
    - same-part links kept/cross-part links dropped (line 621)
    - page labels rebased (line 672)
    - metadata + AcroForm kept (line 709)
- Streaming/cancellation:
  - Covered by [TocSplitTest](C:/Users/hp/Downloads/PDFPaper/engine/src/test/java/com/pdfpaper/engine/split/TocSplitTest.kt):
    - 1,000-page sequential writer (line 751)
    - cancellation between parts (line 790)
- CLI command:
  - Covered by [PdfSplitTocCliTest](C:/Users/hp/Downloads/PDFPaper/engine/src/test/java/com/pdfpaper/engine/tools/PdfSplitTocCliTest.kt) (multiple tests, starting line 129).

### App criteria

- Split-by-chapters UI with level picker, chapter preview, rename/merge/split/uncheck, folder/ZIP export, overwrite prompt, progress/cancel:
  - Unit-level coverage in [TocSplitModelTest](C:/Users/hp/Downloads/PDFPaper/app/src/test/java/com/pdfpaper/app/ui/pages/TocSplitModelTest.kt) and [TocSplitViewModelTest](C:/Users/hp/Downloads/PDFPaper/app/src/test/java/com/pdfpaper/app/ui/pages/TocSplitViewModelTest.kt).
  - Device flow coverage intended in [TocSplitDeviceTest](C:/Users/hp/Downloads/PDFPaper/app/src/androidTest/java/com/pdfpaper/app/ui/pages/TocSplitDeviceTest.kt) lines 138, 216, 266, 301.

### Acceptance criteria missing or not proven

1. **Device acceptance suite is failing (not green).**
   - Evidence: [t66-device.log](C:/Users/hp/Downloads/PDFPaper/t66-device.log) lines 120-170 (4/4 failed).
2. **Manual “opens in two other viewers with working bookmarks” acceptance is not recorded.**
   - Evidence: [status.md](C:/Users/hp/Downloads/PDFPaper/docs/tasks/T66/status.md) lines 100-101.

## 2) Deleted/ignored/weakened tests, stubs/TODOs

- No deleted T66 tests found in T66 commits (`9c81415`, `ecf1c8d`).
- No `TODO`/`FIXME`/`NotImplemented` found in:
  - [TocSplit.kt](C:/Users/hp/Downloads/PDFPaper/engine/src/main/java/com/pdfpaper/engine/split/TocSplit.kt)
  - [PdfSplitTocCli.kt](C:/Users/hp/Downloads/PDFPaper/engine/src/main/java/com/pdfpaper/engine/tools/PdfSplitTocCli.kt)
  - [TocSplitModel.kt](C:/Users/hp/Downloads/PDFPaper/app/src/main/java/com/pdfpaper/app/ui/pages/TocSplitModel.kt)
  - [TocSplitViewModel.kt](C:/Users/hp/Downloads/PDFPaper/app/src/main/java/com/pdfpaper/app/ui/pages/TocSplitViewModel.kt)
  - [TocSplitScreen.kt](C:/Users/hp/Downloads/PDFPaper/app/src/main/java/com/pdfpaper/app/ui/pages/TocSplitScreen.kt)
- **Potential test-harness weakening:** global `unitTests.isReturnDefaultValues = true` was added in [app/build.gradle.kts](C:/Users/hp/Downloads/PDFPaper/app/build.gradle.kts:77). This can mask Android stub behavior in JVM tests.

## 3) Dependencies, forbidden imports/network, secrets/large files

- New dependency footprint for T66 appears limited to built-in test fixtures wiring:
  - `java-test-fixtures` plugin in [engine/build.gradle.kts](C:/Users/hp/Downloads/PDFPaper/engine/build.gradle.kts:6)
  - `testFixtures(project(":engine"))` in [app/build.gradle.kts](C:/Users/hp/Downloads/PDFPaper/app/build.gradle.kts:157)
- No new external network use found in T66 engine/app implementation files.
- No secrets found in reviewed T66 files.
- No large binary files were added by T66 commits (`9c81415`, `ecf1c8d`).

---

## Findings ranked by severity

### High

1. **Required device acceptance is currently failing.**
   - File/line: [t66-device.log](C:/Users/hp/Downloads/PDFPaper/t66-device.log:120), [t66-device.log](C:/Users/hp/Downloads/PDFPaper/t66-device.log:154)
   - Why it matters: task acceptance explicitly requires these end-to-end flows on representative books.

2. **Required cross-viewer acceptance is missing.**
   - File/line: [status.md](C:/Users/hp/Downloads/PDFPaper/docs/tasks/T66/status.md:100)
   - Why it matters: acceptance requires opening outputs in two other viewers and verifying bookmarks/links.

### Medium

3. **CLI auto-falls back to heading detection when outline is empty, instead of only offering fallback.**
   - File/line: [PdfSplitTocCli.kt](C:/Users/hp/Downloads/PDFPaper/engine/src/main/java/com/pdfpaper/engine/tools/PdfSplitTocCli.kt:102)
   - Why it matters: requirement wording says to give a clear no-outline message and offer alternatives; auto-splitting by heuristic headings may surprise users.

4. **Broad exception swallowing in writer hides root cause of split failures.**
   - File/line: [TocSplit.kt](C:/Users/hp/Downloads/PDFPaper/engine/src/main/java/com/pdfpaper/engine/split/TocSplit.kt:312)
   - Why it matters: failures become opaque (“stopped after N parts”) and are harder to diagnose/recover.

5. **Global JVM test setting can weaken Android-related unit test signal.**
   - File/line: [app/build.gradle.kts](C:/Users/hp/Downloads/PDFPaper/app/build.gradle.kts:77)
   - Why it matters: `unitTests.isReturnDefaultValues = true` can hide stub misuse beyond T66 tests.

### Low

6. **Plan vs implementation drift: fixture assets/generator listed in plan are not present.**
   - Planned: [plan.md](C:/Users/hp/Downloads/PDFPaper/docs/tasks/T66/plan.md:100)
   - Actual tests generate fixtures in-memory in [TocSplitDeviceTest.kt](C:/Users/hp/Downloads/PDFPaper/app/src/androidTest/java/com/pdfpaper/app/ui/pages/TocSplitDeviceTest.kt:54)
   - Why it matters: not a correctness defect, but documentation/planning mismatch.
