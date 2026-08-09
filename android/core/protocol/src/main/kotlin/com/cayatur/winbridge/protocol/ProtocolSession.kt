package com.cayatur.winbridge.protocol

import kotlinx.serialization.encodeToString
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

data class LocalIdentity(val deviceId: String, val name: String, val platform: String = "android")

data class InboundMessage(val inner: InnerType, val jsonType: String?, val blobId: String?, val body: ByteArray) {
    inline fun <reified T> decode(): T = ProtocolJson.decodeFromString(body.decodeToString())
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Client half of the handshake and the encrypted channel. Mirrors the C#
 * `ProtocolSession`; the two are pinned together by the vectors in
 * `VectorsTest`.
 *
 * Blocking by design — a carrier gives us a socket's streams, and callers run
 * this on a dispatcher of their choosing.
 */
class ProtocolSession private constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val sendKey: ByteArray,
    private val recvKey: ByteArray,
    private val sendNoncePrefix: ByteArray,
    private val recvNoncePrefix: ByteArray,
    val peerDeviceId: String,
    val peerName: String,
    val peerPlatform: String,
) {
    private val sendCounter = AtomicLong(0)
    private var recvCounter = 0L
    private val writeLock = Any()

    companion object {
        private val base64 = Base64.getEncoder()
        private val base64d = Base64.getDecoder()

        /**
         * @param mode "session" for a normal connection, "pair" while pairing —
         *   in pairing mode [psk] is the PIN bytes and the durable key arrives
         *   afterwards in a `pair.complete` message.
         */
        fun connect(
            input: InputStream,
            output: OutputStream,
            me: LocalIdentity,
            psk: ByteArray,
            mode: String = "session",
        ): ProtocolSession {
            val keyPair = Crypto.generateKeyPair()
            val clientPoint = Crypto.exportPoint(keyPair.public)
            val clientNonce = Crypto.randomBytes(Crypto.NONCE_LENGTH)

            val hello = Hello(
                deviceId = me.deviceId,
                name = me.name,
                platform = me.platform,
                mode = mode,
                ephPub = base64.encodeToString(clientPoint),
                nonce = base64.encodeToString(clientNonce),
            )
            Framing.write(output, FrameType.HELLO, ProtocolJson.encodeToString(hello).encodeToByteArray())

            val frame = Framing.read(input)
            if (frame.type != FrameType.HELLO_ACK) {
                throw ProtocolException("expected HELLO_ACK, got ${frame.type}")
            }

            val ack: HelloAck = ProtocolJson.decodeFromString(frame.payload.decodeToString())
            val serverPoint = base64d.decode(ack.ephPub)
            val serverNonce = base64d.decode(ack.nonce)

            val transcript = KeyScheduleBuilder.transcript(
                me.deviceId, clientPoint, clientNonce,
                ack.deviceId, serverPoint, serverNonce,
            )
            val schedule = KeyScheduleBuilder.compute(
                keyPair.private, serverPoint, clientNonce, serverNonce, psk, transcript,
            )

            // Verify the server before sending our own authenticator, so a
            // machine that does not hold the key learns nothing from us.
            if (!Crypto.constantTimeEquals(base64d.decode(ack.confirm), schedule.confirmServer)) {
                throw ProtocolException("server failed authentication (wrong pairing key?)")
            }

            val session = ProtocolSession(
                input, output,
                sendKey = schedule.keyC2S, recvKey = schedule.keyS2C,
                sendNoncePrefix = schedule.noncePrefixC2S, recvNoncePrefix = schedule.noncePrefixS2C,
                peerDeviceId = ack.deviceId, peerName = ack.name, peerPlatform = ack.platform,
            )

            session.sendJson(AuthMessage(confirm = base64.encodeToString(schedule.confirmClient)))
            return session
        }
    }

    inline fun <reified T> sendJson(message: T) =
        sendJsonBytes(ProtocolJson.encodeToString(message).encodeToByteArray())

    fun sendJsonBytes(json: ByteArray) {
        val body = ByteArray(json.size + 1)
        body[0] = InnerType.JSON.code
        json.copyInto(body, 1)
        sendSealed(body)
    }

    private fun sendSealed(plaintext: ByteArray) {
        synchronized(writeLock) {
            val counter = sendCounter.incrementAndGet()
            if (counter == Long.MAX_VALUE) {
                throw ProtocolException("send counter exhausted; session must be re-established")
            }
            Framing.write(
                output, FrameType.SECURE,
                Crypto.seal(sendKey, sendNoncePrefix, counter, plaintext),
            )
        }
    }

    fun receive(): InboundMessage {
        val frame = Framing.read(input)
        if (frame.type == FrameType.BYE) throw ProtocolException("peer said goodbye")
        if (frame.type != FrameType.SECURE) throw ProtocolException("unexpected ${frame.type} after handshake")

        if (frame.payload.size < 8 + 16) throw ProtocolException("secure frame too short")
        val counter = Crypto.readLongBE(frame.payload, 0)

        // Strictly increasing: this is what makes replay and reordering useless
        // to an attacker who can see the wire.
        if (counter <= recvCounter) {
            throw ProtocolException("replayed or reordered frame ($counter <= $recvCounter)")
        }

        val plaintext = Crypto.open(
            recvKey, recvNoncePrefix, counter,
            frame.payload.copyOfRange(8, frame.payload.size),
        )
        recvCounter = counter

        if (plaintext.isEmpty()) throw ProtocolException("empty secure frame")

        return when (InnerType.from(plaintext[0])) {
            InnerType.JSON -> {
                val body = plaintext.copyOfRange(1, plaintext.size)
                InboundMessage(InnerType.JSON, readMessageType(body), null, body)
            }
            InnerType.BLOB -> {
                if (plaintext.size < 2) throw ProtocolException("truncated blob frame")
                val idLength = plaintext[1].toInt() and 0xFF
                if (plaintext.size < 2 + idLength) throw ProtocolException("truncated blob id")
                val id = plaintext.copyOfRange(2, 2 + idLength).decodeToString()
                InboundMessage(
                    InnerType.BLOB, null, id,
                    plaintext.copyOfRange(2 + idLength, plaintext.size),
                )
            }
        }
    }

    fun sayGoodbye() {
        runCatching { Framing.write(output, FrameType.BYE, ByteArray(0)) }
    }
}
