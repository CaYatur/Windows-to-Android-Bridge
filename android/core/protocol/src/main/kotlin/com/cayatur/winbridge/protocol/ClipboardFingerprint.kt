package com.cayatur.winbridge.protocol

import java.security.MessageDigest

/**
 * How a clipboard is identified on the wire.
 *
 * Both machines fingerprint the same text, and each recognises its own words
 * coming back by comparing the two strings — so the encoding is part of the
 * protocol, not a detail either side is free to pick. It lives here, next to the
 * message definitions and covered by the same vector tests, because when it was
 * a private helper in each app they drifted: base64 on the phone, hex on the PC.
 * Sixteen identical bytes, spelled two different ways, so no comparison ever
 * matched — and every clipboard the phone sent was applied on the PC, seen there
 * as a fresh copy, and sent straight back to the phone.
 */
object ClipboardFingerprint {

    /** First 16 bytes of SHA-256, lowercase hex. */
    fun of(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(32) {
            for (index in 0 until 16) append("%02x".format(digest[index]))
        }
    }

    fun of(text: String): String = of(text.toByteArray(Charsets.UTF_8))
}
