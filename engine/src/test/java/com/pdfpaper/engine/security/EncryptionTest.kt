package com.pdfpaper.engine.security

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.extractText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Standard security handler tests (ISO 32000-1, §7.6).
 *
 * The fixtures under `/encrypted` are produced by `generate_fixtures.py`, an
 * independent implementation of the same algorithms (pure-Python AES checked
 * against FIPS-197 / NIST vectors), so this exercises the reader against a
 * second implementation rather than only against itself.
 */
class EncryptionTest {

    private val allFixtures = listOf(
        "r2-rc4-40.pdf", "r3-rc4-128.pdf", "r4-rc4.pdf",
        "r4-aes128.pdf", "r5-aes256.pdf", "r6-aes256.pdf"
    )

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/encrypted/$name")!!.use { it.readBytes() }

    private fun open(name: String, password: String = "user"): PdfDocument =
        PdfDocument.open(ByteArraySource(fixture(name)), password).getOrThrow()

    private fun infoTitle(doc: PdfDocument): String {
        val ref = PdfRef.parse(doc.file.trailer.raw.getValue("Info"))!!
        val info = doc.resolve(ref) as PdfDict
        return (info["Title"] as PdfString).text()
    }

    private fun assertDecrypts(name: String) {
        open(name).use { doc ->
            assertTrue("$name should be encrypted", doc.isEncrypted)
            assertEquals("$name title", "Top Secret Title", infoTitle(doc))
            val text = doc.pages().first().extractText().runs.joinToString("") { it.text }
            assertTrue("$name text was '$text'", text.contains("Hello Encrypted World"))

            val permissions = doc.permissions!!
            assertEquals("$name raw /P", -7980, permissions.raw)
            assertTrue("$name canPrint", permissions.canPrint)
            assertTrue("$name canCopy", permissions.canCopy)
            assertFalse("$name canModifyContents", permissions.canModifyContents)
            assertFalse("$name canAnnotate", permissions.canAnnotate)
            assertFalse("$name canFillForms", permissions.canFillForms)
            assertFalse("$name canAssemble", permissions.canAssemble)
        }
    }

    @Test
    fun opensRc4Revision2() = assertDecrypts("r2-rc4-40.pdf")

    @Test
    fun opensRc4Revision3() = assertDecrypts("r3-rc4-128.pdf")

    @Test
    fun opensRc4CryptFilterRevision4() = assertDecrypts("r4-rc4.pdf")

    @Test
    fun opensAes128Revision4() = assertDecrypts("r4-aes128.pdf")

    @Test
    fun opensAes256Revision5() = assertDecrypts("r5-aes256.pdf")

    @Test
    fun opensAes256Revision6() = assertDecrypts("r6-aes256.pdf")

    @Test
    fun ownerPasswordAlsoOpens() {
        open("r4-aes128.pdf", "owner").use { doc ->
            assertTrue(doc.security!!.ownerAuthenticated)
            assertFalse(doc.security!!.userAuthenticated)
            assertEquals("Top Secret Title", infoTitle(doc))
        }
    }

    @Test
    fun ownerPasswordOpensAes256Revision6() {
        open("r6-aes256.pdf", "owner").use { doc ->
            assertTrue(doc.security!!.ownerAuthenticated)
            assertEquals("Top Secret Title", infoTitle(doc))
        }
    }

    @Test
    fun wrongPasswordIsReportedWithoutCrash() {
        for (name in allFixtures) {
            val result = PdfDocument.open(ByteArraySource(fixture(name)), "definitely-wrong")
            assertTrue("$name should fail", result.isFailure)
            assertTrue(
                "$name: ${result.exceptionOrNull()}",
                result.exceptionOrNull() is PdfPasswordException
            )
        }
    }

    @Test
    fun emptyPasswordFailsWhenAPasswordIsRequired() {
        for (name in allFixtures) {
            val result = PdfDocument.open(ByteArraySource(fixture(name)))
            assertTrue(
                "$name: ${result.exceptionOrNull()}",
                result.exceptionOrNull() is PdfPasswordException
            )
        }
    }

    @Test
    fun reportsRevisionAndKeyLength() {
        assertEquals(2 to 5, open("r2-rc4-40.pdf").use { it.security!!.revision to it.security!!.keyLengthBytes })
        assertEquals(3 to 16, open("r3-rc4-128.pdf").use { it.security!!.revision to it.security!!.keyLengthBytes })
        assertEquals(4 to 16, open("r4-aes128.pdf").use { it.security!!.revision to it.security!!.keyLengthBytes })
        assertEquals(6 to 32, open("r6-aes256.pdf").use { it.security!!.revision to it.security!!.keyLengthBytes })
    }

    // -- Primitives against published vectors ---------------------------------

    @Test
    fun rc4MatchesRfc6229Vectors() {
        assertEquals(
            "b2396305f03dc027ccc3524a0a1118a86982944f18fc82d589c403a47a0d0919",
            Rc4.apply(hex("0102030405"), ByteArray(32)).hex()
        )
        assertEquals(
            "9ac7cc9a609d1ef7b2932899cde41b975248c4959014126a6e8a84f11d1a9e1c",
            Rc4.apply(hex("0102030405060708090a0b0c0d0e0f10"), ByteArray(32)).hex()
        )
    }

    @Test
    fun aesCbcMatchesNistVectors() {
        val iv = hex("000102030405060708090a0b0c0d0e0f")
        val plain = hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51")
        val key128 = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val out128 = hex("7649abac8119b246cee98e9b12e9197d5086cb9b507219ee95db113a917678b2")
        assertEquals(out128.hex(), AesCbc.encryptNoPadding(key128, iv, plain).hex())
        assertEquals(plain.hex(), AesCbc.decryptNoPadding(key128, iv, out128).hex())

        val key256 = hex("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4")
        val out256 = hex("f58c4c04d6e5f1ba779eabfb5f7bfbd69cfc4e967edb808d679f777bc6702c7d")
        assertEquals(out256.hex(), AesCbc.encryptNoPadding(key256, iv, plain).hex())
        assertEquals(plain.hex(), AesCbc.decryptNoPadding(key256, iv, out256).hex())
    }

    private companion object {
        fun hex(value: String): ByteArray =
            ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }
}
