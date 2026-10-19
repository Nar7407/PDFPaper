package com.pdfpaper.signatures

import java.time.Instant

/**
 * The outcome of validating one signature field. The five states are the ones
 * exposed to callers and to the CLI.
 */
enum class SignatureState {
    /** Signature is intact and chains to a configured trust anchor. */
    INTACT_AND_TRUSTED,

    /** Signature is intact but the signer is unknown, untrusted, expired or revoked. */
    INTACT_SIGNER_UNKNOWN,

    /** Signature is intact for its byte range, but bytes were appended after it. */
    MODIFIED_AFTER_SIGNING,

    /** Digest or CMS signature does not match (tampered/corrupt). */
    INVALID,

    /** A valid construction the verifier does not implement (e.g. encrypted PDFs). */
    UNSUPPORTED
}

/** Result of certificate-chain building. */
enum class ChainStatus { TRUSTED, UNTRUSTED, NOT_CHECKED }

/** Result of revocation checking (only embedded data is ever consulted). */
enum class RevocationStatus { NOT_CHECKED, GOOD, REVOKED, UNKNOWN }

/** One signed byte range from /ByteRange. */
data class SignedRange(val offset: Long, val length: Long) {
    init {
        require(offset >= 0) { "negative offset" }
        require(length >= 0) { "negative length" }
    }

    val end: Long get() = offset + length
}

/**
 * Bytes after the signed ranges. [eofOffsets] lists `%%EOF` markers in the
 * appended region, so the caller can tell one incremental update from several.
 */
data class PostSigningUpdate(
    val offset: Long,
    val length: Long,
    val eofOffsets: List<Long>
)

/** Identity of the certificate that made the signature. */
data class SignerIdentity(
    val subject: String,
    val issuer: String,
    val serialNumber: String,
    val notBefore: Instant,
    val notAfter: Instant,
    val commonName: String?
)

/** Everything learned about one signature field. */
data class SignatureReport(
    val fieldName: String,
    val state: SignatureState,
    val subFilter: String? = null,
    val filter: String? = null,
    /** /Name entry in the signature dictionary, if present. */
    val declaredName: String? = null,
    val signer: SignerIdentity? = null,
    /** signingTime signed attribute from the CMS, when present. */
    val signingTime: Instant? = null,
    /** Raw /M entry, useful when the CMS has no signingTime. */
    val signingTimeText: String? = null,
    val reason: String? = null,
    val location: String? = null,
    val digestAlgorithm: String? = null,
    /** null when the CMS carries no signed messageDigest attribute. */
    val digestMatches: Boolean? = null,
    /** null when no certificate was available to check the signature. */
    val cmsSignatureValid: Boolean? = null,
    val chainStatus: ChainStatus = ChainStatus.NOT_CHECKED,
    val trustAnchor: String? = null,
    val revocation: RevocationStatus = RevocationStatus.NOT_CHECKED,
    /** Human-readable revocation outcome (e.g. "no embedded CRL or OCSP data"). */
    val revocationNote: String? = null,
    val ranges: List<SignedRange> = emptyList(),
    val fileLength: Long = 0,
    val coversWholeFile: Boolean = false,
    val updatesAfterSigning: List<PostSigningUpdate> = emptyList(),
    val problems: List<String> = emptyList()
)

/**
 * The verification of a whole document. [state] aggregates the per-field states
 * by severity (INVALID > MODIFIED_AFTER_SIGNING > UNSUPPORTED >
 * INTACT_SIGNER_UNKNOWN > INTACT_AND_TRUSTED); [signatures] keeps the details.
 */
class SignatureVerification(
    val signatures: List<SignatureReport>,
    val fileLength: Long
) {
    val state: SignatureState? = signatures.minByOrNull { severity(it.state) }?.state

    val allTrusted: Boolean
        get() = signatures.isNotEmpty() && signatures.all { it.state == SignatureState.INTACT_AND_TRUSTED }

    private fun severity(state: SignatureState): Int = when (state) {
        SignatureState.INVALID -> 0
        SignatureState.MODIFIED_AFTER_SIGNING -> 1
        SignatureState.UNSUPPORTED -> 2
        SignatureState.INTACT_SIGNER_UNKNOWN -> 3
        SignatureState.INTACT_AND_TRUSTED -> 4
    }
}
