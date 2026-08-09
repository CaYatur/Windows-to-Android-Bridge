package com.cayatur.winbridge.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Tiny exported trampoline used by Tile LaunchAction buttons.
 * Only media commands are accepted; power commands never pass through here.
 */
class TileCommandActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = intent?.getStringExtra(EXTRA_COMMAND)
        if (command !in ALLOWED_COMMANDS) {
            finish()
            return
        }
        lifecycleScope.launch {
            WearState.send(applicationContext, command!!)
            finish()
        }
    }

    companion object {
        const val EXTRA_COMMAND = "command"
        private val ALLOWED_COMMANDS = setOf("media:prev", "media:toggle", "media:next")
    }
}
