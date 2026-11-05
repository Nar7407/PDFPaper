package com.pdfpaper.signatures.cms

import com.pdfpaper.signatures.der.DerException
import com.pdfpaper.signatures.der.DerReader
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.time.Instant

/** OIDs used while reading a PDF signature (§12.8, RFC 5652). */
internal object CmsOids {
    const val SIGNED_DATA = "1.2.840.113549.1.7.2"
    const val DATA = "1.2.840.113549.1.7.1"

    const val CONTENT_TYPE = "1.2.840.113549.1.9.3"
    const val MESSAGE_DIGEST = "1.2.840.113549.1.9.4"
    const val SIGNING_TIME = "1.2.840.113549.1.9.5"
    const val SIGNING_CERTIFICATE_V2 = "1.2.840.113549.1.9.16.2.47"
    const val ADOBE_REVOCATION_INFO = "1.2.840.113583.1.1.8"
}

/** A parsed CMS SignedData (RFC 5652 §5.1). */
internal class CmsSignedData(
    val eContentType: String,
    /** Embedded content; null for a detached signature. */
    val eContent: ByteArray?,
    /** DER of every X.509 certificate in the certificate set. */
    val certificates: List<ByteArray>,
    /** DER of every CRL in the revocation-info set. */
    val crls: List<ByteArray>,
    /** True when the set carries OCSP or another non-CRL revocation choice. */
    val otherRevocationInfo: Boolean,
    val signers: List<CmsSignerInfo>,
    val digestAlgorithmOids: List<String>
)

/** One SignerInfo (RFC 5652 §5.3). */
internal class CmsSignerInfo(
    val issuerDer: ByteArray?,
    val serial: BigInteger?,
    val subjectKeyId: ByteArray?,
    val digestOid: String,
    /** Signed attributes re-tagged as a SET OF, exactly as they were signed. */
    val signedAttrsTagged: ByteArray?,
    val signedAttrs: CmsAttributes?,
    val signatureOid: String,
    val signature: ByteArray
)

/** The attributes this verifier understands (RFC 5652 §5.3, Table 2). */
internal class CmsAttributes(
    val messageDigest: ByteArray?,
    val contentType: String?,
    val signingTime: Instant?,
    val adobeRevocationInfo: ByteArray?,
    val hasSigningCertificateV2: Boolean
)

/** Parses a ContentInfo/SignedData blob; throws [DerException] on bad data. */
internal object CmsParser {

    fun parse(contents: ByteArray): CmsSignedData {
        val outer = DerReader(contents).readSequence()
        val contentType = outer.readOid()
        if (contentType != CmsOids.SIGNED_DATA) {
            throw DerException("CMS content is $contentType, not SignedData")
        }
        val wrapper = outer.readTag(0xA0)
        val signedData = outer.sub(wrapper).readSequence()

        signedData.readInteger() // version
        val digestAlgorithms = ArrayList<String>()
        val digestSet = signedData.readSet()
        while (digestSet.hasMore()) digestAlgorithms += readAlgorithm(digestSet).first

        val encap = signedData.readSequence()
        val eContentType = encap.readOid()
        var eContent: ByteArray? = null
        if (encap.hasMore()) {
            val explicit = encap.read()
            if (explicit.tag == 0xA0) {
                val inner = encap.sub(explicit)
                if (inner.hasMore()) {
                    val value = inner.read()
                    eContent = when (value.tag) {
                        0x04 -> inner.bytes(value)
                        0x24 -> concatenatedOctets(inner, value)
                        else -> throw DerException("eContent is not an OCTET STRING")
                    }
                }
            }
        }

        val certificates = ArrayList<ByteArray>()
        val crls = ArrayList<ByteArray>()
        var otherRevocationInfo = false
        var signers: List<CmsSignerInfo> = emptyList()

        while (signedData.hasMore()) {
            val tlv = signedData.read()
            when (tlv.tag) {
                0xA0 -> {
                    val set = signedData.sub(tlv)
                    while (set.hasMore()) {
                        val choice = set.read()
                        if (choice.tag == 0x30) certificates += set.raw(choice)
                    }
                }
                0xA1 -> {
                    val set = signedData.sub(tlv)
                    while (set.hasMore()) {
                        val choice = set.read()
                        if (choice.tag == 0x30) {
                            crls += set.raw(choice)
                        } else {
                            otherRevocationInfo = true
                        }
                    }
                }
                0x31 -> {
                    val set = signedData.sub(tlv)
                    val parsed = ArrayList<CmsSignerInfo>()
                    while (set.hasMore()) parsed += parseSigner(set, contents)
                    signers = parsed
                }
                else -> {
                    // Unknown optional field: ignore rather than reject the
                    // whole signature (forward compatibility).
                }
            }
        }

        if (signers.isEmpty()) throw DerException("SignedData has no SignerInfo")
        return CmsSignedData(
            eContentType = eContentType,
            eContent = eContent,
            certificates = certificates,
            crls = crls,
            otherRevocationInfo = otherRevocationInfo,
            signers = signers,
            digestAlgorithmOids = digestAlgorithms
        )
    }

