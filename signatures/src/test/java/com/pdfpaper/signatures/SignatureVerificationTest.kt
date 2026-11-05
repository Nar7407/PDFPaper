package com.pdfpaper.signatures

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignatureVerificationTest {

    private val rootStore: TrustStore get() = SimpleTrustStore(certificates("root.pem"))

    @Test
    fun validSignatureIsIntactAndTrusted() {
        val verification = verify("valid.pdf")
        assertEquals(1, verification.signatures.size)

        val signature = verification.signatures.single()
        assertEquals(SignatureState.INTACT_AND_TRUSTED, signature.state)
        assertEquals(ChainStatus.TRUSTED, signature.chainStatus)
        assertEquals(RevocationStatus.NOT_CHECKED, signature.revocation)
        assertEquals(true, signature.digestMatches)
        assertEquals("SHA-256", signature.digestAlgorithm)
        assertEquals(true, signature.cmsSignatureValid)
        assertEquals("Signature1", signature.fieldName)
        assertEquals("adbe.pkcs7.detached", signature.subFilter)
        assertEquals("Approved", signature.reason)
        assertEquals("PDFPaper", signature.location)
        assertTrue(signature.coversWholeFile)
        assertTrue(signature.updatesAfterSigning.isEmpty())
        assertTrue(signature.problems.isEmpty())

        val signer = assertNotNull(signature.signer)
        assertTrue(signer.subject.contains("Alice Example"), signer.subject)
        assertTrue(signer.notBefore.isBefore(signer.notAfter))
        assertNotNull(signature.signingTime)
        assertTrue(signature.trustAnchor!!.contains("root"))

        assertEquals(0L, signature.ranges.first().offset)
        assertEquals(signature.fileLength, signature.ranges.last().end)
        assertTrue(signature.ranges.last().offset > signature.ranges.first().end)

        assertEquals(SignatureState.INTACT_AND_TRUSTED, verification.state)
        assertTrue(verification.allTrusted)
    }

    @Test
    fun validSignatureWithoutTrustAnchorsIsIntactButUnknown() {
        val signature = single("valid.pdf", EmptyTrustStore)
        assertEquals(SignatureState.INTACT_SIGNER_UNKNOWN, signature.state)
        assertEquals(ChainStatus.NOT_CHECKED, signature.chainStatus)
        assertEquals(true, signature.digestMatches)
        assertEquals(true, signature.cmsSignatureValid)
        assertTrue(signature.problems.any { it.contains("no trust anchors") })
    }

    @Test
    fun expiredCertificateIsNotTrusted() {
        val signature = single("expired.pdf")
        assertEquals(SignatureState.INTACT_SIGNER_UNKNOWN, signature.state)
        assertEquals(ChainStatus.UNTRUSTED, signature.chainStatus)
        assertEquals(true, signature.digestMatches)
        assertEquals(true, signature.cmsSignatureValid)
        assertTrue(signature.problems.any { it.contains("expired") }, signature.problems.toString())
        val signer = assertNotNull(signature.signer)
        assertTrue(signer.notAfter.isBefore(Instant.now()))
    }

    @Test
    fun signatureFromUnknownRootIsNotTrusted() {
        val signature = single("untrusted.pdf")
        assertEquals(SignatureState.INTACT_SIGNER_UNKNOWN, signature.state)
        assertEquals(ChainStatus.UNTRUSTED, signature.chainStatus)
        assertEquals(true, signature.digestMatches)
        assertNull(signature.trustAnchor)
        assertTrue(signature.problems.any { it.contains("not trusted") })
    }

    @Test
    fun tamperedByteRangeIsInvalid() {
        val signature = single("tampered.pdf")
        assertEquals(SignatureState.INVALID, signature.state)
        assertEquals(false, signature.digestMatches)
        assertEquals(true, signature.cmsSignatureValid)
        assertTrue(signature.problems.any { it.contains("digest") })
    }

    @Test
    fun incrementalUpdateIsReportedAsModifiedAfterSigning() {
        val originalLength = resource("valid.pdf").size.toLong()
        val verification = verify("modified.pdf")
        val signature = verification.signatures.single()

        assertEquals(SignatureState.MODIFIED_AFTER_SIGNING, signature.state)
        assertEquals(SignatureState.MODIFIED_AFTER_SIGNING, verification.state)
        assertEquals(true, signature.digestMatches)
        assertEquals(true, signature.cmsSignatureValid)
        assertFalse(signature.coversWholeFile)
        assertEquals(originalLength, signature.ranges.last().end)

        val update = signature.updatesAfterSigning.single()
        assertEquals(originalLength, update.offset)
        assertTrue(update.length > 0)
        assertTrue(update.eofOffsets.isNotEmpty())
        assertTrue(signature.problems.any { it.contains("updated") })
    }

    @Test
    fun signatureWithoutSignedAttributesUsesTheContentDirectly() {
        val signature = single("valid-noattr.pdf")
        assertEquals(SignatureState.INTACT_AND_TRUSTED, signature.state)
        assertNull(signature.digestMatches)
        assertEquals(true, signature.cmsSignatureValid)
        assertEquals(Instant.parse("2026-10-19T12:00:00Z"), signature.signingTime)
    }

    @Test
    fun legacySha1SignatureVerifies() {
        val signature = single("valid-sha1.pdf")
        assertEquals(SignatureState.INTACT_AND_TRUSTED, signature.state)
        assertEquals("adbe.pkcs7.sha1", signature.subFilter)
        assertEquals("SHA-1", signature.digestAlgorithm)
        assertEquals(true, signature.digestMatches)
        assertEquals(true, signature.cmsSignatureValid)
        assertTrue(signature.coversWholeFile)
    }

    @Test
    fun crlGoodIsCheckedAndTrusted() {
        val signature = single("valid-crl.pdf")
        assertEquals(SignatureState.INTACT_AND_TRUSTED, signature.state)
        assertEquals(RevocationStatus.GOOD, signature.revocation)
        assertTrue(signature.revocationNote!!.contains("CRL"))
    }

    @Test
    fun revokedSignerIsNotTrusted() {
        val signature = single("revoked.pdf")
        assertEquals(SignatureState.INTACT_SIGNER_UNKNOWN, signature.state)
        assertEquals(RevocationStatus.REVOKED, signature.revocation)
        assertEquals(true, signature.digestMatches)
        assertEquals(true, signature.cmsSignatureValid)
        assertEquals(ChainStatus.TRUSTED, signature.chainStatus)
    }

    @Test
    fun unsignedPdfHasNoSignatures() {
        val verification = verify("unsigned.pdf")
        assertTrue(verification.signatures.isEmpty())
        assertNull(verification.state)
        assertFalse(verification.allTrusted)
    }

    @Test
    fun unsupportedSubFilterIsReported() {
        val signature = single("unsupported.pdf")
        assertEquals(SignatureState.UNSUPPORTED, signature.state)
        assertEquals("adbe.x509.rsa_sha1", signature.subFilter)
        assertTrue(signature.problems.any { it.contains("adbe.x509.rsa_sha1") })
    }

    @Test
    fun malformedByteRangeIsInvalid() {
        val signature = single("bad-byterange.pdf")
        assertEquals(SignatureState.INVALID, signature.state)
        assertTrue(signature.problems.any { it.contains("/ByteRange") })
    }

    @Test
    fun fileOverloadVerifies() {
        val temporary = File.createTempFile("pdfpaper-signature", ".pdf")
        try {
            temporary.writeBytes(resource("valid.pdf"))
            val verification = PdfSignatures.verify(temporary, rootStore).getOrThrow()
            assertEquals(SignatureState.INTACT_AND_TRUSTED, verification.state)
        } finally {
            temporary.delete()
        }
    }

    @Test
    fun pemTrustStoreLoads() {
        val temporary = File.createTempFile("pdfpaper-root", ".pem")
        try {
            temporary.writeBytes(resource("root.pem"))
            val store = TrustStores.load(temporary)
            assertEquals(1, store.trustAnchors().size)
        } finally {
            temporary.delete()
        }
    }

    @Test
    fun malformedInputsNeverThrow() {
        val valid = resource("valid.pdf")
        val samples = listOf(
            ByteArray(0),
            "this is not a PDF".toByteArray(),
            valid.copyOfRange(0, 64),
            valid.copyOfRange(0, 1000),
            valid.copyOfRange(0, 5000),
            valid.copyOfRange(0, 9000),
            valid.copyOfRange(0, valid.size - 1)
        )
        for (sample in samples) {
            val result = PdfSignatures.verify(sample, rootStore)
            result.getOrNull()?.signatures?.forEach { report ->
                assertNotNull(report.state)
            }
        }

        val random = Random(42)
        repeat(20) {
            val corrupted = valid.copyOf()
            val position = random.nextInt(corrupted.size)
            corrupted[position] = (corrupted[position].toInt() xor 0xFF).toByte()
            val result = PdfSignatures.verify(corrupted, rootStore)
            result.getOrNull()?.signatures?.forEach { report ->
                assertNotNull(report.state)
            }
        }
    }

    @Test
    fun nonPdfBytesFailOrHaveNoSignatures() {
        val result = PdfSignatures.verify("not a PDF at all".toByteArray(), rootStore)
        assertTrue(result.isFailure || result.getOrThrow().signatures.isEmpty())
    }

    private fun verify(name: String, trustStore: TrustStore = rootStore): SignatureVerification =
        PdfSignatures.verify(resource(name), trustStore).getOrThrow()

    private fun single(name: String, trustStore: TrustStore = rootStore): SignatureReport =
        verify(name, trustStore).signatures.single()

    private fun resource(name: String): ByteArray {
        val stream = requireNotNull(javaClass.getResourceAsStream("/signatures/$name")) {
            "missing test resource /signatures/$name"
        }
        return stream.use { it.readBytes() }
    }

    private fun certificates(name: String): List<X509Certificate> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/signatures/$name")) {
            "missing test resource /signatures/$name"
        }
        return stream.use {
            CertificateFactory.getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(it.readBytes()))
                .filterIsInstance<X509Certificate>()
        }
    }
}
