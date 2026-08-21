package com.cayatur.winbridge.wear

import android.content.Context
import com.cayatur.winbridge.protocol.WearAutomations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The automation list and the last answer from the PC, kept the same way the
 * state snapshot is: in memory for the app, and in a preference so a tile
 * rendered while the process is dead still has something to show.
 */
object WearExtras {

    private const val PREFS = "winbridge.wear.extras"
    private const val KEY_AUTOMATIONS = "automations"
    private const val KEY_ANSWER = "answer"

    private val _automations = MutableStateFlow(WearAutomations())
    val automations: StateFlow<WearAutomations> = _automations.asStateFlow()

    private val _answer = MutableStateFlow<String?>(null)
    val answer: StateFlow<String?> = _answer.asStateFlow()

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _automations.value = WearAutomations.decode(prefs.getString(KEY_AUTOMATIONS, null))
        _answer.value = prefs.getString(KEY_ANSWER, null)
    }

    fun storeAutomations(context: Context, value: WearAutomations) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AUTOMATIONS, WearAutomations.encode(value))
            .apply()
        _automations.value = value
    }

    fun readAutomations(context: Context): WearAutomations =
        WearAutomations.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_AUTOMATIONS, null),
        )

    fun storeAnswer(context: Context, text: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ANSWER, text)
            .apply()
        _answer.value = text
    }
}
