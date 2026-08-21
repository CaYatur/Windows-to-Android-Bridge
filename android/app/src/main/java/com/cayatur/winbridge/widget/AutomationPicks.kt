package com.cayatur.winbridge.widget

import android.content.Context
import com.cayatur.winbridge.protocol.AutomationSummary
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What the launcher needs to know about automations when the app is not running.
 *
 * A widget is drawn by the launcher's process, which may happen minutes after a
 * reboot and long before anything has connected to the PC. Reading the live
 * catalogue there gives an empty card — so the list is written down every time
 * it arrives, and read back synchronously at render time. The trade is that a
 * widget can briefly offer an automation that has since been deleted; running
 * one that no longer exists is refused by the PC by id, which is a better
 * failure than a permanently blank widget.
 */
object AutomationCache {

    private const val PREFS = "winbridge.widget.automations"
    private const val KEY_LIST = "list"
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(AutomationSummary.serializer())

    fun write(context: Context, items: List<AutomationSummary>) {
        val runnable = items.filter { it.enabled && it.approved }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIST, json.encodeToString(serializer, runnable))
            .apply()
    }

    fun read(context: Context): List<AutomationSummary> {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LIST, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, stored) }.getOrDefault(emptyList())
    }
}

/**
 * Which automation a particular placed widget runs.
 *
 * Keyed by the widget id the launcher assigns, so the same widget can be placed
 * several times for several automations — which is the point: a home screen
 * button is worth having precisely when it is *one* thing you do often, not a
 * list you still have to read.
 */
object AutomationPicks {

    private const val PREFS = "winbridge.widget.picks"

    fun set(context: Context, widgetId: Int, automationId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(widgetId.toString(), automationId)
            .apply()
    }

    fun get(context: Context, widgetId: Int): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(widgetId.toString(), null)

    fun clear(context: Context, widgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(widgetId.toString())
            .apply()
    }
}
