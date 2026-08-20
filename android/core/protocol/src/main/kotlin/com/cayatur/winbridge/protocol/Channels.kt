package com.cayatur.winbridge.protocol

/**
 * Which queue a frame goes on. One socket carries control, media and bulk
 * traffic, so a strict priority queue in front of the writer is what keeps a
 * touch event from waiting behind a 48 KB tile batch or a file chunk.
 */
enum class SendLane { CONTROL, MEDIA, BULK }

object MediaKind {
    const val VIDEO: Byte = 1
    const val AUDIO: Byte = 2
}

/**
 * Stream identifiers are fixed rather than negotiated: both ends must agree on
 * what "stream 3" means before any control message about it can be exchanged.
 */
object StreamIds {
    const val PC_SCREEN: Byte = 0
    const val PHONE_SCREEN: Byte = 1
    const val PC_AUDIO: Byte = 2
    const val PHONE_AUDIO: Byte = 3
    const val PC_MIC: Byte = 4
    const val PHONE_MIC: Byte = 5

    fun name(id: Byte): String = when (id) {
        PC_SCREEN -> "pc.screen"
        PHONE_SCREEN -> "phone.screen"
        PC_AUDIO -> "pc.audio"
        PHONE_AUDIO -> "phone.audio"
        PC_MIC -> "pc.mic"
        PHONE_MIC -> "phone.mic"
        else -> "stream$id"
    }

    fun fromName(name: String): Byte = when (name) {
        "pc.screen" -> PC_SCREEN
        "phone.screen" -> PHONE_SCREEN
        "pc.audio" -> PC_AUDIO
        "phone.audio" -> PHONE_AUDIO
        "pc.mic" -> PC_MIC
        "phone.mic" -> PHONE_MIC
        else -> -1
    }
}

object MediaFlags {
    const val NONE = 0
    /** Every tile of the frame is present; the receiver may reset its canvas. */
    const val KEYFRAME = 1
    /** Last packet of this frame — the receiver may present now. */
    const val END_OF_FRAME = 2
}

/**
 * One real-time packet: 11-byte header then payload. Deliberately not JSON —
 * at sixty packets a second both the parse cost and the byte overhead matter.
 */
class MediaPacket(
    val kind: Byte,
    val stream: Byte,
    val seq: Int,
    val timestampMs: Int,
    val flags: Int,
    val payload: ByteArray,
    val payloadOffset: Int = 0,
    val payloadLength: Int = payload.size,
) {
    fun toBytes(): ByteArray {
        val body = ByteArray(1 + HEADER_SIZE + payloadLength)
        body[0] = InnerType.MEDIA.code
        body[1] = kind
        body[2] = stream
        writeIntBE(body, 3, seq)
        writeIntBE(body, 7, timestampMs)
        body[11] = flags.toByte()
        payload.copyInto(body, 1 + HEADER_SIZE, payloadOffset, payloadOffset + payloadLength)
        return body
    }

    val isKeyframe: Boolean get() = flags and MediaFlags.KEYFRAME != 0
    val isEndOfFrame: Boolean get() = flags and MediaFlags.END_OF_FRAME != 0

    companion object {
        const val HEADER_SIZE = 11

        /** @param plaintext the decrypted inner frame, including the leading inner-type byte. */
        fun parse(plaintext: ByteArray): MediaPacket {
            if (plaintext.size < 1 + HEADER_SIZE) throw ProtocolException("truncated media packet")
            return MediaPacket(
                kind = plaintext[1],
                stream = plaintext[2],
                seq = readIntBE(plaintext, 3),
                timestampMs = readIntBE(plaintext, 7),
                flags = plaintext[11].toInt() and 0xFF,
                payload = plaintext,
                payloadOffset = 1 + HEADER_SIZE,
                payloadLength = plaintext.size - 1 - HEADER_SIZE,
            )
        }
    }
}

object XferFlags {
    const val NONE = 0
    const val LAST = 1
}

/** One chunk of a file transfer. */
class XferChunk(
    val transferId: Int,
    val seq: Int,
    val flags: Int,
    val data: ByteArray,
    val dataOffset: Int = 0,
    val dataLength: Int = data.size,
) {
    fun toBytes(): ByteArray {
        val body = ByteArray(1 + HEADER_SIZE + dataLength)
        body[0] = InnerType.XFER.code
        writeIntBE(body, 1, transferId)
        writeIntBE(body, 5, seq)
        body[9] = flags.toByte()
        data.copyInto(body, 1 + HEADER_SIZE, dataOffset, dataOffset + dataLength)
        return body
    }

    val isLast: Boolean get() = flags and XferFlags.LAST != 0

    companion object {
        const val HEADER_SIZE = 9

        fun parse(plaintext: ByteArray): XferChunk {
            if (plaintext.size < 1 + HEADER_SIZE) throw ProtocolException("truncated xfer chunk")
            return XferChunk(
                transferId = readIntBE(plaintext, 1),
                seq = readIntBE(plaintext, 5),
                flags = plaintext[9].toInt() and 0xFF,
                data = plaintext,
                dataOffset = 1 + HEADER_SIZE,
                dataLength = plaintext.size - 1 - HEADER_SIZE,
            )
        }
    }
}

/**
 * A screen frame is a list of changed tiles: `index:u16, len:u32, jpeg[len]`,
 * repeated. Only tiles whose content hash moved are sent, which is what makes an
 * intra-only codec affordable — and unlike an inter-frame codec there is no
 * reference-frame latency to pay on either side.
 */
object TileCodec {
    /** Keep one packet small enough that it never becomes a visible stall. */
    const val MAX_PACKET_BYTES = 48 * 1024

    fun writeTile(destination: java.io.ByteArrayOutputStream, index: Int, jpeg: ByteArray, length: Int = jpeg.size) {
        destination.write((index ushr 8) and 0xFF)
        destination.write(index and 0xFF)
        destination.write((length ushr 24) and 0xFF)
        destination.write((length ushr 16) and 0xFF)
        destination.write((length ushr 8) and 0xFF)
        destination.write(length and 0xFF)
        destination.write(jpeg, 0, length)
    }

    /** Calls [onTile] for each tile in a media payload. Slices are views, not copies. */
    inline fun forEachTile(
        payload: ByteArray,
        offset: Int,
        length: Int,
        onTile: (index: Int, data: ByteArray, dataOffset: Int, dataLength: Int) -> Unit,
    ) {
        var cursor = offset
        val end = offset + length
        while (cursor + 6 <= end) {
            val index = ((payload[cursor].toInt() and 0xFF) shl 8) or (payload[cursor + 1].toInt() and 0xFF)
            val size = readIntBE(payload, cursor + 2)
            cursor += 6
            if (size < 0 || size > end - cursor) return
            onTile(index, payload, cursor, size)
            cursor += size
        }
    }
}

internal fun writeIntBE(buffer: ByteArray, offset: Int, value: Int) {
    buffer[offset] = (value ushr 24).toByte()
    buffer[offset + 1] = (value ushr 16).toByte()
    buffer[offset + 2] = (value ushr 8).toByte()
    buffer[offset + 3] = value.toByte()
}

fun readIntBE(buffer: ByteArray, offset: Int): Int =
    ((buffer[offset].toInt() and 0xFF) shl 24) or
        ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
        ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
        (buffer[offset + 3].toInt() and 0xFF)
