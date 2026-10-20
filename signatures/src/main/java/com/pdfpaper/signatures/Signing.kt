package com.pdfpaper.signatures

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.io.RandomAccessSource
import com.pdfpaper.engine.model.PdfDocument
import java.io.File
import java.security.cert.X509Certificate
import java.time.Instant

/**
 * A key that can sign a pre-computed SHA-256 digest.
 *
 * [certificateChain] must start with the signer certificate and may contain
 * intermediates up to (but not including) a trust anchor. Implementations own
 * their private key: this module only receives the digest and the public
 * certificate chain. PKCS#12 files are loaded by [Pkcs12SigningKey]; Android
 * Keystore implementations can implement this same interface in `:app`.
 *
 * The returned bytes must be a standard signature over [digest]:
 * - RSA: PKCS#1 v1.5 with SHA-256 DigestInfo (what `SHA256withRSA` produces);
 * - ECDSA: DER-encoded `r,s` over the digest (what `SHA256withECDSA` produces).
 *
 * [Pkcs12SigningKey] and [DigestSigningKey] implement this contract.
 */
interface SigningKey {
    val certificateChain: List<X509Certificate>

    fun sign(digest: ByteArray): ByteArray
}

/**
 * Supplies an RFC 3161 timestamp token for a signature value.
 *
 * The argument is the SHA-256 digest of the CMS signature value. The returned
 * token is the DER-encoded TimeStampToken (a ContentInfo SEQUENCE), which is
 * placed in the unsigned `signature-time-stamp` attribute. Implementations may
 * perform network I/O; the signing engine never does.
 */
fun interface TimestampProvider {
    fun timestamp(digest: ByteArray): ByteArray?
}

/** What the visible signature panel shows. */
class SignatureAppearance(
    val name: String? = null,
    /** Date shown in the panel; defaults to [SigningOptions.signingTime]. */
    val date: Instant? = null,
    /** Overrides the formatted date text without changing the PDF /M date. */
    val dateText: String? = null,
    val reason: String? = null,
    val location: String? = null,
    /** PNG or JPEG bytes; PNG transparency is preserved with an /SMask. */
    val image: ByteArray? = null,
    val showName: Boolean = true,
    val showDate: Boolean = true,
    val showReason: Boolean = true,
    val showLocation: Boolean = true,
    val showImage: Boolean = true
) {
    internal val imageCopy: ByteArray? = image?.copyOf()
}

/** The byte-range signature handler written into /SubFilter. */
enum class SigningSubFilter(val pdfName: String) {
    /** CAdES-BES signed attributes; the default for new signatures. */
    CADES_DETACHED("ETSI.CAdES.detached"),

    /** Legacy Adobe detached PKCS#7 handler. */
    PKCS7_DETACHED("adbe.pkcs7.detached")
}

/**
 * Options for one signature field.
 *
 * [fieldName] reuses an existing empty signature field; if that name is not
 * present a new field with the name is created. When it is null, the first
 * empty signature field is reused (unless [reuseEmptyField] is false), or a
 * fresh `SignatureN` field is created.
 *
 * A visible signature needs a [rect] (or an existing widget rectangle). With no
 * rectangle the signature is invisible. [visible] can force either mode.
 */
class SigningOptions(
    val fieldName: String? = null,
    val pageIndex: Int = 0,
    val rect: PdfRect? = null,
    val visible: Boolean? = null,
    val name: String? = null,
    val date: Instant? = null,
    val reason: String? = null,
    val location: String? = null,
    val contactInfo: String? = null,
    val image: ByteArray? = null,
    val appearance: SignatureAppearance? = null,
    val signingTime: Instant = Instant.now(),
    val subFilter: SigningSubFilter = SigningSubFilter.CADES_DETACHED,
    val timestampProvider: TimestampProvider? = null,
    val reuseEmptyField: Boolean = true
)

/** The result of a successful incremental signing operation. */
class SigningResult(
    /** The complete signed PDF, including the original bytes unchanged. */
    val bytes: ByteArray,
    val fieldName: String,
    val pageIndex: Int?,
    val visible: Boolean,
    val signatureObjectId: Int,
    val fieldObjectId: Int,
    val subFilter: String,
    val byteRange: List<Long>,
    val contentsBytes: Int,
    val cmsBytes: Int,
    val timestamped: Boolean,
    val signedAt: Instant
)

/**
 * Signs PDF documents (T27).
 *
 * The document is read completely, a new revision is appended with an
 * incremental update, and the original bytes are never rewritten. This is what
 * keeps an earlier signature's byte ranges intact when a later signature is
 * added. The result is returned as bytes rather than written to disk so mobile
 * callers can choose their own storage.
 */
object PdfSigner {

    /** Signs [input] and returns the complete updated file. */
    fun sign(
        input: ByteArray,
        key: SigningKey,
        options: SigningOptions = SigningOptions()
    ): Result<SigningResult> = runCatching {
        require(input.isNotEmpty()) { "input PDF is empty" }
        val document = PdfDocument.open(ByteArraySource(input)).getOrThrow()
        document.use {
            SigningEngine(document, input, key, options).sign()
        }
    }

