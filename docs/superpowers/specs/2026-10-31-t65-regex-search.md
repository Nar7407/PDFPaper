# T65 — Regex search (engine + app)

**Status:** design for approval
**Depends on:** T11 (reading-order text + `rectsFor`), T12 (highlight annotations), T28 (redaction, its find panel and `verify`), T38b (reading session), T50 (local index), T55 (incremental save), T56 (viewer jump with `highlightBounds`)
**Extends:** T11 (literal page search), T50 (FTS index search)

## Goal

A reader who knows what the thing *looks like* rather than what it says can find it:
every invoice number of the form `AB-1234`, every date, every e-mail address. They
type a regular expression (or pick one of six examples), choose four flags, see the
matches grouped by page with the text around them, tap one to land on it in the
viewer with its boxes highlighted, step through them with next/previous, and export
the list as `.txt` or `.csv`. Two quick actions turn a result set into PDF output:
highlight every match, or send every match to the existing redaction panel.

The same pattern can run over the whole local library — the page text the T50 index
already holds — file by file, in the background, with progress and Cancel.

Everything stays on the device. No new dependency.

## Non-goals

- **No replacement.** T11's `PageLayout.search` (literal, per line, case-insensitive)
  and T50's FTS query keep their exact behaviour. The FTS box and the regex box are
  two modes of the same search screen, not one merged syntax.
- **No "replace" or "replace all".** Rewriting text is a different, much more
  dangerous feature than finding it.
- **No regex over comments, bookmarks, form fields, annotations or pictures.** The
  input is the page's extracted reading-order text (which includes an OCR layer when
  one exists), exactly what T11 searches.
- **No second redaction engine.** "Mark all as redactions" hands the matches to T28's
  existing panel; it does not grow its own find/verify/write path (decision 8).
- **No background or scheduled regex index.** A library run is a foreground job the
  reader starts and can cancel; results are not stored.
- **No PCRE.** `java.util.regex` is the engine, with its limits documented below.
- **No sync or sharing of saved patterns.** They live in this app's settings.

## Anti-goals (what would make this a failure even if it works)

- A catastrophic pattern that freezes the UI or burns a core until the app is killed.
- A match whose page or boxes are wrong, so tapping it lands somewhere else.
- "Highlight all" silently touching the open file, or "Mark all as redactions"
  shipping a second, different regex than the redaction panel's own.
- Two panels reporting different counts for the same pattern on the same page.
- A library run that reports matches from a file it could not read as if it had.

## Decisions taken

1. **Approach A (owner, 2026-10-31): one engine matcher, thin wrappers.** A pure
   `RegexSearch.find(text, options)` over a `String`; a `PageLayout` overload that
   attaches `rectsFor` boxes; a `PdfDocument` walk that reports progress and can be
   cancelled. The CLI, the app's document scope and the app's library scope all call
   the same matcher, so flags, context, the deadline and the error wording cannot
   drift. T28's `PdfRedaction.find` is left alone (it is per line, `IGNORE_CASE`
   only, has no context and needs an open document).
2. **One scope per entry point (owner, 2026-10-31).** The panel is one composable
   with two owners of state: the viewer's find bar opens it as **"This document"**
   (`ViewerViewModel`), the Search tab opens it as **"Library"** (`RegexViewModel`).
   There is no scope switch to flip: the scope is where you opened it, and the panel
   shows which one it is. That keeps `AppNav` free of a pattern-carrying route and
   means next/previous never has to cross a file (decision 12).
3. **Both write paths produce a new copy.** Highlight all and the redaction hand-off
   never modify the open file, matching crop/compress/redact.
4. **`RegexFlag` lives in the engine** and is what the CLI flags, the saved-pattern
   record and the UI chips all speak, so there is one vocabulary.
5. **CSV is UTF-8 with a BOM by default** (spreadsheet apps need it), `--no-bom` for
   everything else.
6. **Pages are 1-based in exported files and in the CLI**, 0-based inside the engine,
   matching every other user-facing surface.

### Calls made during the builder check

