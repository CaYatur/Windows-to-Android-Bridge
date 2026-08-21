package com.cayatur.winbridge.wear

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService

/**
 * Asks which automation a watch-face slot should run.
 *
 * Opened by the watch-face editor when the complication is placed, and again by
 * tapping a slot that has not been configured yet — the second route matters
 * because some faces hand the editor no way back once a data source is chosen.
 */
class ComplicationConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val instanceId = intent?.getIntExtra(EXTRA_INSTANCE_ID, -1)
            ?.takeIf { it != -1 }
            ?: intent?.getIntExtra(
                ComplicationDataSourceService.EXTRA_CONFIG_COMPLICATION_ID, -1,
            )?.takeIf { it != -1 }

        setResult(Activity.RESULT_CANCELED)
        if (instanceId == null) {
            finish()
            return
        }

        val items = WearExtras.readAutomations(this).items

        setContent {
            MaterialTheme {
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = rememberScalingLazyListState(),
                ) {
                    item { ListHeader { Text(getString(R.string.wear_complication_pick)) } }

                    if (items.isEmpty()) {
                        item { Text(getString(R.string.wear_no_automations)) }
                    } else {
                        items(items) { automation ->
                            Chip(
                                label = { Text(automation.name, maxLines = 2) },
                                onClick = {
                                    ComplicationPicks.set(this@ComplicationConfigActivity, instanceId, automation.id)
                                    ComplicationPicks.refresh(this@ComplicationConfigActivity)
                                    setResult(Activity.RESULT_OK)
                                    finish()
                                },
                                colors = ChipDefaults.secondaryChipColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_INSTANCE_ID = "instanceId"
    }
}
