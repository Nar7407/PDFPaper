package com.pdfpaper.app.signatures

import com.pdfpaper.signatures.TrustStore
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Android's platform trust store as a signatures [TrustStore] (task UI-2).
 * `AndroidCAStore` is the user + system CA store managed by the platform, so
 * no network access is needed and the panel can say exactly what it checked.
 * An unavailable store yields no anchors: signatures then verify as
 * "signer unknown" instead of crashing.
 */
object PlatformTrustStore : TrustStore {

    private val anchors: List<X509Certificate> by lazy { load() }

    override fun trustAnchors(): List<X509Certificate> = anchors

    private fun load(): List<X509Certificate> = runCatching {
        val store = KeyStore.getInstance(ANDROID_CA_STORE).apply { load(null, null) }
        store.aliases().toList().mapNotNull { alias ->
            runCatching { store.getCertificate(alias) as? X509Certificate }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private const val ANDROID_CA_STORE = "AndroidCAStore"
}
