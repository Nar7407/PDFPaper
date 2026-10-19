# Convert: what works, what does not

`:convert` is a pure-Kotlin/JVM module that depends on `:engine` only. It uses
no third-party Office or PDF library: OOXML is a ZIP package
(`java.util.zip`) of hand-written XML parts.

```sh
./cli convert pdf-to-pptx <in.pdf> --out <out.pptx> [--fidelity]
./cli convert pdf-to-docx <in.pdf> --out <out.docx> [--mode flow|exact]
./cli convert docx-to-pdf <in.docx> --out <out.pdf>
./cli convert pptx-to-pdf <in.pptx> --out <out.pdf>
```

Every conversion writes a loss report next to the output
(`<out>.<ext>.report.txt`) and the machine-readable part of it is also returned
by the API (`PdfToDocxResult.report`, `PdfToPptxResult.report`,
`DocxToPdfResult.report`, `PptxToPdfResult.report`).

Verification used for the tables below:

* `./gradlew :convert:test` — unit tests on local synthetic PDFs that exercise
  each conversion path (`TestPdf.kt`, `TestPdfDocx.kt`).
* `./gradlew :convert:pptxCorpusCheck`, `./gradlew :convert:docxCorpusCheck` —
  converts every PDF in `/testdata` and re-reads each package with the
  independent `PptxValidator`/`DocxValidator` (content types, XML
  well-formedness, relationship targets, `r:id` resolution, section/numbering
  references).
* `./gradlew :convert:docxToPdfCorpusCheck` — converts every DOCX the corpus
  check produced back to PDF and reads each result back with the engine, so the
  table below covers `testdata` documents and not only the fixtures.
* `./gradlew :convert:pptxToPdfCorpusCheck` — does the same for the PPTX corpus
  (T17 output → PDF), and `PptxToPdfTest` converts five decks written by
  `python-pptx` (`convert/tools/make_pptx_samples.py`, an independent writer).
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

```sh
./cli convert docx-to-pdf <in.docx> --out <out.pdf>
```

The package parts are read with `java.util.zip` and the DOM, laid out by
`WordLayout` and written as PDF by hand. Text is drawn with the PDF **standard
14** fonts (`Times`, `Helvetica`, `Courier` and their bold/italic variants)
with `/WinAnsiEncoding` and explicit `/Widths`, so nothing is embedded and any
viewer can render the file. The widths come from the standard Adobe metrics
(`convert/tools/make_standard_widths.py`, a dev-only script, generated
`pdf/StandardWidths.kt`). Pictures are either embedded as JPEG
(`/DCTDecode` bytes verbatim) or, for PNG, decoded by our own reader
(`raster/PngReader`, colour types 0/2/3/4/6, bit depths 1–16, `tRNS`, all five
filters, CRC checked) and re-deflated as `DeviceRGB`/`DeviceGray` with an
`/SMask` for transparency.

### Well converted

| Document | Why |
| --- | --- |
| Body paragraphs | Text is drawn run by run at the position the layout computed; spaces are preserved, tabs jump to the default tab stop (`w:defaultTabStop` from `settings.xml`, else 0.5 in). |
| Left, centre, right and justified alignment | Justified lines are stretched to the text area by giving every space a share of the slack: the layout positions each run, and a run that contains several spaces gets a `TJ` adjustment between them. The last line of a paragraph is not stretched. |
| Paragraph and character styles | `styles.xml` is resolved the way Word does it: docDefaults, then the style's `basedOn` chain, then direct formatting on the paragraph and the runs. Theme fonts (`w:asciiTheme="majorHAnsi"` and friends, which is how most real documents name their fonts) are filled in from the package's `*/theme/theme1.xml` `a:fontScheme`. |
| Bold, italic, underline, strikethrough, size, colour | Drawn with the matching standard font variant; colour as `/DeviceRGB`. Underline and strike are thin rectangles under/over the run. |
| All caps, superscript, subscript, character spacing (`w:spacing`) | `w:allCaps` upper-cases; superscript raises the baseline by 0.33 em, subscript lowers it by 0.14 em (the engine reads the `Tc` correctly); character spacing becomes a `Tc` in points. |
| Indents and vertical spacing | `w:ind` (left, right, first line, hanging) and `w:spacing` before/after are converted from twips to points; `w:line` is honoured as a multiple, an exact height or "at least". |
| Lists | Levels come from `numbering.xml`: `bullet` (the `Symbol`/`Wingdings`/`Courier` private-use glyphs are mapped to `•`, `▪`, `o`), `decimal`, `lowerLetter`, `upperLetter`, `lowerRoman`, `upperRoman` and other decimal styles; `w:lvlText` with its `%n` references, `w:start` and `w:startOverride`; a nine-level counter stack keeps nested lists counting. |
| Page setup and sections | `w:pgSz` (including landscape) and `w:pgMar` become the PDF page box and margins; every `w:sectPr` starts a new page, so mixed portrait/landscape documents keep their page sizes. |
| Explicit page breaks | `w:br w:type="page"` and `w:pageBreakBefore` start a new page. |
| Tables | `w:tblGrid` column widths, `w:tblInd`, `w:tblCellMar`, cell shading (`w:shd`), horizontal merges (`w:gridSpan`), `w:trHeight` and borders when the source states them (directly or through the table style chain, `basedOn` included). A row that does not fit in the text area starts the next page; nested tables are laid out in their cell. |
| Pictures | JPEG data is embedded verbatim as `/DCTDecode`; PNG is decoded and re-deflated (see above). An inline picture sits on the baseline in the line; an anchored one is placed on the page from `wp:posOffset` or `wp:align` (behind or in front of the text), and pictures inside table cells are placed there. |
| PDF metadata | `/Title` comes from `docProps/core.xml` (UTF-16BE when it is not plain ASCII), `/Producer` and `/Creator` name the converter, and the content streams are deflated. |