7. **Library mode searches the indexed (normalised) text, and says so.** The T50
   index stores `IndexTextPolicy.normalize(layout.text)`: every newline became a
   space and whitespace runs were collapsed. So a library run cannot use
   `PageLayout.text` and match the same thing. Instead the library matcher runs on
   the stored string, and **on tap** the app recomputes the normalised text from the
   freshly laid-out page, re-runs the pattern, and picks the instance by its
   **occurrence ordinal** (the n-th match on that page), mapping each hit back to a
   character range in `layout.text` through a small offset map. That is exact for the
   common case and, when the file changed on disk since indexing, degrades to
   "opened, no boxes, with a note" rather than to wrong boxes. The panel tells the
   reader that `MULTILINE` and `ACROSS_LINES` do nothing in library scope, because
   the indexed text has no line breaks.
8. **"Mark all as redactions" is a hand-off, not a second write path.**
   `ViewerViewModel.findRedactions` already runs `PdfRedaction.find(regex = true)`
   with a review list, per-match checkboxes and T28 verification. The regex panel's
   action therefore closes the sheet, sets the redaction panel's mode to
   `REGEX`, fills its query with the pattern and runs it — the reader reviews and
   confirms exactly as they do today. One regex behaviour per document, one verify
   step, one copy flow, and no chance of two panels disagreeing about a count.
9. **Budgets are per run, not per page.** `RegexOptions.timeLimitMs` stays a
   **per-page** 1500 ms deadline (that is where a catastrophic pattern lives), and a
   run also carries `runTimeLimitMs` (60 s document, 30 s library) and
   `maxMatches` **per run** (2000 document, 5000 library). When either stops a run,
   `truncated`/`timedOut` is set, the panel says "stopped after N matches" and the
   write confirmation repeats it, so nothing is silently cut.
10. **The copy is built from the opened bytes.** Highlight all saves the document as
    it was opened, not with unsaved annotations or form values; when the viewer has
    unsaved edits the confirmation says so in one line. This matches compress, crop
    and redact.
11. **"Unique matches only" keys on `(file, page, text)`**, always. In document scope
    the file is constant, so it behaves exactly as the obvious `(page, text)`.
12. **Next/previous stays inside one document.** Library results are a list in the
    Search tab; tapping one opens the viewer on that file, page and box. Inside the
    viewer, next/previous walks the open document's matches only, so the viewer never
    swaps documents behind the reader's back and Back always means what it says.
13. **The matcher reads `PageLayout.text`, not Form XObject text.** That is what T11
    searches and what the boxes come from. Text inside a form is reachable through
    the redaction panel's own search, which is why redaction is a hand-off
    (decision 8) rather than a claim of completeness from this panel.
14. **Files the library cannot search are counted separately, never as zero matches.**
    `filesTotal` is the number of indexed files that have page text; a file indexed as
    locked, error, too large or without text is not in that count and is named in the
    summary ("3 files could not be searched"). The run honours the Search screen's
    current folder/date filters, because it searches what the reader is looking at.

## Architecture

```
engine (pure Kotlin, java.util.regex only)
  text/RegexModel     RegexFlag, RegexOptions, RegexMatch, RegexResult, RegexExample,
                      RegexExamples, contextAround(), normaliseWithOffsets()
  text/RegexSearch    compile + flags, deadline-guarded CharSequence, whole-word filter,
                      page/document entry points, progress + cancel
  tools/PdfSearchRegexCli   search-regex ... --out matches.csv

app
  ui/search/RegexModel        RegexScope, RegexRow/RegexGroup, grouping, unique, csv/txt,
                              RegexPattern + codec, describeRegexSummary, panel state
  ui/search/RegexPanel        stateless panel: pattern, flags, tester, examples, saved
  ui/search/RegexResults      grouped list, next/previous (document), export sheet
  ui/search/RegexViewModel    library run (index text), saved patterns, export
  ui/search/RegexActions      highlight-all (new copy); redaction hand-off
  ui/viewer/ViewerViewModel   document scope: run/cancel, highlight-all, hand-off
  ui/viewer/ViewerScreen      find bar "Regex" chip, panel sheet, jump + boxes
  index/SearchIndexDao        pageTextsForFile(fileId)
```

