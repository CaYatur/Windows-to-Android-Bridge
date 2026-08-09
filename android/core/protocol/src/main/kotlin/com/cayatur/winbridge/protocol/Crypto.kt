package com.cayatur.winbridge.protocol

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.EllipticCurve
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mirror of the C# `CryptoBox`. See docs/PROTOCOL.md §2.
 *
 * Two things here exist specifically because this has to agree byte-for-byte
 * with another implementation:
 *
 * - HKDF is written out rather than taken from the platform. There is no HKDF
 *   on API 26 and no androidx shim for it.
 * - Curve coordinates are padded to exactly 32 bytes. `BigInteger.toByteArray`
 *   emits 33 bytes when the high bit is set and fewer than 32 when the value
 *   has leading zeros; either produces a structurally valid point that derives
 *   a different shared secret, and the only symptom is a failed handshake.
 */
object Crypto {

    const val PSK_LENGTH = 32
    const val NONCE_LENGTH = 16
    private const val TAG_BITS = 128
    private const val COORD_BYTES = 32

    private val random = SecureRandom()

    fun randomBytes(length: Int): ByteArray = ByteArray(length).also { random.nextBytes(it) }

    // ---- P-256 -------------------------------------------------------------

    /**
     * secp256r1 written out explicitly. Building the spec from constants avoids
     * depending on the security provider exposing named-curve lookup, which is
     * not uniform across the Android versions this app supports.
     */
    val P256: ECParameterSpec by lazy {
        val p = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
        val a = BigInteger("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc", 16)
        val b = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
        val gx = BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16)
        val gy = BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16)
        val n = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
        ECParameterSpec(EllipticCurve(ECFieldFp(p), a, b), ECPoint(gx, gy), n, 1)
    }

    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        return generator.generateKeyPair()
    }

    /** X9.62 uncompressed point: `0x04 || X(32) || Y(32)`. */
    fun exportPoint(publicKey: java.security.PublicKey): ByteArray {
        val point = (publicKey as java.security.interfaces.ECPublicKey).w
        return ByteArray(65).apply {
            this[0] = 0x04
            pad32(point.affineX).copyInto(this, 1)
            pad32(point.affineY).copyInto(this, 33)
        }
    }

    fun importPoint(encoded: ByteArray): java.security.PublicKey {
        require(encoded.size == 65 && encoded[0] == 0x04.toByte()) {
            "peer public key is not an uncompressed P-256 point"
        }
        val x = BigInteger(1, encoded.copyOfRange(1, 33))
        val y = BigInteger(1, encoded.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), P256))
    }

    fun importPrivate(scalar: ByteArray): java.security.PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), P256))

    /**
     * Raw ECDH: the X coordinate, unhashed. Matches .NET's
     * `DeriveRawSecretAgreement`. Anything that hashes the agreement here would
     * diverge from the other side with no useful error.
     */
    fun agree(privateKey: java.security.PrivateKey, peerPoint: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(importPoint(peerPoint), true)
        return pad32(BigInteger(1, agreement.generateSecret()))
    }

    private fun pad32(value: BigInteger): ByteArray = pad32(value.toByteArray())

    private fun pad32(value: ByteArray): ByteArray = when {
        value.size == COORD_BYTES -> value
        // BigInteger.toByteArray prepends a zero sign byte when bit 255 is set.
        value.size > COORD_BYTES -> value.copyOfRange(value.size - COORD_BYTES, value.size)
        else -> ByteArray(COORD_BYTES).also { value.copyInto(it, COORD_BYTES - value.size) }
    }

    // ---- HKDF (RFC 5869) ---------------------------------------------------

    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray =
        hmac(salt, ikm)

    fun hkdfExpand(prk: ByteArray, info: String, length: Int): ByteArray {
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var produced = 0
        var counter = 1

        while (produced < length) {
            val input = previous + infoBytes + byteArrayOf(counter.toByte())
            previous = hmac(prk, input)
            val take = minOf(previous.size, length - produced)
            previous.copyInto(output, produced, 0, take)
            produced += take
            counter++
        }
        return output
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // An all-zero salt is legal in HKDF but SecretKeySpec rejects an empty
        // key, so a zero-length salt becomes a block of zeros as RFC 5869 says.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    // ---- AES-256-GCM -------------------------------------------------------

    fun seal(key: ByteArray, noncePrefix: ByteArray, counter: Long, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce(noncePrefix, counter)),
        )
        val body = cipher.doFinal(plaintext)

        return ByteArray(8 + body.size).apply {
            writeLongBE(this, 0, counter)
            body.copyInto(this, 8)
        }
    }

    fun open(key: ByteArray, noncePrefix: ByteArray, counter: Long, ciphertextWithTag: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce(noncePrefix, counter)),
        )
        return cipher.doFinal(ciphertextWithTag)
    }

    private fun nonce(prefix: ByteArray, counter: Long): ByteArray =
        ByteArray(12).apply {
            prefix.copyInto(this, 0, 0, 4)
            writeLongBE(this, 4, counter)
        }

    fun writeLongBE(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) target[offset + i] = (value ushr (56 - 8 * i)).toByte()
    }

    fun readLongBE(source: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (source[offset + i].toLong() and 0xFF)
        return value
    }
}

