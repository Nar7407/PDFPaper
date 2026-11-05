"""T62 acceptance fixtures: a yellowed scan, a blueprint sheet and a textbook.

Writes /tmp/t62-scan.pdf, /tmp/t62-blueprint.pdf and /tmp/t62-textbook.pdf.

The three documents are what the look suggestions and the per-document look are
judged on: the scan is warm paper with no text layer, the blueprint is line art
on white, and the textbook is ordinary text with a grey figure block and a
coloured heading. Nothing in them is unusual for a PDF reader; the look is
applied to the rendered page only, so the files themselves never change.
"""
import pymupdf

A4 = pymupdf.paper_rect("a4")
WARM_PAPER = (0.96, 0.93, 0.80)
BLUEPRINT_INK = (0.05, 0.12, 0.30)


def page(doc):
    return doc.new_page(width=A4.width, height=A4.height)


def text(p, x, y, s, size=11, bold=False, colour=(0, 0, 0)):
    p.insert_text(
        (x, y),
        s,
        fontsize=size,
        fontname="hebo" if bold else "helv",
        color=colour,
    )


def scan(path):
    """A page of an old book, photographed: warm paper, no text layer."""
    doc = pymupdf.open()
    for number in range(1, 4):
        p = page(doc)
        p.draw_rect(A4, color=None, fill=WARM_PAPER)
        # Slightly uneven edges, the way a scan of a page looks.
        p.draw_rect(pymupdf.Rect(0, 0, 6, A4.height), color=None, fill=(0.90, 0.86, 0.73))
        p.draw_rect(pymupdf.Rect(A4.width - 5, 0, A4.width, A4.height), color=None,
                    fill=(0.90, 0.86, 0.73))
        text(p, 70, 96, f"Chapter {number}. The Old Mill", 17, True, (0.14, 0.11, 0.08))
        y = 130
        while y < A4.height - 80:
            text(
                p,
                70,
                y,
                "The mill stood at the bend of the river, and the wheel had not "
                "turned for thirty years.",
                10.5,
                colour=(0.16, 0.13, 0.10),
            )
            y += 17
        text(p, 70, A4.height - 50, str(number), 10, colour=(0.20, 0.17, 0.13))
    doc.save(path)
    doc.close()


def blueprint(path):
    """A CAD-style sheet: white paper, long rules, small labels."""
    doc = pymupdf.open()
    p = page(doc)
    for y in (90, 250, 470, 690):
        p.draw_line((40, y), (A4.width - 40, y), color=BLUEPRINT_INK, width=1.6)
    for x in (90, 300, 505):
        p.draw_line((x, 60), (x, A4.height - 40), color=BLUEPRINT_INK, width=1.6)
    p.draw_rect(pymupdf.Rect(120, 300, 430, 430), color=BLUEPRINT_INK, width=1.4)
    p.draw_circle((275, 365), 46, color=BLUEPRINT_INK, width=1.2)
    text(p, 60, 80, "SHEET 2 - FLOOR PLAN", 13, True, BLUEPRINT_INK)
    text(p, 60, A4.height - 24, "SCALE 1:50", 9, colour=BLUEPRINT_INK)
    text(p, 140, 310, "A", 9, colour=BLUEPRINT_INK)
    text(p, 445, 440, "B", 9, colour=BLUEPRINT_INK)
    doc.save(path)
    doc.close()


def textbook(path):
    """A modern textbook page: text, a coloured heading and a grey figure."""
    doc = pymupdf.open()
    doc_inks = (0.10, 0.16, 0.45)
    for number in (1, 2):
        p = page(doc)
        text(p, 64, 84, f"4.{number}  Energy in a closed system", 18, True, doc_inks)
        y = 118
        while y < 300:
            text(
                p,
                64,
                y,
                "Energy is conserved: it changes form but is never created or "
                "destroyed inside a closed system.",
                11,
            )
            y += 16
        p.draw_rect(pymupdf.Rect(64, 330, A4.width - 64, 520), color=None,
                    fill=(0.55, 0.55, 0.55))
        text(p, 84, 360, "Figure 4.1  A falling mass", 10, colour=(0.95, 0.95, 0.95))
        y = 560
        while y < A4.height - 60:
            text(p, 64, y, "The total energy of the system stays constant.", 11)
            y += 16
        text(p, A4.width / 2, A4.height - 40, str(number + 40), 10)
    doc.save(path)
    doc.close()


if __name__ == "__main__":
    scan("/tmp/t62-scan.pdf")
    blueprint("/tmp/t62-blueprint.pdf")
    textbook("/tmp/t62-textbook.pdf")
    print("wrote /tmp/t62-scan.pdf, /tmp/t62-blueprint.pdf, /tmp/t62-textbook.pdf")
