package com.pdfpaper.signatures

import com.pdfpaper.engine.form.FieldKind
import com.pdfpaper.engine.form.PdfField
import com.pdfpaper.engine.form.formFields
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.io.RandomAccessSource
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.signatures.cms.CmsAttributes
import com.pdfpaper.signatures.cms.CmsOids
import com.pdfpaper.signatures.cms.CmsParser
import com.pdfpaper.signatures.cms.CmsSignerInfo
import com.pdfpaper.signatures.cms.CmsSignedData
import com.pdfpaper.signatures.der.DerReader
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertPathBuilder
import java.security.cert.CertStore
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.PKIXCertPathBuilderResult
import java.security.cert.TrustAnchor
import java.security.cert.X509CRL
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import javax.security.auth.x500.X500Principal

/**
 * Validates PDF digital signatures (T24): finds signature fields, checks the
 * /ByteRange digest, verifies the CMS signature with the signer certificate,
 * builds a certificate chain against a [TrustStore], checks embedded revocation
 * data, and detects incremental updates after signing.
 *
 * Only standards available in the JDK are used (JCA + CertificateFactory plus
 * the small DER/CMS reader in this module). No network access is performed.
 */
object PdfSignatures {

    /** Verifies every signature field in [file]; closes the file afterwards. */
    fun verify(file: File, trustStore: TrustStore = EmptyTrustStore): Result<SignatureVerification> =
        runCatching { verifySource(FileSource(file), trustStore) }

    /** Verifies every signature field in [bytes]. */
    fun verify(bytes: ByteArray, trustStore: TrustStore = EmptyTrustStore): Result<SignatureVerification> =
        runCatching { verifySource(ByteArraySource(bytes), trustStore) }

    /**
     * Verifies every signature field in [source]. The source is consumed by the
     * verifier and is closed together with the document.
     */
    fun verify(
        source: RandomAccessSource,
        trustStore: TrustStore = EmptyTrustStore
    ): Result<SignatureVerification> = runCatching { verifySource(source, trustStore) }

    private fun verifySource(
        source: RandomAccessSource,
        trustStore: TrustStore
    ): SignatureVerification {
        val document = PdfDocument.open(source).getOrThrow()
        document.use {
            return verifyDocument(document, source, trustStore)
        }
    }

    private fun verifyDocument(
        document: PdfDocument,
        source: RandomAccessSource,
        trustStore: TrustStore
    ): SignatureVerification {
        val signatureFields = runCatching {
            document.formFields().filter { it.kind == FieldKind.SIGNATURE }
        }.getOrDefault(emptyList())

        if (signatureFields.isEmpty()) return SignatureVerification(emptyList(), source.size)

        if (document.isEncrypted) {
            val reports = signatureFields.map { field ->
                SignatureReport(
                    fieldName = field.name,
                    state = SignatureState.UNSUPPORTED,
                    fileLength = source.size,
                    problems = listOf("encrypted document: signature verification is skipped")
                )
            }
            return SignatureVerification(reports, source.size)
        }

        val reports = signatureFields.map { field ->
            runCatching { verifyField(document, source, field, trustStore) }.getOrElse { error ->
                SignatureReport(
                    fieldName = field.name,
                    state = SignatureState.INVALID,
                    fileLength = source.size,
                    problems = listOf("verification failed: ${describe(error)}")
                )
            }
        }
        return SignatureVerification(reports, source.size)
    }

    // ------------------------------------------------------------------ field

