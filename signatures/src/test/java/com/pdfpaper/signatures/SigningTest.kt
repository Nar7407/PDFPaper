package com.pdfpaper.signatures

import com.pdfpaper.engine.form.formFields
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class SigningTest {

    private val password = "changeit".toCharArray()

    @Test
    fun visibleSignatureIsIntactAndTrusted() {
        val original = resource("unsigned.pdf").readBytes()
        val key = rsaKey()
        val signer = certificate("signing-rsa.pem")
        val signed = PdfSigner.sign(
            original,
            key,
            SigningOptions(
                rect = PdfRect(72.0, 72.0, 320.0, 150.0),
                reason = "Approved",
                location = "PDFPaper",
                appearance = SignatureAppearance(
                    name = "Alice Example",
                    dateText = "2026-10-20 12:00 UTC",
                    reason = "Approved",
                    location = "PDFPaper"
                )
            )
        ).getOrThrow()

        assertTrue(signed.bytes.copyOfRange(0, original.size).contentEquals(original))
        assertEquals("Signature1", signed.fieldName)
        assertTrue(signed.visible)
        assertEquals(4, signed.byteRange.size)

        val verification = verify(signed.bytes, signer)
        assertEquals(SignatureState.INTACT_AND_TRUSTED, verification.signatures.single().state)
        assertEquals("Signature1", verification.signatures.single().fieldName)
        assertEquals("Alice Example", verification.signatures.single().declaredName)
        assertEquals("Approved", verification.signatures.single().reason)
        assertEquals("PDFPaper", verification.signatures.single().location)
        assertEquals(true, verification.signatures.single().digestMatches)
        assertEquals(true, verification.signatures.single().cmsSignatureValid)
    }

    @Test
    fun selfSignedSignatureIsIntactSignerUnknownWithoutAnchor() {
        val signed = PdfSigner.sign(
            resource("unsigned.pdf").readBytes(),
            rsaKey(),
            SigningOptions(rect = PdfRect(10.0, 10.0, 210.0, 60.0))
        ).getOrThrow()

        val verification = PdfSignatures.verify(signed.bytes).getOrThrow()
        assertEquals(SignatureState.INTACT_SIGNER_UNKNOWN, verification.signatures.single().state)
    }

    @Test
    fun invisibleSignatureHasNoAppearanceAndStillVerifies() {
        val signed = PdfSigner.sign(
            resource("unsigned.pdf").readBytes(),
            rsaKey()
        ).getOrThrow()
        assertFalse(signed.visible)
        val verification = verify(signed.bytes, certificate("signing-rsa.pem"))
        assertEquals(SignatureState.INTACT_AND_TRUSTED, verification.signatures.single().state)
    }

    @Test
    fun ecdsaSignatureVerifies() {
        val signed = PdfSigner.sign(
            resource("unsigned.pdf").readBytes(),
            ecKey(),
            SigningOptions(rect = PdfRect(20.0, 20.0, 240.0, 80.0))
        ).getOrThrow()
        val verification = verify(signed.bytes, certificate("signing-ec.pem"))
        assertEquals(SignatureState.INTACT_AND_TRUSTED, verification.signatures.single().state)
        assertEquals("SHA-256", verification.signatures.single().digestAlgorithm)
        assertEquals(true, verification.signatures.single().cmsSignatureValid)
    }

    @Test
    fun secondSignatureKeepsTheFirstSignatureValid() {
        val key = rsaKey()
        val signer = certificate("signing-rsa.pem")
        val first = PdfSigner.sign(
            resource("unsigned.pdf").readBytes(),
            key,
            SigningOptions(rect = PdfRect(30.0, 30.0, 200.0, 80.0))
        ).getOrThrow()
        val second = PdfSigner.sign(
            first.bytes,
            key,
            SigningOptions(rect = PdfRect(30.0, 100.0, 200.0, 150.0))
        ).getOrThrow()

        assertTrue(second.bytes.copyOfRange(0, first.bytes.size).contentEquals(first.bytes))
        val verification = verify(second.bytes, signer)
        assertEquals(2, verification.signatures.size)
        assertEquals(setOf("Signature1", "Signature2"), verification.signatures.map { it.fieldName }.toSet())
        assertTrue(
            verification.signatures.all { it.state == SignatureState.INTACT_AND_TRUSTED },
            verification.signatures.joinToString { "${it.fieldName}=${it.state}" }
        )
    }

    @Test
    fun reusesAnExistingEmptySignatureField() {
        val signed = PdfSigner.sign(
            resource("prepared.pdf").readBytes(),
            rsaKey(),
            SigningOptions(reason = "Reused ready field")
        ).getOrThrow()

        assertEquals("PreparedSignature", signed.fieldName)
        assertEquals(0, signed.pageIndex)
        assertTrue(signed.visible)
        val verification = verify(signed.bytes, certificate("signing-rsa.pem"))
        assertEquals(SignatureState.INTACT_AND_TRUSTED, verification.signatures.single().state)

        val document = PdfDocument.open(ByteArraySource(signed.bytes)).getOrThrow()
        document.use {
            val field = it.formFields().single { candidate -> candidate.name == "PreparedSignature" }
            assertEquals(com.pdfpaper.engine.form.FieldKind.SIGNATURE, field.kind)
            assertTrue(field.widgets.any { widget ->
                widget.rect?.let { rect -> rect.width > 0.0 && rect.height > 0.0 } == true
            })
        }
    }

    @Test
    fun invisibleSignatureCanReuseAnExistingFieldAndHideItsWidget() {
        val signed = PdfSigner.sign(
            resource("prepared.pdf").readBytes(),
            rsaKey(),
            SigningOptions(visible = false)
        ).getOrThrow()

        assertEquals("PreparedSignature", signed.fieldName)
        assertFalse(signed.visible)
        val document = PdfDocument.open(ByteArraySource(signed.bytes)).getOrThrow()
        document.use {
            val field = it.formFields().single { candidate -> candidate.name == "PreparedSignature" }
            assertTrue(field.widgets.all { widget ->
                val rect = widget.rect
                rect == null || rect.isEmpty
            })
        }
        assertEquals(
            SignatureState.INTACT_SIGNER_UNKNOWN,
            PdfSignatures.verify(signed.bytes).getOrThrow().signatures.single().state
        )
    }

    @Test
    fun createsAFieldWithARequestedName() {
        val signed = PdfSigner.sign(
            resource("unsigned.pdf").readBytes(),
            rsaKey(),
            SigningOptions(fieldName = "Approval", rect = PdfRect(40.0, 40.0, 240.0, 100.0))
        ).getOrThrow()
        assertEquals("Approval", signed.fieldName)
        val verification = verify(signed.bytes, certificate("signing-rsa.pem"))
        assertEquals("Approval", verification.signatures.single().fieldName)
        assertEquals(SignatureState.INTACT_AND_TRUSTED, verification.signatures.single().state)
    }

    @Test
    fun pngAppearanceWithTransparencyEmbedsAnSmask() {
        val signed = PdfSigner.sign(
            resource("unsigned.pdf").readBytes(),
            rsaKey(),
            SigningOptions(
                rect = PdfRect(40.0, 40.0, 320.0, 120.0),
                name = "Alice Example",
                image = transparentPng()
            )
        ).getOrThrow()

        val text = signed.bytes.toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("/SMask"), "PNG transparency should produce an /SMask")
        assertTrue(text.contains("/Im1 Do"), "appearance should draw the embedded image")
        assertEquals(
            SignatureState.INTACT_AND_TRUSTED,
            verify(signed.bytes, certificate("signing-rsa.pem")).signatures.single().state
        )
    }

    @Test
    fun growsTheContentsPlaceholderWhenCmsIsLargerThan32KiB() {
        val base = rsaKey()
        val signer = certificate("signing-rsa.pem")
        val manyCertificates = object : SigningKey {
            override val certificateChain: List<X509Certificate> =
                List(100) { base.certificateChain.first() }

            override fun sign(digest: ByteArray): ByteArray = base.sign(digest)
        }
        val signed = PdfSigner.sign(
            resource("unsigned.pdf").readBytes(),
            manyCertificates
        ).getOrThrow()

        assertTrue(signed.cmsBytes > 32 * 1024, "test CMS should not fit the initial placeholder")
        assertTrue(signed.contentsBytes > 32 * 1024)
        assertTrue(signed.contentsBytes >= signed.cmsBytes)
        assertEquals(
            SignatureState.INTACT_AND_TRUSTED,
            PdfSignatures.verify(signed.bytes, SimpleTrustStore(listOf(signer)))
                .getOrThrow().signatures.single().state
        )
    }

    @Test
    fun tamperingAfterSigningIsDetected() {
        val signed = PdfSigner.sign(
            resource("unsigned.pdf").readBytes(),
            rsaKey()
        ).getOrThrow()
        val bytes = signed.bytes.copyOf()
        val marker = "PDFPaper".toByteArray(Charsets.ISO_8859_1)
        val at = bytes.indexOf(marker)
        assertTrue(at >= 0)
        bytes[at] = 'X'.code.toByte()

        val verification = verify(bytes, certificate("signing-rsa.pem"))
        assertEquals(SignatureState.INVALID, verification.signatures.single().state)
        assertEquals(false, verification.signatures.single().digestMatches)
    }

    @Test
    fun timestampProviderAddsAnUnsignedAttribute() {
        var called = false
        val provider = TimestampProvider { digest ->
            called = true
            assertEquals(32, digest.size)
            byteArrayOf(0x30, 0x00)
        }
        val signed = PdfSigner.sign(
            resource("unsigned.pdf").readBytes(),
            rsaKey(),
            SigningOptions(timestampProvider = provider)
        ).getOrThrow()
        assertTrue(called)
        assertTrue(signed.timestamped)
        assertEquals(
            SignatureState.INTACT_SIGNER_UNKNOWN,
            PdfSignatures.verify(signed.bytes).getOrThrow().signatures.single().state
        )
    }

    @Test
    fun malformedInputFailsWithoutThrowing() {
        val result = PdfSigner.sign(byteArrayOf(1, 2, 3), rsaKey())
        assertTrue(result.isFailure)
    }

    private fun verify(bytes: ByteArray, signer: X509Certificate): SignatureVerification =
        PdfSignatures.verify(bytes, SimpleTrustStore(listOf(signer))).getOrThrow()

    private fun rsaKey(): SigningKey =
        Pkcs12SigningKey.load(File(resourcePath("signing-rsa.p12")), password).getOrThrow()

    private fun transparentPng(): ByteArray {
        val image = BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color(30, 100, 190, 210)
            graphics.fillRect(8, 8, 48, 16)
        } finally {
            graphics.dispose()
        }
        val bytes = ByteArrayOutputStream()
        ImageIO.write(image, "png", bytes)
        return bytes.toByteArray()
    }

    private fun ecKey(): SigningKey =
        Pkcs12SigningKey.load(File(resourcePath("signing-ec.p12")), password).getOrThrow()

    private fun certificate(name: String): X509Certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(File(resourcePath(name)).inputStream()) as X509Certificate

    private fun resource(name: String): File = File(resourcePath(name))

    private fun resourcePath(name: String): String {
        val url = javaClass.classLoader.getResource("signatures/$name")
        assertNotNull(url, "missing test resource $name")
        return File(url.toURI()).path
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
