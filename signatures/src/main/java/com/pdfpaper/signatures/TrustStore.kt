package com.pdfpaper.signatures

import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Supplies the trusted roots (and, optionally, intermediate certificates) used
 * for certificate-chain building.
 *
 * The app passes an implementation backed by Android's platform trust store;
 * the JVM CLI uses [TrustStores.system] or a user-supplied PEM/keystore. No
 * network access is ever performed by the verifier itself.
 */
interface TrustStore {
    /** Self-signed roots or other explicitly trusted certificates. */
    fun trustAnchors(): List<X509Certificate>

    /** Extra intermediates that may be needed to bridge to an anchor. */
    fun intermediates(): List<X509Certificate> = emptyList()
}

/** A trust store with no anchors: signatures verify as "signer unknown". */
object EmptyTrustStore : TrustStore {
    override fun trustAnchors(): List<X509Certificate> = emptyList()
}

/** Simple immutable trust store, used by tests and by callers with their own roots. */
class SimpleTrustStore(
    private val anchors: List<X509Certificate>,
    private val extraIntermediates: List<X509Certificate> = emptyList()
) : TrustStore {
    override fun trustAnchors(): List<X509Certificate> = anchors
    override fun intermediates(): List<X509Certificate> = extraIntermediates
}

/** Loaders for the JVM CLI and for tests. */
object TrustStores {

    fun empty(): TrustStore = EmptyTrustStore

    fun of(
        anchors: List<X509Certificate>,
        intermediates: List<X509Certificate> = emptyList()
    ): TrustStore = SimpleTrustStore(anchors, intermediates)

    /**
     * The JVM's platform trust store: `javax.net.ssl.trustStore` when set,
     * otherwise the JDK's `lib/security/cacerts`. Returns an empty store when it
     * cannot be read (callers still get a complete, untrusted result).
     */
    fun system(): TrustStore {
        return runCatching {
            val property = System.getProperty("javax.net.ssl.trustStore")
            val file = if (!property.isNullOrBlank()) {
                File(property)
            } else {
                File(System.getProperty("java.home"), "lib/security/cacerts")
            }
            val password = System.getProperty("javax.net.ssl.trustStorePassword") ?: "changeit"
            if (!file.isFile) return EmptyTrustStore
            val store = loadKeyStore(file, password.toCharArray())
                ?: return EmptyTrustStore
            val certs = ArrayList<X509Certificate>()
            for (alias in store.aliases()) {
                val cert = runCatching { store.getCertificate(alias) }.getOrNull()
                if (cert is X509Certificate) certs += cert
            }
            SimpleTrustStore(certs)
        }.getOrDefault(EmptyTrustStore)
    }

    /**
     * Loads anchors from a PEM/DER certificate bundle or a JKS/PKCS#12 keystore.
     * Throws [java.io.IOException]/[java.security.GeneralSecurityException] on
     * unreadable input so the CLI can report it.
     */
    fun load(file: File, password: CharArray = "changeit".toCharArray()): TrustStore {
        val bytes = file.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        if (text.contains("-----BEGIN CERTIFICATE-----")) {
            val certs = CertificateFactory.getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(bytes))
                .filterIsInstance<X509Certificate>()
            if (certs.isEmpty()) throw IllegalArgumentException("no certificates found in ${file.name}")
            return SimpleTrustStore(certs)
        }
        val store = loadKeyStore(file, password)
            ?: throw IllegalArgumentException("not a certificate bundle or keystore: ${file.name}")
        val certs = ArrayList<X509Certificate>()
        for (alias in store.aliases()) {
            val cert = runCatching { store.getCertificate(alias) }.getOrNull()
            if (cert is X509Certificate) certs += cert
        }
        if (certs.isEmpty()) throw IllegalArgumentException("no trusted certificates in ${file.name}")
        return SimpleTrustStore(certs)
    }

    private fun loadKeyStore(file: File, password: CharArray): KeyStore? {
        for (type in listOf(KeyStore.getDefaultType(), "PKCS12", "JKS")) {
            val store = runCatching { KeyStore.getInstance(type) }.getOrNull() ?: continue
            val loaded = runCatching {
                file.inputStream().use { store.load(it, password) }
                store
            }.getOrNull()
            if (loaded != null) return loaded
        }
        return null
    }
}