    private fun verifyField(
        document: PdfDocument,
        source: RandomAccessSource,
        field: PdfField,
        trustStore: TrustStore
    ): SignatureReport {
        val dict = field.dict
        val sigDict = resolve(document, dict["V"]) as? PdfDict
            ?: return SignatureReport(
                fieldName = field.name,
                state = SignatureState.INVALID,
                fileLength = source.size,
                problems = listOf("signature field has no /V signature dictionary")
            )

        val subFilter = nameOf(document, sigDict["SubFilter"])
        val filter = nameOf(document, sigDict["Filter"])
        val declaredName = textOf(document, sigDict["Name"])
        val reason = textOf(document, sigDict["Reason"])
        val location = textOf(document, sigDict["Location"])
        val timeText = textOf(document, sigDict["M"])

        val base = SignatureReport(
            fieldName = field.name,
            state = SignatureState.INVALID,
            subFilter = subFilter,
            filter = filter,
            declaredName = declaredName,
            signingTimeText = timeText,
            reason = reason,
            location = location,
            fileLength = source.size
        )

        val kind = when (subFilter) {
            "adbe.pkcs7.detached", "ETSI.CAdES.detached" -> Kind.DETACHED
            "adbe.pkcs7.sha1" -> Kind.SHA1
            null -> return base.copy(
                state = SignatureState.UNSUPPORTED,
                problems = listOf("signature dictionary has no /SubFilter")
            )
            else -> return base.copy(
                state = SignatureState.UNSUPPORTED,
                problems = listOf("unsupported /SubFilter: $subFilter")
            )
        }

        val contents = (resolve(document, sigDict["Contents"]) as? PdfString)?.bytes
        if (contents == null || contents.isEmpty()) {
            return base.copy(
                state = SignatureState.INVALID,
                problems = listOf("signature dictionary has no /Contents")
            )
        }

        val byteRange = readByteRange(document, sigDict["ByteRange"])
            ?: return base.copy(
                state = SignatureState.INVALID,
                problems = listOf("/ByteRange is missing or malformed")
            )

        val coverage = analyzeCoverage(source, byteRange, contents)
        if (coverage.fatal != null) {
            return base.copy(
                state = SignatureState.INVALID,
                ranges = coverage.ranges,
                coversWholeFile = false,
                updatesAfterSigning = coverage.updates,
                problems = listOf(coverage.fatal)
            )
        }

        val cms = try {
            CmsParser.parse(contents)
        } catch (error: Exception) {
            return base.copy(
                state = SignatureState.INVALID,
                ranges = coverage.ranges,
                coversWholeFile = coverage.coversWholeFile,
                updatesAfterSigning = coverage.updates,
                problems = listOf("malformed CMS signature: ${describe(error)}")
            )
        }

        val problems = ArrayList<String>()
        if (coverage.updates.isNotEmpty()) {
            problems += "file was updated after signing"
        }

        if (cms.signers.size != 1) {
            return base.copy(
                state = SignatureState.UNSUPPORTED,
                ranges = coverage.ranges,
                coversWholeFile = coverage.coversWholeFile,
                updatesAfterSigning = coverage.updates,
                problems = problems + "CMS contains ${cms.signers.size} signer infos; exactly one is supported"
            )
        }
        val signerInfo = cms.signers.first()
        val attrs = signerInfo.signedAttrs

        val digestJca = digestAlgorithm(signerInfo.digestOid)
        if (digestJca == null) {
            return base.copy(
                state = SignatureState.UNSUPPORTED,
                ranges = coverage.ranges,
                coversWholeFile = coverage.coversWholeFile,
                updatesAfterSigning = coverage.updates,
                problems = problems + "unsupported digest algorithm: ${signerInfo.digestOid}"
            )
        }

        val certs = cms.certificates.mapNotNull(::parseCertificate)
        if (certs.size != cms.certificates.size) {
            problems += "${cms.certificates.size - certs.size} certificate(s) in the CMS could not be parsed"
        }
        val signerCert = certs.firstOrNull { matches(it, signerInfo) }
        if (signerCert == null) {
            problems += "signer certificate is not included in the CMS"
        }

        // Check 1: digest over the signed byte ranges, compared with the
        // messageDigest signed attribute (or with the embedded SHA-1 value for
        // the legacy adbe.pkcs7.sha1 construction).
        val rangeDigest = digestRanges(source, coverage.ranges, digestJca)
        var digestMatches: Boolean? = null
        val digestLabel = if (kind == Kind.SHA1) "SHA-1" else digestJca

        if (kind == Kind.DETACHED) {
            if (attrs != null) {
                val signedDigest = attrs.messageDigest
                if (signedDigest == null) {
                    problems += "signed attributes contain no messageDigest"
                    digestMatches = false
                } else {
                    digestMatches = signedDigest.contentEquals(rangeDigest)
                    if (digestMatches == false) problems += "byte-range digest does not match the signed digest"
                }
                if (attrs.contentType != null && attrs.contentType != CmsOids.DATA) {
                    problems += "signed contentType is ${attrs.contentType}, expected data"
                    digestMatches = false
                }
            }
        } else {
            val embedded = cms.eContent
            if (embedded == null) {
                return base.copy(
                    state = SignatureState.INVALID,
                    ranges = coverage.ranges,
                    coversWholeFile = coverage.coversWholeFile,
                    updatesAfterSigning = coverage.updates,
                    problems = problems + "adbe.pkcs7.sha1 signature has no embedded digest"
                )
            }
            val sha1 = MessageDigest.getInstance("SHA-1")
            updateDigest(sha1, source, coverage.ranges)
            digestMatches = embedded.contentEquals(sha1.digest())
            if (digestMatches == false) problems += "byte-range SHA-1 digest does not match the embedded digest"
            val cmsDigest = digestBytes(embedded, digestJca)
            val signedDigest = attrs?.messageDigest
            if (signedDigest != null && signedDigest.contentEquals(cmsDigest) == false) {
                problems += "signed messageDigest does not match the embedded content"
                digestMatches = false
            }
        }

        // Check 2: CMS signature with the signer certificate.
        var cmsValid: Boolean? = null
        var unsupportedAlgorithm: String? = null
        if (signerCert != null) {
            val jcaName = signatureAlgorithm(signerInfo.signatureOid, digestJca)
            if (jcaName == null) {
                unsupportedAlgorithm = "unsupported signature algorithm: ${signerInfo.signatureOid}"
                problems += unsupportedAlgorithm
            } else {
                try {
                    val verifier = Signature.getInstance(jcaName)
                    verifier.initVerify(signerCert.publicKey)
                    if (signerInfo.signedAttrsTagged != null) {
                        verifier.update(signerInfo.signedAttrsTagged)
                    } else if (kind == Kind.DETACHED) {
                        updateSignature(verifier, source, coverage.ranges)
                    } else {
                        verifier.update(cms.eContent!!)
                    }
                    cmsValid = verifier.verify(signerInfo.signature)
                    if (cmsValid != true) problems += "CMS signature does not match the signer certificate"
                } catch (error: Exception) {
                    cmsValid = false
                    problems += "signature check failed: ${describe(error)}"
                }
            }
        }

        // Check 3: chain building against the supplied trust store.
        var chainStatus = ChainStatus.NOT_CHECKED
        var trustAnchor: String? = null
        if (signerCert != null) {
            val chain = buildChain(signerCert, certs, trustStore)
            chainStatus = chain.status
            trustAnchor = chain.anchor
            problems += chain.problems
        }

        // Revocation: embedded CRLs only; OCSP is reported, never fetched.
        val revocation = if (signerCert == null) {
            RevocationResult(RevocationStatus.NOT_CHECKED, "signer certificate unavailable")
        } else {
            val trustCerts = runCatching {
                trustStore.trustAnchors() + trustStore.intermediates()
            }.getOrDefault(emptyList())
            checkRevocation(signerCert, certs + trustCerts, cms, attrs)
        }

        val signingTime = attrs?.signingTime ?: parsePdfDate(timeText)

        val state = when {
            digestMatches == false -> SignatureState.INVALID
            cmsValid == false -> SignatureState.INVALID
            unsupportedAlgorithm != null -> SignatureState.UNSUPPORTED
            coverage.updates.isNotEmpty() -> SignatureState.MODIFIED_AFTER_SIGNING
            signerCert == null -> SignatureState.INTACT_SIGNER_UNKNOWN
            chainStatus != ChainStatus.TRUSTED -> SignatureState.INTACT_SIGNER_UNKNOWN
            revocation.status == RevocationStatus.REVOKED -> SignatureState.INTACT_SIGNER_UNKNOWN
            else -> SignatureState.INTACT_AND_TRUSTED
        }

        return base.copy(
            state = state,
            signer = signerCert?.let(::identityOf),
            signingTime = signingTime,
            digestAlgorithm = digestLabel,
            digestMatches = digestMatches,
            cmsSignatureValid = cmsValid,
            chainStatus = chainStatus,
            trustAnchor = trustAnchor,
            revocation = revocation.status,
            revocationNote = revocation.note,
            ranges = coverage.ranges,
            coversWholeFile = coverage.coversWholeFile,
            updatesAfterSigning = coverage.updates,
            problems = problems
        )
    }

