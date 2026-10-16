#!/usr/bin/env python3
"""Generate the 10 self-made PDFs in /testdata (T3).

All files are hand-built byte-by-byte (no real-world content, no copyrighted
material) to exercise different engine paths: classic xref, xref streams,
incremental updates, rebuild fallbacks, CRLF endings, comments, generations.
Deterministic output: same bytes on every run.
"""
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
EOF_MARKER = b"%%EOF"


def classic(objects, root_ref="1 0 R", offset_shift=0, extra_trailer="",
            eol="\n", header="%PDF-1.4", with_startxref=True):
    out = bytearray()
    out += (header + eol).encode("ascii")
    offsets = {}
    gens = {}
    for num, gen, body in objects:
        offsets[num] = len(out)
        gens[num] = gen
        out += ("%d %d obj" % (num, gen)).encode("ascii") + eol.encode("ascii")
        out += body.encode("ascii") + eol.encode("ascii")
        out += b"endobj" + eol.encode("ascii")
    max_num = max(n for n, _, _ in objects)
    xref_at = len(out)
    out += ("xref" + eol).encode("ascii")
    out += ("0 %d" % (max_num + 1)).encode("ascii") + eol.encode("ascii")
    for i in range(max_num + 1):
        if i in offsets:
            out += ("%010d %05d n " % (offsets[i] + offset_shift, gens[i])).encode("ascii")
        else:
            out += b"0000000000 00000 f "
        out += b"\n"
    out += ("trailer" + eol).encode("ascii")
    out += ("<< /Size %d /Root %s%s >>" % (max_num + 1, root_ref, extra_trailer)).encode("ascii")
    out += eol.encode("ascii")
    if with_startxref:
        out += ("startxref" + eol).encode("ascii")
        out += ("%d" % xref_at).encode("ascii") + eol.encode("ascii")
        out += EOF_MARKER + eol.encode("ascii")
    return bytes(out)


def xref_stream(objects, filtered=False):
    out = bytearray()
    out += b"%PDF-1.4\n"
    offsets = {}
    for num, gen, body in objects:
        offsets[num] = len(out)
        out += ("%d %d obj\n%s\nendobj\n" % (num, gen, body)).encode("ascii")
    max_num = max(n for n, _, _ in objects)
    stream_obj_at = len(out)
    entries = bytearray()
    for i in range(max_num + 1):
        if i in offsets:
            entries += struct.pack(">B", 1) + struct.pack(">I", offsets[i]) + struct.pack(">H", 0)
        else:
            entries += struct.pack(">B", 0) + struct.pack(">I", 0) + struct.pack(">H", 65535)
    data = zlib.compress(bytes(entries)) if filtered else bytes(entries)
    filt = " /Filter /FlateDecode" if filtered else ""
    out += ("%d 0 obj\n<< /Type /XRef /Size %d /Root 1 0 R /W [1 4 2]%s /Length %d >>\nstream\n"
            % (max_num + 1, max_num + 1, filt, len(data))).encode("ascii")
    out += data
    out += b"\nendstream\nendobj\n"
    out += ("startxref\n%d\n" % stream_obj_at).encode("ascii") + EOF_MARKER + b"\n"
    return bytes(out)


CATALOG = "<< /Type /Catalog /Pages 2 0 R >>"
PAGES1 = "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
PAGE = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>"


def main():
    files = {}

    # 1. Minimal valid file, classic xref.
    files["valid-classic.pdf"] = classic([(1, 0, CATALOG), (2, 0, PAGES1), (3, 0, PAGE)])

    # 2. Three pages, classic xref.
    pages3 = "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>"
    files["valid-multi-page.pdf"] = classic([
        (1, 0, CATALOG),
        (2, 0, pages3),
        (3, 0, PAGE),
        (4, 0, PAGE),
        (5, 0, PAGE),
    ])

    # 3. Xref stream (unfiltered).
    files["valid-xref-stream.pdf"] = xref_stream([(1, 0, CATALOG), (2, 0, PAGES1), (3, 0, PAGE)])

    # 4. Incremental update: base + revised page object + new xref with /Prev.
    base = classic([(1, 0, CATALOG), (2, 0, PAGES1), (3, 0, PAGE)])
    base_xref = int(base.decode("ascii").split("startxref\n")[1].split("\n")[0])
    new_page = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>"
    upd = bytearray()
    new_off = len(base) + len(upd)
    upd += ("3 0 obj\n%s\nendobj\n" % new_page).encode("ascii")
    upd_xref = len(base) + len(upd)
    upd += b"xref\n3 1\n"
    upd += ("%010d 00000 n \n" % new_off).encode("ascii")
    upd += ("trailer\n<< /Size 4 /Root 1 0 R /Prev %d >>\n" % base_xref).encode("ascii")
    upd += ("startxref\n%d\n" % upd_xref).encode("ascii") + EOF_MARKER + b"\n"
    files["incremental-update.pdf"] = base + bytes(upd)

    # 5. Broken xref offsets -> engine must rebuild by scanning.
    files["rebuilt-bad-xref.pdf"] = classic(
        [(1, 0, CATALOG), (2, 0, PAGES1), (3, 0, PAGE)], offset_shift=11)

    # 6. Missing startxref (truncated tail) -> engine must rebuild by scanning.
    full = classic([(1, 0, CATALOG), (2, 0, PAGES1), (3, 0, PAGE)])
    files["rebuilt-no-startxref.pdf"] = full[:full.index(b"startxref") - 5]

    # 7. Non-zero generation number.
    files["gen-nonzero.pdf"] = classic([
        (1, 0, CATALOG),
        (2, 1, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
        (3, 0, PAGE),
    ])

    # 8. CRLF line endings throughout.
    files["crlf-endings.pdf"] = classic(
        [(1, 0, CATALOG), (2, 0, PAGES1), (3, 0, PAGE)], eol="\r\n")

    # 9. Leading comment line before the header.
    files["leading-comment.pdf"] = classic(
        [(1, 0, CATALOG), (2, 0, PAGES1), (3, 0, PAGE)],
        header="% Self-made PDFPaper test file\n%PDF-1.4")

    # 10. Flate-compressed xref stream: undecodable until T5, must still open
    # via the rebuild fallback.
    files["rebuilt-filtered-xref-stream.pdf"] = xref_stream(
        [(1, 0, CATALOG), (2, 0, PAGES1), (3, 0, PAGE)], filtered=True)

    for name in sorted(files):
        path = os.path.join(HERE, name)
        with open(path, "wb") as f:
            f.write(files[name])
        print("wrote %s (%d bytes)" % (name, len(files[name])))


if __name__ == "__main__":
    main()
