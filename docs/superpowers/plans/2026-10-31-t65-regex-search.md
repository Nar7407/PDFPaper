# Regex Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a reader find text by shape — a pattern over one open document or over the whole local index — with grouped results, exact boxes, export, highlight-all and a hand-off to the redaction panel.

**Architecture:** One pure `java.util.regex` matcher in `:engine` (`RegexSearch`) over a `String`, wrapped by a `PageLayout` overload that attaches `rectsFor` boxes and a `PdfDocument` walk with progress and cancel. The CLI, the app's document scope (`ViewerViewModel`) and the app's library scope (`RegexViewModel`) all call that one matcher, so flags, context, budgets and error wording cannot drift. Catastrophe is bounded three ways: a deadline-checked `CharSequence`, a per-run match cap and a caught `StackOverflowError`.

**Tech Stack:** Kotlin (JVM, no Android types in `:engine`), `java.util.regex`, Jetpack Compose Material 3, Room FTS4 (T50 index), `PdfPageOps.editIncremental` (T55), the T28 redaction panel (hand-off only).

**Spec:** `docs/superpowers/specs/2026-10-31-t65-regex-search.md`

## Global Constraints

- **No new dependency.** The matcher is `java.util.regex`; CSV/TXT writing is hand-written; the UI is existing Compose Material 3.
- `:engine` stays pure Kotlin/JVM: no `android.*`, no `java.awt`, no third-party regex engine.
- **Never write the open file.** Highlight-all writes a new copy built from the bytes the document was opened from; the redaction action hands off to T28's panel, which writes its own copy.
- **Budgets:** per page 1500 ms (`RegexOptions.timeLimitMs`), per run 60 000 ms document / 30 000 ms library (`runTimeLimitMs`), match cap 2000 document / 5000 library (`maxMatches`). A stopped run sets `truncated`/`timedOut`/`cancelled` and says so in the UI.
- **Pages are 1-based in the CLI and in every export, 0-based inside the engine and the app state.**
- **CSV is UTF-8 with a BOM by default**, `--no-bom` on the CLI. RFC 4180 quoting.
- Default flags are `CASE_INSENSITIVE` + `ACROSS_LINES`; `MULTILINE` and `ACROSS_LINES` are **inert in library scope** (the indexed text has no line breaks) and the panel must say so.
- `WHOLE_WORD` is a **neighbour-character filter** (`isLetterOrDigit() || '_'`), never `\b`.
- The engine returns results, never throws, on malformed patterns or unreadable pages (AGENTS rule 5).
- Concurrent work: many unrelated uncommitted files exist in the tree. **Stage only the files listed in each task.**

## Review Focus

These are the input classes the spec implies but no happy-path test names. Each has a test pinned in the task that owns the code.

1. **A pattern matching an empty string** (`a*`, `(?:)`, `^` with `MULTILINE`) — a naive loop never advances. Expect: zero-length matches are skipped and the run terminates (Task 2).
2. **A whole-word match at offset 0 or ending at the last character** — a neighbour check reads `-1` / `length`. Expect: no exception, the match is kept (Task 2).
3. **A match at a page boundary** — text must never be concatenated across pages. Expect: the walk reports each page separately and boxes never span pages (Task 2).
4. **`MULTILINE`/`ACROSS_LINES` in library scope** — flags that cannot work. Expect: the run still returns rows, the panel labels them inert, and boxes only come from the tap recompute (Tasks 4, 5).
5. **A library row whose file changed or vanished since indexing** — the ordinal no longer exists. Expect: the file opens with no boxes and a note, or a clear "cannot open" message; never wrong boxes and never a crash (Task 5).
6. **Highlight-all with unsaved annotations in the viewer** — expect: the copy is built from the opened bytes, the confirmation says unsaved edits are not included, and the open file is byte-identical afterwards (Task 8).

---

### Task 1: Engine regex model and helpers