    // -------------------------------------------------------------- coverage

    private data class Coverage(
        val ranges: List<SignedRange>,
        val updates: List<PostSigningUpdate>,
        val coversWholeFile: Boolean,
        val fatal: String?
    )

    private fun analyzeCoverage(
        source: RandomAccessSource,
        byteRange: List<Long>,
        contents: ByteArray
    ): Coverage {
        if (byteRange.size < 4 || byteRange.size % 2 != 0) {
            return Coverage(emptyList(), emptyList(), false, "/ByteRange must contain at least two offset/length pairs")
        }

        val ranges = ArrayList<SignedRange>(byteRange.size / 2)
        var cursor = 0L
        for (i in byteRange.indices step 2) {
            val offset = byteRange[i]
            val length = byteRange[i + 1]
            if (offset < 0 || length < 0) {
                return Coverage(ranges, emptyList(), false, "/ByteRange contains a negative value")
            }
            if (offset < cursor) {
                return Coverage(ranges, emptyList(), false, "/ByteRange ranges overlap or are out of order")
            }
            val end = offset + length
            if (end < offset || end > source.size) {
                return Coverage(ranges, emptyList(), false, "/ByteRange extends outside the file")
            }
            ranges += SignedRange(offset, length)
            cursor = end
        }

        val gaps = ArrayList<LongRange>()
        var position = 0L
        for (range in ranges) {
            if (range.offset > position) gaps += position until range.offset
            if (range.end > position) position = range.end
        }
        if (position < source.size) gaps += position until source.size

        var contentsGap: LongRange? = null
        val otherGaps = ArrayList<LongRange>()
        for (gap in gaps) {
            val bytes = readGap(source, gap)
            if (contentsGap == null && bytes != null && hexStringMatches(bytes, contents)) {
                contentsGap = gap
            } else {
                otherGaps += gap
            }
        }
        if (contentsGap == null) {
            return Coverage(ranges, emptyList(), false, "/ByteRange does not isolate the /Contents string")
        }

        var coversWholeFile = true
        val updates = ArrayList<PostSigningUpdate>()
        for (gap in otherGaps) {
            val bytes = readGap(source, gap)
            if (bytes != null && bytes.all(::isWhiteSpace)) continue
            coversWholeFile = false
            // Unsigned bytes in the middle of the file are not an incremental
            // update; they mean the byte range is not trustworthy.
            if (gap.last + 1 < source.size) {
                return Coverage(ranges, updates, false, "unsigned bytes at offset ${gap.first}")
            }
            val eofs = eofOffsets(source, gap)
            updates += PostSigningUpdate(gap.first, gap.last - gap.first + 1, eofs)
        }

        // Bytes before the first range are also unsigned; the gap loop above
        // already classified them (they are only legal if whitespace).
        return Coverage(ranges, updates, coversWholeFile, null)
    }

