package com.cayatur.winbridge.protocol

import kotlinx.serialization.encodeToString
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

data class LocalIdentity(val deviceId: String, val name: String, val platform: String = "android")

data class InboundMessage(val inner: InnerType, val jsonType: String?, val blobId: String?, val body: ByteArray) {
    inline fun <reified T> decode(): T = ProtocolJson.decodeFromString(body.decodeToString())

    /** Valid only when [inner] is [InnerType.MEDIA]. */
    fun asMedia(): MediaPacket = MediaPacket.parse(body)

    /** Valid only when [inner] is [InnerType.XFER]. */
    fun asXfer(): XferChunk = XferChunk.parse(body)

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

    // Three queues drained strictly in order. The alternative — one lock around
    // the socket — means a touch event can sit behind a tile batch, which is
    // exactly the latency the mirroring feature exists to avoid.
    private val lanes = Array(3) { ArrayDeque<ByteArray>() }
    private val laneLock = ReentrantLock()
    private val laneChanged = laneLock.newCondition()
    private var writeFault: Exception? = null
    private var closed = false

    /**
     * How many media packets may sit unsent before new ones are discarded. Small
     * on purpose: a queued screen tile that is two frames old has already been
     * superseded, and waiting for it only adds latency the user can see.
     */
    @Volatile
    var mediaQueueLimit: Int = 24

    /** Media packets discarded because the link could not keep up. */
    @Volatile
    var mediaDropped: Long = 0
        private set

    private val writer = Thread({ writeLoop() }, "winbridge-writer").apply {
        isDaemon = true
        start()
    }

    companion object {
        private val base64 = Base64.getEncoder()
        private val base64d = Base64.getDecoder()

        /** Enough to keep the socket busy, small enough that cancelling a transfer feels instant. */
        private const val BULK_QUEUE_LIMIT = 8

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
        enqueue(SendLane.CONTROL, body)
    }

    /**
     * Queues a real-time packet without waiting for the wire. Returns false when
     * the packet was dropped because the link is behind — the caller is a capture
     * loop and blocking it would build the very backlog this lane avoids.
     */
    fun trySendMedia(packet: MediaPacket): Boolean {
        laneLock.lock()
        try {
            if (closed || writeFault != null) return false
            if (lanes[SendLane.MEDIA.ordinal].size >= mediaQueueLimit) {
                mediaDropped++
                return false
            }
            lanes[SendLane.MEDIA.ordinal].addLast(packet.toBytes())
            laneChanged.signalAll()
        } finally {
            laneLock.unlock()
        }
        return true
    }

    /** Queues a bulk chunk. Blocks while the bulk lane is saturated, so a transfer is paced by the link. */
    fun sendXfer(chunk: XferChunk) = enqueue(SendLane.BULK, chunk.toBytes(), boundedTo = BULK_QUEUE_LIMIT)

    fun sendBlob(id: String, data: ByteArray) {
        val idBytes = id.encodeToByteArray()
        require(idBytes.size <= 255) { "blob id too long" }
        val body = ByteArray(2 + idBytes.size + data.size)
        body[0] = InnerType.BLOB.code
        body[1] = idBytes.size.toByte()
        idBytes.copyInto(body, 2)
        data.copyInto(body, 2 + idBytes.size)
        enqueue(SendLane.CONTROL, body)
    }

    private fun enqueue(lane: SendLane, plaintext: ByteArray, boundedTo: Int = 0) {
        laneLock.lock()
        try {
            writeFault?.let { throw it }
            if (closed) throw ProtocolException("session closed")

            if (boundedTo > 0) {
                while (lanes[lane.ordinal].size >= boundedTo && !closed && writeFault == null) {
                    laneChanged.await(1, TimeUnit.SECONDS)
                }
                writeFault?.let { throw it }
                if (closed) throw ProtocolException("session closed")
            }

            lanes[lane.ordinal].addLast(plaintext)
            laneChanged.signalAll()
        } finally {
            laneLock.unlock()
        }
    }

    /**
     * The single writer. Sealing happens here rather than at the call site so the
     * AES-GCM counter is assigned in the same order the bytes reach the wire —
     * assigning it earlier would let a low-priority frame burn a counter ahead of
     * a control frame and trip the peer's replay check.
     */
    private fun writeLoop() {
        while (true) {
            var plaintext: ByteArray? = null
            laneLock.lock()
            try {
                while (plaintext == null) {
                    if (closed || writeFault != null) return
                    plaintext = lanes.firstNotNullOfOrNull { it.pollFirst() }
                    if (plaintext == null) laneChanged.await()
                }
                laneChanged.signalAll()
            } finally {
                laneLock.unlock()
            }

            try {
                val counter = sendCounter.incrementAndGet()
                if (counter == Long.MAX_VALUE) {
                    throw ProtocolException("send counter exhausted; session must be re-established")
                }
                Framing.write(
                    output, FrameType.SECURE,
                    Crypto.seal(sendKey, sendNoncePrefix, counter, plaintext),
                )
            } catch (e: Exception) {
                laneLock.lock()
                try {
                    writeFault = e
                    lanes.forEach { it.clear() }
                    laneChanged.signalAll()
                } finally {
                    laneLock.unlock()
                }
                return
            }
        }
    }

    /** Blocks until every queued frame has been written, or the link fails. */
    fun flush(timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        laneLock.lock()
        try {
            while (lanes.any { it.isNotEmpty() } && writeFault == null && !closed) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return
                laneChanged.await(minOf(remaining, 100L), TimeUnit.MILLISECONDS)
            }
        } finally {
            laneLock.unlock()
        }
    }

    fun close() {
        laneLock.lock()
        try {
            closed = true
            lanes.forEach { it.clear() }
            laneChanged.signalAll()
        } finally {
            laneLock.unlock()
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
            // Media and bulk frames keep their whole plaintext so the consumer can
            // slice the payload as a view instead of copying it per packet.
            InnerType.MEDIA -> InboundMessage(InnerType.MEDIA, null, null, plaintext)
            InnerType.XFER -> InboundMessage(InnerType.XFER, null, null, plaintext)
        }
    }

    fun sayGoodbye() {
        runCatching { flush(1000) }
        runCatching { Framing.write(output, FrameType.BYE, ByteArray(0)) }
        close()
    }
}
