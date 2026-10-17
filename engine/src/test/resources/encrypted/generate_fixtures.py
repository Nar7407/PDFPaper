#!/usr/bin/env python3
"""Generate the encrypted test fixtures used by T13 (ISO 32000-1, section 7.6).

This script is deliberately independent of the Kotlin engine: it implements the
standard security handler from scratch (including a pure-Python AES) so the
engine's reader is checked against a second implementation. The AES core is
self-tested against the FIPS-197 / NIST SP 800-38A vectors before any PDF is
written; if that self-test fails the script exits without producing files.

Passwords are the same for every file: user "user", owner "owner".
Output (next to this script):
    r2-rc4-40.pdf     R2  V1  RC4 40-bit
    r3-rc4-128.pdf    R3  V2  RC4 128-bit
    r4-rc4.pdf        R4  V4  crypt filter /V2   (RC4 128-bit)
    r4-aes128.pdf     R4  V4  crypt filter /AESV2 (AES-128-CBC)
    r5-aes256.pdf     R5  V5  /AESV3 (AES-256-CBC)
    r6-aes256.pdf     R6  V5  /AESV3 (AES-256-CBC, hardened hash)
Deterministic: the same bytes are produced on every run.
"""
import hashlib
import os
import random
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

USER_PW = b"user"
OWNER_PW = b"owner"
# Access permissions (§7.6.3.2, Table 22): print + copy allowed, everything
# else denied, reserved bits 7/8 set. Two's-complement 32-bit value.
PERMISSIONS = -7980  # 0xFFFFE0D4
FILE_ID = bytes.fromhex("00112233445566778899aabbccddeeff")
PLAIN_TEXT = b"BT /F1 24 Tf 30 60 Td (Hello Encrypted World) Tj ET"
PLAIN_TITLE = b"Top Secret Title"

PADDING = bytes.fromhex("28bf4e5e4e758a4164004e56fffa01082e2e00b6d0683e802f0ca9fe6453697a")
SALT = b"sAlT"

rng = random.Random(0xC0FFEE)


# --------------------------------------------------------------------------- #
# Pure-Python AES (ECB core + CBC), generated S-box, self-tested below.
# --------------------------------------------------------------------------- #
def _xtime(a: int) -> int:
    a <<= 1
    if a & 0x100:
        a ^= 0x11B
    return a & 0xFF


def _gmul(a: int, b: int) -> int:
    r = 0
    while b:
        if b & 1:
            r ^= a
        a = _xtime(a)
        b >>= 1
    return r


def _make_sbox():
    inv = [0] * 256
    for i in range(1, 256):
        for j in range(1, 256):
            if _gmul(i, j) == 1:
                inv[i] = j
                break
    sbox = [0] * 256
    for i in range(256):
        x = inv[i]
        s = x
        for _ in range(4):
            x = ((x << 1) | (x >> 7)) & 0xFF
            s ^= x
        sbox[i] = s ^ 0x63
    return sbox


SBOX = _make_sbox()
INV_SBOX = [0] * 256
for _i, _v in enumerate(SBOX):
    INV_SBOX[_v] = _i


def _expand_key(key: bytes):
    nk = len(key) // 4
    nr = nk + 6
    w = [list(key[4 * i:4 * i + 4]) for i in range(nk)]
    rcon = 1
    for i in range(nk, 4 * (nr + 1)):
        temp = list(w[i - 1])
        if i % nk == 0:
            temp = temp[1:] + temp[:1]
            temp = [SBOX[b] for b in temp]
            temp[0] ^= rcon
            rcon = _xtime(rcon)
        elif nk > 6 and i % nk == 4:
            temp = [SBOX[b] for b in temp]
        w.append([w[i - nk][j] ^ temp[j] for j in range(4)])
    return w, nr


def _add_round_key(state, w, rnd):
    for c in range(4):
        for r in range(4):
            state[r][c] ^= w[rnd * 4 + c][r]


