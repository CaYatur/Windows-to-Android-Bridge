package com.cayatur.winbridge.wear

import android.content.ComponentName
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Chip
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

private const val RESOURCES_VERSION = "1"

/**
 * Three automations, one swipe from the watch face.
 *
 * Only approved ones ever reach the watch, so a button here always corresponds
 * to something the PC has already agreed to run. The tile shows nothing rather
 * than greyed-out buttons when the list is empty: a tile of dead controls is
 * worse than a line of text saying there is nothing yet.
 */
class AutomationTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val automations = WearExtras.readAutomations(this)
        val parameters = requestParams.deviceConfiguration
        val items = automations.items.take(3)

        val layout = PrimaryLayout.Builder(parameters)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(
                Text.Builder(this, getString(R.string.wear_automations))
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(argb(Colors.DEFAULT.primary))
                    .build(),
            )
            .setContent(
                if (items.isEmpty()) {
                    Text.Builder(this, getString(R.string.wear_no_automations))
                        .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                        .setColor(argb(Colors.DEFAULT.onSurface))
                        .setMaxLines(3)
                        .build()
                } else {
                    val column = LayoutElementBuilders.Column.Builder()
                        .setWidth(DimensionBuilders.expand())
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

                    items.forEach { automation ->
                        column.addContent(chipFor(automation.id, automation.name, automation.risk, parameters))
                    }
                    column.build()
                },
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            // The list only moves when the user edits something on the phone, so
            // there is nothing to gain from polling it.
            .setFreshnessIntervalMillis(0)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder().setRoot(layout).build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

        return Futures.immediateFuture(tile)
    }

    private fun chipFor(
        id: String,
        label: String,
        risk: String,
        parameters: androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters,
    ): LayoutElementBuilders.LayoutElement {
        val clickable = ModifiersBuilders.Clickable.Builder()
            .setId("auto:$id")
            .setOnClick(
                ActionBuilders.launchAction(
                    ComponentName(this, TileCommandActivity::class.java),
                    mapOf(TileCommandActivity.EXTRA_COMMAND to ActionBuilders.stringExtra("auto:$id")),
                ),
            )
            .build()

        val accent = when (risk) {
            "dangerous" -> 0xFF8A1F2A.toInt()
            "shell" -> 0xFF7A5A14.toInt()
            "elevated-input" -> 0xFF2A3E6B.toInt()
            else -> Colors.DEFAULT.surface
        }

        return Chip.Builder(this, clickable, parameters)
            .setChipColors(ChipColors(accent, Colors.DEFAULT.onSurface))
            .setPrimaryLabelContent(label)
            .setWidth(DimensionBuilders.expand())
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build(),
        )
}
