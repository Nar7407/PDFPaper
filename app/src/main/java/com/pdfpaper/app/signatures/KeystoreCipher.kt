package com.pdfpaper.app.signatures

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption backed by a non-exportable Android Keystore key.
 *
 * The Keystore deliberately does not allow importing an arbitrary PKCS#12
 * private key.  For imported certificates (and for visual signatures) the app
 * therefore stores an encrypted blob whose wrapping key lives in the Android
 * Keystore.  The wrapping key never leaves the secure hardware/Keystore and
 * the PKCS#12 password itself is never persisted.  This is the portable
 * equivalent of "store the key in the Keystore" without pretending that
 * platform KeyStore can import PKCS#8 key material.
 *
 * Each value uses a fresh random IV; the envelope is:
 * `"PPS1" | ivLength (1 byte) | iv | ciphertext+tag`.
 */
internal class KeystoreCipher(private val alias: String) {

    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        require(iv.size in 1..255) { "Keystore returned an unusable IV" }
        val encrypted = cipher.doFinal(plaintext)
        return ByteArray(MAGIC.size + 1 + iv.size + encrypted.size).also { out ->
            MAGIC.copyInto(out, 0)
            out[MAGIC.size] = iv.size.toByte()
            iv.copyInto(out, MAGIC.size + 1)
            encrypted.copyInto(out, MAGIC.size + 1 + iv.size)
        }
    }

    fun decrypt(envelope: ByteArray): ByteArray {
        require(envelope.size >= MAGIC.size + 2) { "The encrypted value is truncated." }
        for (i in MAGIC.indices) {
            require(envelope[i] == MAGIC[i]) { "The encrypted value has an unknown format." }
        }
        val ivLength = envelope[MAGIC.size].toInt() and 0xFF
        require(ivLength in 1..255) { "The encrypted value has an invalid IV." }
        val ivStart = MAGIC.size + 1
        val cipherStart = ivStart + ivLength
        require(cipherStart < envelope.size) { "The encrypted value is truncated." }
        val iv = envelope.copyOfRange(ivStart, cipherStart)
        val ciphertext = envelope.copyOfRange(cipherStart, envelope.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null, null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        val MAGIC = byteArrayOf(0x50, 0x50, 0x53, 0x31) // "PPS1"
    }
}