### Why the matcher is pure text plus a page wrapper

`PageLayout.text` is the page's reading-order lines joined with `\n`, and
`PageLayout.rectsFor(start, end)` already turns any character range into page-space
boxes. So:

- matching over the whole page string makes a match that spans a line break natural,
  and its boxes come out as one rectangle per line fragment — the same geometry the
  T11 highlight already paints;
- the library's indexed text is a plain `String` with no layout, and the very same
  matcher can run on it;
- a JVM test can exercise every flag without a document or a device.

The `PageLayout` overload only adds `rectsFor`; nothing else differs. The library
path adds one helper, `normaliseWithOffsets(text)`, which returns the normalised
string plus a map from each normalised index back to a `layout.text` index, so a hit
found on normalised text can be turned into boxes.

### Flags

| Flag | Meaning | Implementation |
| --- | --- | --- |
| `CASE_INSENSITIVE` (default on) | `Ab-1234` matches `ab-1234` | `Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE` |
| `MULTILINE` | `^`/`$` match at line breaks | `Pattern.MULTILINE` (document scope only; inert in library scope) |
| `ACROSS_LINES` (default on) | a match may include `\n` | matcher input is the whole page text; off = one pass per line, like T11 (document scope only; inert in library scope) |
| `WHOLE_WORD` | the match is not part of a longer word | **filter**, not `\b`: the character before `start` and at `end` must not be a letter, digit or `_` |

Whole-word is a filter on purpose. Wrapping the pattern in `\b(?:…)\b` would change
what the pattern means (alternation, patterns starting with punctuation) and would
shift nothing in the reported error position only by luck. Filtering keeps the
pattern the reader typed exactly as typed, and the rule is the same word-character
test T11 uses for selection.

`Pattern.DOTALL` is **not** implied by `ACROSS_LINES`: `.` still stops at a line
break. A reader who wants `.` to cross writes `[\s\S]` or turns on `(?s)`.

### Catastrophic patterns

Three independent bounds:

1. **Deadline.** The matcher is handed a `CharSequence` wrapper whose `get` checks a
   monotonic deadline every 1024 characters and throws a private
   `SearchDeadlineExceeded`. The engine catches it, keeps the matches found so far
   and sets `timedOut = true`. This aborts the matcher *inside* the JDK engine, so no
   thread is abandoned and no CPU is left burning. `RegexOptions.timeLimitMs` is a
   **per-page** budget (default 1500 ms); `runTimeLimitMs` bounds the whole run
   (decision 9).
2. **Match cap.** `RegexOptions.maxMatches` (default 2000, per run) stops a pattern
   that matches everywhere; `truncated = true` says so.
3. **Depth.** `java.util.regex` recurses; a pathological nesting throws
   `StackOverflowError`. The engine catches it and returns
   `InvalidPattern("This pattern is too complex to run", position = -1)` rather than
   crashing.

Known honest limit: the deadline is cooperative — it fires when the engine reads
characters. A pattern that loops without reading the text cannot be interrupted by
it. The app therefore also runs every search on a background dispatcher, so the UI
never blocks even in that case, and the panel always offers Cancel.

### Errors

`Pattern.compile` failures are caught as `PatternSyntaxException` and reported as
`RegexResult.InvalidPattern(pattern, message = e.description, position = e.index)`.
The panel draws the caret under that character and the CLI prints the same sentence
with the position, so the two never disagree.

### Context

`contextAround(text, start, end, radius)` returns `before`/`after` with whitespace
runs collapsed to one space, a leading/trailing `…` when clipped, and never more
than `radius` characters (default 48) on each side. Newlines inside the window become
spaces so a result row is one line.

### Library mode and geometry

The T50 index stores one FTS row per page holding text only — no boxes. So:

- a library run reads the stored text (a new DAO query returning `(pageIndex, text)`
  per file, ordered by page; **no schema change, no migration**) and runs the pure
  matcher on it. It reports file, page, match, context and the **occurrence ordinal**
  on that page, with no rectangles;
