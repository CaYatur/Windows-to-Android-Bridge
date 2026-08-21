package com.cayatur.winbridge.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.cayatur.winbridge.R

private val AutomationKey = ActionParameters.Key<String>("automationId")

/**
 * One automation, one button.
 *
 * The list widget answers "what can I run"; this answers "run the thing I always
 * run". Which automation it is gets chosen when the widget is placed and stored
 * against the id the launcher assigns, so several of these can sit side by side
 * doing different things — the home-screen equivalent of a keyboard shortcut.
 *
 * The colour follows the automation's own risk band, for the same reason the
 * watch tile does: a button that shuts the PC down should not look like one that
 * pauses music.
 */
class AutomationButtonWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrNull()
        val chosen = widgetId?.let { AutomationPicks.get(context, it) }

        val live = runCatching { com.cayatur.winbridge.WinBridgeApp.instance.state.automations.value }.getOrNull()
        val known = live?.items ?: AutomationCache.read(context)
        val automation = known.firstOrNull { it.id == chosen }

        provideContent {
            GlanceTheme {
                val accent = when (automation?.risk) {
                    "dangerous" -> Color(0xFF8A1F2A)
                    "shell" -> Color(0xFF7A5A14)
                    "elevated-input" -> Color(0xFF2A3E6B)
                    else -> null
                }

                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(
                            accent?.let { ColorProvider(it) } ?: GlanceTheme.colors.primaryContainer,
                        )
                        .cornerRadius(18.dp)
                        .padding(10.dp)
                        .let { base ->
                            // Unconfigured: tapping opens the app rather than
                            // running nothing, so the widget is never a dead
                            // square on the home screen.
                            if (automation == null) base
                            else base.clickable(
                                actionRunCallback<RunAutomationAction>(
                                    actionParametersOf(AutomationKey to automation.id),
                                ),
                            )
                        },
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                    horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                ) {
                    Text(
                        automation?.name ?: context.getString(R.string.widget_pick_automation),
                        maxLines = 2,
                        style = TextStyle(
                            color = if (accent != null) ColorProvider(Color.White)
                            else GlanceTheme.colors.onPrimaryContainer,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }
        }
    }
}

class AutomationButtonWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = AutomationButtonWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // Widget ids are reused, so a stale pick would otherwise turn up under a
        // completely unrelated widget later on.
        appWidgetIds.forEach { AutomationPicks.clear(context, it) }
        super.onDeleted(context, appWidgetIds)
    }
}

/** Where the configuration screen sends its answer. */
internal fun configResult(widgetId: Int): Intent =
    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