    private fun parseSigner(reader: DerReader, whole: ByteArray): CmsSignerInfo {
        val info = reader.readSequence()
        info.readInteger() // version

        var issuerDer: ByteArray? = null
        var serial: BigInteger? = null
        var subjectKeyId: ByteArray? = null
        val sid = info.read()
        when (sid.tag) {
            0x30 -> {
                val nameAndSerial = info.sub(sid)
                val issuer = nameAndSerial.read()
                if (issuer.tag != 0x30) throw DerException("signer issuer is not a Name")
                issuerDer = nameAndSerial.raw(issuer)
                serial = nameAndSerial.readInteger()
            }
            0x80 -> subjectKeyId = info.bytes(sid)
            else -> throw DerException("unsupported signer identifier")
        }

        val digestOid = readAlgorithm(info).first
        var signedAttrsTlv: DerReader.Tlv? = null
        var next = info.read()
        if (next.tag == 0xA0) {
            signedAttrsTlv = next
            next = info.read()
        }
        val signatureOid = readAlgorithmTlv(info, next).first
        val signature = info.readOctets()
        if (signature.isEmpty()) throw DerException("empty signature value")

        val attrs = signedAttrsTlv?.let { parseAttributes(it, whole) }
        val tagged = signedAttrsTlv?.let {
            val raw = whole.copyOfRange(it.start, it.end)
            raw[0] = 0x31 // [0] IMPLICIT SET OF -> SET OF for the signed bytes
            raw
        }
        return CmsSignerInfo(
            issuerDer = issuerDer,
            serial = serial,
            subjectKeyId = subjectKeyId,
            digestOid = digestOid,
            signedAttrsTagged = tagged,
            signedAttrs = attrs,
            signatureOid = signatureOid,
            signature = signature
        )
    }

    private fun parseAttributes(tlv: DerReader.Tlv, whole: ByteArray): CmsAttributes {
        val set = DerReader(whole, tlv.contentStart, tlv.contentEnd)
        var messageDigest: ByteArray? = null
        var contentType: String? = null
        var signingTime: Instant? = null
        var adobeRevocationInfo: ByteArray? = null
        var signingCertificateV2 = false

        while (set.hasMore()) {
            val attribute = set.readSequence()
            val type = attribute.readOid()
            val values = attribute.readSet()
            if (!values.hasMore()) continue
            val value = values.read()
            when (type) {
                CmsOids.MESSAGE_DIGEST -> if (value.tag == 0x04) messageDigest = values.bytes(value)
                CmsOids.CONTENT_TYPE -> if (value.tag == 0x06) contentType = values.readOid(value)
                CmsOids.SIGNING_TIME -> if (value.tag == 0x17 || value.tag == 0x18) {
                    signingTime = values.time(value)
                }
                CmsOids.ADOBE_REVOCATION_INFO -> adobeRevocationInfo = values.raw(value)
                CmsOids.SIGNING_CERTIFICATE_V2 -> signingCertificateV2 = true
            }
        }
        return CmsAttributes(
            messageDigest = messageDigest,
            contentType = contentType,
            signingTime = signingTime,
            adobeRevocationInfo = adobeRevocationInfo,
            hasSigningCertificateV2 = signingCertificateV2
        )
    }

    /** AlgorithmIdentifier -> (OID, parameters TLV). */
    private fun readAlgorithm(reader: DerReader): Pair<String, DerReader.Tlv?> {
        val tlv = reader.read()
        return readAlgorithmTlv(reader, tlv)
    }

    private fun readAlgorithmTlv(reader: DerReader, tlv: DerReader.Tlv): Pair<String, DerReader.Tlv?> {
        if (tlv.tag != 0x30) throw DerException("AlgorithmIdentifier is not a SEQUENCE")
        val algorithm = reader.sub(tlv)
        val oid = algorithm.readOid()
        val parameters = if (algorithm.hasMore()) algorithm.read() else null
        return oid to parameters
    }

    private fun concatenatedOctets(reader: DerReader, tlv: DerReader.Tlv): ByteArray {
        val inner = reader.sub(tlv)
        val out = ByteArrayOutputStream()
        while (inner.hasMore()) {
            val part = inner.readTag(0x04)
            out.write(inner.bytes(part))
        }
        return out.toByteArray()
    }
}
