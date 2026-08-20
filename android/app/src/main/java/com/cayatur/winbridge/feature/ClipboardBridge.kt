package com.cayatur.winbridge.feature

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import android.util.Log
import com.cayatur.winbridge.R
import com.cayatur.winbridge.data.SecureStore
import com.cayatur.winbridge.net.TAG
import com.cayatur.winbridge.protocol.ClipboardMessage
import com.cayatur.winbridge.ui.ClipboardRelayActivity
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * Clipboard in both directions, built as a chain of attempts rather than around
 * a remembered rule.
 *
 * Android restricts clipboard access to whichever app currently has input focus,
 * and exactly how that behaves has moved between versions and differs on some
 * OEM builds. Writing this against a specific API-level rule would mean a
 * feature that silently does nothing on the devices where the rule is not what
 * the documentation says. So instead:
 *
 *   1. try the direct [ClipboardManager] call;
 *   2. if that comes back empty or throws, hand off to a transparent activity,
 *      which *is* focused for the frame it exists for;
 *   3. if even that is unavailable — no foreground allowed, screen off — post a
 *      notification or leave it to the Quick Settings tile, which is a user
 *      gesture and therefore always permitted.
 *
 * Every attempt logs which tier answered, so what actually happens on a given
 * device is a question with an answer in logcat rather than a guess.
 */
object ClipboardBridge {

    /** Which route last worked, for the diagnostics screen. */
    val lastTier = AtomicReference("unknown")

    /**
     * What we most recently wrote or read. Applying a clipboard raises the same
     * change event a human copy does, so without this the two machines hand the
     * same string back and forth for ever.
     */
    private val lastHash = AtomicReference<String?>(null)

    fun manager(context: Context): ClipboardManager =
        context.getSystemService(ClipboardManager::class.java)

    // ---- reading ----------------------------------------------------------

    /** Reads the clipboard directly. Null when it is empty or Android refused. */
    fun readDirect(context: Context): ClipboardMessage? {
        return try {
            val clip = manager(context).primaryClip ?: return null
            if (clip.itemCount == 0) return null

            val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
            if (text.isBlank()) return null

            lastTier.set("direct")
            build(text)
        } catch (e: SecurityException) {
            Log.i(TAG, "clipboard read refused: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "clipboard read failed: ${e.message}")
            null
        }
    }

    /**
     * Reads it however it can. Returns null when the caller has to fall back to
     * the relay activity, which cannot report a result synchronously.
     */
    fun read(context: Context): ClipboardMessage? = readDirect(context)

    fun build(text: String): ClipboardMessage {
        val hash = fingerprint(text.toByteArray())
        lastHash.set(hash)
        return ClipboardMessage(
            format = if (looksLikeUri(text)) "uri" else "text",
            text = text,
            hash = hash,
            label = Build.MODEL,
        )
    }

    // ---- writing ----------------------------------------------------------

    /** Applies a clipboard from the PC. Returns false when Android refused. */
    fun applyDirect(context: Context, clip: ClipboardMessage): Boolean {
        val text = clip.text ?: return false
        return try {
            val label = clip.label ?: "WinBridge"
            manager(context).setPrimaryClip(ClipData.newPlainText(label, text))

            // setPrimaryClip does not throw on a refusal on every build, so the
            // write is confirmed by reading it back where that is allowed.
            lastHash.set(clip.hash ?: fingerprint(text.toByteArray()))
            lastTier.set("direct")
            true
        } catch (e: SecurityException) {
            Log.i(TAG, "clipboard write refused: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "clipboard write failed: ${e.message}")
            false
        }
    }

    /**
     * Second tier: a transparent, animation-free activity that exists for a few
     * frames purely to hold input focus while the clipboard is touched.
     */
    fun applyViaActivity(context: Context, clip: ClipboardMessage) {
        lastTier.set("activity")
        val intent = Intent(context, ClipboardRelayActivity::class.java).apply {
            action = ClipboardRelayActivity.ACTION_APPLY
            putExtra(ClipboardRelayActivity.EXTRA_TEXT, clip.text)
            putExtra(ClipboardRelayActivity.EXTRA_LABEL, clip.label)
            putExtra(ClipboardRelayActivity.EXTRA_HASH, clip.hash)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Log.i(TAG, "clipboard relay could not start: ${it.message}")
            lastTier.set("notification")
        }
    }

    fun sendViaActivity(context: Context) {
        lastTier.set("activity")
        val intent = Intent(context, ClipboardRelayActivity::class.java).apply {
            action = ClipboardRelayActivity.ACTION_SEND
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        runCatching { context.startActivity(intent) }
    }

    // ---- echo suppression ---------------------------------------------------

    /** True when this is the clipboard we just set, arriving back at us. */
    fun isEcho(clip: ClipboardMessage): Boolean {
        val hash = clip.hash ?: clip.text?.let { fingerprint(it.toByteArray()) }
        return hash != null && hash == lastHash.get()
    }

    fun remember(clip: ClipboardMessage) {
        lastHash.set(clip.hash ?: clip.text?.let { fingerprint(it.toByteArray()) })
    }

    fun fingerprint(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.encodeToString(digest.copyOf(16), Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun looksLikeUri(text: String): Boolean =
        text.length < 2048 && !text.contains('\n') &&
            (text.startsWith("http://") || text.startsWith("https://"))

    /** Human-readable tier for the diagnostics row in settings. */
    fun describeTier(context: Context): String = when (lastTier.get()) {
        "direct" -> context.getString(R.string.settings_granted)
        "activity" -> "via relay"
        "notification" -> context.getString(R.string.clipboard_blocked)
        else -> "—"
    }
}
