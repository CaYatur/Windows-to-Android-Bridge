package com.cayatur.winbridge.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Tiny exported trampoline used by Tile LaunchAction buttons.
 *
 * Exported, so anything on the watch can start it — which is why the command is
 * validated rather than forwarded. Media transport verbs are a fixed list, and
 * an automation is accepted only if its id is one the phone actually pushed to
 * this watch. Power commands never pass through here at all.
 */
class TileCommandActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val command = intent?.getStringExtra(EXTRA_COMMAND)
        if (command == null || !isAllowed(command)) {
            finish()
            return
        }

        lifecycleScope.launch {
            WearState.send(applicationContext, command)
            finish()
        }
    }

    private fun isAllowed(command: String): Boolean {
        if (command in ALLOWED_COMMANDS) return true

        if (command.startsWith("auto:")) {
            val id = command.removePrefix("auto:")
            return WearExtras.readAutomations(applicationContext).items.any { it.id == id }
        }
        return false
    }

    companion object {
        const val EXTRA_COMMAND = "command"
        private val ALLOWED_COMMANDS = setOf("media:prev", "media:toggle", "media:next")
    }
}
