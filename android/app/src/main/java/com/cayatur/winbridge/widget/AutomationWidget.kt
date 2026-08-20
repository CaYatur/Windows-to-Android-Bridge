package com.cayatur.winbridge.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.protocol.AutoRunRequest

private val AutomationKey = ActionParameters.Key<String>("automationId")

/**
 * Runs an automation from the home screen.
 *
 * Only approved automations appear. An unapproved one would sit on the launcher
 * looking like a button and do nothing when pressed, which is a worse experience
 * than not being offered.
 */
class RunAutomationAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[AutomationKey] ?: return
        WinBridgeApp.instance.client.sendMessage(AutoRunRequest(id = id))
    }
}

class AutomationWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read through the running app rather than a persisted snapshot: unlike
        // the metrics widgets, this list only matters while there is a link, and
        // a stale button that fires the wrong automation would be worse than an
        // empty card.
        val catalog = WinBridgeApp.instance.state.automations.value
        val items = catalog?.items.orEmpty().filter { it.enabled && it.approved }.take(4)

        provideContent {
            GlanceTheme {
                Column(
                    GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(18.dp)
                        .padding(12.dp),
                ) {
                    Text(
                        context.getString(R.string.automations_title),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    Spacer(GlanceModifier.height(6.dp))

                    if (items.isEmpty()) {
                        Text(
                            context.getString(R.string.automations_empty),
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                        )
                    } else {
                        items.forEach { item ->
                            AutomationButton(item.name, item.id)
                            Spacer(GlanceModifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomationButton(label: String, id: String) {
    Text(
        label,
        maxLines = 1,
        style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp),
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.primaryContainer)
            .cornerRadius(12.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .clickable(actionRunCallback<RunAutomationAction>(actionParametersOf(AutomationKey to id))),
    )
}

class AutomationWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = AutomationWidget()
}
