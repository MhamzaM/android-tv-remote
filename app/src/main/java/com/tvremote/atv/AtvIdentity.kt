package com.tvremote.atv

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Base64
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The client certificate this phone presents to Android TVs. It is created once and reused,
 * because the TV remembers which certificate was paired.
 */
class AtvIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {

    val publicKey: RSAPublicKey get() = certificate.publicKey as RSAPublicKey

    fun createSslContext(): SSLContext {
        val password = "tvremote".toCharArray()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("client", privateKey, password, arrayOf(certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)
        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf(TrustAnyServer), SecureRandom())
        }
    }

    /** Android TVs use self-signed server certificates, so there is nothing to verify against. */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustAnyServer : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val PREFS = "atv_identity"
        private const val KEY_PRIVATE = "private_key"
        private const val KEY_CERT = "certificate"

        @Volatile private var cached: AtvIdentity? = null

        /** Loads the saved identity or creates a new one. Slow the first time: call off the main thread. */
        fun get(context: Context): AtvIdentity = cached ?: synchronized(this) {
            cached ?: (load(context) ?: create(context)).also { cached = it }
        }

        fun generate(commonName: String): AtvIdentity {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            return AtvIdentity(keyPair.private, SelfSignedCert.create(keyPair, commonName))
        }

        private fun load(context: Context): AtvIdentity? = runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val keyBytes = Base64.decode(prefs.getString(KEY_PRIVATE, null) ?: return null, Base64.NO_WRAP)
            val certBytes = Base64.decode(prefs.getString(KEY_CERT, null) ?: return null, Base64.NO_WRAP)
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
            AtvIdentity(key, cert)
        }.getOrNull()

        private fun create(context: Context): AtvIdentity {
            val identity = generate("atvremote/${Build.MANUFACTURER} ${Build.MODEL}")
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PRIVATE, Base64.encodeToString(identity.privateKey.encoded, Base64.NO_WRAP))
                .putString(KEY_CERT, Base64.encodeToString(identity.certificate.encoded, Base64.NO_WRAP))
                .apply()
            return identity
        }
    }
}
