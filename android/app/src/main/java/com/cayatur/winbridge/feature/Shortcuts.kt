package com.cayatur.winbridge.feature

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.util.Log
import com.cayatur.winbridge.MainActivity
import com.cayatur.winbridge.R
import com.cayatur.winbridge.net.TAG
import com.cayatur.winbridge.protocol.AutomationSummary

/**
 * Publishes each automation as a launcher shortcut.
 *
 * This is the honest answer to "let the assistant run my PC automations". There
 * is no public API that lets Gemini or Assistant call into a third-party app with
 * an arbitrary payload, and no MCP-style extension surface on the device — so the
 * integration cannot be built as one. What can be built is this: a shortcut per
 * automation, which the launcher shows, the user can drop on the home screen, and
 * an assistant routine can be pointed at.
 *
 * Whether saying the shortcut name aloud reaches it varies by device and by
 * whether Gemini has replaced Assistant there, so the app never promises that.
 * The shortcuts and the intent API are deterministic; voice is a bonus that
 * should be verified on the phone in hand.
 */
object Shortcuts {

    private const val MAX = 8

    fun publish(context: Context, automations: List<AutomationSummary>, enabled: Boolean) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return

        if (!enabled) {
            runCatching { manager.removeAllDynamicShortcuts() }
            return
        }

        // The platform caps how many a launcher will hold, and the cap includes
        // the static ones from shortcuts.xml. Publishing more than that gets an
        // exception, not a truncation, so the list is trimmed here.
        val room = (manager.maxShortcutCountPerActivity - manager.manifestShortcuts.size)
            .coerceIn(0, MAX)

        val wanted = automations
            .filter { it.enabled && it.approved }
            .sortedByDescending { it.updatedAt ?: "" }
            .take(room)

        val shortcuts = wanted.map { automation ->
            ShortcutInfo.Builder(context, "auto:${automation.id}")
                .setShortLabel(automation.name.take(24))
                .setLongLabel(automation.description?.take(48) ?: automation.name.take(48))
                .setIcon(Icon.createWithResource(context, R.drawable.ic_monitor))
                .setIntent(
                    Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        putExtra(EXTRA_RUN_AUTOMATION, automation.id)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                )
                .build()
        }

        runCatching { manager.dynamicShortcuts = shortcuts }
            .onFailure { Log.w(TAG, "could not publish shortcuts: ${it.message}") }
    }

    const val EXTRA_RUN_AUTOMATION = "runAutomation"
}