- tapping a library result opens the file, lays out that page, recomputes the
  normalised text with its offset map, re-runs the pattern and takes the ordinal-th
  match, then maps it to `layout.text` and `rectsFor` for the boxes. If the fresh page
  has fewer matches than the ordinal, the page still opens, without boxes, with a
  note (decision 7);
- the run is a foreground job on `Dispatchers.Default`, bounded by 30 s and 5000
  matches, with progress `(filesDone, filesTotal, currentName)` and a Cancel that is
  checked between pages and between files. Files without text, or indexed as locked
  or errored, are excluded from `filesTotal` and named in the summary; they never
  contribute matches (decision 14).

### Write paths

**Highlight all matches** acts on the open document only, behind a confirmation, and
writes a **new copy**: one `AnnotationWriter.HighlightSpec` per match (`contents` =
the matched text, `title` = "PDFPaper regex"), matches without boxes skipped and
counted, then `PdfPageOps.applyAnnotationsIncremental` (falling back to
`applyAnnotations` when the file has no usable `/startxref`) and SAF. Capped at 2000
highlights; when the run was truncated or timed out the confirmation says so.

**Mark all as redactions** closes the regex sheet and hands the pattern to the
existing redaction panel in `REGEX` mode, which searches, lists, verifies and writes
its own copy. The regex panel does not build redaction marks itself (decision 8).

Neither action touches autosave, the version store or the open file's bytes, and the
copy is built from the opened bytes (decision 10).

### CLI

```
./cli search-regex <in.pdf> "<pattern>" --out matches.csv
      [--case-sensitive] [--multiline] [--lines] [--whole-word]
      [--max N] [--timeout MS] [--run-timeout MS] [--context N]
      [--pages 1,3-5] [--no-bom]
```

- `--lines` turns `ACROSS_LINES` **off**; the default matches across line breaks.
- Columns: `file,page,match,context`, one row per match, RFC 4180 quoting, UTF-8 with
  a BOM unless `--no-bom`.
- Exit 0 when the search ran (even with no matches), 1 for a bad PDF, an invalid
  pattern or an IO error, 2 for usage. An invalid pattern prints its position, and a
  timed-out or truncated run prints that on stderr while still writing the rows it
  found.
- `--out` is required; `*`/`?` are expanded by the CLI wrapper like every other
  command.

## Behaviour details

- **Tester.** The pattern is compiled 300 ms after typing stops and run on the
  current page (document scope) or the first indexed file (library scope), showing
  "3 matches on page 4 · 1.2 s" or the error with its caret. It never runs the whole
  document.
- **Saved patterns.** Name + pattern + flags + creation time, stored as a positional
  Base64-URL record list in `AppSettings` under `regex.saved` (the `ReadingStateCodec`
  rule, so a name with a tab or a newline survives). Save, apply, rename and delete;
  a malformed record is dropped on read, never a crash.
- **Examples.** Exactly six, defined once in the engine: ISO dates
  (`\b\d{4}-\d{2}-\d{2}\b`), e-mail addresses, phone-style numbers, URLs
  (`https?://[^\\s<>\"]+`), IPv4-shaped addresses
  (`\b(?:\d{1,3}\.){3}\d{1,3}\b`, shape only, not a validity check) and ID codes
  (`\b[A-Z]{2,3}-\d{4,8}\b`).
- **Results.** Grouped by file (library) and then by page, in reading order; each row
  shows the matched text and its context with the match emphasised. In the viewer,
  next/previous walk the open document's flat list (decision 12); tapping opens the
  viewer at that page with `startQuery` = the matched text (T11's literal highlight)
  and `highlightBounds` = the match's bounding box (T56's transient outline), so the
  exact instance is marked even when the same text occurs twice on the page.
- **Export.** `.txt` = one match per line, `page <n>: <text> — <context>`; `.csv` =
  `file,page,match,context`. "Unique matches only" de-duplicates by
  `(file, page, text)`; "all" keeps every occurrence. Save through SAF or share
  through the FileProvider.