    private fun readGap(source: RandomAccessSource, gap: LongRange): ByteArray? {
        val length = gap.last - gap.first + 1
        if (length < 0) return ByteArray(0)
        if (length > MAX_GAP_BYTES) return null
        return runCatching { source.read(gap.first, length.toInt()) }.getOrNull()
    }

    private fun hexStringMatches(raw: ByteArray, contents: ByteArray): Boolean {
        val text = String(raw, StandardCharsets.ISO_8859_1).trim()
        if (text.isEmpty()) return false
        var body = text
        if (body.startsWith("<") && body.endsWith(">")) {
            body = body.substring(1, body.length - 1)
        }
        val digits = StringBuilder(body.length)
        for (char in body) {
            when {
                char.isWhitespace() -> Unit
                char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F' -> digits.append(char)
                else -> return false
            }
        }
        if (digits.length % 2 != 0) return false
        val decoded = ByteArray(digits.length / 2) { index ->
            digits.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        val size = maxOf(decoded.size, contents.size)
        for (i in 0 until size) {
            val a = if (i < decoded.size) decoded[i].toInt() and 0xFF else 0
            val b = if (i < contents.size) contents[i].toInt() and 0xFF else 0
            if (a != b) return false
        }
        return true
    }

    private fun eofOffsets(source: RandomAccessSource, gap: LongRange): List<Long> {
        val length = gap.last - gap.first + 1
        if (length <= 0) return emptyList()
        val out = ArrayList<Long>()
        val chunkSize = 64 * 1024
        val overlap = 4L
        var position = 0L
        var carry = ByteArray(0)
        while (position < length) {
            if (out.size >= MAX_EOF_MARKERS) return out
            val count = minOf(chunkSize.toLong(), length - position).toInt()
            val chunk = runCatching { source.read(gap.first + position, count) }.getOrNull()
                ?: return out
            val haystack = carry + chunk
            var index = indexOfEof(haystack)
            while (index >= 0) {
                val absolute = gap.first + position - carry.size + index
                if (out.isEmpty() || out.last() != absolute) out += absolute
                index = indexOfEof(haystack, index + 1)
            }
            carry = if (haystack.size >= overlap) haystack.copyOfRange(haystack.size - overlap.toInt(), haystack.size) else haystack
            position += count
        }
        return out
    }

    private fun indexOfEof(bytes: ByteArray, from: Int = 0): Int {
        val marker = byteArrayOf('%'.code.toByte(), '%'.code.toByte(), 'E'.code.toByte(), 'O'.code.toByte(), 'F'.code.toByte())
        outer@ for (i in from..bytes.size - marker.size) {
            for (j in marker.indices) {
                if (bytes[i + j] != marker[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun isWhiteSpace(byte: Byte): Boolean = when (byte.toInt() and 0xFF) {
        0x00, 0x09, 0x0A, 0x0C, 0x0D, 0x20 -> true
        else -> false
    }

    private fun readByteRange(document: PdfDocument, obj: PdfObject?): List<Long>? {
        val array = resolve(document, obj) as? PdfArray ?: return null
        val out = ArrayList<Long>(array.items.size)
        for (item in array.items) {
            when (val value = resolve(document, item)) {
                is PdfInteger -> out += value.value
                is PdfReal -> {
                    val asLong = value.value.toLong()
                    if (asLong.toDouble() != value.value) return null
                    out += asLong
                }
                else -> return null
            }
        }
        return out
    }

    // --------------------------------------------------------------- crypto

    private fun digestAlgorithm(oid: String): String? = DIGEST_OIDS[oid]

    private fun signatureAlgorithm(oid: String, digestJca: String): String? {
        SIGNATURE_OIDS[oid]?.let { return it }
        return when (oid) {
            "1.2.840.113549.1.1.1" -> "${digestJca.replace("-", "")}withRSA"
            "1.2.840.10045.2.1" -> "${digestJca.replace("-", "")}withECDSA"
            "1.2.840.10040.4.1" -> "${digestJca.replace("-", "")}withDSA"
            else -> null
        }
    }

    private fun digestRanges(
        source: RandomAccessSource,
        ranges: List<SignedRange>,
        algorithm: String
    ): ByteArray {
        val digest = MessageDigest.getInstance(algorithm)
        updateDigest(digest, source, ranges)
        return digest.digest()
    }

    private fun updateDigest(
        digest: MessageDigest,
        source: RandomAccessSource,
        ranges: List<SignedRange>
    ) {
        for (range in ranges) {
            var position = 0L
            while (position < range.length) {
                val count = minOf(CHUNK.toLong(), range.length - position).toInt()
                digest.update(source.read(range.offset + position, count))
                position += count
            }
        }
    }

    private fun updateSignature(
        verifier: Signature,
        source: RandomAccessSource,
        ranges: List<SignedRange>
    ) {
        for (range in ranges) {
            var position = 0L
            while (position < range.length) {
                val count = minOf(CHUNK.toLong(), range.length - position).toInt()
                verifier.update(source.read(range.offset + position, count))
                position += count
            }
        }
    }

    private fun digestBytes(bytes: ByteArray, algorithm: String): ByteArray =
        MessageDigest.getInstance(algorithm).digest(bytes)

    private fun parseCertificate(der: ByteArray): X509Certificate? = runCatching {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }.getOrNull()

    private fun matches(cert: X509Certificate, signer: CmsSignerInfo): Boolean {
        if (signer.subjectKeyId != null) {
            return subjectKeyIdentifier(cert)?.contentEquals(signer.subjectKeyId) == true
        }
        val issuer = signer.issuerDer ?: return false
        val serial = signer.serial ?: return false
        if (cert.serialNumber != serial) return false
        return runCatching { X500Principal(issuer) }.getOrNull() == cert.issuerX500Principal
    }

    private fun subjectKeyIdentifier(cert: X509Certificate): ByteArray? {
        val extension = runCatching { cert.getExtensionValue("2.5.29.14") }.getOrNull() ?: return null
        return runCatching {
            val inner = DerReader(extension).readOctets()
            DerReader(inner).readOctets()
        }.getOrNull()
    }

    private fun identityOf(cert: X509Certificate): SignerIdentity {
        val subject = cert.subjectX500Principal.getName()
        val cn = Regex("""(?:^|,)\s*CN=([^,]+)""").find(subject)?.groupValues?.get(1)
        return SignerIdentity(
            subject = subject,
            issuer = cert.issuerX500Principal.getName(),
            serialNumber = cert.serialNumber.toString(16),
            notBefore = cert.notBefore.toInstant(),
            notAfter = cert.notAfter.toInstant(),
            commonName = cn
        )
    }

    // ---------------------------------------------------------------- chain

    private class ChainResult(
        val status: ChainStatus,
        val anchor: String?,
        val problems: List<String> = emptyList()
    )

    private fun buildChain(
        signer: X509Certificate,
        cmsCerts: List<X509Certificate>,
        trustStore: TrustStore
    ): ChainResult {
        val anchors = runCatching { trustStore.trustAnchors() }.getOrDefault(emptyList())
        if (anchors.isEmpty()) {
            return ChainResult(
                ChainStatus.NOT_CHECKED,
                null,
                listOf("no trust anchors configured; signer trust was not evaluated")
            )
        }

        val problems = ArrayList<String>()
        validityProblem(signer)?.let {
            problems += it
            return ChainResult(ChainStatus.UNTRUSTED, null, problems)
        }

        val allCerts = LinkedHashMap<String, X509Certificate>()
        for (cert in cmsCerts + runCatching { trustStore.intermediates() }.getOrDefault(emptyList())) {
            val encoded = runCatching { cert.encoded }.getOrNull() ?: continue
            allCerts.putIfAbsent(Base64.getEncoder().encodeToString(encoded), cert)
        }

        val signerEncoded = runCatching { signer.encoded }.getOrNull()
        val isAnchor = signerEncoded != null && anchors.any {
            runCatching { it.encoded.contentEquals(signerEncoded) }.getOrDefault(false)
        }
        if (isAnchor) {
            return ChainResult(ChainStatus.TRUSTED, signer.subjectX500Principal.name)
        }

        return try {
            val selector = X509CertSelector().apply { certificate = signer }
            val parameters = PKIXBuilderParameters(
                anchors.map { TrustAnchor(it, null) }.toSet(),
                selector
            ).apply {
                isRevocationEnabled = false
                addCertStore(
                    CertStore.getInstance(
                        "Collection",
                        CollectionCertStoreParameters(allCerts.values)
                    )
                )
            }
            val result = CertPathBuilder.getInstance("PKIX").build(parameters) as PKIXCertPathBuilderResult
            val path = result.certPath.certificates.filterIsInstance<X509Certificate>()
            for (cert in path) {
                validityProblem(cert)?.let {
                    problems += it
                    return ChainResult(ChainStatus.UNTRUSTED, null, problems)
                }
            }
            val anchorCert = result.trustAnchor?.trustedCert
            if (anchorCert != null) {
                validityProblem(anchorCert)?.let {
                    problems += "trust anchor is not currently valid: $it"
                    return ChainResult(ChainStatus.UNTRUSTED, null, problems)
                }
            }
            ChainResult(ChainStatus.TRUSTED, anchorCert?.subjectX500Principal?.name, problems)
        } catch (error: Exception) {
            val expired = causeChain(error).filterIsInstance<CertificateExpiredException>().firstOrNull()
            val notYet = causeChain(error).filterIsInstance<CertificateNotYetValidException>().firstOrNull()
            when {
                expired != null -> problems += "certificate in the signer chain has expired"
                notYet != null -> problems += "certificate in the signer chain is not yet valid"
                else -> problems += "certificate chain is not trusted: ${describe(error)}"
            }
            ChainResult(ChainStatus.UNTRUSTED, null, problems)
        }
    }

    private fun validityProblem(cert: X509Certificate): String? = try {
        cert.checkValidity()
        null
    } catch (_: CertificateExpiredException) {
        "signer certificate expired on ${cert.notAfter.toInstant()} (${cert.subjectX500Principal.name})"
    } catch (_: CertificateNotYetValidException) {
        "signer certificate is not valid before ${cert.notBefore.toInstant()} (${cert.subjectX500Principal.name})"
    } catch (_: Exception) {
        null
    }

    private fun causeChain(error: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>()
        var current: Throwable? = error
        var guard = 0
        while (current != null && guard++ < 10) {
            out += current
            current = current.cause
        }
        return out
    }

    // ----------------------------------------------------------- revocation

    private class RevocationResult(val status: RevocationStatus, val note: String?)

    private fun checkRevocation(
        signer: X509Certificate,
        cmsCerts: List<X509Certificate>,
        cms: CmsSignedData,
        attrs: CmsAttributes?
    ): RevocationResult {
        val crlDers = ArrayList<ByteArray>()
        crlDers += cms.crls
        var hasOcsp = cms.otherRevocationInfo
        val archive = attrs?.adobeRevocationInfo
        if (archive != null) {
            val fromArchive = parseAdobeRevocationInfo(archive)
            crlDers += fromArchive.crls
            hasOcsp = hasOcsp || fromArchive.hasOcsp
        }

        if (crlDers.isEmpty()) {
            return if (hasOcsp) {
                RevocationResult(
                    RevocationStatus.NOT_CHECKED,
                    "embedded OCSP response present; OCSP is not checked (no network access)"
                )
            } else {
                RevocationResult(RevocationStatus.NOT_CHECKED, "no embedded CRL or OCSP data")
            }
        }

        val factory = CertificateFactory.getInstance("X.509")
        var applicable = 0
        var revoked = false
        var unusable = false
        val notes = ArrayList<String>()
        for (der in crlDers) {
            val crl = runCatching {
                factory.generateCRL(ByteArrayInputStream(der)) as X509CRL
            }.getOrNull() ?: continue
            if (crl.issuerX500Principal != signer.issuerX500Principal) continue

            val issuer = cmsCerts.firstOrNull { it.subjectX500Principal == crl.issuerX500Principal }
            if (issuer == null) {
                unusable = true
                notes += "CRL issuer certificate is not included"
                continue
            }
            val crlSignatureValid = runCatching { crl.verify(issuer.publicKey) }.getOrDefault(false)
            if (crlSignatureValid == false) {
                unusable = true
                notes += "embedded CRL signature is invalid"
                continue
            }
            val now = Instant.now()
            if (crl.thisUpdate.toInstant().isAfter(now)) {
                unusable = true
                notes += "embedded CRL is not yet valid"
                continue
            }
            val next = crl.nextUpdate?.toInstant()
            if (next != null && next.isBefore(now)) {
                unusable = true
                notes += "embedded CRL has expired"
                continue
            }
            applicable++
            if (runCatching { crl.isRevoked(signer) }.getOrDefault(false)) revoked = true
        }

        return when {
            revoked -> RevocationResult(RevocationStatus.REVOKED, "signer certificate is revoked by an embedded CRL")
            applicable > 0 -> RevocationResult(RevocationStatus.GOOD, "checked against an embedded CRL")
            unusable -> RevocationResult(
                RevocationStatus.UNKNOWN,
                notes.distinct().joinToString("; ")
            )
            else -> RevocationResult(
                RevocationStatus.NOT_CHECKED,
                "no CRL for the signer's issuer was embedded"
            )
        }
    }

    private class ArchiveRevocationInfo(val crls: List<ByteArray>, val hasOcsp: Boolean)

    /**
     * Adobe's adbe-revocationInfoArchival signed attribute (§12.8.4.4):
     * SEQUENCE { crl [0] EXPLICIT SEQUENCE OF CertificateList OPTIONAL,
     *            ocsp [1] EXPLICIT SEQUENCE OF OCSPResponse OPTIONAL,
     *            other [2] ... }.
     */
    private fun parseAdobeRevocationInfo(raw: ByteArray): ArchiveRevocationInfo {
        val crls = ArrayList<ByteArray>()
        var hasOcsp = false
        runCatching {
            val sequence = DerReader(raw).readSequence()
            while (sequence.hasMore()) {
                val choice = sequence.read()
                when (choice.tag) {
                    0xA0 -> {
                        val wrapper = sequence.sub(choice)
                        if (wrapper.hasMore()) {
                            val list = wrapper.readTag(0x30)
                            val entries = wrapper.sub(list)
                            while (entries.hasMore()) {
                                val crl = entries.read()
                                if (crl.tag == 0x30) crls += entries.raw(crl)
                            }
                        }
                    }
                    0xA1 -> hasOcsp = true
                }
            }
        }
        return ArchiveRevocationInfo(crls, hasOcsp)
    }

    // --------------------------------------------------------------- helpers

    private enum class Kind { DETACHED, SHA1 }

    private fun resolve(document: PdfDocument, obj: PdfObject?): PdfObject? = when (obj) {
        null -> null
        is PdfRef -> document.resolve(obj)
        else -> obj
    }

    private fun nameOf(document: PdfDocument, obj: PdfObject?): String? =
        (resolve(document, obj) as? PdfName)?.name

    private fun textOf(document: PdfDocument, obj: PdfObject?): String? =
        (resolve(document, obj) as? PdfString)?.text()

    private fun describe(error: Throwable, limit: Int = 3): String {
        val messages = causeChain(error).mapNotNull { it.message?.takeIf(String::isNotBlank) }.distinct()
        if (messages.isEmpty()) return error.javaClass.simpleName
        return messages.take(limit).joinToString(": ")
    }

    /** `/M` (ISO 32000-1 §7.9.4) is best-effort; malformed values just yield null. */
    private fun parsePdfDate(text: String?): Instant? {
        if (text.isNullOrBlank()) return null
        var value = text.trim()
        if (value.startsWith("D:")) value = value.substring(2)
        val digits = value.takeWhile { it.isDigit() }
        if (digits.length < 4) return null
        fun part(start: Int, default: Int): Int =
            if (digits.length >= start + 2) digits.substring(start, start + 2).toIntOrNull() ?: default else default
        val year = digits.substring(0, 4).toIntOrNull() ?: return null
        val month = part(4, 1)
        val day = part(6, 1)
        val hour = part(8, 0)
        val minute = part(10, 0)
        val second = part(12, 0)
        val rest = value.substring(digits.length)
        val zone = when {
            rest.startsWith("Z") || rest.startsWith("z") -> ZoneOffset.UTC
            rest.startsWith("+") || rest.startsWith("-") -> {
                val sign = if (rest[0] == '-') -1 else 1
                val zoneDigits = rest.drop(1).filter(Char::isDigit)
                val hours = zoneDigits.take(2).toIntOrNull() ?: 0
                val minutes = zoneDigits.drop(2).take(2).toIntOrNull() ?: 0
                runCatching { ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes) }.getOrDefault(ZoneOffset.UTC)
            }
            else -> ZoneOffset.UTC
        }
        return runCatching {
            LocalDateTime.of(year, month, day, hour, minute, second).toInstant(zone)
        }.getOrNull()
    }

    private val DIGEST_OIDS = mapOf(
        "1.2.840.113549.2.5" to "MD5",
        "1.3.14.3.2.26" to "SHA-1",
        "2.16.840.1.101.3.4.2.4" to "SHA-224",
        "2.16.840.1.101.3.4.2.1" to "SHA-256",
        "2.16.840.1.101.3.4.2.2" to "SHA-384",
        "2.16.840.1.101.3.4.2.3" to "SHA-512"
    )

    private val SIGNATURE_OIDS = mapOf(
        "1.2.840.113549.1.1.4" to "MD5withRSA",
        "1.2.840.113549.1.1.5" to "SHA1withRSA",
        "1.2.840.113549.1.1.14" to "SHA224withRSA",
        "1.2.840.113549.1.1.11" to "SHA256withRSA",
        "1.2.840.113549.1.1.12" to "SHA384withRSA",
        "1.2.840.113549.1.1.13" to "SHA512withRSA",
        "1.2.840.10045.4.1" to "SHA1withECDSA",
        "1.2.840.10045.4.3.1" to "SHA224withECDSA",
        "1.2.840.10045.4.3.2" to "SHA256withECDSA",
        "1.2.840.10045.4.3.3" to "SHA384withECDSA",
        "1.2.840.10045.4.3.4" to "SHA512withECDSA",
        "1.2.840.10040.4.3" to "SHA1withDSA",
        "2.16.840.1.101.3.4.3.2" to "SHA256withDSA"
    )

    private const val CHUNK = 64 * 1024
    private const val MAX_GAP_BYTES = 8L * 1024 * 1024

    /** Reporting bound; a hostile tail must not build an unbounded marker list. */
    private const val MAX_EOF_MARKERS = 256
}
