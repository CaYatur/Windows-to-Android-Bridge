package com.cayatur.winbridge.protocol

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

enum class FrameType(val code: Byte) {
    HELLO(0x01), HELLO_ACK(0x02), SECURE(0x03), BYE(0x04);

    companion object {
        fun from(code: Byte): FrameType = entries.firstOrNull { it.code == code }
            ?: throw ProtocolException("unknown frame type 0x%02x".format(code))
    }
}

enum class InnerType(val code: Byte) {
    JSON(0x01), BLOB(0x02);

    companion object {
        fun from(code: Byte): InnerType = entries.firstOrNull { it.code == code }
            ?: throw ProtocolException("unknown inner type 0x%02x".format(code))
    }
}

class ProtocolException(message: String) : Exception(message)

data class Frame(val type: FrameType, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Length-prefixed framing. See docs/PROTOCOL.md §1. */
object Framing {

    const val MAX_FRAME_SIZE = 4 * 1024 * 1024

    fun write(output: OutputStream, type: FrameType, payload: ByteArray) {
        val length = payload.size + 1
        if (length > MAX_FRAME_SIZE) throw ProtocolException("frame too large: $length")

        val header = ByteArray(5)
        header[0] = (length ushr 24).toByte()
        header[1] = (length ushr 16).toByte()
        header[2] = (length ushr 8).toByte()
        header[3] = length.toByte()
        header[4] = type.code

        // One write for header+payload: two writes on an RFCOMM stream can be
        // sent as two packets, which doubles the latency of every small frame.
        output.write(header + payload)
        output.flush()
    }

    fun read(input: InputStream): Frame {
        val header = readExact(input, 5)
        val length =
            ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)

        if (length <= 0) throw ProtocolException("zero-length frame")
        if (length > MAX_FRAME_SIZE) throw ProtocolException("frame too large: $length")

        val payload = if (length > 1) readExact(input, length - 1) else ByteArray(0)
        return Frame(FrameType.from(header[4]), payload)
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) throw EOFException("peer closed the connection")
            read += n
        }
        return buffer
    }
}
