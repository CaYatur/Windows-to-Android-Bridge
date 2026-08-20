package com.cayatur.winbridge.feature

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.net.TAG
import com.cayatur.winbridge.protocol.AutoRunRequest
import com.cayatur.winbridge.protocol.AutomationSummary
import com.cayatur.winbridge.protocol.DescribeRequest
import com.cayatur.winbridge.protocol.MediaCommand
import com.cayatur.winbridge.protocol.PowerCommand
import com.cayatur.winbridge.protocol.VolumeCommand
import com.cayatur.winbridge.protocol.WindowCommand
import com.cayatur.winbridge.protocol.WindowsRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Turns a spoken or typed sentence into something the PC understands.
 *
 * This exists because the integration people ask for does not: there is no
 * public on-device API that lets Gemini or Assistant hand a third-party app an
 * arbitrary command, and no extension surface to register one with. What *is*
 * reachable without any key or account is the device's own speech recogniser and
 * a shortcut the assistant can launch by name — so the assistant opens this, and
 * the matching happens here, offline.
 *
 * The matcher is deliberately small and rule-based rather than clever. A command
 * that works nine times and does something surprising the tenth is worse than
 * one that says "I did not catch that", because the surprising tenth might be a
 * shutdown.
 */
class VoiceCommands(private val context: Context) {

    private var speaker: TextToSpeech? = null

    /** Result of a parse, so the caller can show what happened. */
    data class Outcome(val understood: Boolean, val description: String)

    fun execute(text: String): Outcome {
        val app = WinBridgeApp.instance
        val phrase = text.trim().lowercase(Locale.getDefault())
        if (phrase.isEmpty()) return Outcome(false, "")

        if (!app.client.isConnected) return Outcome(false, "no PC connected")

        // Automations first: a user who named one "goodnight" means that one,
        // not the built-in phrase that happens to contain the same word.
        matchAutomation(phrase, app.state.automations.value?.items.orEmpty())?.let { automation ->
            app.scope.launch { app.client.sendMessage(AutoRunRequest(id = automation.id)) }
            return Outcome(true, automation.name)
        }

        val outcome = builtIn(phrase, app)
        if (outcome != null) return outcome

        // Anything left that names a window gets treated as "bring that forward",
        // which is what a sentence mentioning an app almost always means.
        val window = app.state.windows.value?.items.orEmpty()
            .firstOrNull { phrase.contains(it.process.lowercase(Locale.getDefault())) }
            ?: app.state.windows.value?.items.orEmpty()
                .firstOrNull { candidate ->
                    candidate.title.lowercase(Locale.getDefault())
                        .split(' ', '-', '—')
                        .any { it.length > 3 && phrase.contains(it) }
                }

        if (window != null) {
            app.scope.launch { app.client.sendMessage(WindowCommand(action = "focus", handle = window.handle)) }
            return Outcome(true, "focus ${window.title}")
        }

        return Outcome(false, text)
    }

    private fun builtIn(phrase: String, app: WinBridgeApp): Outcome? {
        fun has(vararg words: String) = words.any { phrase.contains(it) }

        return when {
            has("what is on", "what's on", "read the screen", "ekranda ne", "ekranı oku") -> {
                app.scope.launch { app.client.sendMessage(DescribeRequest(ocr = true)) }
                Outcome(true, "describe the screen")
            }

            has("lock", "kilitle") -> power(app, "lock", "lock the PC")
            has("sleep", "uyku") -> power(app, "sleep", "sleep")
            has("shut down", "shutdown", "kapat") && !has("volume", "ses") ->
                power(app, "shutdown", "shut down")
            has("restart", "reboot", "yeniden başlat") -> power(app, "restart", "restart")
            has("sign out", "log off", "oturumu kapat") -> power(app, "logoff", "sign out")
            has("screen off", "ekranı kapat") -> power(app, "display_off", "turn the display off")

            has("pause", "duraklat") -> media(app, "pause", "pause")
            has("play", "çal", "oynat") -> media(app, "play", "play")
            has("next", "sonraki") -> media(app, "next", "next track")
            has("previous", "önceki") -> media(app, "prev", "previous track")

            has("mute", "sessize") -> volume(app, "mute", 0, "mute")
            has("unmute", "sesi aç") -> volume(app, "unmute", 0, "unmute")

            has("volume", "ses") -> {
                val level = Regex("""\d+""").find(phrase)?.value?.toIntOrNull()
                when {
                    level != null -> volume(app, "set", level, "volume $level")
                    has("up", "artır", "yükselt") -> {
                        val current = app.state.volume.value?.level ?: 50
                        volume(app, "set", (current + 10).coerceAtMost(100), "volume up")
                    }
                    has("down", "azalt", "kıs") -> {
                        val current = app.state.volume.value?.level ?: 50
                        volume(app, "set", (current - 10).coerceAtLeast(0), "volume down")
                    }
                    else -> null
                }
            }

            has("windows", "pencereler") -> {
                app.scope.launch { app.client.sendMessage(WindowsRequest()) }
                Outcome(true, "list windows")
            }

            else -> null
        }
    }

    private fun power(app: WinBridgeApp, action: String, description: String): Outcome {
        app.scope.launch { app.client.sendMessage(PowerCommand(action = action)) }
        return Outcome(true, description)
    }

    private fun media(app: WinBridgeApp, action: String, description: String): Outcome {
        app.scope.launch { app.client.sendMessage(MediaCommand(action = action)) }
        return Outcome(true, description)
    }

    private fun volume(app: WinBridgeApp, action: String, level: Int, description: String): Outcome {
        app.scope.launch { app.client.sendMessage(VolumeCommand(action = action, level = level)) }
        return Outcome(true, description)
    }

    /**
     * Matches an automation by name, allowing for a recogniser that heard it
     * approximately. Exact containment first, then a word-overlap score with a
     * floor — a loose match that fires the wrong automation is worse than none.
     */
    private fun matchAutomation(phrase: String, items: List<AutomationSummary>): AutomationSummary? {
        if (items.isEmpty()) return null

        items.firstOrNull { phrase.contains(it.name.lowercase(Locale.getDefault())) }?.let { return it }

        val spoken = phrase.split(' ', ',', '.').filter { it.length > 2 }.toSet()
        if (spoken.isEmpty()) return null

        var best: AutomationSummary? = null
        var bestScore = 0.0

        for (item in items) {
            val words = item.name.lowercase(Locale.getDefault())
                .split(' ', ',', '.')
                .filter { it.length > 2 }
                .toSet()
            if (words.isEmpty()) continue

            val overlap = words.count { spoken.contains(it) }.toDouble() / words.size
            if (overlap > bestScore) {
                bestScore = overlap
                best = item
            }
        }

        // Two thirds of the words, not a majority: "open the browser on my PC"
        // should not fire "close the browser".
        return if (bestScore >= 0.67) best else null
    }

    // ---- speaking back -------------------------------------------------------

    fun speak(text: String) {
        if (text.isBlank()) return

        val existing = speaker
        if (existing != null) {
            existing.speak(text.take(600), TextToSpeech.QUEUE_FLUSH, null, "winbridge")
            return
        }

        speaker = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.i(TAG, "no text-to-speech engine available")
                return@TextToSpeech
            }
            speaker?.language = Locale.getDefault()
            speaker?.speak(text.take(600), TextToSpeech.QUEUE_FLUSH, null, "winbridge")
        }
    }

    fun release() {
        runCatching { speaker?.shutdown() }
        speaker = null
    }
}
