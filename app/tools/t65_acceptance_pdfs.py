"""T65 acceptance fixtures: an invoice with one of each example and a wrapped sentence.

Writes build/t65-acceptance/t65-invoice.pdf and t65-report.pdf.

The invoice carries every RegexExamples pattern on exactly three of its four
pages, so a document-scope run can be checked against a known page set. The
report puts one sentence across two lines, which is what ACROSS_LINES finds and
--lines must not.

Run from the repo root:

    python app/tools/t65_acceptance_pdfs.py
"""
from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

OUT = Path(__file__).resolve().parents[2] / "build" / "t65-acceptance"

# Each tuple is (label, value). The page sets are fixed by the acceptance
# checklist: every example must be findable on exactly three pages.
PAGES = [
    [
        ("Invoice", "AB-1234"),
        ("Reference", "INV-00042"),
        ("Date", "2026-10-31"),
        ("Email", "reader@example.com"),
        ("Link", "https://example.com/inv/9"),
        ("Host", "192.168.0.1"),
        ("Phone", "+1 555 010 0042"),
    ],
    [
        ("Invoice", "AB-1234"),
        ("Reference", "INV-00042"),
        ("Date", "2026-10-31"),
        ("Phone", "+1 555 010 0042"),
    ],
    [
        ("Invoice", "AB-1234"),
        ("Email", "reader@example.com"),
        ("Link", "https://example.com/inv/9"),
        ("Host", "192.168.0.1"),
    ],
    [
        ("Reference", "INV-00042"),
        ("Date", "2026-10-31"),
        ("Email", "reader@example.com"),
        ("Link", "https://example.com/inv/9"),
        ("Host", "192.168.0.1"),
        ("Phone", "+1 555 010 0042"),
    ],
]


def invoice(path: Path) -> None:
    pdf = canvas.Canvas(str(path), pagesize=A4)
    width, height = A4
    for number, rows in enumerate(PAGES, start=1):
        pdf.setFont("Helvetica-Bold", 18)
        pdf.drawString(64, height - 80, f"Invoice sheet {number}")
        pdf.setFont("Helvetica", 12)
        y = height - 130
        for label, value in rows:
            pdf.drawString(64, y, f"{label}:")
            pdf.drawString(180, y, value)
            y -= 28
        pdf.setFont("Helvetica-Oblique", 10)
        pdf.drawString(64, 70, "T65 acceptance fixture. No real customer data.")
        pdf.setFont("Helvetica", 10)
        pdf.drawRightString(width - 64, 70, f"page {number}")
        pdf.showPage()
    pdf.save()


def report(path: Path) -> None:
    pdf = canvas.Canvas(str(path), pagesize=A4)
    height = A4[1]
    pdf.setFont("Helvetica-Bold", 16)
    pdf.drawString(64, height - 90, "Quarterly statement")
    pdf.setFont("Helvetica", 12)
    # The sentence deliberately crosses a line break. ACROSS_LINES finds it as
    # one match with two boxes; a per-line run finds neither half.
    pdf.drawString(64, height - 150, "Please note that the total due is")
    pdf.drawString(64, height - 168, "142.50 EUR for the quarter.")
    pdf.drawString(64, height - 220, "Payment is due on 2026-11-30.")
    pdf.save()


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    invoice(OUT / "t65-invoice.pdf")
    report(OUT / "t65-report.pdf")
    print(f"wrote {OUT / 't65-invoice.pdf'}")
    print(f"wrote {OUT / 't65-report.pdf'}")