### Partly converted (opens, looks close, details differ)

| Document | What changes |
| --- | --- |
| Any font outside the standard 14 (Calibri, Cambria, Arial, …) | Replaced by a standard font of the same shape (Calibri→Helvetica, Cambria→Times, Courier New→Courier, and their bold/italic variants); the metrics of the substitute are used, so line breaks can move. **Every substitute is named in the report** — on real documents this is normal, not a defect. |
| Characters WinAnsi has no code for (CJK, Greek, maths, emoji, …) | Drawn as `?` and reported once per document. |
| Small caps | Drawn as lower case, reported (there is no small-caps flag in the standard 14 fonts). |
| Fields (`PAGE`, `NUMPAGES`, `DATE`, `TOC`…) | The text Word last saved for them is drawn. A field that was never updated shows its cached value; the report says so. |
| Hyperlinks | The text is drawn, the clickable annotation is not created; reported. |
| Multi-column sections | Laid out as one column; reported. |
| Vertically merged cells | Drawn as ordinary separate cells; reported. |
| A column break | Treated as a page break; reported. |
| Table borders | A table that asks for borders gets a full grid in one thin grey line: the per-edge widths, colours and `nil` edges are not reproduced. |
| Tab stops | Only the default tab stop is used; explicit `w:tabs` positions and alignment are ignored (the text is correct, the columns inside a paragraph may not line up). |
| Multi-line cells and rows that are taller than a page | A row is never split, and a row taller than the text area overflows the bottom margin instead of continuing on the next page. |
| Floating pictures | Anchored pictures are placed at their offsets, but text does not wrap around them (reported). A picture anchored `behindDoc="1"` is drawn behind the text. |
| Grouped drawings / a drawing with several pictures | Only the first picture is converted; reported. |

### Badly converted (the result opens but the page is not what it was)

| Document | Why |
| --- | --- |
| Headers and footers (including page numbers) | Not converted at all — a PDF has no page areas; reported. |
| Footnotes, endnotes and their references | Dropped; reported. |
| Text boxes, WordArt, charts, SmartArt, equations, embedded objects | Not converted: the text inside them is not read and the graphics are skipped; drawings without a picture are reported. |
| Legacy VML pictures (`w:pict`) | Dropped; reported. |
| BMP, GIF, TIFF, EMF, WMF and SVG pictures | Skipped and named in the report (only PNG and JPEG are converted). An interlaced PNG is skipped as well. |
| Tracked changes | Insertions are drawn (the `w:ins` wrapper is transparent), deletions are dropped; reported. What the reviewer wanted is not what the reader sees. |
| RTL and other bidi text (`w:bidi`, `w:rtl`) | Drawn left-to-right in logical order, without reordering or shaping: Arabic/Hebrew text comes out reversed. |
| Rotated text (`w:textDirection`, vertical labels) | Drawn horizontally; a transform would need the text to be reshaped. |
| Documents that rely on exact typography (contracts, invoices with ruled lines drawn as table borders in colour, forms) | The text and the layout survive, the font and the rule styling do not; Word and the PDF will differ where they use different fonts. |

## PPTX → PDF

```sh
./cli convert pptx-to-pdf <in.pptx> --out <out.pdf>
```

One PDF page per slide, exactly the slide's size (`p:sldSz`), in slide order —
hidden slides included (reported), so the printed deck is complete. A slide is
painted back to front: its background, then the shapes in `p:spTree` order.
Text is drawn with the same PDF **standard 14** fonts, `/WinAnsiEncoding` and
explicit `/Widths` as DOCX → PDF, and pictures are embedded the same way (JPEG
`/DCTDecode` verbatim, PNG decoded by our own reader and re-deflated with an
`/SMask` for transparency). Geometry comes from `a:xfrm`; a placeholder's
position, insets, anchor, wrap and outline levels are inherited from the layout
and then the master, attribute by attribute, the way PowerPoint walks the
chain. Theme colours (`a:schemeClr`) resolve through `a:clrScheme` and the
master's `p:clrMap`.

### Well converted

