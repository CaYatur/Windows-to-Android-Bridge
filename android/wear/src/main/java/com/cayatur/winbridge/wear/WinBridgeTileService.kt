package com.cayatur.winbridge.wear

import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
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
 * The glanceable tile: what is playing and how the PC is doing, one swipe from
 * the watch face.
 *
 * Tiles are rendered by the system, often while our process is not running, so
 * everything here comes from the snapshot cached on disk rather than a live
 * connection. That also means it degrades honestly — it shows the last known
 * state and says so, instead of hanging.
 */
class WinBridgeTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val snapshot = WearState.load(this)
        val parameters = requestParams.deviceConfiguration

        val heading = if (snapshot.connected) {
            snapshot.hostName ?: getString(R.string.app_name)
        } else {
            getString(R.string.wear_offline)
        }

        val primary = when {
            !snapshot.connected -> getString(R.string.wear_offline)
            !snapshot.title.isNullOrBlank() -> snapshot.title!!
            else -> getString(R.string.wear_nothing_playing)
        }

        val secondary = buildString {
            append("CPU ${snapshot.cpu}%  ·  GPU ${snapshot.gpu}%")
            if (snapshot.ramTotalMb > 0) {
                append("  ·  RAM ${"%.1f".format(snapshot.ramUsedMb / 1024.0)}G")
            }
            if (snapshot.batteryPresent) {
                append("  ·  ${snapshot.batteryPct}%")
                if (snapshot.batteryCharging) append("⚡")
            }
        }

        val layout = PrimaryLayout.Builder(parameters)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(
                Text.Builder(this, heading)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(argb(if (snapshot.connected) 0xFF3FB950.toInt() else 0xFFE5484D.toInt()))
                    .build(),
            )
            .setContent(
                Text.Builder(this, primary)
                    .setTypography(Typography.TYPOGRAPHY_BODY1)
                    .setColor(argb(Colors.DEFAULT.onSurface))
                    .setMaxLines(2)
                    .build(),
            )
            .setSecondaryLabelTextContent(
                Text.Builder(this, secondary)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setColor(argb(Colors.DEFAULT.onSurface))
                    .build(),
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            // Refreshed on data change too; this is the ceiling, not the rate.
            .setFreshnessIntervalMillis(60_000)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(layout)
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build(),
        )
}
