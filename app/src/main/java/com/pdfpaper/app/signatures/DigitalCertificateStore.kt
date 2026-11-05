package com.pdfpaper.app.signatures

import android.content.Context
import android.util.Base64
import com.pdfpaper.signatures.DigestSigningKey
import com.pdfpaper.signatures.SigningKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID

/** Public details of a certificate imported for digital signing. */
data class CertificateSummary(
    val id: String,
    val label: String,
    val commonName: String?,
    val subject: String,
    val issuer: String,
    val serialNumber: String,
    val notBefore: Long,
    val notAfter: Long,
    val keyAlgorithm: String,
    val keySize: Int,
    val chainLength: Int
) {
    val isExpired: Boolean get() = notAfter < System.currentTimeMillis()
    val isNotYetValid: Boolean get() = notBefore > System.currentTimeMillis()
}

/**
 * Imports PKCS#12 files and keeps their private keys for later signing.
 *
 * AndroidKeyStore cannot import a PKCS#12 private key directly, so the key is
 * stored as PKCS#8 inside an AES-GCM blob wrapped by a non-exportable
 * AndroidKeystore key ([KeystoreCipher]).  The PKCS#12 password is used only
 * while importing, wiped from the supplied char array immediately, and never
 * written to storage.  The key is materialised into memory only for the short
 * signing operation and is not retained by [signingKey].
 */
class DigitalCertificateStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(KEY_ALIAS)

    fun load(): Result<List<CertificateSummary>> = runCatching {
        loadRecords().map { it.summary }.sortedBy { it.label.lowercase() }
    }

    /**
     * Reads the first private-key entry in [pkcs12].  The caller must not reuse
     * [password] afterwards; it is cleared in the `finally` block.
     */
    fun import(pkcs12: ByteArray, password: CharArray): Result<CertificateSummary> = runCatching {
        require(pkcs12.isNotEmpty()) { "The selected file is empty." }
        require(pkcs12.size <= MAX_PKCS12_BYTES) { "That PKCS#12 file is too large (maximum 16 MB)." }
        try {
            val keyStore = try {
                KeyStore.getInstance("PKCS12").apply {
                    ByteArrayInputStream(pkcs12).use { load(it, password) }
                }
            } catch (error: Exception) {
                throw IllegalArgumentException(
                    "Could not open this certificate. Check the password and try again.",
                    error
                )
            }

            var chosenAlias: String? = null
            var privateKey: PrivateKey? = null
            var chain: List<X509Certificate>? = null
            for (alias in keyStore.aliases()) {
                if (!keyStore.isKeyEntry(alias)) continue
                val key = runCatching {
                    keyStore.getKey(alias, password) as? PrivateKey
                }.getOrNull()
                val certificates = keyStore.getCertificateChain(alias)
                    ?.filterIsInstance<X509Certificate>()
                    .orEmpty()
                if (key != null && certificates.isNotEmpty()) {
                    chosenAlias = alias
                    privateKey = key
                    chain = certificates
                    break
                }
            }

            val key = privateKey
                ?: throw IllegalArgumentException(
                    "This PKCS#12 file has no private key with a certificate. Choose the right .p12 or .pfx file."
                )
            val certificates = chain.orEmpty()
            require(certificates.isNotEmpty()) {
                "The certificate chain in this PKCS#12 file is empty."
            }
            val algorithm = when (key.algorithm.uppercase()) {
                "RSA" -> "RSA"
                "EC", "ECDSA" -> "EC"
                else -> throw IllegalArgumentException(
                    "Only RSA and EC signing keys are supported (this key is ${key.algorithm})."
                )
            }
            val encoded = key.encoded
                ?: throw IllegalArgumentException("The private key in this file cannot be exported.")
            val signer = certificates.first()
            val commonName = commonName(signer)
            val summary = CertificateSummary(
                id = UUID.randomUUID().toString(),
                label = commonName ?: chosenAlias ?: "Digital certificate",
                commonName = commonName,
                subject = signer.subjectX500Principal.name,
                issuer = signer.issuerX500Principal.name,
                serialNumber = signer.serialNumber.toString(16).uppercase(),
                notBefore = signer.notBefore.time,
                notAfter = signer.notAfter.time,
                keyAlgorithm = algorithm,
                keySize = keySize(key),
                chainLength = certificates.size
            )
            val record = CertificateRecord(
                summary = summary,
                privateKeyPkcs8 = encoded,
                chainDer = certificates.map { it.encoded }
            )
            val existing = loadRecords()
            persist((listOf(record) + existing).take(MAX_CERTIFICATES))
            try {
                summary
            } finally {
                record.privateKeyPkcs8.fill(0)
            }
        } finally {
            password.fill('\u0000')
        }
    }

    /** Loads the private key for one imported certificate. */
    fun signingKey(id: String): Result<SigningKey> = runCatching {
        val record = loadRecords().firstOrNull { it.summary.id == id }
            ?: throw IllegalArgumentException("That certificate is no longer available. Import it again.")
        try {
            val factory = CertificateFactory.getInstance("X.509")
            val chain = record.chainDer.map { der ->
                factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            }
            val keyFactory = KeyFactory.getInstance(record.summary.keyAlgorithm)
            val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(record.privateKeyPkcs8))
            DigestSigningKey(privateKey, chain)
        } finally {
            record.privateKeyPkcs8.fill(0)
        }
    }

    fun delete(id: String): Result<List<CertificateSummary>> = runCatching {
        val next = loadRecords().filterNot { it.summary.id == id }
        persist(next)
        next.map { it.summary }.sortedBy { it.label.lowercase() }
    }

    private class CertificateRecord(
        val summary: CertificateSummary,
        val privateKeyPkcs8: ByteArray,
        val chainDer: List<ByteArray>
    )

    private fun loadRecords(): List<CertificateRecord> {
        val encoded = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        val envelope = Base64.decode(encoded, Base64.NO_WRAP)
        val json = String(cipher.decrypt(envelope), Charsets.UTF_8)
        val array = JSONArray(json)
        return buildList {
            for (i in 0 until array.length()) {
                runCatching { parseRecord(array.getJSONObject(i)) }.getOrNull()?.let(::add)
            }
        }
    }

    private fun persist(records: List<CertificateRecord>) {
        val array = JSONArray()
        for (record in records) array.put(serialize(record))
        val encrypted = cipher.encrypt(array.toString().toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString(KEY_ITEMS, encoded).commit()) {
            "Could not write to app-private storage."
        }
    }

    private fun serialize(record: CertificateRecord): JSONObject = JSONObject().apply {
        put("id", record.summary.id)
        put("label", record.summary.label)
        put("commonName", record.summary.commonName)
        put("subject", record.summary.subject)
        put("issuer", record.summary.issuer)
        put("serial", record.summary.serialNumber)
        put("notBefore", record.summary.notBefore)
        put("notAfter", record.summary.notAfter)
        put("algorithm", record.summary.keyAlgorithm)
        put("keySize", record.summary.keySize)
        put("chainLength", record.summary.chainLength)
        put("privateKey", Base64.encodeToString(record.privateKeyPkcs8, Base64.NO_WRAP))
        put("chain", JSONArray().apply {
            for (certificate in record.chainDer) {
                put(Base64.encodeToString(certificate, Base64.NO_WRAP))
            }
        })
    }

    private fun parseRecord(json: JSONObject): CertificateRecord {
        val summary = CertificateSummary(
            id = json.getString("id"),
            label = json.optString("label", "Digital certificate"),
            commonName = json.optString("commonName").takeIf { it.isNotBlank() && it != "null" },
            subject = json.optString("subject"),
            issuer = json.optString("issuer"),
            serialNumber = json.optString("serial"),
            notBefore = json.optLong("notBefore", 0L),
            notAfter = json.optLong("notAfter", 0L),
            keyAlgorithm = json.optString("algorithm", "RSA"),
            keySize = json.optInt("keySize", 0),
            chainLength = json.optInt("chainLength", 1)
        )
        val privateKey = Base64.decode(json.getString("privateKey"), Base64.NO_WRAP)
        val chainJson = json.getJSONArray("chain")
        val chain = buildList {
            for (i in 0 until chainJson.length()) {
                add(Base64.decode(chainJson.getString(i), Base64.NO_WRAP))
            }
        }
        require(privateKey.isNotEmpty() && chain.isNotEmpty()) { "certificate record is incomplete" }
        return CertificateRecord(summary, privateKey, chain)
    }

    private fun keySize(key: PrivateKey): Int = when (key) {
        is RSAPrivateKey -> key.modulus.bitLength()
        is ECPrivateKey -> key.params.order.bitLength()
        else -> 0
    }

    private fun commonName(certificate: X509Certificate): String? {
        val name = certificate.subjectX500Principal.name
        val match = Regex("(?:^|,)\\s*CN=((?:\\\\.|[^,])+)").find(name) ?: return null
        return match.groupValues[1]
            .replace("\\,", ",")
            .replace("\\\\", "\\")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val PREFS = "pdfpaper_digital_certificates"
        const val KEY_ITEMS = "items_v1"
        const val KEY_ALIAS = "pdfpaper.digital-certificates.v1"
        const val MAX_PKCS12_BYTES = 16 * 1024 * 1024
        const val MAX_CERTIFICATES = 8
    }
}
