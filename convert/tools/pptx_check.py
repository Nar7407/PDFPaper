"""Independent verification of PDFPaper's generated .pptx files.

This is a *development/acceptance* helper, not part of the build: it opens the
output with python-pptx (a third-party implementation written by someone else)
so a generator bug that our own validator shares cannot hide. The convert
module itself has no third-party Office dependency.

    python pptx_check.py <file.pptx> [more.pptx ...]

Exits non-zero if any file fails to open.
"""

import sys

from pptx import Presentation


def check(path: str) -> bool:
    try:
        deck = Presentation(path)
    except Exception as exc:  # noqa: BLE001 - report whatever python-pptx raises
        print(f"FAIL  {path}: {type(exc).__name__}: {exc}")
        return False

    slides = list(deck.slides)
    texts = sum(
        1
        for slide in slides
        for shape in slide.shapes
        if shape.has_text_frame and shape.text_frame.text.strip()
    )
    pictures = sum(1 for slide in slides for shape in slide.shapes if shape.shape_type == 13)
    print(
        f"OK    {path}: {len(slides)} slide(s), "
        f"{deck.slide_width}x{deck.slide_height} EMU, {texts} text, {pictures} picture(s)"
    )
    return True


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 1
    ok = all(check(path) for path in argv[1:])
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
