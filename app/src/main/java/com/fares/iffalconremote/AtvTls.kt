package com.fares.iffalconremote

import android.content.Context
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object AtvTls {

    private const val PASSWORD = "local-atv-identity"
    private const val TV_CERT_FILE = "atv-tv-cert.der"

    private fun keyManagers(context: Context) =
        KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm()
        ).apply {
            init(
                AtvIdentity.loadOrCreate(context),
                PASSWORD.toCharArray()
            )
        }.keyManagers

    private fun context(trustManager: X509TrustManager): SSLContext {
        return SSLContext.getInstance("TLS").apply {
            init(
                null,
                arrayOf<TrustManager>(trustManager),
                SecureRandom()
            )
        }
    }

    fun createPairingContext(
        context: Context,
        onServerCertificate: (X509Certificate) -> Unit
    ): SSLContext {
        val trustManager = object : X509TrustManager {

            override fun getAcceptedIssuers() =
                emptyArray<X509Certificate>()

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?
            ) {}

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?
            ) {
                require(!chain.isNullOrEmpty()) {
                    "TV did not provide a certificate"
                }
                onServerCertificate(chain[0])
            }
        }

        return SSLContext.getInstance("TLS").apply {
            init(
                keyManagers(context),
                arrayOf<TrustManager>(trustManager),
                SecureRandom()
            )
        }
    }

    fun saveTvCertificate(
        context: Context,
        certificate: X509Certificate
    ) {
        File(context.filesDir, TV_CERT_FILE).writeBytes(
            certificate.encoded
        )
    }

    fun createRemoteContext(context: Context): SSLContext {
        val certificateFile = File(context.filesDir, TV_CERT_FILE)

        require(certificateFile.exists()) {
            "TV has not been paired yet"
        }

        val pinnedCertificate = CertificateFactory
            .getInstance("X.509")
            .generateCertificate(certificateFile.inputStream())
                as X509Certificate

        val trustManager = object : X509TrustManager {

            override fun getAcceptedIssuers() =
                arrayOf(pinnedCertificate)

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?
            ) {}

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?
            ) {
                require(!chain.isNullOrEmpty()) {
                    "TV did not provide a certificate"
                }

                require(
                    MessageDigest.isEqual(
                        chain[0].encoded,
                        pinnedCertificate.encoded
                    )
                ) {
                    "TV certificate does not match the paired TV"
                }
            }
        }

        return SSLContext.getInstance("TLS").apply {
            init(
                keyManagers(context),
                arrayOf<TrustManager>(trustManager),
                SecureRandom()
            )
        }
    }
}
