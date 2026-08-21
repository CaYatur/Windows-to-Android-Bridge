package com.cayatur.winbridge.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.protocol.AutomationSummary
import com.cayatur.winbridge.ui.WinBridgeTheme
import kotlinx.coroutines.launch

/**
 * Asks which automation a freshly placed button should run.
 *
 * Shown by the launcher between the drop and the widget appearing, which is the
 * only moment where asking is not an interruption. Only approved automations are
 * offered: an unapproved one would sit on the home screen looking like a button
 * and refuse every press.
 */
class AutomationWidgetConfigActivity : ComponentActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Cancelled by default: if the user backs out, the launcher must not be
        // left holding a widget nobody configured.
        setResult(RESULT_CANCELED, configResult(widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val live = runCatching { WinBridgeApp.instance.state.automations.value }.getOrNull()
        val items = (live?.items ?: AutomationCache.read(this))
            .filter { it.enabled && it.approved }

        setContent {
            WinBridgeTheme {
                Surface(Modifier.fillMaxSize()) {
                    Picker(items, onPick = ::choose)
                }
            }
        }
    }

    private fun choose(automation: AutomationSummary) {
        AutomationPicks.set(this, widgetId, automation.id)

        // The widget is repainted before the result goes back, so the launcher
        // never shows the "pick an automation" placeholder it was just told
        // about.
        (application as? WinBridgeApp)?.scope?.launch {
            runCatching {
                GlanceAppWidgetManager(this@AutomationWidgetConfigActivity)
                    .getGlanceIdBy(widgetId)
                    .let { AutomationButtonWidget().update(this@AutomationWidgetConfigActivity, it) }
            }.onFailure {
                runCatching { AutomationButtonWidget().updateAll(this@AutomationWidgetConfigActivity) }
            }
        }

        setResult(RESULT_OK, configResult(widgetId))
        finish()
    }
}

@Composable
private fun Picker(items: List<AutomationSummary>, onPick: (AutomationSummary) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            androidx.compose.ui.res.stringResource(R.string.widget_pick_automation),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        if (items.isEmpty()) {
            Text(
                androidx.compose.ui.res.stringResource(R.string.automations_empty),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
            items(items) { automation ->
                Card(
                    onClick = { onPick(automation) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(automation.name, fontWeight = FontWeight.Medium)
                        automation.description?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