- **Empty states.** No pattern, no matches, a file with no text layer, a library with
  no folders — each says which one it is and what to do.

## Files

### Engine (new)

| File | Responsibility |
| --- | --- |
| `engine/src/main/java/com/pdfpaper/engine/text/RegexModel.kt` | `RegexFlag`, `RegexOptions`, `RegexMatch`, `RegexResult`, `RegexExample`/`RegexExamples`, `contextAround`, `normaliseWithOffsets` |
| `engine/src/main/java/com/pdfpaper/engine/text/RegexSearch.kt` | compile/flags, deadline `CharSequence`, whole-word filter, `find(text)`, `find(layout)`, `PdfDocument.searchRegex`, progress/cancel |
| `engine/src/main/java/com/pdfpaper/engine/tools/PdfSearchRegexCli.kt` | the `search-regex` command and CSV writer |

### Engine (modified)

| File | Change |
| --- | --- |
| `engine/build.gradle.kts` | `search-regex` JavaExec task (same pattern as `comments`) |
| `cli.bat` | `search-regex` branch |

### App (new)

| File | Responsibility |
| --- | --- |
| `app/src/main/java/com/pdfpaper/app/ui/search/RegexModel.kt` | scope, rows/groups, grouping, unique, txt/csv, `RegexPattern` + codec, summaries, panel state |
| `app/src/main/java/com/pdfpaper/app/ui/search/RegexPanel.kt` | pattern field, flag chips, tester, examples, saved patterns, scope label |
| `app/src/main/java/com/pdfpaper/app/ui/search/RegexResults.kt` | grouped results, next/previous, export sheet |
| `app/src/main/java/com/pdfpaper/app/ui/search/RegexViewModel.kt` | library run over index text, saved patterns, export |
| `app/src/main/java/com/pdfpaper/app/ui/search/RegexActions.kt` | highlight-all (new copy) and the redaction hand-off |

### App (modified)

| File | Change |
| --- | --- |
| `index/SearchIndexDao.kt` | `pageTextsForFile(fileId)` |
| `index/IndexRepository.kt` | `pageTexts(fileId)` |
| `ui/search/SearchScreen.kt` | Regex toggle beside the box, panel + results, scope Library |
| `ui/viewer/ViewerViewModel.kt` | document scope: run/cancel, state, highlight-all, redaction hand-off |
| `ui/viewer/ViewerScreen.kt` | find bar "Regex" chip, panel sheet, jump with query + boxes |
| `ui/redact/RedactionSheet.kt` | accept a prefilled pattern + `REGEX` mode from the regex panel |
| `data/AppSettings.kt` | `regex.saved`, `regex.flags` |
| `ui/UiTags.kt` | regex tags |
| `app/tools/t65_acceptance_pdfs.py` | the two acceptance documents |

## Tests

- **Engine `RegexSearchTest`** (~24): case-insensitive by default and off; `MULTILINE`
  `^`/`$`; a match across a line break with two boxes; `ACROSS_LINES` off finding only
  the per-line part; whole-word kept and rejected; context with ellipsis and collapsed
  whitespace; zero-length matches skipped; the match cap; the per-page deadline and
  the run deadline on a genuinely catastrophic pattern returning within a bound;
  `StackOverflowError` → "too complex"; an invalid pattern's message and position; a
  document walk with progress and cancel; an empty pattern; a page with no text;
  every example compiles and finds its sample; boxes on a rotated page;
  `normaliseWithOffsets` round-tripping a wrapped line and its ordinal.
- **Engine `RegexCliTest`** (~7): CSV quoting (comma, quote, newline), the BOM and
  `--no-bom`, 1-based pages, `--pages` selection, exit codes, an invalid pattern's
  message, a truncated run still writing its rows and saying so on stderr.
- **App `RegexModelTest`** (~18): flag mapping both ways, grouping by file then page,
  unique vs all (the `(file, page, text)` key across two files), CSV escaping and BOM,
  txt lines, summaries (matches, truncation, timeout, unsearchable files), pattern
  codec round trip and malformed records, scope labels, tester summary text, the
  redaction hand-off request carrying pattern and mode.
