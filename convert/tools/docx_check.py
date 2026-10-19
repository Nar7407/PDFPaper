"""Independent verification of PDFPaper's generated .docx files.

This is a *development/acceptance* helper, not part of the build: it opens the
output with python-docx (a third-party implementation written by someone else)
so a generator bug that our own validator shares cannot hide. The convert
module itself has no third-party Office dependency.

    python docx_check.py <file.docx> [more.docx ...]

Exits non-zero if any file fails to open. The summary is deliberately dull —
what matters is that python-docx (and therefore Word's parser) accepts the
package, the styles/numbering references and every relationship in it.
"""

import sys
from collections import Counter

from docx import Document
from docx.table import Table
from docx.text.paragraph import Paragraph


def iter_body(document):
    """Body-level paragraphs and tables, in document order."""
    for child in document.element.body.iterchildren():
        tag = child.tag.rsplit("}", 1)[-1]
        if tag == "p":
            yield Paragraph(child, document)
        elif tag == "tbl":
            yield Table(child, document)


def check(path: str) -> bool:
    try:
        document = Document(path)
    except Exception as exc:  # noqa: BLE001 - report whatever python-docx raises
        print(f"FAIL  {path}: {type(exc).__name__}: {exc}")
        return False

    styles = Counter()
    paragraphs = 0
    list_items = 0
    tables = 0
    pictures = 0
    texts = 0
    for block in iter_body(document):
        if isinstance(block, Table):
            tables += 1
            for row in block.rows:
                for cell in row.cells:
                    texts += len(cell.text.strip()) > 0
                    for paragraph in cell.paragraphs:
                        paragraphs += 1
                        styles[paragraph.style.name] += 1
            continue
        paragraphs += 1
        styles[block.style.name] += 1
        texts += len(block.text.strip()) > 0
        if block._p.find(".//{http://schemas.openxmlformats.org/wordprocessingml/2006/main}numPr") is not None:
            list_items += 1
        pictures += len(block._p.findall(
            ".//{http://schemas.openxmlformats.org/drawingml/2006/main}blip"
        ))
    sections = len(document.sections)
    notes = ", ".join(f"{name}x{count}" for name, count in styles.most_common(4) if count)
    print(
        f"OK    {path}: {paragraphs} paragraph(s), {list_items} list item(s), "
        f"{tables} table(s), {pictures} picture(s), {sections} section(s), "
        f"{texts} with text [{notes}]"
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
