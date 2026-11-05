package com.pdfpaper.engine.security

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfBoolean
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.model.parseDirectObject
import com.pdfpaper.engine.xref.PdfFile
import java.nio.charset.StandardCharsets

/**
 * The standard security handler (ISO 32000-1, §7.6.3): it derives the file
 * encryption key from a password and decrypts every string and stream.
 *
 * Supported revisions (the `/R` entry):
 * - 2 and 3: RC4 with a 40- or 128-bit key (Algorithm 2/4/5).
 * - 4: crypt filters — RC4 or AES-128 (AESV2), §7.6.3.4.
 * - 5 and 6: AES-256 (AESV3); revision 6 uses the hardened hash of Algorithm
 *   2.B from the PDF 2.0 / Adobe Extension Level 3 update to §7.6.3.3.
 *
 * Both the user and the owner password are accepted; [ownerAuthenticated] says
 * which one matched. Public-key handlers (`/Filter` other than `/Standard`) are
 * rejected with [PdfPasswordException] rather than mis-decrypted.
 *
 * The handler is created by [create] *before* any object is read, so the
 * encryption dictionary itself (which is never encrypted) can be resolved while
 * the document is still in plaintext.
 */
class StandardSecurityHandler private constructor(
    /** Object number of the `/Encrypt` dictionary; it is never encrypted. */
    val encryptObjNum: Int,
    /** `/V` — the algorithm version. */
    val version: Int,
    /** `/R` — the revision. */
    val revision: Int,
    /** Length of the file encryption key in bytes (`n`). */
    val keyLengthBytes: Int,
    /** `/P` access permissions (§7.6.3.2, Table 22). */
    val permissions: PdfPermissions,
    /** `/EncryptMetadata`; when false the XMP metadata stream stays plaintext. */
    val encryptMetadata: Boolean,
    /** True when the supplied password matched the owner entry. */
    val ownerAuthenticated: Boolean,
    /** True when the supplied password matched the user entry. */
    val userAuthenticated: Boolean,
    private val fileKey: ByteArray,
    private val stringMethod: CryptMethod,
    private val streamMethod: CryptMethod
) {

    /**
     * Recursively decrypts [obj] in place (structurally — the model is
     * immutable): strings are decrypted with the entry's method, stream data
     * with the stream method. Only called for objects stored uncompressed;
     * objects inside an object stream are already plaintext because the
     * containing stream was decrypted as a whole (§7.5.7).
     */
    fun decrypt(obj: PdfObject, objNum: Int, gen: Int): PdfObject = when (obj) {
        is PdfString -> PdfString(applyString(obj.bytes, objNum, gen))
        is PdfStream -> {
            val metadata = (obj.dict["Type"] as? PdfName)?.name == "Metadata"
            if (metadata && !encryptMetadata) obj
            else PdfStream(decryptDict(obj.dict, objNum, gen), applyStream(obj.data, objNum, gen))
        }
        is PdfDict -> decryptDict(obj, objNum, gen)
        is PdfArray -> PdfArray(obj.items.map { decrypt(it, objNum, gen) })
        else -> obj
    }

    private fun decryptDict(dict: PdfDict, objNum: Int, gen: Int): PdfDict {
        if (dict.entries.isEmpty()) return dict
        val mapped = LinkedHashMap<PdfName, PdfObject>(dict.entries.size)
        for ((name, value) in dict.entries) mapped[name] = decrypt(value, objNum, gen)
        return PdfDict(mapped)
    }

    private fun applyString(data: ByteArray, objNum: Int, gen: Int): ByteArray = when (stringMethod) {
        CryptMethod.IDENTITY -> data
        CryptMethod.RC4 -> Rc4.apply(objectKey(objNum, gen, aes = false), data)
        CryptMethod.AESV2 -> AesCbc.decrypt(objectKey(objNum, gen, aes = true), data)
        CryptMethod.AESV3 -> AesCbc.decrypt(fileKey, data)
    }

    private fun applyStream(data: ByteArray, objNum: Int, gen: Int): ByteArray = when (streamMethod) {
        CryptMethod.IDENTITY -> data
        CryptMethod.RC4 -> Rc4.apply(objectKey(objNum, gen, aes = false), data)
        CryptMethod.AESV2 -> AesCbc.decrypt(objectKey(objNum, gen, aes = true), data)
        CryptMethod.AESV3 -> AesCbc.decrypt(fileKey, data)
    }

    /**
     * Per-object key for revisions 2–4 (§7.6.3.4, Algorithm 1): the file key
     * followed by the low 3 bytes of the object number, the low 2 bytes of the
     * generation, and (for AESV2 only) the four bytes "sAlT", hashed with MD5
     * and truncated to min(n + 5, 16) bytes. AESV3 (revision 5/6) has no
     * per-object key; the file key is used directly.
     */
    private fun objectKey(objNum: Int, gen: Int, aes: Boolean): ByteArray {
        if (revision >= 5) return fileKey
        val salt = if (aes) SALT else EMPTY
        val input = ByteArray(fileKey.size + 5 + salt.size)
        System.arraycopy(fileKey, 0, input, 0, fileKey.size)
        var p = fileKey.size
        input[p++] = (objNum and 0xFF).toByte()
        input[p++] = ((objNum ushr 8) and 0xFF).toByte()
        input[p++] = ((objNum ushr 16) and 0xFF).toByte()
        input[p++] = (gen and 0xFF).toByte()
        input[p++] = ((gen ushr 8) and 0xFF).toByte()
        System.arraycopy(salt, 0, input, p, salt.size)
        return Digest.md5(input).copyOf(minOf(fileKey.size + 5, 16))
    }

    override fun toString(): String {
        val methods = if (stringMethod == streamMethod) "$stringMethod"
        else "strings=$stringMethod streams=$streamMethod"
        return "StandardSecurityHandler(encryptObj=$encryptObjNum, " +
            "R$revision V$version ${keyLengthBytes * 8}-bit $methods)"
    }

    companion object {
        private val SALT = byteArrayOf(0x73, 0x41, 0x6C, 0x54) // "sAlT"
        private val EMPTY = ByteArray(0)

        /**
         * Builds a handler for [doc], or returns null when the file is not
         * encrypted. Throws [PdfPasswordException] when the file is encrypted
         * but [password] does not authenticate.
         */
        fun create(file: PdfFile, doc: PdfDocument, password: String): StandardSecurityHandler? {
            val rawEncrypt = file.trailer.raw["Encrypt"] ?: return null
            val ref = PdfRef.parse(rawEncrypt)
            val encryptObjNum = ref?.objNum ?: -1
            val dict = resolveEncryptDict(doc, ref, rawEncrypt) ?: return null

            val filter = (dict["Filter"] as? PdfName)?.name
            if (filter != null && filter != "Standard") {
                throw PdfPasswordException("Unsupported security handler ($filter)")
            }

            val v = intOf(dict["V"]) ?: 0
            val r = intOf(dict["R"]) ?: throw PdfPasswordException("Encryption dictionary is missing /R")
            val p = intOf(dict["P"]) ?: -1
            val permissions = PdfPermissions(p)
            val encryptMetadata = (dict["EncryptMetadata"] as? PdfBoolean)?.value ?: true
            val o = bytesOf(dict["O"])
            val u = bytesOf(dict["U"])
            val id = firstFileId(file)

            if (r in 2..4) {
                val n = if (r == 2) 5 else ((intOf(dict["Length"]) ?: 40) / 8).coerceIn(5, 16)
                val pw = password.toByteArray(StandardCharsets.ISO_8859_1)
                var key = fileKey2to4(pw, o, p, id, r, n, encryptMetadata)
                var owner = false
                if (!validUser(key, u, id, r)) {
                    val recovered = userPasswordFromOwner(pw, o, r, n)
                    val ownerKey = fileKey2to4(recovered, o, p, id, r, n, encryptMetadata)
                    if (validUser(ownerKey, u, id, r)) {
                        key = ownerKey
                        owner = true
                    } else {
                        throw PdfPasswordException("Incorrect password")
                    }
                }
                val stringMethod = if (v < 4) CryptMethod.RC4 else cryptMethod(dict, "StrF", false)
                val streamMethod = if (v < 4) CryptMethod.RC4 else cryptMethod(dict, "StmF", false)
                return StandardSecurityHandler(
                    encryptObjNum, v, r, n, permissions, encryptMetadata, owner, !owner,
                    key, stringMethod, streamMethod
                )
            }

            if (r == 5 || r == 6) {
                val pw = password.toByteArray(StandardCharsets.UTF_8)
                    .let { if (it.size > 127) it.copyOf(127) else it }
                var owner = false
                var key = authenticateAes256(pw, u, bytesOf(dict["UE"]), r, null)
                if (key == null) {
                    key = authenticateAes256(pw, o, bytesOf(dict["OE"]), r, u)
                    if (key != null) owner = true
                }
                if (key == null) throw PdfPasswordException("Incorrect password")
                val method = cryptMethod(dict, "StmF", true)
                return StandardSecurityHandler(
                    encryptObjNum, v, r, 32, permissions, encryptMetadata, owner, !owner,
                    key, method, method
                )
            }

            throw PdfPasswordException("Unsupported encryption revision $r")
        }

        private fun resolveEncryptDict(doc: PdfDocument, ref: PdfRef?, raw: String): PdfDict? {
            if (ref != null) return doc.resolve(ref) as? PdfDict
            return parseDirectObject(raw.toByteArray(StandardCharsets.ISO_8859_1)).getOrNull() as? PdfDict
        }

        /** First element of the trailer `/ID` array (§7.5.5); empty when absent. */
        private fun firstFileId(file: PdfFile): ByteArray {
            val raw = file.trailer.raw["ID"] ?: return EMPTY
            val array = parseDirectObject(raw.toByteArray(StandardCharsets.ISO_8859_1)).getOrNull() as? PdfArray
            return (array?.items?.firstOrNull() as? PdfString)?.bytes ?: EMPTY
        }

        private fun intOf(obj: PdfObject?): Int? =
            (obj as? PdfInteger)?.value?.let {
                if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) null else it.toInt()
            }

        private fun bytesOf(obj: PdfObject?): ByteArray = (obj as? PdfString)?.bytes ?: EMPTY

        // -- Revision 2–4 key derivation and validation (§7.6.3.3 / §7.6.3.4) --

        private fun intLE(value: Int): ByteArray = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte()
        )

        /**
         * Algorithm 2: derives the file encryption key. For revision 2 [n] is
         * 5; for revisions 3 and 4 the MD5 digest is re-hashed 50 times using
         * only its first [n] bytes.
         */
        private fun fileKey2to4(
            password: ByteArray, o: ByteArray, p: Int, id: ByteArray,
            r: Int, n: Int, encryptMetadata: Boolean
        ): ByteArray {
            var input = PasswordPadding.pad(password) + o + intLE(p) + id
            if (r >= 4 && !encryptMetadata) input += byteArrayOf(-1, -1, -1, -1)
            var hash = Digest.md5(input)
            if (r >= 3) repeat(50) { hash = Digest.md5(hash.copyOf(n)) }
            return hash.copyOf(n)
        }

        /** Algorithm 4 (R2) / Algorithm 5 (R3–4): verify the user password. */
        private fun validUser(key: ByteArray, u: ByteArray, id: ByteArray, r: Int): Boolean {
            if (u.size < 16) return false
            if (r == 2) return Rc4.apply(key, PasswordPadding.PADDING).contentEquals(u)
            var out = Rc4.apply(key, Digest.md5(PasswordPadding.PADDING + id))
            for (i in 1..19) out = Rc4.apply(keyXor(key, i), out)
            return out.copyOfRange(0, 16).contentEquals(u.copyOfRange(0, 16))
        }

        /**
         * Algorithm 3, run in reverse: recovers the padded user password from
         * the `/O` entry using the owner password.
         */
        private fun userPasswordFromOwner(ownerPassword: ByteArray, o: ByteArray, r: Int, n: Int): ByteArray {
            var hash = Digest.md5(PasswordPadding.pad(ownerPassword))
            if (r >= 3) repeat(50) { hash = Digest.md5(hash) }
            val key = hash.copyOf(n)
            if (r == 2) return Rc4.apply(key, o)
            var current = o
            for (i in 19 downTo 1) current = Rc4.apply(keyXor(key, i), current)
            return Rc4.apply(key, current)
        }

        private fun keyXor(key: ByteArray, value: Int): ByteArray =
            ByteArray(key.size) { (key[it].toInt() xor value).toByte() }

        // -- Revision 5–6 (AES-256) authentication -------------------------------

        /**
         * Validates [password] against a 48-byte `/U` (or `/O`) entry and
         * returns the 32-byte file key decrypted from `/UE` (or `/OE`), or null.
         * [uForHash] is the `/U` entry when checking the owner password.
         */
        private fun authenticateAes256(
            password: ByteArray, entry: ByteArray, encryptedKey: ByteArray,
            r: Int, uForHash: ByteArray?
        ): ByteArray? {
            if (entry.size < 48 || encryptedKey.size < 32) return null
            val validationSalt = entry.copyOfRange(32, 40)
            val keySalt = entry.copyOfRange(40, 48)
            val ownerData = uForHash ?: EMPTY
            val hash = if (r == 5) Digest.sha256(password + validationSalt + ownerData)
            else hash2B(password, validationSalt, uForHash)
            if (!hash.copyOfRange(0, 32).contentEquals(entry.copyOfRange(0, 32))) return null
            val intermediate = if (r == 5) Digest.sha256(password + keySalt + ownerData)
            else hash2B(password, keySalt, uForHash)
            val key = AesCbc.decryptNoPadding(intermediate.copyOfRange(0, 32), ByteArray(16), encryptedKey.copyOfRange(0, 32))
            return key.takeIf { it.size == 32 }
        }

        /**
         * Algorithm 2.B (revision 6): iterated SHA-256/384/512 over an AES-CBC
         * transform of the password, stopping once at least 64 rounds have run
         * and the last ciphertext byte is small enough.
         */
        private fun hash2B(password: ByteArray, salt: ByteArray, u: ByteArray?): ByteArray {
            val extra = u ?: EMPTY
            var k = Digest.sha256(password + salt + extra)
            var round = 0
            while (true) {
                val unit = password + k + extra
                val block = ByteArray(unit.size * 64)
                for (i in 0 until 64) System.arraycopy(unit, 0, block, i * unit.size, unit.size)
                val e = AesCbc.encryptNoPadding(k.copyOfRange(0, 16), k.copyOfRange(16, 32), block)
                if (e.size != block.size) return k.copyOf(32) // AES unavailable: best effort
                val mod = (0 until 16).sumOf { e[it].toInt() and 0xFF } % 3
                k = when (mod) {
                    0 -> Digest.sha256(e)
                    1 -> Digest.sha384(e)
                    else -> Digest.sha512(e)
                }
                round++
                if (round >= 64 && (e[e.size - 1].toInt() and 0xFF) <= round - 32) break
            }
            return k.copyOf(32)
        }

        // -- Crypt filters (§7.6.3.4, Table 25) ----------------------------------

        private fun cryptMethod(dict: PdfDict, key: String, isV5: Boolean): CryptMethod {
            val filters = parseCryptFilters(dict)
            val name = (dict[key] as? PdfName)?.name
            if (name == null) {
                if (isV5) return filters["StdCF"] ?: CryptMethod.AESV3
                return CryptMethod.IDENTITY
            }
            if (name == "Identity") return CryptMethod.IDENTITY
            return filters[name] ?: CryptMethod.IDENTITY
        }

        private fun parseCryptFilters(dict: PdfDict): Map<String, CryptMethod> {
            val cf = dict["CF"] as? PdfDict ?: return emptyMap()
            val out = HashMap<String, CryptMethod>()
            for ((name, value) in cf.entries) {
                val filter = value as? PdfDict ?: continue
                out[name.name] = when ((filter["CFM"] as? PdfName)?.name) {
                    "V2" -> CryptMethod.RC4
                    "AESV2" -> CryptMethod.AESV2
                    "AESV3" -> CryptMethod.AESV3
                    else -> CryptMethod.IDENTITY
                }
            }
            return out
        }
    }
}
