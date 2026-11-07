package com.fares.iffalconremote

import android.content.Context
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

class AtvPairingSession internal constructor(
    private val context: Context,
    private val socket: SSLSocket,
    private val tvCertificate: X509Certificate
) : AutoCloseable {

    private var completed = false

    @Synchronized
    fun finish(pin: String) {
        require(!completed) { "Pairing session already completed" }

        val code = pin.trim().uppercase()
        require(code.matches(Regex("[0-9A-F]{6}"))) {
            "Enter the six-character hexadecimal PIN shown on the TV"
        }

        val clientCertificate = AtvIdentity.loadOrCreate(context).getCertificate("atvremote") as X509Certificate

        val clientKey = clientCertificate.publicKey as RSAPublicKey
        val serverKey = tvCertificate.publicKey as RSAPublicKey

        fun unsigned(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return if (bytes.size > 1 && bytes[0] == 0.toByte()) {
                bytes.copyOfRange(1, bytes.size)
            } else {
                bytes
            }
        }

        val nonce = code.substring(2).chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(unsigned(clientKey.modulus))
        digest.update(unsigned(clientKey.publicExponent))
        digest.update(unsigned(serverKey.modulus))
        digest.update(unsigned(serverKey.publicExponent))
        digest.update(nonce)

        val secret = digest.digest()

        require((secret[0].toInt() and 0xFF) == code.substring(0, 2).toInt(16)) {
            "PIN checksum is incorrect. Check the code displayed on the TV."
        }

        val secretMessage = ProtoCodec.encodeBytesField(1, secret)
        sendOuter(40, secretMessage)

        val response = readOuter()
        checkStatus(response)

        val secretAck = response.firstOrNull { it.number == 41 }
            ?: throw IllegalStateException("TV did not confirm pairing")

        val acknowledgedSecret = ProtoCodec.parse(secretAck.bytes)
            .firstOrNull { it.number == 1 }?.bytes
            ?: throw IllegalStateException("TV returned an invalid pairing confirmation")

        require(MessageDigest.isEqual(secret, acknowledgedSecret)) {
            "TV pairing confirmation did not match"
        }

        AtvTls.saveTvCertificate(context, tvCertificate)
        completed = true
        close()
    }

    private fun sendOuter(payloadField: Int, payload: ByteArray) {
        val outer =
            ProtoCodec.encodeVarintField(1, 2) +
            ProtoCodec.encodeVarintField(2, 200) +
            ProtoCodec.encodeBytesField(payloadField, payload)

        ProtoCodec.writeFrame(socket.outputStream, outer)
    }

    private fun readOuter(): List<ProtoCodec.Field> {
        return ProtoCodec.parse(
            ProtoCodec.readFrame(socket.inputStream)
        )
    }

    private fun checkStatus(fields: List<ProtoCodec.Field>) {
        val status = fields.firstOrNull { it.number == 2 }?.varint ?: 0L
        check(status == 200L) {
            when (status) {
                400L -> "TV pairing error"
                401L -> "TV rejected the pairing configuration"
                402L -> "TV rejected the PIN"
                else -> "Unexpected TV pairing status: $status"
            }
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        internal fun create(
            context: Context,
            socket: SSLSocket,
            certificate: X509Certificate
        ) = AtvPairingSession(context, socket, certificate)
    }
}

object AtvPairing {

    fun begin(
        context: Context,
        host: String,
        port: Int = 6467
    ): AtvPairingSession {

        var serverCertificate: X509Certificate? = null

        val sslContext = AtvTls.createPairingContext(context) {
            serverCertificate = it
        }

        val socket = sslContext.socketFactory
            .createSocket() as SSLSocket

        try {
            socket.connect(InetSocketAddress(host, port), 10000)
            socket.soTimeout = 15000
            socket.startHandshake()

            val certificate = serverCertificate
                ?: throw IllegalStateException("TV certificate was not received")

            fun send(payloadField: Int, payload: ByteArray) {
                val outer =
                    ProtoCodec.encodeVarintField(1, 2) +
                    ProtoCodec.encodeVarintField(2, 200) +
                    ProtoCodec.encodeBytesField(payloadField, payload)

                ProtoCodec.writeFrame(socket.outputStream, outer)
            }

            fun receive(expectedField: Int): List<ProtoCodec.Field> {
                val fields = ProtoCodec.parse(
                    ProtoCodec.readFrame(socket.inputStream)
                )

                val status = fields.firstOrNull {
                    it.number == 2
                }?.varint ?: 0L

                check(status == 200L) {
                    "TV returned pairing status $status"
                }

                require(fields.any { it.number == expectedField }) {
                    "Unexpected TV pairing response; expected field $expectedField"
                }

                return fields
            }

            // 1. Pairing request
            val request =
                ProtoCodec.encodeStringField(1, "atvremote") +
                ProtoCodec.encodeStringField(2, "iFFALCON Remote")

            send(10, request)
            receive(11)

            // 2. Offer six-character hexadecimal PIN input
            val encoding =
                ProtoCodec.encodeVarintField(1, 3) +
                ProtoCodec.encodeVarintField(2, 6)

            val options =
                ProtoCodec.encodeBytesField(1, encoding) +
                ProtoCodec.encodeVarintField(3, 1)

            send(20, options)
            receive(20)

            // 3. Select hexadecimal input and the input role
            val configuration =
                ProtoCodec.encodeBytesField(1, encoding) +
                ProtoCodec.encodeVarintField(2, 1)

            send(30, configuration)
            receive(31)

            // The TV should now display its six-character PIN.
            return AtvPairingSession.create(context, socket, certificate)

        } catch (error: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            throw error
        }
    }
}
