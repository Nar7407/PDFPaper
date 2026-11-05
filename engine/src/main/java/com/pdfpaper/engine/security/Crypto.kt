package com.pdfpaper.engine.security

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Low-level cryptographic primitives used by the standard security handler
 * (ISO 32000-1, §7.6). The engine keeps these separate from the PDF-object
 * plumbing so they can be unit-tested against published test vectors.
 *
 * No Android or third-party library is used: the hashes come from
 * [java.security.MessageDigest] and AES from [javax.crypto], both part of the
 * JDK/Android platform.
 */

/** RC4 stream cipher (§7.6.3.4, Algorithm 1). */
internal object Rc4 {

    fun apply(key: ByteArray, data: ByteArray): ByteArray {
        if (key.isEmpty()) return data.copyOf()
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val out = ByteArray(data.size)
        var a = 0
        var b = 0
        for (k in data.indices) {
            a = (a + 1) and 0xFF
            b = (b + s[a]) and 0xFF
            val t = s[a]; s[a] = s[b]; s[b] = t
            out[k] = (data[k].toInt() xor s[(s[a] + s[b]) and 0xFF]).toByte()
        }
        return out
    }
}

/** AES in CBC/ECB mode (§7.6.3.4 AESV2/AESV3); the block IV is never secret. */
internal object AesCbc {

    /**
     * Decrypts a stored AES value: the first 16 bytes are the random IV and the
     * rest is ciphertext with PKCS#5 padding, per §7.6.3.4 (AESV2/AESV3).
     * Returns an empty array when the input is too short or the padding is bad
     * (a corrupt file, never a crash).
     */
    fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
        if (data.size <= 16 || (data.size - 16) % 16 != 0) return ByteArray(0)
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(data.copyOfRange(0, 16))
            )
            cipher.doFinal(data, 16, data.size - 16)
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    /** AES-CBC with no padding; used by the revision 6 password algorithm. */
    fun decryptNoPadding(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        transform(Cipher.DECRYPT_MODE, "AES/CBC/NoPadding", key, iv, data)

    /** AES-CBC with no padding; used by the revision 6 password algorithm. */
    fun encryptNoPadding(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        transform(Cipher.ENCRYPT_MODE, "AES/CBC/NoPadding", key, iv, data)

    /** AES-ECB with no padding; used to check the /Perms entry (§7.6.3.3). */
    fun decryptEcbNoPadding(key: ByteArray, data: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        cipher.doFinal(data)
    } catch (_: Exception) {
        ByteArray(0)
    }

    private fun transform(
        mode: Int,
        transformation: String,
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray
    ): ByteArray = try {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        cipher.doFinal(data)
    } catch (_: Exception) {
        ByteArray(0)
    }
}

/** Message digests used by the security handler. */
internal object Digest {

    fun md5(vararg parts: ByteArray): ByteArray = digest("MD5", parts)
    fun sha256(vararg parts: ByteArray): ByteArray = digest("SHA-256", parts)
    fun sha384(vararg parts: ByteArray): ByteArray = digest("SHA-384", parts)
    fun sha512(vararg parts: ByteArray): ByteArray = digest("SHA-512", parts)

    private fun digest(algorithm: String, parts: Array<out ByteArray>): ByteArray {
        val md = MessageDigest.getInstance(algorithm)
        for (part in parts) md.update(part)
        return md.digest()
    }
}

/** The 32-byte padding string from §7.6.3.3 (Algorithm 2, step (a)). */
internal object PasswordPadding {

    val PADDING: ByteArray = byteArrayOf(
        0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41, 0x64, 0x00,
        0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08, 0x2E, 0x2E, 0x00, 0xB6.toByte(),
        0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(), 0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(),
        0x64, 0x53, 0x69, 0x7A
    )

    /** Pads (or truncates) [password] to exactly 32 bytes (§7.6.3.3). */
    fun pad(password: ByteArray): ByteArray {
        val out = ByteArray(32)
        val copy = minOf(password.size, 32)
        System.arraycopy(password, 0, out, 0, copy)
        if (copy < 32) System.arraycopy(PADDING, 0, out, copy, 32 - copy)
        return out
    }
}