    /** Reads and signs [file]. */
    fun sign(
        file: File,
        key: SigningKey,
        options: SigningOptions = SigningOptions()
    ): Result<SigningResult> = runCatching {
        require(file.isFile) { "no such file: $file" }
        val bytes = file.readBytes()
        val document = PdfDocument.open(ByteArraySource(bytes)).getOrThrow()
        document.use {
            SigningEngine(document, bytes, key, options).sign()
        }
    }

    /**
     * Reads all bytes from [source] and signs them. The source is not closed;
     * the caller retains ownership.
     */
    fun sign(
        source: RandomAccessSource,
        key: SigningKey,
        options: SigningOptions = SigningOptions()
    ): Result<SigningResult> = runCatching {
        require(source.size in 1..Int.MAX_VALUE.toLong()) { "input PDF is too large" }
        val bytes = source.read(0, source.size.toInt())
        val document = PdfDocument.open(ByteArraySource(bytes)).getOrThrow()
        document.use {
            SigningEngine(document, bytes, key, options).sign()
        }
    }
}

/** Alias used by callers that prefer the noun form. */
object PdfSigning {
    fun sign(
        input: ByteArray,
        key: SigningKey,
        options: SigningOptions = SigningOptions()
    ): Result<SigningResult> = PdfSigner.sign(input, key, options)

    fun sign(
        file: File,
        key: SigningKey,
        options: SigningOptions = SigningOptions()
    ): Result<SigningResult> = PdfSigner.sign(file, key, options)
}

/**
 * Helper for test keys and other simple RSA/EC keys that hold a [java.security.PrivateKey].
 *
 * It applies the raw-digest conventions documented on [SigningKey]. Production
 * Android code should normally wrap Keystore keys in its own [SigningKey]
 * implementation instead.
 */
class DigestSigningKey(
    private val privateKey: java.security.PrivateKey,
    override val certificateChain: List<X509Certificate>
) : SigningKey {

    init {
        require(certificateChain.isNotEmpty()) { "certificate chain is empty" }
    }

    override fun sign(digest: ByteArray): ByteArray {
        require(digest.size == 32) { "expected a SHA-256 digest" }
        return when (privateKey.algorithm.uppercase()) {
            "RSA" -> RawSignatures.rsaSha256(privateKey, digest)
            "EC", "ECDSA" -> RawSignatures.ecdsaSha256(privateKey, digest)
            else -> throw IllegalArgumentException("unsupported key algorithm: ${privateKey.algorithm}")
        }
    }
}

/** Loads a PKCS#12 file without exposing its private key. */
object Pkcs12SigningKey {

    fun load(file: File, password: CharArray): Result<SigningKey> = runCatching {
        require(file.isFile) { "no such PKCS#12 file: $file" }
        file.inputStream().use { load(it.readBytes(), password).getOrThrow() }
    }

    fun load(bytes: ByteArray, password: CharArray): Result<SigningKey> = runCatching {
        val store = java.security.KeyStore.getInstance("PKCS12")
        java.io.ByteArrayInputStream(bytes).use { store.load(it, password) }
        val aliases = store.aliases().toList()
        for (alias in aliases) {
            if (!store.isKeyEntry(alias)) continue
            val privateKey = store.getKey(alias, password) as? java.security.PrivateKey ?: continue
            val chain = store.getCertificateChain(alias)
                ?.filterIsInstance<X509Certificate>()
                .orEmpty()
            if (chain.isEmpty()) continue
            return@runCatching DigestSigningKey(privateKey, chain)
        }
        error("PKCS#12 file contains no private key with a certificate chain")
    }
}

/** Raw-digest RSA/ECDSA helpers for [DigestSigningKey]; implementation detail. */
internal object RawSignatures {

    private val SHA256_DIGEST_INFO_PREFIX = byteArrayOf(
        0x30, 0x31, 0x30, 0x0D, 0x06, 0x09,
        0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01,
        0x05, 0x00, 0x04, 0x20
    )

    fun rsaSha256(privateKey: java.security.PrivateKey, digest: ByteArray): ByteArray {
        require(digest.size == 32) { "expected a SHA-256 digest" }
        val digestInfo = ByteArray(SHA256_DIGEST_INFO_PREFIX.size + digest.size)
        SHA256_DIGEST_INFO_PREFIX.copyInto(digestInfo)
        digest.copyInto(digestInfo, SHA256_DIGEST_INFO_PREFIX.size)
        return try {
            val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, privateKey)
            cipher.doFinal(digestInfo)
        } finally {
            digestInfo.fill(0)
        }
    }

    fun ecdsaSha256(privateKey: java.security.PrivateKey, digest: ByteArray): ByteArray {
        require(digest.size == 32) { "expected a SHA-256 digest" }
        val signature = java.security.Signature.getInstance("NONEwithECDSA")
        signature.initSign(privateKey)
        signature.update(digest)
        return signature.sign()
    }
}