**Files:**
- Create: `engine/src/main/java/com/pdfpaper/engine/text/RegexModel.kt`
- Test: `engine/src/test/java/com/pdfpaper/engine/text/RegexModelTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces (used by Tasks 2–8):
  - `enum class RegexFlag { CASE_INSENSITIVE, MULTILINE, ACROSS_LINES, WHOLE_WORD }`
  - `data class RegexOptions(val flags: Set<RegexFlag>, val maxMatches: Int, val timeLimitMs: Long, val runTimeLimitMs: Long, val contextRadius: Int)` with `fun isOn(flag: RegexFlag): Boolean` and `companion object { DEFAULT_MAX_MATCHES=2000; DEFAULT_TIME_LIMIT_MS=1500L; DEFAULT_RUN_TIME_LIMIT_MS=60_000L; DEFAULT_CONTEXT_RADIUS=48; val DEFAULT_FLAGS }`
  - `data class RegexPatternError(val message: String, val position: Int)` — `position` is `-1` when the JDK gives none
  - `data class RegexMatch(val pageIndex: Int, val start: Int, val end: Int, val text: String, val before: String, val after: String, val rects: List<TextRect>, val ordinal: Int)` with `val contextText: String get() = before + text + after`
  - `sealed interface RegexResult { val truncated: Boolean; val timedOut: Boolean; val cancelled: Boolean }` with `data class Matches(val matches: List<RegexMatch>, override val truncated: Boolean = false, override val timedOut: Boolean = false, override val cancelled: Boolean = false, val pagesSearched: Int = 0)` and `data class InvalidPattern(val pattern: String, val error: RegexPatternError)` (whose flags are all `false`)
  - `data class RegexExample(val id: String, val label: String, val pattern: String, val sample: String)` and `object RegexExamples { val ALL: List<RegexExample>; fun byId(id: String): RegexExample? }` — exactly six
  - `fun contextAround(text: CharSequence, start: Int, end: Int, radius: Int = RegexOptions.DEFAULT_CONTEXT_RADIUS): Pair<String, String>`
  - `class NormalisedText(val text: String, val offsets: IntArray)` where `offsets` has `text.length + 1` entries and `offsets[i]` is the index in the source of the character that produced normalised index `i` (the last entry is the source length)
  - `fun normaliseWithOffsets(text: String, maxChars: Int = 200_000): NormalisedText`

- [ ] **Step 1: Write the failing test**

`RegexModelTest.kt`:

```kotlin
class RegexModelTest {
    @Test fun defaultFlagsAreCaseInsensitiveAndAcrossLines()
    @Test fun contextClipsWithEllipsisAndCollapsesWhitespace()
    @Test fun contextAtTheEndsOfTheTextHasNoLeadingOrTrailingEllipsis()
    @Test fun contextNeverExceedsRadiusOnEachSide()
    @Test fun sixExamplesHaveUniqueIdsAndCompile()
    @Test fun everyExampleFindsItsSample()
    @Test fun normaliseWithOffsetsMatchesIndexNormalisation()
    @Test fun normaliseWithOffsetsMapsEveryCharacterBack()
    @Test fun normaliseWithOffsetsCapsAtMaxChars()
    @Test fun optionsNormalisedClampsBudgets()
}
```

Pinned assertions:
- `RegexExamples.ALL.size == 6`; ids are exactly `date,email,phone,url,ipv4,code`; every pattern compiles; `date` sample `2026-10-31` matches, `email` sample `reader@example.com`, `phone` sample `+1 555 010 0042`, `url` sample `https://example.com/a`, `ipv4` sample `192.168.0.1`, `code` sample `AB-1234`.
- `contextAround("alpha beta gamma", 6, 10, 4)` == `"… "` to `" …"`-shaped: before ends with `"beta"` clipped to 4 chars and starts with `…`; after starts with `" gam"` clipped. Assert `before.startsWith("…") && before.trimStart('…').endsWith("a")` and `after.endsWith("…")`.
- `contextAround("one two", 0, 3, 10)` == `""` to `" two"` with no ellipsis.
- `normaliseWithOffsets` on `"a \n\t b   c"` gives text `"a b c"` and `NormalisedText.text == "a b c"`; for every `i in text.indices`, `source[offsets[i]]` is that character; `offsets[text.length] == source.length`.
- Agreement: for a list of awkward inputs (`""`, `"   "`, `"a\n\nb"`, `"x".repeat(300)`, a string containing `\u0000`) `normaliseWithOffsets(s).text == IndexTextPolicy.normalize(s)` — import `com.pdfpaper.app...` is impossible from `:engine`, so instead assert against an inline copy of the rule in the test (documented as the T50 rule: trim, collapse whitespace and chars `< 0x20` to one space, cap at 200 000).
- `RegexOptions(flags = emptySet(), maxMatches = 0, timeLimitMs = 0, runTimeLimitMs = 0, contextRadius = -5).normalised()` gives `maxMatches >= 1`, `timeLimitMs >= 1`, `runTimeLimitMs >= 1`, `contextRadius == 0`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :engine:test --tests "*RegexModelTest" --console=plain`
Expected: FAIL — `RegexModel.kt` does not exist (unresolved reference).

- [ ] **Step 3: Implement `RegexModel.kt`**

Rules the body must follow:
- `contextAround` windows `[start - radius, end + radius)`, collapses every whitespace run to one `' '`, prefixes `…` when the window was clipped at the start and suffixes `…` when clipped at the end, and clamps `radius` at 0.
- `normaliseWithOffsets` walks the source once: a whitespace or `ch.code < 0x20` char sets `pendingSpace` (only if output is non-empty); a printable char appends the pending space first, records `offsets += index`, appends the char; stop appending after `maxChars`. Append the source length as the final offset entry and pad any unused tail so `offsets.size == text.length + 1`.
- `RegexExamples` patterns, exactly as the spec fixes them: `\b\d{4}-\d{2}-\d{2}\b`, an e-mail pattern such as `\b[\w.+-]+@[\w-]+\.[\w.-]{2,}\b`, a phone pattern such as `\+?\d[\d\s().-]{6,}\d`, `https?://[^\s<>"]+`, `\b(?:\d{1,3}\.){3}\d{1,3}\b`, `\b[A-Z]{2,3}-\d{4,8}\b`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :engine:test --tests "*RegexModelTest" --console=plain`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add engine/src/main/java/com/pdfpaper/engine/text/RegexModel.kt engine/src/test/java/com/pdfpaper/engine/text/RegexModelTest.kt
git commit -m "feat(engine): regex model, context windows and normalised offset map"
```

---

### Task 2: Engine matcher with deadline, whole-word filter and document walk

**Files:**
- Create: `engine/src/main/java/com/pdfpaper/engine/text/RegexSearch.kt`
- Test: `engine/src/test/java/com/pdfpaper/engine/text/RegexSearchTest.kt`

**Interfaces:**
- Consumes: Task 1's `RegexFlag`, `RegexOptions`, `RegexMatch`, `RegexResult`, `RegexPatternError`, `contextAround`; existing `PageLayout.text`, `PageLayout.lines`, `PageLayout.rectsFor`, `TextLine.start` (internal, same module), `PdfPage.layout()`, `PdfDocument.pages()`.
- Produces (used by Tasks 3–8, 6, 8):
  - `object RegexSearch`
  - `fun find(text: String, options: RegexOptions = RegexOptions()): RegexResult`
  - `fun find(layout: PageLayout, options: RegexOptions = RegexOptions()): RegexResult`
  - `fun PdfDocument.searchRegex(pattern: String, options: RegexOptions = RegexOptions(), pages: List<Int>? = null, onProgress: ((pagesDone: Int, pagesTotal: Int) -> Unit)? = null, isCancelled: () -> Boolean = { false }): RegexResult`

Rules:
- `find(text)` returns page 0 matches with empty `rects`; `find(layout)` fills `rects = layout.rectsFor(start, end)` and page 0.
- Compilation: `Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE` when `CASE_INSENSITIVE`; `Pattern.MULTILINE` when `MULTILINE`. `Pattern.DOTALL` is **never** implied.
- `ACROSS_LINES` off: run once per `layout.lines` entry (document scope only) on `line.text`; offsets are rebased by `line.start`. `find(text)` with `ACROSS_LINES` off runs on the whole string only if it has no `\n`; otherwise per `\n`-split segment (keeps the same observable rule).
- `WHOLE_WORD`: after `Matcher.find`, reject the match when `start > 0 && isWordChar(text[start-1])` or `end < text.length && isWordChar(text[end])`. `isWordChar` is `isLetterOrDigit() || '_'`. Rejected matches do **not** consume the cap.
- Zero-length matches (`start == end`) are skipped; `Matcher.find()` already advances, so the loop cannot spin.
- Deadline: wrap the input in `private class DeadlineText(text, deadlineNanos) : CharSequence` whose `get`/`subSequence` throw `private object SearchDeadlineExceeded : RuntimeException()` once every 1024 reads past `System.nanoTime()` deadline. Use `CharSequence` input for `Pattern.matcher`.
- On `SearchDeadlineExceeded`: return `Matches(found, timedOut = true)`.
- On `StackOverflowError`: return `InvalidPattern(pattern, RegexPatternError("This pattern is too complex to run", -1))`.
- On `PatternSyntaxException`: `InvalidPattern(pattern, RegexPatternError(e.description, e.index))`.
- Match cap and **run** deadline: the document walk computes `runDeadline = now + runTimeLimitMs`, stops before each page when exceeded (`timedOut = true`), passes the min of the per-page deadline and the run deadline into each page, caps at `maxMatches` (`truncated = true`), and checks `isCancelled()` before every page and after every `onProgress` call (`cancelled = true`, keeping what it found).
- `pages` selection: when non-null, visit exactly those indices in the given order, skipping out-of-range ones; `pagesTotal` in `onProgress` is the count actually visited.
- The walk visits pages with `page.layout()` and never concatenates text across pages.

- [ ] **Step 1: Write the failing test**

`RegexSearchTest.kt` with these tests (names pinned):

```kotlin
@Test fun caseInsensitiveByDefaultAndOff()
@Test fun multilineMakesCaretMatchEveryLine()
@Test fun acrossLinesFindsASentenceOverALineBreakWithTwoBoxes()
@Test fun acrossLinesOffFindsOnlyThePerLinePart()
@Test fun wholeWordKeepsARealWordAndRejectsAnEmbeddedOne()
@Test fun wholeWordAtBothEndsOfTheText()
@Test fun contextIsAttachedToEachMatch()
@Test fun zeroLengthMatchesAreSkippedAndTheRunTerminates()
@Test fun matchCapSetsTruncated()
@Test fun perPageDeadlineStopsACatastrophicPattern()
@Test fun runDeadlineStopsALongWalk()
@Test fun nestedQuantifierReportsTooComplexOrTimesOut()
@Test fun invalidPatternCarriesMessageAndPosition()
@Test fun emptyPatternReturnsNoMatches()
@Test fun aPageWithNoTextReturnsNoMatches()
@Test fun boxesOnARotatedPageComeFromTheRenderGeometry()
@Test fun documentWalkReportsPagesAndProgress()
@Test fun documentWalkCanBeCancelledAndKeepsWhatItFound()
@Test fun matchesNeverSpanAPageBoundary()
```

Build pages with `engine/src/test/java/.../TestPdf.kt` (already used by `PdfCommentsTest`). For the boxes test, rotate the page 90° and assert every returned rect lies inside the rotated crop box and that a match on the only line yields exactly one rect.

Pinned assertions:
- `"The AB-1234 invoice"` with `\bAB-\d{4}\b` and default flags → 1 match, `text == "AB-1234"`.
- With `RegexFlag.CASE_INSENSITIVE` removed, `ab-1234` finds nothing.
- `"aaa\nbbb"` with `^bbb` and `MULTILINE` on → 1 match at offset 4; off → 0.
- `"alpha beta\ngamma delta"` with `beta\ngamma` and `ACROSS_LINES` → 1 match, `rects.size == 2`; without `ACROSS_LINES` → 0.
- Whole word: `"cat scatter cat"` with `cat` + `WHOLE_WORD` → 2 matches (offsets 0 and 12); without → 3.
- Whole word at ends: `"cat"` with `cat` + `WHOLE_WORD` → 1 match (guards `-1`/`length`).
- `"cat"` with `a*` → 0 matches and returns within a second.
- `"x".repeat(50_000)` with `x` and `maxMatches = 10` → 10 matches, `truncated`.
- Catastrophic: `"a".repeat(40) + "!"` with `(a+)+$`, `timeLimitMs = 150`, `runTimeLimitMs = 150` → `timedOut == true`, returns in under ~2 s (assert wall clock < 5 000 ms).
- `[A-Z` → `InvalidPattern` with `error.message` containing "Unclosed" and `error.position >= 0`.
- Two pages each containing `XY-1` → 2 matches with `pageIndex` 0 and 1, each with its own rects.
- Progress: a 3-page walk reports `pagesDone` 1..3 in order.
- Cancel after page 1: `isCancelled` true on the second call → `cancelled == true`, `matches.size == 1`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :engine:test --tests "*RegexSearchTest" --console=plain`
Expected: FAIL — `RegexSearch.kt` does not exist.

- [ ] **Step 3: Implement `RegexSearch.kt`**

Implement exactly the rules above. Keep helpers `private`:
`compile(pattern, options, multilineForced: Boolean = false)`, `run(matcher, input, text, bounds, options, deadline)`, `deadlineSequence(text, deadlineNanos)`.

The `ACROSS_LINES`-off branch is the only place `TextLine.start` is read.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :engine:test --tests "*RegexSearchTest" --console=plain`
Expected: PASS (19 tests).

- [ ] **Step 5: Commit**

```bash
git add engine/src/main/java/com/pdfpaper/engine/text/RegexSearch.kt engine/src/test/java/com/pdfpaper/engine/text/RegexSearchTest.kt
git commit -m "feat(engine): deadline-bounded regex matcher with whole-word filter and page walk"
```

---

### Task 3: `search-regex` CLI with CSV export

**Files:**
- Create: `engine/src/main/java/com/pdfpaper/engine/tools/PdfSearchRegexCli.kt`
- Modify: `engine/build.gradle.kts` (add a `search-regex` JavaExec task after `comments`)
- Modify: `cli.bat` (branch + usage line)
- Test: `engine/src/test/java/com/pdfpaper/engine/tools/RegexCliTest.kt`

**Interfaces:**
- Consumes: `RegexSearch`, `RegexOptions`, `RegexFlag`, `RegexResult`, `PdfDocument.open(FileSource)`.
- Produces: the executable `com.pdfpaper.engine.tools.PdfSearchRegexCliKt`; module task `:engine:search-regex`.

CLI contract (from the spec, exact):

```
cli search-regex <in.pdf> "<pattern>" --out matches.csv
      [--case-sensitive] [--multiline] [--lines] [--whole-word]
      [--max N] [--timeout MS] [--run-timeout MS] [--context N]
      [--pages 1,3-5] [--no-bom]
```

- Exit 0 when the search ran (even with zero matches); 1 for a bad PDF, invalid pattern or IO error; 2 for usage (missing file/pattern/`--out`).
- CSV header `file,page,match,context`; `page` is 1-based; `file` is the input file's name; `context` is `match.before + match.text + match.after` with newlines already collapsed; RFC 4180 quoting (wrap in `"` when the field contains `,` `"` `\n` or `\r`, double internal quotes).
- UTF-8 with a BOM (`\uFEFF`) unless `--no-bom`.
- An invalid pattern prints `Invalid pattern: <message> (at <position>)` on stderr and exits 1.
- A timed-out or truncated run prints `Search stopped early: <reason>` on stderr, still writes the rows it found, and exits 0.
- `--lines` clears `ACROSS_LINES`; `--case-sensitive` clears `CASE_INSENSITIVE`; defaults keep both on.
- `--pages` uses the existing one-based range parser shape (`--pages 1,3-5`); out-of-range indices are ignored.

- [ ] **Step 1: Write the failing test**

`RegexCliTest.kt` (7 tests, run the CLI `main` in-process with `kotlin.system.exitProcess` isolated by invoking the private functions through a thin test seam — follow `PdfMarkCliTest`'s existing approach; if that test invokes `main` in a subprocess, do the same):

```kotlin
@Test fun writesHeaderAndOneBasedPages()
@Test fun quotesCommasQuotesAndNewlines()
@Test fun bomByDefaultAndNoBomFlag()
@Test fun pagesSelectionKeepsOnlyTheListedPages()
@Test fun exitCodesForUsageBadFileAndInvalidPattern()
@Test fun invalidPatternPrintsItsPosition()
@Test fun truncatedRunStillWritesRowsAndSaysSo()
```

Pinned assertions: a 2-page PDF with `AB-1234` on page 2 produces one body row whose second field is `2`; a match containing `,` and `"` round-trips through a small reader that splits RFC 4180; the default output's first char is `\uFEFF` and `--no-bom` output's first char is `f`; `--pages 1` on the two-page file yields zero body rows.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :engine:test --tests "*RegexCliTest" --console=plain`
Expected: FAIL — the CLI does not exist.

- [ ] **Step 3: Implement the CLI, the Gradle task and the wrapper branch**

`engine/build.gradle.kts` — mirror the `comments` block exactly:

```kotlin
//   ./gradlew :engine:search-regex --args="search-regex in.pdf \"AB-\d{4}\" --out matches.csv"
tasks.register<JavaExec>("search-regex") {
    group = "application"
    description = "Search a PDF with a regular expression and write the matches as CSV."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pdfpaper.engine.tools.PdfSearchRegexCliKt")
}
```

`cli.bat`: add `if /I "%~1"=="search-regex" goto searchregex` beside the other branches, a `:searchregex` label calling `"%~dp0gradlew.bat" -q :engine:search-regex --args="%*"`, and one line in the usage echo.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :engine:test --tests "*RegexCliTest" --console=plain`
Expected: PASS (7 tests).

- [ ] **Step 5: End-to-end CLI check**

Run:

```bash
./cli search-regex /testdata/text-and-graphics.pdf "select" --out build/t65.csv
```

Expected: exit 0, `build/t65.csv` starts with a BOM and `file,page,match,context`.

- [ ] **Step 6: Commit**

```bash
git add engine/src/main/java/com/pdfpaper/engine/tools/PdfSearchRegexCli.kt engine/build.gradle.kts cli.bat engine/src/test/java/com/pdfpaper/engine/tools/RegexCliTest.kt
git commit -m "feat(engine): search-regex CLI with BOM CSV export"
```

---

### Task 4: App regex model — rows, grouping, unique, TXT/CSV, saved patterns, summaries

**Files:**
- Create: `app/src/main/java/com/pdfpaper/app/ui/search/RegexModel.kt`
- Modify: `app/src/main/java/com/pdfpaper/app/data/AppSettings.kt` (keys `regex.saved`, `regex.flags`)
- Test: `app/src/test/java/com/pdfpaper/app/ui/search/RegexModelTest.kt`

**Interfaces:**
- Consumes: engine `RegexFlag`, `RegexOptions`, `RegexMatch`, `RegexResult`, `NormalisedText`, `normaliseWithOffsets`, `TextRect`, `RegexPatternError`.
- Produces (used by Tasks 5–8):

```kotlin
enum class RegexScope(val label: String) { DOCUMENT("This document"), LIBRARY("Library") }

data class RegexRow(
    val fileId: Long,              // 0 in document scope
    val documentUri: String,       // "" in document scope
    val displayName: String,
    val pageIndex: Int,
    val ordinal: Int,
    val text: String,
    val before: String,
    val after: String,
    val rects: List<TextRect> = emptyList()
)

data class RegexPageGroup(val pageIndex: Int, val rows: List<RegexRow>)
data class RegexGroup(val fileId: Long, val documentUri: String, val displayName: String, val pages: List<RegexPageGroup>)
data class RegexProgress(val filesDone: Int, val filesTotal: Int, val currentName: String)
data class RegexTesterResult(val text: String, val error: RegexPatternError? = null)
data class RegexRedactionHandoff(val pattern: String)

data class RegexPattern(val id: String, val name: String, val pattern: String, val flags: Set<RegexFlag>, val createdAt: Long)

data class RegexUiState(
    val scope: RegexScope,
    val pattern: String = "",
    val flags: Set<RegexFlag> = DEFAULT_REGEX_FLAGS,
    val running: Boolean = false,
    val rows: List<RegexRow> = emptyList(),
    val error: RegexPatternError? = null,
    val truncated: Boolean = false,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val progress: RegexProgress? = null,
    val filesTotal: Int = 0,
    val unsearchable: List<String> = emptyList(),
    val uniqueOnly: Boolean = false,
    val activeIndex: Int = -1,
    val tester: RegexTesterResult? = null,
    val handoff: RegexRedactionHandoff? = null
) {
    val visibleRows: List<RegexRow>
    val activeRow: RegexRow?
    val summary: String
}

val DEFAULT_REGEX_FLAGS: Set<RegexFlag> = setOf(RegexFlag.CASE_INSENSITIVE, RegexFlag.ACROSS_LINES)
const val LIBRARY_MAX_MATCHES = 5000
const val LIBRARY_RUN_TIME_MS = 30_000L
```

Functions (all pure):

```kotlin
fun toggleRegexFlag(flags: Set<RegexFlag>, flag: RegexFlag, on: Boolean): Set<RegexFlag>
fun regexOptions(flags: Set<RegexFlag>, maxMatches: Int, runTimeMs: Long): RegexOptions
fun rowsForPage(scope: RegexScope, fileId: Long, documentUri: String, displayName: String, pageIndex: Int, result: RegexResult): List<RegexRow>
fun groupRows(rows: List<RegexRow>): List<RegexGroup>
fun uniqueRows(rows: List<RegexRow>): List<RegexRow>          // key = (fileId, pageIndex, text)
fun rowsToTxt(rows: List<RegexRow>): String
fun rowsToCsv(rows: List<RegexRow>): String                    // starts with '\uFEFF'
fun regexFileName(scope: RegexScope, baseName: String, csv: Boolean): String
fun describeRegexSummary(matchCount: Int, pageCount: Int, fileCount: Int?, truncated: Boolean, timedOut: Boolean, cancelled: Boolean, unsearchableCount: Int): String
fun describeRegexFlagsNote(scope: RegexScope): String?
fun regexConfirmText(matchCount: Int, truncated: Boolean, timedOut: Boolean, hasUnsavedEdits: Boolean): String
fun boundsOf(rects: List<TextRect>): String?                   // "l,b,r,t" of the union, 3 decimals
fun layoutRangeFor(normalised: NormalisedText, start: Int, end: Int): IntRange
fun rowForOrdinal(result: RegexResult, ordinal: Int): RegexMatch?
fun csvField(value: String): String
```

`RegexPatternCodec` object in the same file: `encode(list: List<RegexPattern>): String`, `decode(raw: String?): List<RegexPattern>`. Record = `id|base64(name)|base64(pattern)|FLAG,FLAG|createdAt`, records joined by `\n`; unknown flags dropped; a record with a wrong field count, bad Base64 or a bad timestamp is dropped silently.

Exact summary strings (tests depend on them):
- No rows: `"No matches"`.
- Document, one page: `"1 match on 1 page"`; `"7 matches on 3 pages"`.
- Library: `"7 matches in 2 files (3 pages)"` (singular `"1 file"`).
- Suffixes appended in this order, separated by `"; "`: `"stopped after the match limit"` (truncated), `"timed out"`, `"cancelled"`, `"3 files could not be searched"` (count > 0, singular `"1 file could not be searched"`).

Exact TXT line: `"page 4: AB-1234 — …invoice AB-1234 dated…"` where the context is `before + text + after`; library rows are prefixed with the file name: `"report.pdf page 4: …"`.

- [ ] **Step 1: Write the failing test**

`RegexModelTest.kt` (18 tests, **synchronous, no coroutines**):

```kotlin
@Test fun flagToggleKeepsOrderIndependence()
@Test fun optionsMapFlagsAndBudgets()
@Test fun rowsForPageCarriesOrdinalAndRects()
@Test fun groupingIsByFileThenPageInReadingOrder()
@Test fun uniqueUsesFilePageTextSoTheSameTextInTwoFilesIsKept()
@Test fun uniqueDropsARepeatedRowInTheSamePage()
@Test fun csvStartsWithBomAndQuotesOnlyWhenNeeded()
@Test fun csvEscapesQuoteCommaAndNewline()
@Test fun txtUsesOneLinePerMatchWithFilePrefixInLibraryScope()
@Test fun summaryForNoMatches()
@Test fun summaryCountsPagesAndFilesWithSingulars()
@Test fun summaryAppendsTruncatedTimedOutCancelledAndUnsearchable()
@Test fun patternCodecRoundTripsNamesWithTabsAndNewlines()
@Test fun patternCodecDropsMalformedRecordsAndUnknownFlags()
@Test fun scopeLabelsAndLibraryFlagNote()
@Test fun confirmTextMentionsUnsavedEditsAndStopping()
@Test fun boundsAreTheUnionWithThreeDecimals()
@Test fun layoutRangeMapsANormalisedRangeBack()
```

Pinned assertions:
- CSV for a row with text `a,b` and context `he said "hi"` yields `"a,b"` and `"he said ""hi"""` quoted; a row without specials is unquoted.
- `uniqueRows` over rows `(file 1, page 2, "AB-1")`, `(file 1, page 2, "AB-1")`, `(file 2, page 2, "AB-1")` keeps 2.
- `describeRegexSummary(0, 0, null, false, false, false, 0) == "No matches"`; `describeRegexSummary(7, 3, 2, false, false, false, 0) == "7 matches in 2 files (3 pages)"`; `describeRegexSummary(7, 3, null, true, true, false, 3) == "7 matches on 3 pages; stopped after the match limit; timed out; 3 files could not be searched"`.
- Codec round-trips a name `"Invoices\ttab\nnewline"` and pattern `"AB-\\d{4}"`.
- `boundsOf(listOf(TextRect(10.0, 20.0, 30.0, 40.0))) == "10.000,20.000,30.000,40.000"`.
- `layoutRangeFor` on `normaliseWithOffsets("alpha\nbeta")` for the normalised range of `"beta"` returns the source range of `"beta"`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*RegexModelTest" --console=plain`
Expected: FAIL — `RegexModel.kt` does not exist.

- [ ] **Step 3: Implement `RegexModel.kt` and the two `AppSettings` keys**

`AppSettings` additions (same style as the snip block):

```kotlin
fun savedRegexPatternsRaw(context: Context): String? = prefs(context).getString(KEY_REGEX_SAVED, null)
fun setSavedRegexPatternsRaw(context: Context, raw: String?) { /* put or remove */ }
fun regexFlags(context: Context): Set<RegexFlag>   // comma-joined names; unknown dropped; empty → DEFAULT_REGEX_FLAGS
fun setRegexFlags(context: Context, flags: Set<RegexFlag>)
```

with `private const val KEY_REGEX_SAVED = "regex.saved"` and `private const val KEY_REGEX_FLAGS = "regex.flags"`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*RegexModelTest" --console=plain`
Expected: PASS (18 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pdfpaper/app/ui/search/RegexModel.kt app/src/main/java/com/pdfpaper/app/data/AppSettings.kt app/src/test/java/com/pdfpaper/app/ui/search/RegexModelTest.kt
git commit -m "feat(app): pure regex rows, grouping, exports and saved-pattern codec"
```

---

### Task 5: Library scope — index text query, repository and `RegexViewModel`

**Files:**
- Modify: `app/src/main/java/com/pdfpaper/app/index/SearchIndexDao.kt`
- Modify: `app/src/main/java/com/pdfpaper/app/index/IndexRepository.kt`
- Create: `app/src/main/java/com/pdfpaper/app/ui/search/RegexViewModel.kt`
- Test: `app/src/test/java/com/pdfpaper/app/ui/search/RegexLibraryModelTest.kt`

**Interfaces:**
- Consumes: Task 4's model, `IndexRepository`, `RegexSearch.find(text)`, `normaliseWithOffsets`, `PdfDocument` + `PageLayout` for the tap recompute, `DocumentAccess` for opening a `content://` path.
- Produces (used by Tasks 7 and 8):

```kotlin
data class PageTextRow(val fileId: Long, val pageIndex: Int, val text: String)

// SearchIndexDao
@Query("SELECT m.fileId AS fileId, m.pageIndex AS pageIndex, p.text AS text " +
       "FROM page_meta m JOIN page_text p ON p.rowid = m.rowId " +
       "WHERE m.fileId = :fileId ORDER BY m.pageIndex ASC")
fun pageTexts(fileId: Long): List<PageTextRow>

// IndexRepository
suspend fun pageTexts(fileId: Long): List<PageTextRow>
suspend fun indexedFiles(filters: SearchFilters): List<FileSearchRow>
suspend fun unsearchableFiles(filters: SearchFilters): List<FileSearchRow>

// RegexViewModel
class RegexViewModel(application: Application) : AndroidViewModel(application) {
    val state: StateFlow<RegexUiState>                 // scope = LIBRARY
    fun setPattern(pattern: String)
    fun setFlags(flags: Set<RegexFlag>)
    fun setUniqueOnly(unique: Boolean)
    fun run(filters: SearchFilters)
    fun cancel()
    fun clearResults()
    fun runTester(layoutText: String? = null)           // first indexed file, first page
    fun setActive(index: Int)
    fun savedPatterns(): List<RegexPattern>             // exposed via state
    fun savePattern(name: String)
    fun applySaved(id: String)
    fun renameSaved(id: String, name: String)
    fun deleteSaved(id: String)
    fun exportPayload(csv: Boolean): RegexExportPayload?
    fun openRow(row: RegexRow, onReady: (path: String, pageIndex: Int, query: String, bounds: String?, note: String?) -> Unit)
}

data class RegexExportPayload(val fileName: String, val text: String, val mimeType: String)
```

`indexedFiles(filters)` returns rows whose `status == INDEXED` and that have at least one page with text (`pageCount - pagesWithoutText > 0`), honouring `filters.folderId` and `filters.date` (reuse the existing private `cutoffFor`). `unsearchableFiles(filters)` returns `LOCKED`/`NEEDS_OCR`/`ERROR`/`TOO_LARGE` rows under the same folder/date filter.

`run(filters)`:
- clears rows, sets `running`, reads both file lists, sets `filesTotal` and `unsearchable`.
- for each searchable file: `pageTexts(id)`, then `RegexSearch.find(storedText, regexOptions(flags, LIBRARY_MAX_MATCHES - rows.size, LIBRARY_RUN_TIME_MS))`, converts with `rowsForPage(LIBRARY, ...)`; stops on cap/timeout/cancel.
- progress via `_state.update { copy(progress = RegexProgress(done, total, name)) }` before each file and after each file.
- ends with `running = false` and the engine flags copied into state.

`openRow(row, onReady)`:
- opens the document through `DocumentAccess.openDocument(application, row.documentUri)` (`Result<PdfDocument>`); on failure calls `onReady(row.documentUri, row.pageIndex, row.text, null, "This file cannot be opened any more.")`.
- lays out `row.pageIndex`, computes `normaliseWithOffsets(layout.text)`, runs the pattern on the **normalised** text with library options, takes `rowForOrdinal(result, row.ordinal)`; on a miss calls `onReady(..., null, "The file changed since it was indexed; no highlight boxes.")`.
- on a hit maps through `layoutRangeFor`, computes `boundsOf(layout.rectsFor(range.first, range.last + 1))` and calls `onReady(uri, page, match.text, bounds, null)`.

- [ ] **Step 1: Write the failing test**

`RegexLibraryModelTest.kt` (pure helpers only — no Room, no coroutines; the DAO is covered by the device test in Task 9):

```kotlin
@Test fun optionsForLibraryUseTheLibraryBudget()
@Test fun rowsFromStoredTextHaveNoRectsAndKeepOrdinals()
@Test fun ordinalSelectionPicksTheNthMatch()
@Test fun ordinalBeyondTheListReturnsNull()
@Test fun normalisedRangeMapsAcrossAWrappedLine()
@Test fun layoutRangeNeverEscapesTheText()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*RegexLibraryModelTest" --console=plain`
Expected: FAIL — `pageTexts`/helpers missing.

- [ ] **Step 3: Add the DAO query and the repository methods, then implement `RegexViewModel`**

Query shape and repository code as in **Interfaces**. Keep the DAO query plain (no `MATCH`): it is a direct row read, so FTS syntax never applies.

`RegexViewModel` runs work on `viewModelScope.launch { withContext(Dispatchers.Default) { ... } }`, checks `isActive` between pages, and never touches the main thread for layout. Saved patterns are read once in `init` from `AppSettings.savedRegexPatternsRaw` through `RegexPatternCodec`, and every mutation writes back.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*RegexLibraryModelTest" --console=plain`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pdfpaper/app/index/SearchIndexDao.kt app/src/main/java/com/pdfpaper/app/index/IndexRepository.kt app/src/main/java/com/pdfpaper/app/ui/search/RegexViewModel.kt app/src/test/java/com/pdfpaper/app/ui/search/RegexLibraryModelTest.kt
git commit -m "feat(app): library regex run over stored page text with ordinal tap mapping"
```

---

### Task 6: Document scope in `ViewerViewModel`

**Files:**
- Modify: `app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerViewModel.kt`
- Test: `app/src/test/java/com/pdfpaper/app/ui/viewer/ViewerRegexModelTest.kt`

**Interfaces:**
- Consumes: Task 2's `PdfDocument.searchRegex`, Task 4's model, existing `pages`, `layoutFor`, `textScope`, `documentDispatcher`, `currentPath`, `currentDisplayName`.
- Produces (used by Tasks 7 and 8):

```kotlin
val regex: StateFlow<RegexUiState>                       // scope = DOCUMENT
fun setRegexPattern(pattern: String)
fun setRegexFlags(flags: Set<RegexFlag>)                 // persists via AppSettings.setRegexFlags
fun runDocumentRegex(pattern: String, flags: Set<RegexFlag>)
fun cancelDocumentRegex()
fun regexNext()
fun regexPrevious()
fun setRegexActive(index: Int)
fun regexTester(pattern: String, flags: Set<RegexFlag>)  // current visible page only
fun clearRegex()
```

Behaviour:
- `runDocumentRegex` cancels any earlier job, clears previous matches, uses `RegexOptions(flags, 2000, 1500, 60_000, 48)`, calls `searchRegex` with `isCancelled = { !isActive }`, and maps `RegexResult.Matches` into rows via `rowsForPage(DOCUMENT, 0, "", displayName, pageIndex, result)`; `InvalidPattern` sets `state.error`.
- Progress is reported as `RegexProgress(pagesDone, pagesTotal, "")` — the panel shows "page N of M".
- `regexNext`/`regexPrevious` step `activeIndex` over `visibleRows` with wrap-around, and the screen scrolls to `activeRow.pageIndex`.
- `regexTester` lays out the page under `listState`'s last visible index (`currentVisiblePage` — reuse the existing `lastVisiblePage` variable if present, otherwise keep a `@Volatile var visiblePage` updated by `onPageVisible`), runs the matcher on that one page with `maxMatches = 50` and a 300 ms budget, and publishes `RegexTesterResult("3 matches on page 4 · 1.2 s")` or `RegexTesterResult("", error)`.
- `clearRegex()` resets to `RegexUiState(DOCUMENT)`.

- [ ] **Step 1: Write the failing test**

`ViewerRegexModelTest.kt` — pure helpers extracted into `RegexModel.kt` in this task (so the VM body stays thin and testable without coroutines):

```kotlin
@Test fun stepIndexWrapsForwardAndBackward()
@Test fun stepIndexOnAnEmptyListStaysMinusOne()
@Test fun visibleRowsRespectUniqueOnly()
@Test fun testerTextCountsMatchesAndTime()
@Test fun documentOptionsUseTheDocumentBudget()
```

Add to `RegexModel.kt`:

```kotlin
fun stepRegexIndex(current: Int, size: Int, delta: Int): Int
fun describeRegexTester(matchCount: Int, pageIndex: Int, elapsedMs: Long): String
```

Pinned: `stepRegexIndex(-1, 3, 1) == 0`, `stepRegexIndex(2, 3, 1) == 0`, `stepRegexIndex(0, 3, -1) == 2`, `stepRegexIndex(0, 0, 1) == -1`; `describeRegexTester(3, 3, 1234) == "3 matches on page 4 · 1.2 s"`, `describeRegexTester(1, 0, 300) == "1 match on page 1 · 0.3 s"`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*ViewerRegexModelTest" --console=plain`
Expected: FAIL.

- [ ] **Step 3: Implement the helpers and the viewer state**

Add the helpers to `RegexModel.kt`; add `_regex`/`regex` and the functions above to `ViewerViewModel`, next to the T11 search block. Reuse `MAX_MATCHES` conventions but a separate job (`regexJob`), so a literal search and a regex run cannot cancel each other.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*ViewerRegexModelTest" --console=plain`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerViewModel.kt app/src/main/java/com/pdfpaper/app/ui/search/RegexModel.kt app/src/test/java/com/pdfpaper/app/ui/viewer/ViewerRegexModelTest.kt
git commit -m "feat(app): document-scope regex run in the viewer state"
```

---

### Task 7: Regex panel and results UI, wired into the Search tab and the viewer

**Files:**
- Create: `app/src/main/java/com/pdfpaper/app/ui/search/RegexPanel.kt`
- Create: `app/src/main/java/com/pdfpaper/app/ui/search/RegexResults.kt`
- Modify: `app/src/main/java/com/pdfpaper/app/ui/search/SearchScreen.kt`
- Modify: `app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerScreen.kt`
- Modify: `app/src/main/java/com/pdfpaper/app/ui/UiTags.kt`
- Test: `app/src/androidTest/java/com/pdfpaper/app/ui/search/RegexPanelDeviceTest.kt`

**Interfaces:**
- Consumes: Tasks 4–6 (`RegexUiState`, `RegexScope`, `RegexExamples`, `RegexPattern`, `RegexRow`, `groupRows`, `RegexViewModel`, `ViewerViewModel.regex`), `UiTags`.
- Produces: two stateless composables used by both scopes:

```kotlin
@Composable
fun RegexPanel(
    state: RegexUiState,
    saved: List<RegexPattern>,
    onPatternChange: (String) -> Unit,
    onFlagsChange: (Set<RegexFlag>) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onExample: (RegexExample) -> Unit,
    onSavePattern: (String) -> Unit,
    onApplySaved: (String) -> Unit,
    onDeleteSaved: (String) -> Unit,
    modifier: Modifier = Modifier
)

@Composable
fun RegexResults(
    state: RegexUiState,
    onActive: (Int) -> Unit,
    onRow: (RegexRow) -> Unit,
    onUniqueChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    onHighlightAll: (() -> Unit)?,   // null in library scope
    onRedactAll: (() -> Unit)?,      // null in library scope
    modifier: Modifier = Modifier
)

const val LIBRARY_FLAGS_NOTE = "The index stores text without line breaks, so Multiline and Across lines do nothing here. Boxes appear when a file is opened."
```

UI requirements:
- Panel: scope label chip (`state.scope.label`), pattern field with a monospace hint, four `FilterChip`s (Case, Multiline, Across lines, Whole word), Run/Cancel button, a caret line under the field when `state.error != null` (spaces up to `position`, then `^`), tester line, a horizontally scrollable row of the six example chips, and a saved-pattern list with Save/Rename/Delete behind a small dialog.
- Results: summary line, "Unique matches only" switch, grouped list (`groupRows(state.visibleRows)`), each row showing `page N` + matched text bold + context dimmed, a next/previous pair in document scope, and Export / Highlight all / Mark all as redactions actions in a bottom row. Empty states: "No pattern yet", "No matches", "The library has no indexed files".
- Library scope shows `LIBRARY_FLAGS_NOTE` under the chips.
- Search tab: a `FilterChip` "Regex" beside the search field (`UiTags.REGEX_TOGGLE`). When on, the FTS list is hidden and the panel + results are shown; the same folder/date chips stay, and Run passes `state.filters`. Export uses `RegexViewModel.exportPayload` + `CreateDocument("text/csv"/"text/plain")` and a share button through `FileProvider` (copy the `AnnotationsViewModel.shareExport` pattern).
- Viewer: add a "Regex" `TextButton` to `SearchRow` (`UiTags.REGEX_VIEWER_OPEN`) that opens a `ModalBottomSheet` containing `RegexPanel` + `RegexResults` bound to `viewModel.regex` (`UiTags.REGEX_SHEET`). In document scope, tapping a row sets `focusMark = pageIndex to union rect` (reuse T56's transient outline) and scrolls to the page; `onExport` builds bytes from `rowsToCsv`/`rowsToTxt` and writes through `createDocument`.
- New tags: `REGEX_TOGGLE`, `REGEX_PATTERN_FIELD`, `REGEX_RUN`, `REGEX_CANCEL`, `REGEX_EXAMPLES`, `REGEX_RESULTS`, `REGEX_SUMMARY`, `REGEX_UNIQUE`, `REGEX_EXPORT`, `REGEX_HIGHLIGHT_ALL`, `REGEX_REDACT_ALL`, `REGEX_VIEWER_OPEN`, `REGEX_SHEET`, `REGEX_CARET`, `REGEX_SAVED`.

- [ ] **Step 1: Write the failing device test**

`RegexPanelDeviceTest.kt` (2 tests, `createComposeRule`, driven through `SearchScreen` so the real panel is built):

```kotlin
@Test fun panelShowsExamplesFlagsTesterAndErrorCaret()
@Test fun libraryScopeShowsTheInertFlagsNoteAndEmptyState()
```

Assertions: the six example chips exist by label; toggling "Whole word" leaves the chip selected; typing `[A-Z` and pressing Run shows a caret under the bracket (the `REGEX_CARET` node exists and its text contains `^`); with no indexed files the empty state text appears and the inert-flags note is visible.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.pdfpaper.app.ui.search.RegexPanelDeviceTest`
Expected: FAIL — the panel does not exist.

- [ ] **Step 3: Implement the composables and the wiring**

Follow the existing sheet/list style (`SearchScreen` result cards, `SnipOverlay`'s chip rows). Never compute match work inside composition: all runs go through a view model function.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.pdfpaper.app.ui.search.RegexPanelDeviceTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pdfpaper/app/ui/search/RegexPanel.kt app/src/main/java/com/pdfpaper/app/ui/search/RegexResults.kt app/src/main/java/com/pdfpaper/app/ui/search/SearchScreen.kt app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerScreen.kt app/src/main/java/com/pdfpaper/app/ui/UiTags.kt app/src/androidTest/java/com/pdfpaper/app/ui/search/RegexPanelDeviceTest.kt
git commit -m "feat(app): regex panel and grouped results in the search tab and viewer"
```

---

### Task 8: Highlight-all copy and the redaction hand-off

**Files:**
- Create: `app/src/main/java/com/pdfpaper/app/ui/search/RegexActions.kt`
- Modify: `app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerViewModel.kt`
- Modify: `app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerScreen.kt`
- Modify: `app/src/main/java/com/pdfpaper/app/ui/redact/RedactionSheet.kt` (accept a prefilled state; no behaviour change to its own search)
- Test: `app/src/test/java/com/pdfpaper/app/ui/search/RegexActionsTest.kt`
- Test: `app/src/androidTest/java/com/pdfpaper/app/RegexWriteDeviceTest.kt`

**Interfaces:**
- Consumes: Task 4/6, `AnnotationWriter.HighlightSpec`, `PdfPageOps.applyAnnotationsIncremental`/`applyAnnotations`, `ViewerExport`, `RedactionFindMode.REGEX`, `RedactionFindUi`.
- Produces:

```kotlin
// RegexActions.kt
object RegexActions {
    fun highlightsFor(rows: List<RegexRow>, author: String, maxHighlights: Int = 2000): List<HighlightSpec>
    fun skippedWithoutBoxes(rows: List<RegexRow>): Int
}

// ViewerViewModel
fun highlightRegexMatches()
fun handOffRegexToRedactions()

// ViewerExportKind
HIGHLIGHTS
```

Rules:
- `highlightsFor` emits one `HighlightSpec(pageIndex = row.pageIndex, rects = row.rects.map { PdfRect(it.x0, it.y0, it.x1, it.y1) }, contents = row.text, title = author.ifBlank { "PDFPaper regex" })` per row **that has rects**, capped at `maxHighlights`; `skippedWithoutBoxes` counts the rest.
- `highlightRegexMatches()`:
  - refuses when `_regex.value.rows.none { it.rects.isNotEmpty() }` with message "No matches with boxes on this document".
  - reads the opened bytes (`originalBytes` the viewer already keeps for T55), applies annotations incrementally inside `runCatching`, falls back to `applyAnnotations` when the incremental form fails (no `/startxref`).
  - sets `_export.value = ViewerExport(bytes, "<base>-highlighted.pdf", ViewerExportKind.HIGHLIGHTS, originalBytes = openedBytes)` and sets `_regex` `handoff`/message with `regexConfirmText(...)` including the unsaved-edits line when `_edit.value.hasPendingChanges`.
- `handOffRegexToRedactions()`: sets `_redaction` to `active = true`, `find = RedactionFindUi(mode = RedactionFindMode.REGEX, query = pattern)` and clears the regex sheet through a `handoff` value the screen consumes. It must **not** run the search itself.
- `ViewerScreen`: `LaunchedEffect(export)` treats `ViewerExportKind.HIGHLIGHTS` as copy-only (`createDocument.launch`, never `saveChoice`); `completeExport` writes the bytes, shows `"Saved a copy with N highlights"` and does **not** call `reloadSaved`.
- `RedactionSheet` gains no new parameter: the VM's `_redaction` state already drives it, so the prefill is a state write. If the sheet's query field is a local `remember`, switch it to be seeded from `state.find.query` on open (one-line change, no new parameter).

- [ ] **Step 1: Write the failing tests**

`RegexActionsTest.kt` (pure, 4 tests):

```kotlin
@Test fun oneSpecPerRowWithBoxes()
@Test fun rowsWithoutBoxesAreCountedAndSkipped()
@Test fun capDropsTheTail()
@Test fun contentsAndTitleAreCarried()
```

Pinned: two rows with 2 and 1 rects produce 2 specs with `rects.size` 2 and 1; a row with no rects produces no spec and `skippedWithoutBoxes == 1`.

`RegexWriteDeviceTest.kt` (2 tests):

```kotlin
@Test fun highlightAllWritesACopyAndLeavesTheOriginalByteIdentical()
@Test fun redactionHandoffPrefillsThePanelAndItsVerifyRemovesTheText()
```

- Build a 3-page PDF with `AB-1234` twice and `XY-9` once through `TestPdf`/`PdfBuilder`, open it in the app, run the document regex `[A-Z]{2}-\d{1,4}`, press Highlight all, choose a destination through a `FileProvider`-backed `CreateDocument` result, then reopen the copy with the engine and assert 3 `/Highlight` annotations while the original file's SHA-256 is unchanged.
- For the hand-off: press "Mark all as redactions", assert the redaction panel is open with the pattern prefilled and `Custom regex` selected, run its Find, then its apply + verify and assert the copy's extracted text no longer contains `AB-1234` while the original is unchanged.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*RegexActionsTest"` → FAIL.
Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.pdfpaper.app.RegexWriteDeviceTest` → FAIL.

- [ ] **Step 3: Implement `RegexActions`, the viewer functions and the copy-only export path**

Keep the write off the main thread (`documentDispatcher`), and only set `_export` after the engine produced bytes.

- [ ] **Step 4: Run tests to verify they pass**

Run both commands from Step 2. Expected: PASS (4 unit, 2 device).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pdfpaper/app/ui/search/RegexActions.kt app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerViewModel.kt app/src/main/java/com/pdfpaper/app/ui/viewer/ViewerScreen.kt app/src/main/java/com/pdfpaper/app/ui/redact/RedactionSheet.kt app/src/test/java/com/pdfpaper/app/ui/search/RegexActionsTest.kt app/src/androidTest/java/com/pdfpaper/app/RegexWriteDeviceTest.kt
git commit -m "feat(app): highlight-all copy and redaction hand-off for regex results"
```

---

### Task 9: Acceptance documents, device coverage, screenshots and AGENTS.md

**Files:**
- Create: `app/tools/t65_acceptance_pdfs.py`
- Create: `app/src/androidTest/java/com/pdfpaper/app/RegexSearchDeviceTest.kt`
- Create: `app/src/androidTest/java/com/pdfpaper/app/RegexLibraryDeviceTest.kt`
- Modify: `AGENTS.md` (status line, counts, limitations, milestone entry)
- Create: `screenshots/t65-panel.png`, `t65-results.png`, `t65-error.png`, `t65-export.png`

**Interfaces:**
- Consumes: everything above.
- Produces: the acceptance evidence and the recorded limitations.

- [ ] **Step 1: Write the acceptance PDF generator**

`app/tools/t65_acceptance_pdfs.py` (dev-only, `reportlab` if available, else hand-written PDF bytes like the other `app/tools` scripts) writes into `build/t65-acceptance/`:
- `t65-invoice.pdf` — 4 pages carrying `AB-1234`, `INV-00042`, `2026-10-31`, `reader@example.com`, `https://example.com/inv/9`, `192.168.0.1`, `+1 555 010 0042`, spread so each example hits 2–3 pages.
- `t65-report.pdf` — one sentence split across two lines (`…the total due is` / `142.50 EUR…`).

- [ ] **Step 2: Write the device tests**

`RegexSearchDeviceTest` (3):
```kotlin
@Test fun documentRunFindsTheRightPagesAndBoxes()
@Test fun tappingARowOpensThatPageWithTheOutline()
@Test fun nextAndPreviousWalkTheFlatList()
```
`RegexLibraryDeviceTest` (3):
```kotlin
@Test fun fiftyFilesShowProgressAndFinish()
@Test fun cancelStopsWithinAPageAndKeepsWhatWasFound()
@Test fun tappingAResultRecomputesBoxesAcrossAWrappedLine()
```
Each generates its own files under the app cache, indexes them through `IndexRepository` (as `SearchIndexDeviceTest` does), and asserts on real state, never on a screenshot.

- [ ] **Step 3: Run the device tests**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.pdfpaper.app.RegexSearchDeviceTest,com.pdfpaper.app.RegexLibraryDeviceTest
```

Expected: PASS (6 tests). If the emulator is unavailable, record that and do not claim a pass.

- [ ] **Step 4: Run the acceptance checklist on the emulator and capture screenshots**

1. Each of the six examples on `t65-invoice.pdf` finds exactly the expected pages and boxes.
2. `ACROSS_LINES` finds the `t65-report.pdf` sentence with two boxes; `--lines` does not (verify both through the CLI too).
3. `(a+)+$` over a long run of `a`s is cancelled within the limit and the app stays responsive; the CLI prints the early-stop line.
4. `[A-Z` reports "Unclosed character class" with the caret under the bracket.
5. The CLI CSV opens in Python's `csv` module with the right columns and no mojibake.
6. A 50-file library run shows "12 of 50 files", cancels cleanly, and names an unsearchable file.
7. Highlight all → a copy with N `/Highlight` annotations; the original is byte-identical (`Get-FileHash` before/after).
8. Mark all as redactions → the panel opens prefilled; after its verify the text is gone from the copy and the original is unchanged.
9. Save the four screenshots.

- [ ] **Step 5: Run the full verification**

```bash
./gradlew build --console=plain
./gradlew :engine:test --console=plain
./gradlew :app:testDebugUnitTest --console=plain
```

Record the real counts and paste the real output, including the new engine tests (10 + 19 + 7), app unit tests (18 + 6 + 5 + 4) and device tests (2 + 2 + 3 + 3).

- [ ] **Step 6: Update `AGENTS.md`**

- New "Last session" entry at the top of *Current status* with the behaviour, budgets, files and counts.
- `T65` added to *Done* and a milestone checklist line.
- Decisions: one matcher/approach A; one scope per entry point; library normalised text + ordinal; redaction hand-off; whole-word filter; per-run budgets; copy from opened bytes; unique key.
- Limitations: `java.util.regex` only (no `\K`, no variable-length lookbehind, no recursion); reading-order text inherits T11 (hyphenation, ligatures, OCR needed, Form XObject text not searched); cooperative deadline; library text is normalised and `MULTILINE`/`ACROSS_LINES` are inert there; boxes are interpolated; saved patterns are app-local; CSV BOM; 1-based pages.
- Build-test counts updated from the real run.

- [ ] **Step 7: Commit**

```bash
git add app/tools/t65_acceptance_pdfs.py app/src/androidTest/java/com/pdfpaper/app/RegexSearchDeviceTest.kt app/src/androidTest/java/com/pdfpaper/app/RegexLibraryDeviceTest.kt AGENTS.md screenshots/t65-panel.png screenshots/t65-results.png screenshots/t65-error.png screenshots/t65-export.png
git commit -m "test(app): regex search and library acceptance, screenshots and docs"
```

---

## Self-Review

**1. Spec coverage**

| Spec section | Task |
| --- | --- |
| `RegexModel` (flags, options, match, result, examples, context, offsets) | 1 |
| `RegexSearch` (compile, deadline, whole word, page/document, progress/cancel) | 2 |
| CLI + CSV + exit codes + Gradle/wrapper | 3 |
| App model, grouping, unique, TXT/CSV, saved patterns, summaries | 4 |
| `pageTextsForFile` DAO, repository, library run, ordinal tap | 5 |
| Document scope in `ViewerViewModel` | 6 |
| `RegexPanel`, `RegexResults`, Search tab, viewer sheet, tags | 7 |
| Highlight all + redaction hand-off + copy-only export | 8 |
| Acceptance PDFs, device tests, screenshots, limitations | 9 |
| Tester (300 ms, current page / first indexed file) | 6 (`regexTester`) + 7 (display) |
| Empty states | 7 |
| Budgets (1500 ms/page, 60 s, 30 s, 2000/5000) | 1, 2, 5, 6 |
| CSV BOM / `--no-bom` | 3, 4 |
| 1-based CLI + exports, 0-based engine | 1–5 |

Gaps found and closed in the plan: `cancelled` is a distinct result flag (the spec only named `truncated`/`timedOut`) because the library Cancel must not be reported as a cap; `RegexExportPayload` and `RegexViewModel.openRow` are added because decision 2 forbids a pattern-carrying route, so the tap recompute happens in the library view model and travels as an existing `hl` bound.

**2. Step scan:** every step names a file, a signature or a command with its expected result. No "handle edge cases" lines. The only prose-only steps are implementation rules that the tests do not pin (deadline wrapper shape, progress cadence), which is the intended level.

**3. Type consistency:** `RegexUiState` fields used in Tasks 5–8 (`rows`, `visibleRows`, `activeRow`, `progress`, `unsearchable`, `handoff`, `tester`) are all declared in Task 4. `RegexRow` is the single row type for both scopes. `RegexResult.Matches.cancelled` is declared in Task 1 and consumed in 2, 5, 6. `ViewerExportKind.HIGHLIGHTS` is introduced in Task 8 and consumed only there. `boundsOf`/`layoutRangeFor`/`rowForOrdinal` are declared in Task 4 and used in Task 5. `stepRegexIndex`/`describeRegexTester` are declared in Task 6 and used there.

**4. Review Focus:** each of the six lines has a test — zero-length (Task 2), whole-word at both ends (Task 2), page boundary (Task 2), inert library flags (Tasks 4 + 7), changed/missing library file (Task 5 `openRow` miss path, device test in Task 9), unsaved edits on copy (Task 4 `regexConfirmText`, Task 8 device test).

**5. Proportion:** the plan is longer than the spec but carries no implementation bodies, only signatures, exact strings and test names.