| Slide content | Why |
| --- | --- |
| Slide size, order and count | One page per slide in `p:sldIdLst` order; the page box is `p:sldSz`. |
| Placeholder text (`p:ph`) | Position, insets, anchor and outline levels come down the layout→master chain, one attribute at a time: a slide that only drops an empty `a:bodyPr` still gets the master's centring and the title style. |
| Title, body and list text | Runs with size, bold, italic, underline, strike and colour; bullets (`a:buChar`), auto numbers (`a:buAutoNum`: `arabicPeriod`, `alpha`, `roman`… with a counter per outline level), indents (`marL`/`indent`), left/centre/right/justified alignment (the last line of a justified paragraph is not stretched), line spacing (`a:lnSpc` as a multiple or in points), space before/after, and explicit breaks (`a:br` and the `_x000B_` vertical tab). |
| Text in plain shapes and text boxes | The same run formatting, with the shape's `p:style` (`fontRef`) and the theme's `a:objectDefaults` filled in underneath the master's other style; `a:wrap="none"` text is never wrapped. |
| Solid fills | `a:solidFill`/`a:noFill` in an RGB, system or theme colour, with the slide's own colour map (`bg1`, `tx1`, …) resolved through the master. |
| Rectangles, ellipses and straight connectors | `rect` outlines are drawn as four rules on the edges, an `ellipse` as four Bézier arcs, a `roundRect` as a rectangle with square corners (reported); connectors run corner to corner honouring `flipH`/`flipV`, with the stated width. |
| Groups | Flattened through the group's `a:xfrm` and `chOff`/`chExt`, so nested shapes land where they belong. |
| Slide backgrounds | A solid `p:bgPr` colour, or a picture from `p:bgPr`/`p:bgRef` stretched over the page (counted as a picture). |
| Pictures | Placement from `a:xfrm`; JPEG verbatim, PNG decoded (colour types 0/2/4/6, `tRNS`, bit depths 1–16). |
| Slide masters and layouts | Their backgrounds and placeholder geometry/styles are used; their decoration shapes are not converted, and the count is reported. |

### Partly converted (opens, looks close, details differ)

| Slide content | What changes |
| --- | --- |
| Any font outside the standard 14 (Calibri, Arial, …) | Replaced by a standard font of the same shape, exactly as in DOCX → PDF; every substitute is named in the report. Theme fonts (`+mj-lt`, `+mn-lt`) are resolved to the theme's typeface first, then substituted like any other name. |
| Characters WinAnsi has no code for | Drawn as `?` and reported once. |
| Rounded rectangles | Drawn with square corners; reported. |
| Rotated shapes, pictures and groups (`a:xfrm/@rot`) | Drawn upright; reported. |
| Fills and outlines taken from `p:style` | The referenced style's first colour is used as a solid approximation; gradients and pattern fills are drawn as their first/foreground colour, picture fills are dropped, and each is reported. |
| Dashed and custom outlines (`a:prstDash`, `a:custDash`) | Drawn solid; reported. |
| Shape effects (shadow, glow, reflection) | Not drawn — neither an `a:effectLst` of the shape's own nor the theme's `a:effectStyleLst` entry its `effectRef` points at; reported. |
| Transparency (`alpha`) | Drawn opaque; reported once. |
| Text shrunk to fit (`a:normAutofit`) | Drawn at its full size, so it can overflow the frame; reported. |
| Cropped pictures (`a:srcRect`) | The whole picture is drawn in the frame; reported. |
| Tiled picture fills (`a:tile`) | Stretched over the frame; reported. |
| Bullet geometry | The marker is placed at the hanging indent on the first line's baseline; PowerPoint's exact offsets, `a:buFont` shapes and `a:buClr` are approximated. |
| Line wrap | Greedy wrapping with the substitute font's metrics, so line breaks can move slightly compared with PowerPoint. |

### Badly converted (the result opens but the page is not what it was)

| Slide content | Why |
| --- | --- |
| Charts | Skipped (a chart would need a chart renderer); reported. |
| Tables on a slide (`p:graphicFrame`) | Skipped; a PDF page has no table object and the flow layout only exists in the DOCX converter; reported. |
| SmartArt, WordArt and diagrams | The drawing frame is skipped and the text inside is not read; reported. |
| OLE objects and embedded content parts | Skipped; reported. |
| Video and audio (`a:videoFile`/`a:audioFile`) | Skipped, the poster frame is not drawn either; reported. |
| Animations (`p:timing`) and transitions (`p:transition`) | Not represented in a PDF; reported. |
| Fields (`a:fld`) | The text the file last saved for them is drawn; a field the deck never refreshed shows its cached value, and the report says so. |
| Custom geometry (`a:custGeom`) and shapes with an unknown preset | Skipped; reported. |
| Bent and curved connectors | Skipped; reported. |
| 3D, bevel, materials and scene cameras | Shape effects and the 3D parts of the theme are not drawn. |
| A presentation that relies on its theme's exact fonts, gradients and effects | The text and the layout survive; the styling does not. |