- **Device `RegexSearchDeviceTest`** (3): a document-scope run on a generated
  multi-page PDF gives the right pages and boxes; tapping a row opens the viewer on
  that page with the highlight; next/previous walk the list.
- **Device `RegexLibraryDeviceTest`** (3): 50 generated files show progress and
  finish; Cancel stops within a page and keeps what was found; a result tapped in the
  library opens the file with correct boxes for a pattern that matched across a line
  break in the index text.
- **Device `RegexWriteDeviceTest`** (2): highlight-all writes a copy with the expected
  annotation count and the original bytes unchanged; the redaction hand-off prefills
  the panel, and its own verify removes the text from the copy while the original
  stays byte-identical.
- **Device `RegexPanelDeviceTest`** (2): the six examples, the flag chips and the
  tester; a catastrophic pattern shows Cancel and the panel returns within the limit.

## Acceptance

1. `t65-invoice.pdf` (4 pages with ID codes, ISO dates, e-mails, URLs, IPv4 addresses
   and phone numbers): each example finds exactly the expected pages and boxes.
2. `t65-report.pdf` (a sentence wrapped over two lines): `ACROSS_LINES` finds it with
   two boxes; `--lines` does not find it.
3. `(a+)+$` against a long run of `a`s is cancelled within the time limit; the app
   stays responsive and the CLI prints `timed out`.
4. `[A-Z` reports "Unclosed character class" at its position, with the caret under it.
5. The CLI's CSV opens in a spreadsheet app with the right columns and no mojibake
   (checked with Python's `csv` module and by eye).
6. A library run over 50 files shows "12 of 50 files", cancels cleanly, and names an
   unsearchable file in the summary instead of inventing matches for it.
7. Highlight all → a new copy with N `/Highlight` annotations; the original is
   byte-identical.
8. Mark all as redactions → the redaction panel opens with the pattern and its
   `REGEX` mode; after its own verify the matched text is gone from the copy and the
   original is byte-identical.
9. Screenshots: `t65-panel.png` (pattern, flags, tester, examples), `t65-results.png`
   (grouped results with context), `t65-error.png` (caret), `t65-export.png`.

## Risks and limitations to record in AGENTS.md

- Only `java.util.regex`: no variable-length lookbehind, no `\K`, no recursion, no
  PCRE-specific escapes. A pattern valid in another tool may be invalid here, and the
  error position is the JDK's.
- The input is the extracted reading-order text, so it inherits T11's limits: a word
  hyphenated across a line break is `hy-\nphen` and a pattern must allow for it; a
  ligature is the characters the extractor produced; a scanned page needs its OCR
  layer first; text inside Form XObjects is not searched (the redaction panel's own
  search does reach it).
- Boxes are interpolated along the baseline, so a match inside a ligature or a very
  wide glyph can be a few points off; a match with no boxes (library scope, or a file
  that changed since indexing) is reported without rectangles rather than guessed.
- Whole-word is a neighbour-character filter, so a pattern that starts or ends with
  punctuation is judged by the characters around it, not by `\b`.
- The deadline is cooperative (it fires while the engine reads characters); a pattern
  that loops without reading cannot be stopped by it, which is why searches run off
  the main thread. A `StackOverflowError` is reported as "too complex".
- Library mode searches the **indexed, whitespace-normalised** text: a file changed
  on disk is searched as it was indexed until the next index run, `MULTILINE` and
  `ACROSS_LINES` do nothing there, and boxes are recomputed only when the file is
  opened. The index has no geometry, so the library list shows no previews.
- Highlight all writes a new copy and never the open file; the copy is built from the
  opened bytes, so unsaved annotations are not in it and the confirmation says so.
  Redaction is irreversible in its copy, and is done by T28's panel, not by this one.
- Saved patterns are app-local settings; they are not synced, shared or backed up by
  this app's own code.
- CSV uses a BOM by default (spreadsheet apps need it); `--no-bom` for other tools.
- Pages are 1-based in the CLI and in exports, 0-based inside the engine.
