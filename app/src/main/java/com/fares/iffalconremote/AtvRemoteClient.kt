package com.fares.iffalconremote

import android.content.Context
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket

class AtvRemoteClient(
    private val context: Context,
    private val host: String,
    private val onDisconnected: (String) -> Unit = {}
) : AutoCloseable {

    companion object {
        private const val PORT = 6466
        private const val FEATURE_PING = 1
        private const val FEATURE_KEY = 2
        private const val FEATURE_POWER = 32
        private const val KEYCODE_POWER = 26
    }

    private var socket: SSLSocket? = null
    private var features = 0

    @Volatile
    var connected = false
        private set

    private val writeLock = Any()

    fun connect() {
        check(!connected) { "Already connected" }

        val sslSocket = AtvTls.createRemoteContext(context)
            .socketFactory.createSocket() as SSLSocket

        try {
            sslSocket.connect(InetSocketAddress(host, PORT), 10000)
            sslSocket.soTimeout = 15000
            sslSocket.startHandshake()

            socket = sslSocket

            // TV sends RemoteConfigure first.
            val configureMessage = readMessage()
            val configure = configureMessage.firstOrNull {
                it.number == 1
            } ?: error("TV did not send RemoteConfigure")

            val offeredFeatures = ProtoCodec.parse(configure.bytes)
                .firstOrNull { it.number == 1 }?.varint?.toInt()
                ?: error("TV did not advertise remote features")

            features = offeredFeatures and (
                FEATURE_PING or FEATURE_KEY or FEATURE_POWER
            )

            check(features and FEATURE_KEY != 0) {
                "TV does not support key commands"
            }

            // Reply with our selected features and client identity.
            val deviceInfo =
                ProtoCodec.encodeVarintField(3, 1) +
                ProtoCodec.encodeStringField(4, "1") +
                ProtoCodec.encodeStringField(5, "atvremote") +
                ProtoCodec.encodeStringField(6, "1.0.0")

            val configureReply =
                ProtoCodec.encodeVarintField(1, features.toLong()) +
                ProtoCodec.encodeBytesField(2, deviceInfo)

            sendMessage(1, configureReply)

            // TV sends SetActive; answer it.
            var activeReceived = false

            while (!activeReceived) {
                val message = readMessage()

                if (message.any { it.number == 2 }) {
                    sendMessage(
                        2,
                        ProtoCodec.encodeVarintField(1, features.toLong())
                    )
                    activeReceived = true
                } else {
                    answerPing(message)
                }
            }

            // Wait until the TV sends RemoteStart.
            // Respond to pings while waiting.
            var started = false

            while (!started) {
                val message = readMessage()

                if (message.any { it.number == 40 }) {
                    started = true
                } else {
                    answerPing(message)
                    checkNoRemoteError(message)
                }
            }

            connected = true
            sslSocket.soTimeout = 0

            Thread({
                readLoop()
            }, "AtvRemoteReader").start()

        } catch (error: Exception) {
            close()
            throw error
        }
    }

    fun pressPower() {
        check(connected) { "Remote is not connected" }
        check(features and FEATURE_KEY != 0) {
            "KEY feature is unavailable"
        }

        val keyMessage =
            ProtoCodec.encodeVarintField(1, KEYCODE_POWER.toLong()) +
            ProtoCodec.encodeVarintField(2, 3)

        sendMessage(10, keyMessage)
    }

    private fun answerPing(message: List<ProtoCodec.Field>) {
        val ping = message.firstOrNull { it.number == 8 } ?: return

        if (features and FEATURE_PING != 0) {
            val value = ProtoCodec.parse(ping.bytes)
                .firstOrNull { it.number == 1 }?.varint ?: 0L

            sendMessage(
                9,
                ProtoCodec.encodeVarintField(1, value)
            )
        }
    }

    private fun checkNoRemoteError(message: List<ProtoCodec.Field>) {
        if (message.any { it.number == 3 }) {
            error("TV reported a remote protocol error")
        }
    }

    private fun readLoop() {
        try {
            while (connected) {
                val message = readMessage()
                answerPing(message)
                checkNoRemoteError(message)
            }
        } catch (error: Exception) {
            if (connected) {
                onDisconnected(error.message ?: "Remote connection lost")
            }
        } finally {
            close()
        }
    }

    private fun readMessage(): List<ProtoCodec.Field> {
        val currentSocket = socket
            ?: error("Remote socket is not connected")

        return ProtoCodec.parse(
            ProtoCodec.readFrame(currentSocket.inputStream)
        )
    }

    private fun sendMessage(field: Int, payload: ByteArray) {
        val currentSocket = socket
            ?: error("Remote socket is not connected")

        synchronized(writeLock) {
            ProtoCodec.writeFrame(
                currentSocket.outputStream,
                ProtoCodec.encodeBytesField(field, payload)
            )
        }
    }

    @Synchronized
    override fun close() {
        connected = false

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        socket = null
    }
}
