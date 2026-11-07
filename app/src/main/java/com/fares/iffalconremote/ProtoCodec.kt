package com.fares.iffalconremote

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

object ProtoCodec {

    data class Field(
        val number: Int,
        val wireType: Int,
        val varint: Long = 0,
        val bytes: ByteArray = ByteArray(0)
    )

    fun varint(value: Long): ByteArray {
        val output = ByteArrayOutputStream()
        var v = value

        while (v and -128L != 0L) {
            output.write(((v and 127L) or 128L).toInt())
            v = v ushr 7
        }

        output.write(v.toInt())
        return output.toByteArray()
    }

    fun encodeVarintField(number: Int, value: Long): ByteArray {
        return varint((number shl 3).toLong()) + varint(value)
    }

    fun encodeBytesField(number: Int, value: ByteArray): ByteArray {
        return varint(((number shl 3) or 2).toLong()) +
            varint(value.size.toLong()) + value
    }

    fun encodeStringField(number: Int, value: String): ByteArray {
        return encodeBytesField(number, value.toByteArray(Charsets.UTF_8))
    }

    fun frame(message: ByteArray): ByteArray {
        return varint(message.size.toLong()) + message
    }

    fun writeFrame(output: OutputStream, message: ByteArray) {
        output.write(frame(message))
        output.flush()
    }

    fun readFrame(input: InputStream): ByteArray {
        val length = readVarint(input).toInt()

        require(length in 1..1_048_576) {
            "Invalid protobuf message length: $length"
        }

        val message = ByteArray(length)
        var offset = 0

        while (offset < length) {
            val count = input.read(message, offset, length - offset)
            if (count < 0) {
                throw java.io.EOFException("Incomplete protobuf message")
            }
            offset += count
        }

        return message
    }

    private fun readVarint(input: InputStream): Long {
        var result = 0L
        var shift = 0

        while (shift < 64) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException("Missing protobuf varint")

            result = result or ((b and 127).toLong() shl shift)

            if ((b and 128) == 0) return result
            shift += 7
        }

        throw IllegalArgumentException("Malformed protobuf varint")
    }

    fun parse(message: ByteArray): List<Field> {
        val fields = mutableListOf<Field>()
        var position = 0

        fun readValue(): Long {
            var result = 0L
            var shift = 0

            while (shift < 64) {
                require(position < message.size) {
                    "Unexpected end of protobuf message"
                }

                val b = message[position++].toInt() and 255
                result = result or ((b and 127).toLong() shl shift)

                if ((b and 128) == 0) return result
                shift += 7
            }

            throw IllegalArgumentException("Malformed protobuf varint")
        }

        while (position < message.size) {
            val tag = readValue()
            val number = (tag ushr 3).toInt()
            val wireType = (tag and 7).toInt()

            require(number > 0) { "Invalid protobuf field number" }

            when (wireType) {
                0 -> fields += Field(number, wireType, varint = readValue())

                1 -> {
                    require(position + 8 <= message.size)
                    val bytes = message.copyOfRange(position, position + 8)
                    position += 8
                    fields += Field(number, wireType, bytes = bytes)
                }

                2 -> {
                    val length = readValue().toInt()
                    require(length >= 0 && position + length <= message.size)
                    val bytes = message.copyOfRange(position, position + length)
                    position += length
                    fields += Field(number, wireType, bytes = bytes)
                }

                5 -> {
                    require(position + 4 <= message.size)
                    val bytes = message.copyOfRange(position, position + 4)
                    position += 4
                    fields += Field(number, wireType, bytes = bytes)
                }

                else -> throw IllegalArgumentException(
                    "Unsupported protobuf wire type: $wireType"
                )
            }
        }

        return fields
    }
}
