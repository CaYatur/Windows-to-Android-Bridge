package com.cayatur.winbridge.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * An automation on the watch face itself.
 *
 * A tile is one swipe away; a complication is zero. For the one or two things
 * someone runs constantly — mute the PC, lock it, start the evening routine —
 * that difference is most of the value, and it is the closest thing a watch has
 * to a home-screen widget.
 *
 * Which automation each slot runs is chosen in [ComplicationConfigActivity],
 * addressed by the complication instance id, so the same face can carry two of
 * these doing different things.
 */
class AutomationComplication : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        complication(type, getString(R.string.wear_automations), tapIntent(this, null))

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val chosenId = ComplicationPicks.get(this, request.complicationInstanceId)
        val automation = WearExtras.readAutomations(this).items.firstOrNull { it.id == chosenId }

        // Unconfigured, or configured for something the phone has since removed:
        // the slot still says what it is and opens the picker, rather than going
        // blank or silently doing nothing when pressed.
        val label = automation?.name ?: getString(R.string.wear_complication_unset)
        val tap = if (automation != null) tapIntent(this, automation.id) else configIntent(this, request.complicationInstanceId)

        return complication(request.complicationType, label, tap)
    }

    private fun complication(type: ComplicationType, label: String, tap: PendingIntent?): ComplicationData? {
        val text = PlainComplicationText.Builder(label.take(24)).build()
        val description = PlainComplicationText.Builder(getString(R.string.wear_automations)).build()

        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text, description)
                    .setTapAction(tap)
                    .build()

            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(text, description)
                    .setTapAction(tap)
                    .build()

            else -> null
        }
    }

    private fun tapIntent(context: Context, automationId: String?): PendingIntent? {
        val intent = if (automationId == null) {
            Intent(context, WearMainActivity::class.java)
        } else {
            Intent(context, TileCommandActivity::class.java)
                .putExtra(TileCommandActivity.EXTRA_COMMAND, "auto:$automationId")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return PendingIntent.getActivity(
            context,
            (automationId ?: "open").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun configIntent(context: Context, instanceId: Int): PendingIntent? {
        val intent = Intent(context, ComplicationConfigActivity::class.java)
            .putExtra(ComplicationConfigActivity.EXTRA_INSTANCE_ID, instanceId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return PendingIntent.getActivity(
            context, instanceId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Which automation each complication slot runs, by instance id. */
object ComplicationPicks {

    private const val PREFS = "winbridge.wear.complications"

    fun set(context: Context, instanceId: Int, automationId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(instanceId.toString(), automationId)
            .apply()
    }

    fun get(context: Context, instanceId: Int): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(instanceId.toString(), null)

    /** Asks the system to redraw every slot this data source fills. */
    fun refresh(context: Context) {
        runCatching {
            androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, AutomationComplication::class.java))
                .requestUpdateAll()
        }
    }
}
