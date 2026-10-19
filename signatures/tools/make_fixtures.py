#!/usr/bin/env python3
"""Generate signed PDF fixtures for the :signatures tests (T24).

This is development-only tooling, like the T13 encrypted-PDF generator. The
verifier itself has no Python/OpenSSL dependency: the generated PDFs and root
certificates are committed under signatures/src/test/resources/signatures/.

Requirements: Python 3 and OpenSSL 3.5 or newer (the expired fixture uses
`x509 -not_before/-not_after`; Git for Windows ships a suitable build). Run from
anywhere:

    python signatures/tools/make_fixtures.py

Private keys are written under build/signature-fixtures/ (gitignored); only
PDFs and public certificates are written to the test resources directory.
"""

from __future__ import annotations

import base64
import hashlib
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "build" / "signature-fixtures"
OUT = ROOT / "signatures" / "src" / "test" / "resources" / "signatures"

CONTENTS_BYTES = 4096  # 8192 hex characters; a 2048-bit CMS is ~1.5 KB
CONTENTS_HEX = CONTENTS_BYTES * 2
BYTE_RANGE_PLACEHOLDER = (
    b"/ByteRange [0 " + b"0" * 10 + b" " + b"0" * 10 + b" " + b"0" * 10 + b"]"
)


def find_openssl() -> str:
    candidates = [
        shutil.which("openssl"),
        r"C:\Program Files\Git\usr\bin\openssl.exe",
        r"C:\Program Files\OpenSSL-Win64\bin\openssl.exe",
        "/usr/bin/openssl",
        "/usr/local/bin/openssl",
    ]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return str(candidate)
    sys.exit("openssl was not found; install it or add it to PATH")


OPENSSL = find_openssl()


