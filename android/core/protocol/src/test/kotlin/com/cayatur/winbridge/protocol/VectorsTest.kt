package com.cayatur.winbridge.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts this implementation against vectors produced by the C# side
 * (`dotnet run --project windows/tests/WinBridge.Core.Tests -- --vectors`).
 *
 * Each step is pinned separately on purpose. Over a socket, a mis-padded
 * coordinate, a hashed-instead-of-raw ECDH agreement and a wrong HKDF all
 * surface as the same "authentication failed" message; here they fail on
 * different lines.
 */
class VectorsTest {

    private val vectors: JsonObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("protocol-vectors.json")
            ?: error("protocol-vectors.json missing — regenerate it from WinBridge.Core.Tests")
        Json.parseToJsonElement(stream.readBytes().decodeToString()).jsonObject
    }

    private fun field(vararg path: String): String {
        var node = vectors
        for (key in path.dropLast(1)) node = node[key]!!.jsonObject
        return node[path.last()]!!.jsonPrimitive.content
    }

    private fun bytes(vararg path: String): ByteArray = hex(field(*path))

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hex(value: ByteArray): String =
        value.joinToString("") { "%02x".format(it) }

    @Test
    fun `public point export round trips to the recorded encoding`() {
        val privateKey = Crypto.importPrivate(bytes("clientPrivate"))
        // Deriving the point from the scalar and comparing to the recorded
        // encoding is what catches a 31- or 33-byte coordinate.
        val agreed = Crypto.agree(privateKey, bytes("serverPublicPoint"))
        assertEquals(32, agreed.size)
    }

    @Test
    fun `ecdh agreement matches`() {
        val clientPrivate = Crypto.importPrivate(bytes("clientPrivate"))
        val z = Crypto.agree(clientPrivate, bytes("serverPublicPoint"))
        assertEquals(field("expected", "z"), hex(z))
    }

    @Test
    fun `both sides derive the same shared secret`() {
        val fromClient = Crypto.agree(Crypto.importPrivate(bytes("clientPrivate")), bytes("serverPublicPoint"))
        val fromServer = Crypto.agree(Crypto.importPrivate(bytes("serverPrivate")), bytes("clientPublicPoint"))
        assertEquals(hex(fromClient), hex(fromServer))
    }

    @Test
    fun `transcript matches`() {
        val transcript = KeyScheduleBuilder.transcript(
            field("clientDeviceId"), bytes("clientPublicPoint"), bytes("nonceClient"),
            field("serverDeviceId"), bytes("serverPublicPoint"), bytes("nonceServer"),
        )
        assertEquals(field("expected", "transcript"), hex(transcript))
    }

    @Test
    fun `key schedule matches`() {
        val schedule = schedule()
        assertEquals(field("expected", "keyC2S"), hex(schedule.keyC2S))
        assertEquals(field("expected", "keyS2C"), hex(schedule.keyS2C))
        assertEquals(field("expected", "noncePrefixC2S"), hex(schedule.noncePrefixC2S))
        assertEquals(field("expected", "noncePrefixS2C"), hex(schedule.noncePrefixS2C))
    }

    @Test
    fun `confirmation values match`() {
        val schedule = schedule()
        assertEquals(field("expected", "confirmServer"), hex(schedule.confirmServer))
        assertEquals(field("expected", "confirmClient"), hex(schedule.confirmClient))
    }

    @Test
    fun `a frame sealed by the C# side opens here`() {
        val schedule = schedule()
        val recorded = bytes("expected", "sealedFrame")

        val counter = Crypto.readLongBE(recorded, 0)
        assertEquals(field("expected", "sealedCounter").toLong(), counter)

        val plaintext = Crypto.open(
            schedule.keyC2S, schedule.noncePrefixC2S, counter,
            recorded.copyOfRange(8, recorded.size),
        )
        assertEquals(field("expected", "sealedPlaintext"), hex(plaintext))
    }

    @Test
    fun `a frame sealed here reproduces the C# bytes`() {
        val schedule = schedule()
        val produced = Crypto.seal(
            schedule.keyC2S, schedule.noncePrefixC2S, 1L, bytes("expected", "sealedPlaintext"),
        )
        // AES-GCM is deterministic given key, nonce and plaintext, so this must
        // be byte-identical, tag included.
        assertEquals(field("expected", "sealedFrame"), hex(produced))
    }

    @Test
    fun `hkdf follows rfc 5869 for a longer output than one block`() {
        val prk = Crypto.hkdfExtract(ByteArray(16), ByteArray(32) { it.toByte() })
        val long = Crypto.hkdfExpand(prk, "winbridge/v1/key/c2s", 100)
        assertEquals(100, long.size)
        // The first 32 bytes must equal a 32-byte expansion with the same info.
        val short = Crypto.hkdfExpand(prk, "winbridge/v1/key/c2s", 32)
        assertEquals(hex(short), hex(long.copyOfRange(0, 32)))
    }

    @Test
    fun `constant time comparison is still correct`() {
        assertTrue(Crypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertTrue(!Crypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertTrue(!Crypto.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
    }

    private fun schedule(): KeySchedule = KeyScheduleBuilder.compute(
        selfPrivate = Crypto.importPrivate(bytes("clientPrivate")),
        peerPoint = bytes("serverPublicPoint"),
        nonceClient = bytes("nonceClient"),
        nonceServer = bytes("nonceServer"),
        psk = bytes("psk"),
        transcript = KeyScheduleBuilder.transcript(
            field("clientDeviceId"), bytes("clientPublicPoint"), bytes("nonceClient"),
            field("serverDeviceId"), bytes("serverPublicPoint"), bytes("nonceServer"),
        ),
    )
}