def _mix_columns(state):
    for c in range(4):
        a = [state[r][c] for r in range(4)]
        for r in range(4):
            state[r][c] = _gmul(a[r], 2) ^ _gmul(a[(r + 1) % 4], 3) ^ a[(r + 2) % 4] ^ a[(r + 3) % 4]


def _inv_mix_columns(state):
    for c in range(4):
        a = [state[r][c] for r in range(4)]
        for r in range(4):
            state[r][c] = (_gmul(a[r], 14) ^ _gmul(a[(r + 1) % 4], 11)
                           ^ _gmul(a[(r + 2) % 4], 13) ^ _gmul(a[(r + 3) % 4], 9))


def _to_state(block):
    return [[block[r + 4 * c] for c in range(4)] for r in range(4)]


def _from_state(state):
    return bytes(state[r][c] for c in range(4) for r in range(4))


def encrypt_block(block: bytes, w, nr: int) -> bytes:
    state = _to_state(block)
    _add_round_key(state, w, 0)
    for rnd in range(1, nr):
        for r in range(4):
            for c in range(4):
                state[r][c] = SBOX[state[r][c]]
        for r in range(1, 4):
            state[r] = state[r][r:] + state[r][:r]
        _mix_columns(state)
        _add_round_key(state, w, rnd)
    for r in range(4):
        for c in range(4):
            state[r][c] = SBOX[state[r][c]]
    for r in range(1, 4):
        state[r] = state[r][r:] + state[r][:r]
    _add_round_key(state, w, nr)
    return _from_state(state)


def decrypt_block(block: bytes, w, nr: int) -> bytes:
    state = _to_state(block)
    _add_round_key(state, w, nr)
    for rnd in range(nr - 1, 0, -1):
        for r in range(1, 4):
            state[r] = state[r][-r:] + state[r][:-r]
        for r in range(4):
            for c in range(4):
                state[r][c] = INV_SBOX[state[r][c]]
        _add_round_key(state, w, rnd)
        _inv_mix_columns(state)
    for r in range(1, 4):
        state[r] = state[r][-r:] + state[r][:-r]
    for r in range(4):
        for c in range(4):
            state[r][c] = INV_SBOX[state[r][c]]
    _add_round_key(state, w, 0)
    return _from_state(state)


def cbc_encrypt(key, iv, data):
    w, nr = _expand_key(key)
    out = bytearray()
    prev = iv
    for i in range(0, len(data), 16):
        block = bytes(x ^ y for x, y in zip(data[i:i + 16], prev))
        enc = encrypt_block(block, w, nr)
        out += enc
        prev = enc
    return bytes(out)


def cbc_decrypt(key, iv, data):
    w, nr = _expand_key(key)
    out = bytearray()
    prev = iv
    for i in range(0, len(data), 16):
        block = data[i:i + 16]
        dec = decrypt_block(block, w, nr)
        out += bytes(x ^ y for x, y in zip(dec, prev))
        prev = block
    return bytes(out)


def ecb_encrypt(key, data):
    w, nr = _expand_key(key)
    return b"".join(encrypt_block(data[i:i + 16], w, nr) for i in range(0, len(data), 16))


