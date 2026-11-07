package com.fares.iffalconremote

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Date

object AtvIdentity {

    private const val FILE_NAME = "atv-client.p12"
    private const val ALIAS = "atvremote"
    private const val PASSWORD = "local-atv-identity"

    private val provider = BouncyCastleProvider()

    @Synchronized
    fun loadOrCreate(context: Context): KeyStore {
        val file = File(context.filesDir, FILE_NAME)
        val password = PASSWORD.toCharArray()

        if (file.exists()) {
            val keyStore = KeyStore.getInstance("PKCS12", provider)
            FileInputStream(file).use {
                keyStore.load(it, password)
            }
            return keyStore
        }

        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()

        val subject = X500Name("CN=atvremote")
        val now = System.currentTimeMillis()

        val certificateBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(160, SecureRandom()),
            Date(now - 60_000),
            Date(now + 10L * 365 * 24 * 60 * 60 * 1000),
            subject,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(provider)
            .build(keyPair.private)

        val certificate = JcaX509CertificateConverter()
            .setProvider(provider)
            .getCertificate(certificateBuilder.build(signer))

        certificate.verify(keyPair.public)

        val keyStore = KeyStore.getInstance("PKCS12", provider)
        keyStore.load(null, password)
        keyStore.setKeyEntry(
            ALIAS,
            keyPair.private,
            password,
            arrayOf(certificate)
        )

        FileOutputStream(file).use {
            keyStore.store(it, password)
        }

        return keyStore
    }
}
