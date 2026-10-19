# Convert: what works, what does not

`:convert` is a pure-Kotlin/JVM module that depends on `:engine` only. It uses
no third-party Office or PDF library: OOXML is a ZIP package
(`java.util.zip`) of hand-written XML parts.

```sh
./cli convert pdf-to-pptx <in.pdf> --out <out.pptx> [--fidelity]
./cli convert pdf-to-docx <in.pdf> --out <out.docx> [--mode flow|exact]
```

Every conversion writes a loss report next to the output
(`<out>.<ext>.report.txt`) and the machine-readable part of it is also returned
by the API (`PdfToDocxResult.report`, `PdfToPptxResult.report`).

Verification used for the tables below:

* `./gradlew :convert:test` — unit tests on local synthetic PDFs that exercise
  each conversion path (`TestPdf.kt`, `TestPdfDocx.kt`).
* `./gradlew :convert:pptxCorpusCheck`, `./gradlew :convert:docxCorpusCheck` —
  converts every PDF in `/testdata` and re-reads each package with the
  independent `PptxValidator`/`DocxValidator` (content types, XML
  well-formedness, relationship targets, `r:id` resolution, section/numbering
  references).
* `convert/tools/pptx_check.py` and `convert/tools/docx_check.py` — dev-only
  helpers (not build dependencies) that open the outputs with `python-pptx` /
  `python-docx`, i.e. with a third-party reader rather than our own validator.

## PDF → DOCX (`--mode flow`, the default)

Flow mode rebuilds an editable document: one section per PDF page (which is
also where the page breaks come from), paragraphs merged out of the lines,
headings from the font sizes, markers turned into real Word lists, aligned
runs turned into tables, pictures placed in reading order.

### Well converted

| Document | Why |
| --- | --- |
| Body text of one font and one size, single column | Lines that belong together are merged into one paragraph (`w:p`), the line breaks between them disappear, a hyphen split across lines is rejoined. |
| Titles and headings sized up from the body text (24 pt over 10 pt, 14 pt over 10 pt) | Become `Heading 1`–`Heading 3`; the level is the size ratio (≥1.55, ≥1.30, ≥1.15) and is only applied to short paragraphs. |
| Bullet lists (`•`, `*`, `-`) | Become Word bullet lists with a hanging indent. |
| Numbered lists (`1.`, `1)`, `a.`, `i.`, roman/letters) | Become Word numbered lists; a run of markers that counts up reuses one numbering instance, a list that starts at a number other than 1 gets a `startOverride`. |
| Simple tables: ≥2 rows, the same cells on every row, cell left edges that line up, a table that is more than 45 % of the column width | Become `w:tbl` with a fixed grid; the cell text is kept verbatim. |
| Pictures: photos, screenshots, icons, scanned figures | DCT (JPEG) bytes are embedded verbatim; everything else the engine can decode is re-encoded as PNG (soft masks multiplied into the alpha). |
| Page geometry | Page size comes from the crop box; a page that is wider than tall stays landscape. Margins are measured from the text extents and clamped to 0.25–1.5 in. |
| Two- and three-column pages (both columns carry a comparable amount of text) | Become a `w:cols` section, read column by column. |
| Running heads, footers and page numbers | Lines in the top/bottom 8 % that look like a page number or repeat on at least half the pages are dropped and counted in the report. |

### Partly converted (opens, looks close, details differ)

| Document | What changes |
| --- | --- |
| Bold, italic | Inferred from the font name (`/BaseFont` with `Bold`, `Italic`, `Oblique`, `-Bd`…); an unusual naming scheme may be missed. The report names the fonts it did not recognise. |
| Underline, strikethrough, superscript/subscript, letter spacing, small caps | Not detected (the engine's text model carries no such flags). |
| Text colour, highlights, shading behind text | The engine exposes no text colour, so every run is written in the automatic colour (black). This is stated in every report. |
| Justified text | Guessed: a paragraph whose lines (all but the last) reach the right margin becomes `w:jc="both"`. A ragged paragraph whose lines happen to end at the margin may be marked justified. |
| Vertical spacing | Paragraph spacing is normalised to one value per document (the detected inter-paragraph gap, ≤24 pt) and applied after each body paragraph; the original line spacing inside a paragraph is not preserved. |
| Tables that only look like tables | A table is recognised by aligned, gap-separated rows. Cell borders, merged cells, nested tables and multi-line cells are not detected; the converted table is a plain borderless grid with one paragraph per cell. |
| Vector rules and boxes (table borders, section rules) | Flow mode drops them and reports the counts (`vectorFills`/`vectorStrokes`); a DOCX has no way to place an arbitrary line. |
| Pictures wider than the text area | Scaled down to fit and reported. |
| One column of a two-column page that is much shorter than the other (less than 25 % of the taller column's text height) | Read as a single column, so the short side's text is interleaved into the page's reading order. The rule exists because a table's last column sits at exactly the same kind of gap. |
| A line with a very wide gap inside it | May be taken for a table row (needs a second, aligned row) or for a tab stop; either way the text survives, the layout does not. |
| Rotated text (vertical labels, watermarks) | Written horizontally and counted in the report; `w:framePr`/runs cannot rotate text. |
| Fonts without `/Widths`, unusual encodings | The engine estimates advances (size × 0.5 × character count) and reports it; column and table detection can be thrown off by the estimate. |

### Badly converted (the result opens but the page is not what it was)

| Document | Why |
| --- | --- |
| Scanned pages (one big image per page) | The image is converted (inline in flow mode, anchored behind the text in exact mode), but there is no OCR: the page is a picture, not text. |
| Text converted to outlines/curves (many "print-ready" PDFs) | It is vector art, not text: flow mode drops it and reports it; exact mode keeps it as part of the rendered page picture. |
| Labels and captions placed with no regular line structure (diagrams, maps, posters) | Lines are merged into paragraphs by vertical distance, so unrelated labels can end up in the same paragraph and their order is the reading order of the page, not the visual arrangement. |
| Maths, charts, callouts, SmartArt-like graphics | Vector art plus symbol fonts: the graphics are dropped (flow) or rasterised (exact), the formula glyphs are unsupported fonts. |
| RTL, CJK or other text without `/ToUnicode` | The engine cannot map the character codes, so the text comes out wrong or empty. The font is listed in the report. |
| Exact mode (`--mode exact`) in general | It keeps the geometry: every line becomes a `w:framePr` paragraph at its original coordinates and pictures stay anchored. The vertical placement is approximate (the engine exposes no ascent/descent; the frame top is the run's bounding box and the line height is 1.21 em), the words inside a line are re-laid out by the target application, and text cannot be rotated. Vector art becomes one rasterised page-sized picture per page (at `--dpi`, default 200). |

### Exact mode (`--mode exact`)

Everything in the tables above applies except that nothing is re-flowed: the
document is a set of absolutely positioned text frames over an optional page
picture. Use it when the layout matters more than editability (forms, flyers,
certificates), and flow mode when the text matters more than the layout.

## DOCX → PDF

Not implemented yet (T19).

## PPTX → PDF

Not implemented yet (T20).
