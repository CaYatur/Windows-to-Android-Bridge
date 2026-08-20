package com.cayatur.winbridge.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.service.BridgeService
import java.util.Locale

/**
 * "Tell my PC" — speak a command, have it run there.
 *
 * The recogniser is the device's own, reached through the standard intent, so
 * there is no key, no account and no audio leaving the phone beyond whatever the
 * user's chosen recogniser already does. The matching afterwards is local.
 *
 * This is also what an assistant routine can be pointed at: the activity is a
 * published shortcut, and launching a shortcut is something assistants can do
 * even though handing a third-party app an arbitrary command is not.
 */
class VoiceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeService.start(this)

        val spoken = intent?.getStringExtra(EXTRA_TEXT)
        if (!spoken.isNullOrBlank()) {
            handle(spoken)
            return
        }

        val listen = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_listening))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        try {
            startActivityForResult(listen, REQUEST)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @Deprecated("Deprecated in favour of the result APIs, which a plain Activity cannot use here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST) return

        val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        if (resultCode != RESULT_OK || heard.isEmpty()) {
            Toast.makeText(this, R.string.voice_no_match, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Every alternative is tried, best first. Recognisers frequently put the
        // right words second when a command contains an app name they do not
        // know, and refusing on the first miss would waste a good match.
        for (candidate in heard) {
            val outcome = WinBridgeApp.instance.voice.execute(candidate)
            if (outcome.understood) {
                Toast.makeText(this, getString(R.string.voice_sent, outcome.description), Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        }

        Toast.makeText(this, getString(R.string.voice_no_match), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun handle(text: String) {
        val outcome = WinBridgeApp.instance.voice.execute(text)
        Toast.makeText(
            this,
            if (outcome.understood) getString(R.string.voice_sent, outcome.description)
            else getString(R.string.voice_no_match),
            Toast.LENGTH_SHORT,
        ).show()
        finish()
    }

    companion object {
        private const val REQUEST = 5150
        const val EXTRA_TEXT = "text"
    }
}
