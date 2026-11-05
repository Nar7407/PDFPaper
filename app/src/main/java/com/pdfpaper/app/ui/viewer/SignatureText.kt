package com.pdfpaper.app.ui.viewer

import com.pdfpaper.signatures.ChainStatus
import com.pdfpaper.signatures.RevocationStatus
import com.pdfpaper.signatures.SignatureReport
import com.pdfpaper.signatures.SignatureState

enum class SignatureCheckState { PASSED, FAILED, NOT_CHECKED }

data class SignatureCheck(
    val label: String,
    val state: SignatureCheckState,
    val detail: String
)

/**
 * Plain-language presentation of the engine's signature report (task UI-2).
 * The rules are pure so they can be unit-tested; the panel never invents a
 * verdict the verifier did not reach.
 */
fun signatureHeadline(report: SignatureReport): String = when (report.state) {
    SignatureState.INTACT_AND_TRUSTED ->
        "Signature is intact and the signer is trusted."
    SignatureState.INTACT_SIGNER_UNKNOWN ->
        "Signature is intact, but the signer is not trusted by this device."
    SignatureState.MODIFIED_AFTER_SIGNING ->
        "The file was changed after it was signed."
    SignatureState.INVALID ->
        "The signature does not match the file."
    SignatureState.UNSUPPORTED ->
        "This signature cannot be checked here."
}

/** One row per check, with an explicit NOT_CHECKED state instead of silence. */
fun signatureChecks(report: SignatureReport): List<SignatureCheck> {
    val integrity = when (report.digestMatches) {
        true -> SignatureCheck(
            "Signed content",
            SignatureCheckState.PASSED,
            "The signed bytes still match the digest in the signature."
        )
        false -> SignatureCheck(
            "Signed content",
            SignatureCheckState.FAILED,
            "The signed bytes no longer match the digest."
        )
        null -> SignatureCheck(
            "Signed content",
            SignatureCheckState.NOT_CHECKED,
            "No signed digest was available to compare."
        )
    }

    val math = when (report.cmsSignatureValid) {
        true -> SignatureCheck(
            "Signature maths",
            SignatureCheckState.PASSED,
            "The signer certificate verifies the CMS signature."
        )
        false -> SignatureCheck(
            "Signature maths",
            SignatureCheckState.FAILED,
            "The CMS signature does not verify with the signer certificate."
        )
        null -> SignatureCheck(
            "Signature maths",
            SignatureCheckState.NOT_CHECKED,
            "No signer certificate was available to verify the signature."
        )
    }

    val chain = when (report.chainStatus) {
        ChainStatus.TRUSTED -> SignatureCheck(
            "Certificate",
            SignatureCheckState.PASSED,
            "Chains to a trusted certificate on this device" +
                (report.trustAnchor?.let { " ($it)" } ?: "") + "."
        )
        ChainStatus.UNTRUSTED -> SignatureCheck(
            "Certificate",
            SignatureCheckState.FAILED,
            "The signer is not trusted by this device's certificate store."
        )
        ChainStatus.NOT_CHECKED -> SignatureCheck(
            "Certificate",
            SignatureCheckState.NOT_CHECKED,
            "The certificate chain was not checked."
        )
    }

    val revocation = when (report.revocation) {
        RevocationStatus.GOOD -> SignatureCheck(
            "Revocation",
            SignatureCheckState.PASSED,
            report.revocationNote ?: "The embedded revocation data says the certificate is valid."
        )
        RevocationStatus.REVOKED -> SignatureCheck(
            "Revocation",
            SignatureCheckState.FAILED,
            report.revocationNote ?: "The signer certificate is revoked."
        )
        RevocationStatus.UNKNOWN -> SignatureCheck(
            "Revocation",
            SignatureCheckState.NOT_CHECKED,
            report.revocationNote ?: "Revocation data was present but could not be confirmed."
        )
        RevocationStatus.NOT_CHECKED -> SignatureCheck(
            "Revocation",
            SignatureCheckState.NOT_CHECKED,
            report.revocationNote
                ?: "Not checked. Only CRLs embedded in the file are read; no network request is made."
        )
    }

    val range = when {
        report.ranges.isEmpty() -> SignatureCheck(
            "Signed range",
            SignatureCheckState.NOT_CHECKED,
            "No signed byte range was found."
        )
        !report.coversWholeFile -> SignatureCheck(
            "Signed range",
            SignatureCheckState.FAILED,
            "The signature does not cover the whole file."
        )
        report.updatesAfterSigning.isNotEmpty() -> SignatureCheck(
            "Signed range",
            SignatureCheckState.FAILED,
            "${report.updatesAfterSigning.size} change(s) were appended after signing."
        )
        else -> SignatureCheck(
            "Signed range",
            SignatureCheckState.PASSED,
            "Covers the whole file; nothing was appended after signing."
        )
    }

    val time = SignatureCheck(
        "Signing time",
        SignatureCheckState.NOT_CHECKED,
        if (report.signingTime != null) {
            "The time comes from the signature; no timestamp authority was verified."
        } else {
            "No signing time was found in the signature."
        }
    )

    return listOf(integrity, math, chain, revocation, range, time)
}

/** True only when every check passed; the panel shows a green tick just then. */
fun allChecksPassed(report: SignatureReport): Boolean =
    report.state == SignatureState.INTACT_AND_TRUSTED &&
        report.digestMatches == true &&
        report.cmsSignatureValid == true &&
        report.chainStatus == ChainStatus.TRUSTED &&
        report.revocation == RevocationStatus.GOOD &&
        report.ranges.isNotEmpty() &&
        report.coversWholeFile &&
        report.updatesAfterSigning.isEmpty() &&
        report.problems.isEmpty()

/** Things this build deliberately does not check, shown under the report. */
fun signatureNotChecked(report: SignatureReport): List<String> {
    val out = ArrayList<String>()
    if (report.chainStatus == ChainStatus.NOT_CHECKED) {
        out += "Certificate chain: not checked."
    }
    if (report.cmsSignatureValid == null) {
        out += "CMS signature: no signer certificate was available."
    }
    if (report.revocation != RevocationStatus.GOOD) {
        out += "Revocation: no trusted online check; only embedded CRLs are read."
    }
    out += "Timestamp authority: not verified."
    out += "Document permissions (DocMDP): not enforced."
    out += "Whether the signer had authority to sign: not decided by this panel."
    return out
}
