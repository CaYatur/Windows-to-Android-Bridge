package com.cayatur.winbridge.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persisted pairing state.
 *
 * The pre-shared key authenticates this phone to the PC, so it is wrapped with
 * an AES key held in the Android Keystore — hardware-backed where the device
 * offers it — rather than sitting in plain text in shared preferences. App
 * sandboxing already protects it from other apps; this additionally protects it
 * from anything that can read the app's data directory off the device.
 */
class SecureStore(context: Context) {

    private val prefs = context.getSharedPreferences("winbridge", Context.MODE_PRIVATE)

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: java.util.UUID.randomUUID().toString()
            .also { prefs.edit { putString(KEY_DEVICE_ID, it) } }
        set(value) = prefs.edit { putString(KEY_DEVICE_ID, value) }

    var hostName: String?
        get() = prefs.getString(KEY_HOST_NAME, null)
        set(value) = prefs.edit { putString(KEY_HOST_NAME, value) }

    var hostDeviceId: String?
        get() = prefs.getString(KEY_HOST_ID, null)
        set(value) = prefs.edit { putString(KEY_HOST_ID, value) }

    /** Classic BR/EDR address. Never an LE address — RFCOMM cannot use those. */
    var hostBtMac: String?
        get() = prefs.getString(KEY_BT_MAC, null)
        set(value) = prefs.edit { putString(KEY_BT_MAC, value) }

    var hostLanHosts: List<String>
        get() = prefs.getString(KEY_LAN_HOSTS, "")!!.split(",").filter { it.isNotBlank() }
        set(value) = prefs.edit { putString(KEY_LAN_HOSTS, value.joinToString(",")) }

    var hostLanPort: Int
        get() = prefs.getInt(KEY_LAN_PORT, 8737)
        set(value) = prefs.edit { putInt(KEY_LAN_PORT, value) }

    var preferBluetooth: Boolean
        get() = prefs.getBoolean(KEY_PREFER_BT, true)
        set(value) = prefs.edit { putBoolean(KEY_PREFER_BT, value) }

    var setupComplete: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_SETUP_DONE, value) }

    /** Mirror the PC's media as a phone media session. On by default. */
    var showMediaNotification: Boolean
        get() = prefs.getBoolean(KEY_MEDIA_NOTIFICATION, true)
        set(value) = prefs.edit { putBoolean(KEY_MEDIA_NOTIFICATION, value) }

    val isPaired: Boolean get() = psk != null && hostDeviceId != null

    var psk: ByteArray?
        get() {
            val blob = prefs.getString(KEY_PSK, null) ?: return null
            return runCatching { unwrap(Base64.decode(blob, Base64.NO_WRAP)) }.getOrNull()
        }
        set(value) {
            if (value == null) {
                prefs.edit { remove(KEY_PSK) }
            } else {
                prefs.edit { putString(KEY_PSK, Base64.encodeToString(wrap(value), Base64.NO_WRAP)) }
            }
        }

    fun forgetHost() {
        prefs.edit {
            remove(KEY_PSK)
            remove(KEY_HOST_ID)
            remove(KEY_HOST_NAME)
            remove(KEY_BT_MAC)
            remove(KEY_LAN_HOSTS)
        }
    }

    // ---- Keystore wrapping -------------------------------------------------

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun wrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(plaintext)
        // The keystore picks the IV; store it alongside so unwrap can rebuild it.
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + body
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val ivLength = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLength)
        val body = blob.copyOfRange(1 + ivLength, blob.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "winbridge.psk.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_HOST_ID = "hostDeviceId"
        const val KEY_HOST_NAME = "hostName"
        const val KEY_BT_MAC = "hostBtMac"
        const val KEY_LAN_HOSTS = "hostLanHosts"
        const val KEY_LAN_PORT = "hostLanPort"
        const val KEY_PREFER_BT = "preferBluetooth"
        const val KEY_SETUP_DONE = "setupComplete"
        const val KEY_MEDIA_NOTIFICATION = "showMediaNotification"
        const val KEY_PSK = "psk"
    }
}
