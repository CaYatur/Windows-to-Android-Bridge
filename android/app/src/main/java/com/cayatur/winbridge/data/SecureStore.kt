package com.cayatur.winbridge.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import kotlin.reflect.KProperty
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

    /**
     * Off by default from 0.2.0. Bluetooth is the right carrier for presence and
     * control, but it cannot carry mirroring, audio or a file of any size, and
     * preferring a link most of the features cannot use is not a good default.
     */
    var preferBluetooth: Boolean
        get() = prefs.getBoolean(KEY_PREFER_BT, false)
        set(value) = prefs.edit { putBoolean(KEY_PREFER_BT, value) }

    var bluetoothEnabled: Boolean
        get() = prefs.getBoolean(KEY_BT_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_BT_ENABLED, value) }

    var setupComplete: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_SETUP_DONE, value) }

    /** Mirror the PC's media as a phone media session. On by default. */
    var showMediaNotification: Boolean
        get() = prefs.getBoolean(KEY_MEDIA_NOTIFICATION, true)
        set(value) = prefs.edit { putBoolean(KEY_MEDIA_NOTIFICATION, value) }

    // ---- 0.2.0 feature switches -------------------------------------------
    //
    // Enough of these arrived at once that spelling every one out as a get/set
    // pair buried the two that actually matter. The delegates below keep the
    // default visible on the same line as the name, which is the thing worth
    // reading when someone asks "is this on out of the box?".

    /** Send what is copied here to the PC. Off until asked for. */
    var clipboardToPc by BoolPref("clipboardToPc", false)

    /** Apply what the PC copies to this clipboard. Off until asked for. */
    var clipboardFromPc by BoolPref("clipboardFromPc", false)

    /** Copy without asking, rather than posting a tap-to-apply notification. */
    var clipboardAutoApply by BoolPref("clipboardAutoApply", true)

    var fileTransferEnabled by BoolPref("fileTransfer", true)
    var fileAutoAccept by BoolPref("fileAutoAccept", false)
    var fileAutoAcceptMaxMb by IntPref("fileAutoAcceptMaxMb", 64)
    var downloadFolderUri by StringPref("downloadFolderUri")

    /** Let the PC ask this phone to mirror its screen. Each session still needs a tap. */
    var allowScreenShare by BoolPref("allowScreenShare", true)

    /** Send phone audio along with the screen, when the app being captured permits it. */
    var screenShareAudio by BoolPref("screenShareAudio", true)

    /** Let the PC drive this phone through the accessibility service. */
    var allowRemoteInput by BoolPref("allowRemoteInput", false)

    /** View the PC screen from the phone. */
    var viewPcEnabled by BoolPref("viewPc", true)
    var viewPcInteract by BoolPref("viewPcInteract", true)
    var viewPcAudio by BoolPref("viewPcAudio", true)
    var viewPcQuality by IntPref("viewPcQuality", 70)
    var viewPcMaxFps by IntPref("viewPcMaxFps", 30)
    var viewPcMaxEdge by IntPref("viewPcMaxEdge", 1280)

    /** Play PC audio through this phone. */
    var audioFromPc by BoolPref("audioFromPc", false)

    /** Send this phone microphone to the PC. */
    var micToPc by BoolPref("micToPc", false)

    /** Play the PC microphone here. */
    var micFromPc by BoolPref("micFromPc", false)

    /** Mirror notifications to the PC. Off by default: it reads every notification. */
    var notificationMirror by BoolPref("notificationMirror", false)
    var notificationSkipOngoing by BoolPref("notifSkipOngoing", true)
    var notificationBlocked by StringSetPref("notifBlocked")

    /** Keep the foreground status notification visible when disconnected. On by default. */
    var persistentNotification by BoolPref("persistentNotification", true)

    var automationsEnabled by BoolPref("automations", true)

    /** Let the PC ring this phone when it is lost down the side of a sofa. */
    var allowRing by BoolPref("allowRing", true)

    /** Publish automations as app shortcuts, so the assistant can launch them by name. */
    var publishShortcuts by BoolPref("publishShortcuts", true)

    /** Allow other apps (Tasker and friends) to trigger automations through the intent API. */
    var allowExternalTriggers by BoolPref("allowExternalTriggers", false)

    /** Secret that external triggers must present. Rotated from the UI. */
    var triggerToken by StringPref("triggerToken")

    /** Speak the answer when asking the PC what is on screen. */
    var speakAnswers by BoolPref("speakAnswers", true)

    /** Only try LAN for the things Bluetooth cannot carry. Mirrors the host setting. */
    var mediaLanOnly by BoolPref("mediaLanOnly", true)

    val isPaired: Boolean get() = psk != null && hostDeviceId != null

    // ---- preference delegates ----------------------------------------------

    private inner class BoolPref(private val key: String, private val default: Boolean) {
        operator fun getValue(owner: Any?, property: KProperty<*>): Boolean =
            prefs.getBoolean(key, default)

        operator fun setValue(owner: Any?, property: KProperty<*>, value: Boolean) =
            prefs.edit { putBoolean(key, value) }
    }

    private inner class IntPref(private val key: String, private val default: Int) {
        operator fun getValue(owner: Any?, property: KProperty<*>): Int = prefs.getInt(key, default)

        operator fun setValue(owner: Any?, property: KProperty<*>, value: Int) =
            prefs.edit { putInt(key, value) }
    }

    private inner class StringPref(private val key: String, private val default: String? = null) {
        operator fun getValue(owner: Any?, property: KProperty<*>): String? =
            prefs.getString(key, default)

        operator fun setValue(owner: Any?, property: KProperty<*>, value: String?) =
            prefs.edit { putString(key, value) }
    }

    private inner class StringSetPref(private val key: String) {
        operator fun getValue(owner: Any?, property: KProperty<*>): Set<String> =
            prefs.getStringSet(key, emptySet()) ?: emptySet()

        operator fun setValue(owner: Any?, property: KProperty<*>, value: Set<String>) =
            prefs.edit { putStringSet(key, value) }
    }

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
        const val KEY_BT_ENABLED = "bluetoothEnabled"
        const val KEY_SETUP_DONE = "setupComplete"
        const val KEY_MEDIA_NOTIFICATION = "showMediaNotification"
        const val KEY_PSK = "psk"
    }
}