/** Everything the key schedule produces, so tests can pin each step. */
data class KeySchedule(
    val z: ByteArray,
    val keyC2S: ByteArray,
    val keyS2C: ByteArray,
    val noncePrefixC2S: ByteArray,
    val noncePrefixS2C: ByteArray,
    val confirmServer: ByteArray,
    val confirmClient: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

object KeyScheduleBuilder {

    fun compute(
        selfPrivate: java.security.PrivateKey,
        peerPoint: ByteArray,
        nonceClient: ByteArray,
        nonceServer: ByteArray,
        psk: ByteArray,
        transcript: ByteArray,
    ): KeySchedule {
        val z = Crypto.agree(selfPrivate, peerPoint)
        val prk = Crypto.hkdfExtract(nonceClient + nonceServer, z + psk)

        val confirmKey = Crypto.hkdfExpand(prk, "winbridge/v1/confirm", 32)
        return KeySchedule(
            z = z,
            keyC2S = Crypto.hkdfExpand(prk, "winbridge/v1/key/c2s", 32),
            keyS2C = Crypto.hkdfExpand(prk, "winbridge/v1/key/s2c", 32),
            noncePrefixC2S = Crypto.hkdfExpand(prk, "winbridge/v1/nonce/c2s", 4),
            noncePrefixS2C = Crypto.hkdfExpand(prk, "winbridge/v1/nonce/s2c", 4),
            confirmServer = Crypto.hmac(confirmKey, "server".toByteArray() + transcript),
            confirmClient = Crypto.hmac(confirmKey, "client".toByteArray() + transcript),
        )
    }

    /**
     * Binds both identities and both ephemeral keys into one hash, over
     * length-prefixed components rather than JSON bytes so the two
     * implementations cannot disagree about serialization.
     */
    fun transcript(
        clientDeviceId: String,
        clientPoint: ByteArray,
        clientNonce: ByteArray,
        serverDeviceId: String,
        serverPoint: ByteArray,
        serverNonce: ByteArray,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun chunk(data: ByteArray) {
            out.write((data.size ushr 8) and 0xFF)
            out.write(data.size and 0xFF)
            out.write(data)
        }
        chunk("winbridge/v1".toByteArray())
        chunk(clientDeviceId.toByteArray())
        chunk(clientPoint)
        chunk(clientNonce)
        chunk(serverDeviceId.toByteArray())
        chunk(serverPoint)
        chunk(serverNonce)
        return Crypto.sha256(out.toByteArray())
    }
}