def _self_test():
    # FIPS-197 Appendix C known-answer tests.
    w, nr = _expand_key(bytes.fromhex("000102030405060708090a0b0c0d0e0f"))
    assert encrypt_block(bytes.fromhex("00112233445566778899aabbccddeeff"), w, nr) == \
        bytes.fromhex("69c4e0d86a7b0430d8cdb78070b4c55a"), "AES-128 vector failed"
    w, nr = _expand_key(bytes.fromhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"))
    ct = encrypt_block(bytes.fromhex("00112233445566778899aabbccddeeff"), w, nr)
    assert ct == bytes.fromhex("8ea2b7ca516745bfeafc49904b496089"), "AES-256 vector failed"
    assert decrypt_block(ct, w, nr) == bytes.fromhex("00112233445566778899aabbccddeeff"), "AES inverse failed"
    # NIST SP 800-38A AES-128-CBC / AES-256-CBC vectors (first two blocks).
    key = bytes.fromhex("2b7e151628aed2a6abf7158809cf4f3c")
    iv = bytes.fromhex("000102030405060708090a0b0c0d0e0f")
    pt = bytes.fromhex("6bc1bee22e409f96e93d7e117393172a" "ae2d8a571e03ac9c9eb76fac45af8e51")
    assert cbc_encrypt(key, iv, pt) == \
        bytes.fromhex("7649abac8119b246cee98e9b12e9197d" "5086cb9b507219ee95db113a917678b2"), "CBC-128 failed"
    key = bytes.fromhex("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4")
    assert cbc_encrypt(key, iv, pt) == \
        bytes.fromhex("f58c4c04d6e5f1ba779eabfb5f7bfbd6" "9cfc4e967edb808d679f777bc6702c7d"), "CBC-256 failed"
    assert cbc_decrypt(key, iv, cbc_encrypt(key, iv, pt)) == pt, "CBC inverse failed"


# --------------------------------------------------------------------------- #
# Standard security handler (section 7.6.3).
# --------------------------------------------------------------------------- #
def md5(*parts):
    h = hashlib.md5()
    for p in parts:
        h.update(p)
    return h.digest()


def pad_password(pw: bytes) -> bytes:
    body = pw[:32]
    return body + PADDING[:32 - len(body)]


def xor_byte(key: bytes, value: int) -> bytes:
    return bytes(b ^ value for b in key)


def rc4(key: bytes, data: bytes) -> bytes:
    s = list(range(256))
    j = 0
    for i in range(256):
        j = (j + s[i] + key[i % len(key)]) & 0xFF
        s[i], s[j] = s[j], s[i]
    out = bytearray()
    i = 0
    j = 0
    for byte in data:
        i = (i + 1) & 0xFF
        j = (j + s[i]) & 0xFF
        s[i], s[j] = s[j], s[i]
        out.append(byte ^ s[(s[i] + s[j]) & 0xFF])
    return bytes(out)


def owner_password_key(owner_pw: bytes, n: int, r: int) -> bytes:
    h = md5(pad_password(owner_pw))
    if r >= 3:
        for _ in range(50):
            h = md5(h)
    return h[:n]


def compute_o(owner_pw: bytes, user_pw: bytes, n: int, r: int) -> bytes:
    key = owner_password_key(owner_pw, n, r)
    o = rc4(key, pad_password(user_pw))
    if r >= 3:
        for i in range(1, 20):
            o = rc4(xor_byte(key, i), o)
    return o


def file_key_r234(user_pw: bytes, o: bytes, p: int, fid: bytes, r: int, n: int) -> bytes:
    data = pad_password(user_pw) + o + struct.pack("<i", p) + fid
    h = md5(data)
    if r >= 3:
        for _ in range(50):
            h = md5(h[:n])
    return h[:n]


def compute_u(file_key: bytes, fid: bytes, n: int, r: int) -> bytes:
    if r == 2:
        return rc4(file_key, pad_password(b""))
    u = rc4(file_key, md5(PADDING + fid))
    for i in range(1, 20):
        u = rc4(xor_byte(file_key, i), u)
    return u + bytes(range(16))  # 16 arbitrary padding bytes


def object_key(file_key: bytes, num: int, gen: int, aes: bool) -> bytes:
    data = file_key + struct.pack("<I", num)[:3] + struct.pack("<H", gen)
    if aes:
        data += SALT
    return md5(data)[:min(len(file_key) + 5, 16)]


def hash2b(password: bytes, salt: bytes, u: bytes) -> bytes:
    k = hashlib.sha256(password + salt + u).digest()
    rnd = 0
    while True:
        block = (password + k + u) * 64
        e = cbc_encrypt(k[:16], k[16:32], block)
        mod = sum(e[:16]) % 3
        k = [hashlib.sha256, hashlib.sha384, hashlib.sha512][mod](e).digest()
        rnd += 1
        if rnd >= 64 and e[-1] <= rnd - 32:
            break
    return k[:32]


def hexstring(data: bytes) -> bytes:
    return b"<" + data.hex().encode("ascii") + b">"


class Encrypter:
    """Encrypts strings/streams for one revision."""

    def __init__(self, revision, version, n, file_key, string_method, stream_method):
        self.revision = revision
        self.version = version
        self.n = n
        self.file_key = file_key
        self.string_method = string_method
        self.stream_method = stream_method

    def value(self, num, gen, method, plain: bytes) -> bytes:
        if method == "rc4":
            return rc4(object_key(self.file_key, num, gen, False), plain)
        if method == "aes128":
            key = object_key(self.file_key, num, gen, True)
            return self._aes_value(key, plain)
        if method == "aes256":
            return self._aes_value(self.file_key, plain)
        raise ValueError(method)

    def _aes_value(self, key, plain: bytes) -> bytes:
        iv = rng.randbytes(16)
        pad = 16 - (len(plain) % 16)
        return iv + cbc_encrypt(key, iv, plain + bytes([pad]) * pad)


class PdfBuilder:
    def __init__(self, version=b"1.7"):
        self.buf = bytearray()
        self.buf += b"%PDF-" + version + b"\n%\xe2\xe3\xcf\xd3\n"
        self.offsets = {}

    def add(self, num: int, body: bytes):
        self.offsets[num] = len(self.buf)
        self.buf += b"%d 0 obj\n" % num + body + b"\nendobj\n"

    def finish(self, size: int, extra_trailer: bytes) -> bytes:
        xref_at = len(self.buf)
        self.buf += b"xref\n0 %d\n" % size
        for i in range(size):
            if i in self.offsets:
                self.buf += b"%010d 00000 n \n" % self.offsets[i]
            else:
                self.buf += b"0000000000 00000 f \n"
        self.buf += b"trailer\n<< /Size %d /Root 1 0 R /Info 6 0 R /Encrypt 7 0 R" % size
        hex_id = FILE_ID.hex().encode("ascii")
        self.buf += b" /ID [<" + hex_id + b"><" + hex_id + b">]"
        self.buf += extra_trailer
        self.buf += b" >>\nstartxref\n%d\n%%%%EOF\n" % xref_at
        return bytes(self.buf)


def build_pdf(enc: Encrypter, encrypt_dict: bytes, encrypt_metadata: bool) -> bytes:
    contents = enc.value(4, 0, enc.stream_method, PLAIN_TEXT)
    title = enc.value(6, 0, enc.string_method, PLAIN_TITLE)

    b = PdfBuilder()
    b.add(1, b"<< /Type /Catalog /Pages 2 0 R >>")
    b.add(2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    b.add(3, b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 120] "
             b"/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>")
    b.add(4, b"<< /Length %d >>\nstream\n" % len(contents) + contents + b"\nendstream")
    b.add(5, b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    b.add(6, b"<< /Title " + hexstring(title) + b" /Producer (PDFPaper) >>")
    b.add(7, encrypt_dict)
    return b.finish(8, b"")


def standard_dict(entries: bytes) -> bytes:
    return b"<< /Filter /Standard " + entries + b" /P %d >>" % PERMISSIONS


def make_r2():
    n, r, v = 5, 2, 1
    o = compute_o(OWNER_PW, USER_PW, n, r)
    fk = file_key_r234(USER_PW, o, PERMISSIONS, FILE_ID, r, n)
    u = compute_u(fk, FILE_ID, n, r)
    enc = Encrypter(r, v, n, fk, "rc4", "rc4")
    d = standard_dict(b"/V 1 /R 2 /O " + hexstring(o) + b" /U " + hexstring(u) + b" /Length 40")
    return build_pdf(enc, d, True)


def make_r3():
    n, r, v = 16, 3, 2
    o = compute_o(OWNER_PW, USER_PW, n, r)
    fk = file_key_r234(USER_PW, o, PERMISSIONS, FILE_ID, r, n)
    u = compute_u(fk, FILE_ID, n, r)
    enc = Encrypter(r, v, n, fk, "rc4", "rc4")
    d = standard_dict(b"/V 2 /R 3 /O " + hexstring(o) + b" /U " + hexstring(u) + b" /Length 128")
    return build_pdf(enc, d, True)


def make_r4(cfm: str):
    n, r, v = 16, 4, 4
    o = compute_o(OWNER_PW, USER_PW, n, r)
    fk = file_key_r234(USER_PW, o, PERMISSIONS, FILE_ID, r, n)
    u = compute_u(fk, FILE_ID, n, r)
    method = "rc4" if cfm == "V2" else "aes128"
    enc = Encrypter(r, v, n, fk, method, method)
    d = standard_dict(
        b"/V 4 /R 4 /O " + hexstring(o) + b" /U " + hexstring(u) + b" /Length 128"
        b" /CF << /StdCF << /CFM /" + cfm.encode() + b" /Length 16 >> >>"
        b" /StmF /StdCF /StrF /StdCF /EncryptMetadata true"
    )
    return build_pdf(enc, d, True)


def make_r5_r6(revision: int):
    v, n = 5, 32
    fk = rng.randbytes(32)
    us, uk = rng.randbytes(8), rng.randbytes(8)
    os_, ok = rng.randbytes(8), rng.randbytes(8)
    if revision == 5:
        u = hashlib.sha256(USER_PW + us).digest() + us + uk
        o = hashlib.sha256(OWNER_PW + os_ + u).digest() + os_ + ok
        ue = cbc_encrypt(hashlib.sha256(USER_PW + uk).digest(), bytes(16), fk)
        oe = cbc_encrypt(hashlib.sha256(OWNER_PW + ok + u).digest(), bytes(16), fk)
    else:
        u = hash2b(USER_PW, us, b"") + us + uk
        o = hash2b(OWNER_PW, os_, u) + os_ + ok
        ue = cbc_encrypt(hash2b(USER_PW, uk, b""), bytes(16), fk)
        oe = cbc_encrypt(hash2b(OWNER_PW, ok, u), bytes(16), fk)
    perms_plain = struct.pack("<i", PERMISSIONS) + b"\xff\xff\xff\xff" + b"T" + b"adb" + rng.randbytes(4)
    perms = ecb_encrypt(fk, perms_plain)
    enc = Encrypter(revision, v, n, fk, "aes256", "aes256")
    d = standard_dict(
        b"/V 5 /R " + str(revision).encode() + b" /Length 256 /O " + hexstring(o)
        + b" /U " + hexstring(u) + b" /OE " + hexstring(oe) + b" /UE " + hexstring(ue)
        + b" /Perms " + hexstring(perms)
        + b" /CF << /StdCF << /CFM /AESV3 /Length 32 >> >> /StmF /StdCF /StrF /StdCF /EncryptMetadata true"
    )
    return build_pdf(enc, d, True)


def main():
    _self_test()
    outputs = {
        "r2-rc4-40.pdf": make_r2(),
        "r3-rc4-128.pdf": make_r3(),
        "r4-rc4.pdf": make_r4("V2"),
        "r4-aes128.pdf": make_r4("AESV2"),
        "r5-aes256.pdf": make_r5_r6(5),
        "r6-aes256.pdf": make_r5_r6(6),
    }
    for name, data in outputs.items():
        path = os.path.join(HERE, name)
        with open(path, "wb") as handle:
            handle.write(data)
        print("wrote %-16s %5d bytes" % (name, len(data)))


if __name__ == "__main__":
    try:
        main()
    except AssertionError as error:
        print("self-test failed:", error, file=sys.stderr)
        sys.exit(1)