def run(*args: str) -> None:
    result = subprocess.run(
        [OPENSSL, *args],
        cwd=WORK,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if result.returncode != 0:
        print(result.stdout, file=sys.stderr)
        sys.exit(f"openssl {args[0]} failed ({result.returncode})")


def cert_pair(root_name: str, leaf_name: str, days: int = 3650,
              not_before: str | None = None, not_after: str | None = None,
              common_name: str | None = None) -> None:
    """Create root and leaf key/cert pairs with predictable file names."""
    common_name = common_name or leaf_name
    root_key = f"{root_name}.key"
    root_pem = f"{root_name}.pem"
    if not Path(WORK, root_pem).exists():
        run(
            "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256",
            "-keyout", root_key, "-out", root_pem, "-days", "7300",
            "-subj", f"/CN={root_name}/O=PDFPaper Test",
            "-addext", "basicConstraints=critical,CA:TRUE",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign",
            "-addext", "subjectKeyIdentifier=hash",
        )
    csr = f"{leaf_name}.csr"
    run(
        "req", "-newkey", "rsa:2048", "-nodes", "-sha256",
        "-keyout", f"{leaf_name}.key", "-out", csr,
        "-subj", f"/CN={common_name}/O=PDFPaper Test",
    )
    serial = Path(WORK, root_pem).with_suffix(".srl")
    if serial.exists():
        serial_arg = ["-CAserial", str(serial.name)]
    else:
        serial_arg = ["-CAcreateserial"]
    args = [
        "x509", "-req", "-in", csr,
        "-CA", root_pem, "-CAkey", root_key, *serial_arg,
        "-sha256", "-out", f"{leaf_name}.pem",
        "-extfile", "leaf.ext",
    ]
    if not_before is not None:
        args += ["-not_before", not_before]
    if not_after is not None:
        args += ["-not_after", not_after]
    else:
        args += ["-days", str(days)]
    run(*args)


def build_base_pdf(sub_filter: str = "adbe.pkcs7.detached", signed: bool = True) -> bytes:
    """A minimal one-page PDF with one signature field (or no field)."""
    content = b"BT /F1 18 Tf 72 760 Td (PDFPaper signature fixture) Tj ET"
    contents = b"<" + b"0" * CONTENTS_HEX + b">"
    objects: list[tuple[int, bytes]] = []

    objects.append((1, b"<< /Type /Catalog /Pages 2 0 R"
                       + (b" /AcroForm << /Fields [7 0 R] /SigFlags 3 >>" if signed else b"")
                       + b" >>"))
    objects.append((2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>"))
    objects.append((3, b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842]"
                       b" /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R"
                       + (b" /Annots [7 0 R]" if signed else b"")
                       + b" >>"))
    objects.append((4, b"<< /Length " + str(len(content)).encode() + b" >>\nstream\n"
                       + content + b"\nendstream"))
    objects.append((5, b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"))
    if signed:
        sig = (
            b"<< /Type /Sig /Filter /Adobe.PPKLite /SubFilter /"
            + sub_filter.encode()
            + b" "
            + BYTE_RANGE_PLACEHOLDER
            + b" /Contents "
            + contents
            + b" /M (D:20261019120000Z) /Name (Alice Example) /Reason (Approved)"
            + b" /Location (PDFPaper) >>"
        )
        objects.append((6, sig))
        objects.append((7, b"<< /Type /Annot /Subtype /Widget /FT /Sig /T (Signature1)"
                           b" /Rect [72 600 320 660] /P 3 0 R /V 6 0 R /F 132 >>"))

    buf = bytearray(b"%PDF-1.7\n%\xE2\xE3\xCF\xD3\n")
    offsets: dict[int, int] = {}
    for number, body in objects:
        offsets[number] = len(buf)
        buf += f"{number} 0 obj\n".encode() + body + b"\nendobj\n"
    xref_offset = len(buf)
    max_object = max(offsets)
    buf += b"xref\n"
    buf += f"0 {max_object + 1}\n".encode()
    buf += b"0000000000 65535 f \n"
    for number in range(1, max_object + 1):
        buf += f"{offsets.get(number, 0):010d} 00000 n \n".encode()
    buf += f"trailer\n<< /Size {max_object + 1} /Root 1 0 R >>\nstartxref\n{xref_offset}\n%%EOF\n".encode()
    return bytes(buf)


def der_length(length: int) -> bytes:
    if length < 0x80:
        return bytes([length])
    if length <= 0xFF:
        return bytes([0x81, length])
    if length <= 0xFFFF:
        return bytes([0x82, (length >> 8) & 0xFF, length & 0xFF])
    return bytes([0x83, (length >> 16) & 0xFF, (length >> 8) & 0xFF, length & 0xFF])


def der(tag: int, content: bytes) -> bytes:
    return bytes([tag]) + der_length(len(content)) + content


def read_tlv(data: bytes, offset: int) -> tuple[int, int, int, int]:
    """Returns (tag, content_start, content_end, end)."""
    if offset >= len(data):
        raise SystemExit("truncated DER")
    tag = data[offset]
    position = offset + 1
    if position >= len(data):
        raise SystemExit("truncated DER length")
    first = data[position]
    position += 1
    if first < 0x80:
        length = first
    else:
        count = first & 0x7F
        length = int.from_bytes(data[position:position + count], "big")
        position += count
    end = position + length
    if end > len(data):
        raise SystemExit("truncated DER value")
    return tag, position, end, end


def pem_der(path: Path) -> bytes:
    body = "".join(
        line for line in path.read_text(encoding="ascii").splitlines()
        if not line.startswith("-----")
    )
    return base64.b64decode(body)


def insert_crl(cms: bytes, crl_der: bytes) -> bytes:
    """Insert a CertificateList into the SignedData crls [1] set.

    The CMS signature covers only the signed attributes, so adding a revocation
    info choice does not change the signature. This is exactly where Acrobat and
    OpenSSL place embedded CRLs; OpenSSL's `cms -sign` just cannot add them.
    """
    tag, start, end, _ = read_tlv(cms, 0)
    if tag != 0x30:
        raise SystemExit("CMS is not a ContentInfo SEQUENCE")
    content_info = cms[start:end]
    tag, _, oid_end, _ = read_tlv(content_info, 0)
    if tag != 0x06:
        raise SystemExit("CMS ContentInfo has no contentType OID")
    tag, sd_start, sd_end, _ = read_tlv(content_info, oid_end)
    if tag != 0xA0:
        raise SystemExit("CMS ContentInfo has no [0] content")
    signed_data = content_info[sd_start:sd_end]
    tag, body_start, body_end, _ = read_tlv(signed_data, 0)
    if tag != 0x30:
        raise SystemExit("CMS content is not a SignedData SEQUENCE")

    body = signed_data[body_start:body_end]
    fields: list[tuple[int, bytes]] = []
    position = 0
    while position < len(body):
        field_tag, _, _, field_end = read_tlv(body, position)
        fields.append((field_tag, body[position:field_end]))
        position = field_end
    if len(fields) < 4 or fields[-1][0] != 0x31:
        raise SystemExit("unexpected SignedData layout")

    rebuilt: list[bytes] = []
    for index, (field_tag, field_bytes) in enumerate(fields):
        if index == len(fields) - 1:
            rebuilt.append(der(0xA1, crl_der))
        rebuilt.append(field_bytes)
    new_signed_data = der(0x30, b"".join(rebuilt))
    new_content = content_info[:oid_end] + der(0xA0, new_signed_data)
    return der(0x30, new_content)


def sign_base_pdf(
    pdf: bytes,
    signer: str,
    key: str | None = None,
    extra: list[str] | None = None,
    crl: Path | None = None,
    sha1_legacy: bool = False,
) -> bytes:
    """Fill ByteRange, sign the two ranges with OpenSSL CMS, fill /Contents."""
    key = key or signer
    buf = bytearray(pdf)
    marker = b"/Contents <"
    position = bytes(buf).find(marker)
    if position < 0:
        raise SystemExit("signature field is missing")
    contents_start = position + len(b"/Contents ")
    contents_end = contents_start + 1 + CONTENTS_HEX + 1
    if bytes(buf[contents_start:contents_start + 1]) != b"<" or bytes(buf[contents_end - 1:contents_end]) != b">":
        raise SystemExit("bad /Contents placeholder")

    length = len(buf)
    b1 = contents_start
    b2 = contents_end
    b3 = length - contents_end
    old = BYTE_RANGE_PLACEHOLDER
    new = f"/ByteRange [0 {b1:010d} {b2:010d} {b3:010d}]".encode()
    if len(old) != len(new):
        raise SystemExit("ByteRange placeholder changed length")
    if bytes(buf).count(old) != 1:
        raise SystemExit("ByteRange placeholder not found exactly once")
    buf = bytearray(bytes(buf).replace(old, new, 1))

    signed_content = bytes(buf[:contents_start]) + bytes(buf[contents_end:])
    if sha1_legacy:
        # adbe.pkcs7.sha1 embeds SHA-1(signed ranges) as the CMS content and
        # signs that digest (ISO 32000-1 §12.8.3.3).
        (WORK / "content.bin").write_bytes(hashlib.sha1(signed_content).digest())
    else:
        (WORK / "content.bin").write_bytes(signed_content)
    signature_path = WORK / "signature.der"
    args = [
        "cms", "-sign", "-binary", "-in", "content.bin",
        "-signer", f"{signer}.pem", "-inkey", f"{key}.key",
        "-outform", "DER", "-out", signature_path.name, "-md", "sha256",
        "-nosmimecap",
    ]
    if sha1_legacy:
        args += ["-nodetach"]
    if extra:
        args += extra
    run(*args)
    signature = signature_path.read_bytes()
    if crl is not None:
        signature = insert_crl(signature, pem_der(WORK / crl))
    if len(signature) * 2 > CONTENTS_HEX:
        raise SystemExit(f"CMS is too large for the placeholder: {len(signature)} bytes")
    hex_signature = signature.hex() + "0" * (CONTENTS_HEX - len(signature) * 2)
    replacement = b"<" + hex_signature.encode() + b">"
    buf[contents_start:contents_end] = replacement
    return bytes(buf)


def append_update(pdf: bytes) -> bytes:
    """Append a genuine one-object incremental update after the signature."""
    text = pdf.decode("latin-1")
    marker = "startxref\n"
    start = text.rfind(marker)
    old_xref = int(text[start + len(marker):].split("\n", 1)[0].strip())
    buf = bytearray(pdf)
    if not buf.endswith(b"\n"):
        buf += b"\n"
    obj_offset = len(buf)
    buf += b"10 0 obj\n<< /Producer (PDFPaper modified after signing) >>\nendobj\n"
    xref_offset = len(buf)
    buf += b"xref\n10 1\n"
    buf += f"{obj_offset:010d} 00000 n \n".encode()
    buf += (
        f"trailer\n<< /Size 11 /Root 1 0 R /Prev {old_xref} /Info 10 0 R >>\n"
        f"startxref\n{xref_offset}\n%%EOF\n"
    ).encode()
    return bytes(buf)


def main() -> None:
    WORK.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    (WORK / "leaf.ext").write_text(
        "basicConstraints=critical,CA:FALSE\n"
        "keyUsage=critical,digitalSignature,nonRepudiation\n"
        "subjectKeyIdentifier=hash\n"
        "authorityKeyIdentifier=keyid,issuer\n",
        encoding="ascii",
    )

    # Remove old keys/serials so a rerun produces a fresh, coherent set.
    for pattern in ("*.key", "*.pem", "*.csr", "*.srl"):
        for path in WORK.glob(pattern):
            path.unlink()

    cert_pair("root", "signer", common_name="Alice Example")
    cert_pair(
        "root", "expired",
        not_before="20200101000000Z",
        not_after="20210101000000Z",
        common_name="Expired Signer",
    )
    cert_pair("other-root", "other-signer", common_name="Mallory")

    # A small CA database for embedded-CRL fixtures. The signer is issued by the
    # same root and then revoked for revoked.pdf.
    shutil.rmtree(WORK / "ca", ignore_errors=True)
    ca = WORK / "ca"
    (ca / "newcerts").mkdir(parents=True)
    (ca / "index.txt").write_text("", encoding="ascii")
    (ca / "serial").write_text("1000\n", encoding="ascii")
    (ca / "crlnumber").write_text("1000\n", encoding="ascii")
    (WORK / "ca.cnf").write_text(
        "[ca]\n"
        "default_ca = CA_default\n"
        "\n"
        "[CA_default]\n"
        "dir = ca\n"
        "database = $dir/index.txt\n"
        "new_certs_dir = $dir/newcerts\n"
        "certificate = root.pem\n"
        "private_key = root.key\n"
        "serial = $dir/serial\n"
        "crlnumber = $dir/crlnumber\n"
        "default_md = sha256\n"
        "default_days = 3650\n"
        "default_crl_days = 3650\n"
        "policy = policy_any\n"
        "unique_subject = no\n"
        "\n"
        "[policy_any]\n"
        "commonName = supplied\n"
        "organizationName = optional\n",
        encoding="ascii",
    )
    run(
        "ca", "-batch", "-config", "ca.cnf", "-in", "signer.csr",
        "-out", "signer-ca.pem", "-extfile", "leaf.ext", "-notext",
    )
    run("ca", "-gencrl", "-config", "ca.cnf", "-out", "crl-good.pem")
    run("ca", "-revoke", "signer-ca.pem", "-config", "ca.cnf")
    run("ca", "-gencrl", "-config", "ca.cnf", "-out", "crl-revoked.pem")

    base = build_base_pdf()
    valid = sign_base_pdf(base, "signer")
    (OUT / "valid.pdf").write_bytes(valid)
    (OUT / "valid-noattr.pdf").write_bytes(sign_base_pdf(base, "signer", extra=["-noattr"]))
    (OUT / "valid-sha1.pdf").write_bytes(
        sign_base_pdf(build_base_pdf(sub_filter="adbe.pkcs7.sha1"), "signer", sha1_legacy=True)
    )
    (OUT / "expired.pdf").write_bytes(sign_base_pdf(base, "expired"))
    (OUT / "untrusted.pdf").write_bytes(sign_base_pdf(base, "other-signer"))
    (OUT / "valid-crl.pdf").write_bytes(
        sign_base_pdf(base, "signer-ca", key="signer", crl=Path("crl-good.pem"))
    )
    (OUT / "revoked.pdf").write_bytes(
        sign_base_pdf(base, "signer-ca", key="signer", crl=Path("crl-revoked.pem"))
    )

    tampered = bytearray(valid)
    text = bytes(tampered)
    marker = b"PDFPaper signature fixture"
    index = text.find(marker)
    if index < 0:
        raise SystemExit("content marker not found")
    tampered[index] = ord("X")
    (OUT / "tampered.pdf").write_bytes(bytes(tampered))

    (OUT / "modified.pdf").write_bytes(append_update(valid))

    (OUT / "unsigned.pdf").write_bytes(build_base_pdf(signed=False))
    (OUT / "unsupported.pdf").write_bytes(build_base_pdf(sub_filter="adbe.x509.rsa_sha1"))
    (OUT / "bad-byterange.pdf").write_bytes(build_base_pdf())

    for name in ("root.pem", "other-root.pem", "signer.pem", "signer-ca.pem",
                 "expired.pem", "other-signer.pem"):
        shutil.copyfile(WORK / name, OUT / name)

    # Basic sanity: the signature placeholder must contain a DER SEQUENCE and
    # the file must still end in %%EOF (OpenSSL indentation must not matter).
    with (OUT / "valid.pdf").open("rb") as handle:
        data = handle.read()
    if b"<30" not in data or not data.rstrip().endswith(b"%%EOF"):
        raise SystemExit("generated valid.pdf looks wrong")

    print(f"fixtures written to {OUT}")
    for path in sorted(OUT.iterdir()):
        print(f"  {path.name:24} {path.stat().st_size:8d} bytes")


if __name__ == "__main__":
    main()
