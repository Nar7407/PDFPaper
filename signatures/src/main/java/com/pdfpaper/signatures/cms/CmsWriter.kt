package com.pdfpaper.signatures.cms

import com.pdfpaper.signatures.TimestampProvider
import com.pdfpaper.signatures.der.DerWriter
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Instant

/** Signature algorithms this writer can put into a CMS SignerInfo. */
internal enum class CmsSignatureAlgorithm(
    val oid: String,
    val parametersAreNull: Boolean
) {
    RSA_SHA256("1.2.840.113549.1.1.11", true),
    ECDSA_SHA256("1.2.840.10045.4.3.2", false)
}

/**
 * Creates a detached CMS/PKCS#7 SignedData with CAdES-BES signed attributes
 * (RFC 5652 §5, RFC 5035 for signingCertificateV2).
 *
 * The caller supplies a pre-computed SHA-256 digest of the PDF byte ranges. A
 * second SHA-256 digest is computed over the DER SET OF signed attributes and
 * passed to [signDigest]; the returned value must be an RSA PKCS#1 v1.5 or
 * ECDSA signature over that digest. Keeping the key operation behind that
 * callback means no private-key bytes enter this module.
 *
 * An optional RFC 3161 timestamp is requested for the signature value and
 * inserted as the unsigned `signature-time-stamp` attribute. Networking is the
 * provider's responsibility.
 */
internal object CmsWriter {

    class Result(val bytes: ByteArray, val timestamped: Boolean)

    fun create(
        dataDigest: ByteArray,
        signerCertificate: X509Certificate,
        chain: List<X509Certificate>,
        signingTime: Instant,
        algorithm: CmsSignatureAlgorithm,
        timestampProvider: TimestampProvider?,
        signDigest: (ByteArray) -> ByteArray
    ): Result {
        require(dataDigest.size == SHA256_LENGTH) { "dataDigest must be a SHA-256 digest" }
        require(chain.isNotEmpty()) { "certificate chain is empty" }

        val attributes = signedAttributes(dataDigest, signerCertificate, signingTime)
        val signedAttributes = DerWriter.set(attributes)
        val signedAttributesField = DerWriter.implicitSet(0xA0, attributes)

        val attributesDigest = sha256(signedAttributes)
        val signature = try {
            signDigest(attributesDigest)
        } finally {
            attributesDigest.fill(0)
        }
        require(signature.isNotEmpty()) { "signing key returned an empty signature" }

        var timestamped = false
        val unsignedAttributes = if (timestampProvider != null) {
            val signatureDigest = sha256(signature)
            val token = try {
                timestampProvider.timestamp(signatureDigest)
            } finally {
                signatureDigest.fill(0)
            }
            if (token == null || token.isEmpty()) {
                null
            } else {
                timestamped = true
                require(token[0].toInt() and 0xFF == 0x30) {
                    "timestamp token is not a DER SEQUENCE"
                }
                val attribute = DerWriter.sequence(
                    DerWriter.oid(TIMESTAMP_TOKEN),
                    DerWriter.set(token)
                )
                DerWriter.implicitSet(0xA1, listOf(attribute))
            }
        } else {
            null
        }

        val digestAlgorithm = algorithmIdentifier(DIGEST_SHA256, nullParameters = true)
        val signatureAlgorithm = algorithmIdentifier(algorithm.oid, algorithm.parametersAreNull)
        val issuerAndSerial = DerWriter.sequence(
            signerCertificate.issuerX500Principal.encoded,
            DerWriter.integer(signerCertificate.serialNumber)
        )

        val signerInfoParts = ArrayList<ByteArray>()
        signerInfoParts += DerWriter.integer(1)
        signerInfoParts += issuerAndSerial
        signerInfoParts += digestAlgorithm
        signerInfoParts += signedAttributesField
        signerInfoParts += signatureAlgorithm
        signerInfoParts += DerWriter.octetString(signature)
        if (unsignedAttributes != null) signerInfoParts += unsignedAttributes
        val signerInfo = DerWriter.sequence(signerInfoParts)

        val certificates = DerWriter.implicitSet(0xA0, chain.map { it.encoded })
        val signerInfos = DerWriter.set(signerInfo)
        val signedData = DerWriter.sequence(
            DerWriter.integer(1),
            DerWriter.set(digestAlgorithm),
            DerWriter.sequence(DerWriter.oid(CONTENT_TYPE_DATA)),
            certificates,
            signerInfos
        )
        val contentInfo = DerWriter.sequence(
            DerWriter.oid(CONTENT_TYPE_SIGNED_DATA),
            DerWriter.explicit(0xA0, signedData)
        )

        // The CMS output is public data, but the temporary signature buffer is
        // still wiped so callers cannot accidentally retain two copies of it.
        signature.fill(0)
        return Result(contentInfo, timestamped)
    }

    private fun signedAttributes(
        dataDigest: ByteArray,
        signer: X509Certificate,
        signingTime: Instant
    ): List<ByteArray> {
        val contentType = DerWriter.sequence(
            DerWriter.oid(ATTR_CONTENT_TYPE),
            DerWriter.set(DerWriter.oid(CONTENT_TYPE_DATA))
        )
        val messageDigest = DerWriter.sequence(
            DerWriter.oid(ATTR_MESSAGE_DIGEST),
            DerWriter.set(DerWriter.octetString(dataDigest))
        )
        val signingTimeAttribute = DerWriter.sequence(
            DerWriter.oid(ATTR_SIGNING_TIME),
            DerWriter.set(DerWriter.utcTime(signingTime))
        )
        val signingCertificateV2 = DerWriter.sequence(
            DerWriter.oid(ATTR_SIGNING_CERTIFICATE_V2),
            DerWriter.set(signingCertificateV2Value(signer))
        )
        return listOf(contentType, messageDigest, signingTimeAttribute, signingCertificateV2)
    }

    /**
     * SigningCertificateV2 ::= SEQUENCE {
     *   certs SEQUENCE OF ESSCertIDv2,
     *   policies SEQUENCE OF PolicyInformation OPTIONAL, ... }
     *
     * ESSCertIDv2 defaults its hashAlgorithm to SHA-256, so only certHash is
     * necessary for the signer certificate (RFC 5035 §5.4).
     */
    private fun signingCertificateV2Value(signer: X509Certificate): ByteArray {
        val certHash = sha256(signer.encoded)
        val essCertId = DerWriter.sequence(DerWriter.octetString(certHash))
        certHash.fill(0)
        return DerWriter.sequence(DerWriter.sequence(essCertId))
    }

    private fun algorithmIdentifier(oid: String, nullParameters: Boolean): ByteArray {
        val parts = ArrayList<ByteArray>()
        parts += DerWriter.oid(oid)
        if (nullParameters) parts += DerWriter.nullValue()
        return DerWriter.sequence(parts)
    }

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private const val SHA256_LENGTH = 32
    private const val CONTENT_TYPE_DATA = "1.2.840.113549.1.7.1"
    private const val CONTENT_TYPE_SIGNED_DATA = "1.2.840.113549.1.7.2"
    private const val DIGEST_SHA256 = "2.16.840.1.101.3.4.2.1"
    private const val ATTR_CONTENT_TYPE = "1.2.840.113549.1.9.3"
    private const val ATTR_MESSAGE_DIGEST = "1.2.840.113549.1.9.4"
    private const val ATTR_SIGNING_TIME = "1.2.840.113549.1.9.5"
    private const val ATTR_SIGNING_CERTIFICATE_V2 = "1.2.840.113549.1.9.16.2.47"
    private const val TIMESTAMP_TOKEN = "1.2.840.113549.1.9.16.2.14"
}
