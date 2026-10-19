"""Generates the standard-14 width tables used by PDF creation and layout.

The PdfPaper engine has no glyph outline rasterizer and may not use `java.awt`,
so the PDF builder and the DOCX->PDF layout need advance widths for the PDF
standard 14 fonts (the fonts every reader has: Helvetica, Times, Courier and
their bold/italic variants). Those widths are not shipped by the JDK, so they
are generated once, here, from reportlab's copy of the Adobe AFM metrics
(reportlab is a dev-time tool only — the generated Kotlin table is plain data
and the build has no new dependency).

    python convert/tools/make_standard_widths.py

Writes engine/src/main/java/com/pdfpaper/engine/edit/StandardWidths.kt.
Re-run only when the font set changes.
"""

import sys
from pathlib import Path

from reportlab.pdfbase import _fontdata

# Order matters: StandardFonts.kt indexes the generated arrays by these names.
FONTS = [
    ("HELVETICA", "Helvetica"),
    ("HELVETICA_BOLD", "Helvetica-Bold"),
    ("HELVETICA_OBLIQUE", "Helvetica-Oblique"),
    ("HELVETICA_BOLD_OBLIQUE", "Helvetica-BoldOblique"),
    ("TIMES_ROMAN", "Times-Roman"),
    ("TIMES_BOLD", "Times-Bold"),
    ("TIMES_ITALIC", "Times-Italic"),
    ("TIMES_BOLD_ITALIC", "Times-BoldItalic"),
    ("COURIER", "Courier"),
    ("COURIER_BOLD", "Courier-Bold"),
    ("COURIER_OBLIQUE", "Courier-Oblique"),
    ("COURIER_BOLD_OBLIQUE", "Courier-BoldOblique"),
]

HEADER = '''package com.pdfpaper.engine.edit

/**
 * Font metrics, in 1/1000 em, for the 256 WinAnsi codes of the PDF standard 14
 * text fonts: advance widths (§9.7.4), the AFM ascender and descender.
 *
 * Generated from the Adobe AFM metrics by `convert/tools/make_standard_widths.py`
 * — do not edit by hand. The widths make line breaking, centring and
 * justification exact without embedding (or measuring) a font.
 */
internal object StandardWidths {

'''


def widths(base_font: str) -> list[int]:
    encoding = _fontdata.encodings["WinAnsiEncoding"]
    glyphs = _fontdata.widthsByFontGlyph[base_font]
    values = []
    for code in range(256):
        glyph = encoding[code]
        if glyph is None or glyph == ".notdef":
            values.append(0)
        else:
            values.append(int(glyphs.get(glyph, 0)))
    return values


def rows(values: list[int], per_line: int = 16) -> list[str]:
    lines = []
    for start in range(0, len(values), per_line):
        chunk = ", ".join(str(v) for v in values[start:start + per_line])
        lines.append(f"        {chunk},")
    return lines


def main() -> int:
    out = (
        Path(__file__).resolve().parents[2]
        / "engine/src/main/java/com/pdfpaper/engine/edit/StandardWidths.kt"
    )
    parts = [HEADER]
    for name, base_font in FONTS:
        values = widths(base_font)
        parts.append(f"    /** {base_font} */\n    val {name}: IntArray = intArrayOf(\n")
        parts.append("\n".join(rows(values)))
        parts.append("\n    )\n\n")

    parts.append("    /** Indexed by [StandardFont.ordinal]. */\n")
    parts.append("    val ALL: Array<IntArray> = arrayOf(\n")
    for name, _ in FONTS:
        parts.append(f"        {name},\n")
    parts.append("    )\n\n")

    parts.append("    /** AFM Ascender, 1/1000 em. */\n")
    parts.append("    val ASCENT: IntArray = intArrayOf(\n")
    parts.append("\n".join(rows([int(_fontdata.ascent_descent[base][0]) for _, base in FONTS])))
    parts.append("\n    )\n\n")

    parts.append("    /** AFM Descender, 1/1000 em (negative). */\n")
    parts.append("    val DESCENT: IntArray = intArrayOf(\n")
    parts.append("\n".join(rows([int(_fontdata.ascent_descent[base][1]) for _, base in FONTS])))
    parts.append("\n    )\n}\n")

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(parts), encoding="utf-8", newline="\n")

    # Sanity checks against the published metrics.
    helvetica = widths("Helvetica")
    assert helvetica[ord(" ")] == 278 and helvetica[ord("A")] == 667 and helvetica[ord("M")] == 833
    assert all(v == 600 for v in widths("Courier") if v != 0), "Courier is a fixed-pitch font"
    assert widths("Times-Roman")[ord("M")] == 889
    assert _fontdata.ascent_descent["Helvetica"] == (718, -207)
    print(f"wrote {out} ({out.stat().st_size} bytes, {len(FONTS)} fonts)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
