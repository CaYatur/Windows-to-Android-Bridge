package com.cayatur.winbridge.wear

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.cayatur.winbridge.protocol.StateSnapshot
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream

private const val MEDIA_RES_PREV = "media_prev"
private const val MEDIA_RES_PLAY = "media_play"
private const val MEDIA_RES_PAUSE = "media_pause"
private const val MEDIA_RES_NEXT = "media_next"
private const val MEDIA_RES_ART = "media_art"

/** Dedicated media tile with cover art and direct transport controls. */
class MediaTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val snapshot = WearState.load(this)
        val params = requestParams.deviceConfiguration
        val resourceVersion = resourcesVersion(snapshot)

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())

        if (snapshot.artHash != null && WearArtwork.current(this) != null) {
            root.addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(MEDIA_RES_ART)
                    .setWidth(DimensionBuilders.expand())
                    .setHeight(DimensionBuilders.expand())
                    .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_CROP)
                    .build(),
            )
        }

        root.addContent(
            LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setBackground(
                            ModifiersBuilders.Background.Builder()
                                .setColor(argb(0xC914141A.toInt()))
                                .build(),
                        )
                        .build(),
                )
                .addContent(mediaContent(snapshot, params))
                .build(),
        )

        return Futures.immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(resourceVersion)
                .setFreshnessIntervalMillis(15_000)
                .setTileTimeline(
                    TimelineBuilders.Timeline.Builder()
                        .addTimelineEntry(
                            TimelineBuilders.TimelineEntry.Builder()
                                .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(root.build()).build())
                                .build(),
                        )
                        .build(),
                )
                .build(),
        )
    }

    private fun mediaContent(
        snapshot: StateSnapshot,
        params: DeviceParametersBuilders.DeviceParameters,
    ): LayoutElementBuilders.LayoutElement {
        val title = snapshot.title?.takeIf { it.isNotBlank() } ?: getString(R.string.wear_nothing_playing)
        val artist = snapshot.artist?.takeIf { it.isNotBlank() }

        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                Text.Builder(this, if (snapshot.playing) getString(R.string.wear_playing) else getString(R.string.wear_paused))
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setColor(argb(if (snapshot.playing) 0xFF67D47E.toInt() else Colors.DEFAULT.onSurface))
                    .build(),
            )
            .addContent(
                Text.Builder(this, title)
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setColor(argb(Colors.DEFAULT.onSurface))
                    .setMaxLines(2)
                    .build(),
            )

        if (artist != null) {
            column.addContent(
                Text.Builder(this, artist)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(argb(Colors.DEFAULT.onSurface))
                    .setMaxLines(1)
                    .build(),
            )
        }

        val controls = LayoutElementBuilders.Row.Builder()
            .setWidth(DimensionBuilders.wrap())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(mediaButton(MEDIA_RES_PREV, "media:prev", snapshot.canPrev, params))
            .addContent(mediaButton(if (snapshot.playing) MEDIA_RES_PAUSE else MEDIA_RES_PLAY, "media:toggle", true, params))
            .addContent(mediaButton(MEDIA_RES_NEXT, "media:next", snapshot.canNext, params))
            .build()

        column.addContent(controls)
        return column.build()
    }

    private fun mediaButton(
        iconId: String,
        command: String,
        enabled: Boolean,
        params: DeviceParametersBuilders.DeviceParameters,
    ): LayoutElementBuilders.LayoutElement {
        val clickable = ModifiersBuilders.Clickable.Builder()
            .setId(command)
            .setOnClick(
                ActionBuilders.launchAction(
                    ComponentName(this, TileCommandActivity::class.java),
                    mapOf(TileCommandActivity.EXTRA_COMMAND to ActionBuilders.stringExtra(command)),
                ),
            )
            .build()

        return Button.Builder(this, clickable)
            .setIconContent(iconId)
            .setSize(DimensionBuilders.dp(if (command == "media:toggle") 48f else 42f))
            .build()
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val snapshot = WearState.load(this)
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(resourcesVersion(snapshot))
            .addIdToImageMapping(MEDIA_RES_PREV, androidResource(R.drawable.ic_media_previous))
            .addIdToImageMapping(MEDIA_RES_PLAY, androidResource(R.drawable.ic_media_play))
            .addIdToImageMapping(MEDIA_RES_PAUSE, androidResource(R.drawable.ic_media_pause))
            .addIdToImageMapping(MEDIA_RES_NEXT, androidResource(R.drawable.ic_media_next))

        WearArtwork.current(this)?.let { bitmap ->
            resources.addIdToImageMapping(MEDIA_RES_ART, inlineResource(bitmap))
        }
        return Futures.immediateFuture(resources.build())
    }

    private fun androidResource(resId: Int): ResourceBuilders.ImageResource =
        ResourceBuilders.ImageResource.Builder()
            .setAndroidResourceByResId(
                ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(resId).build(),
            )
            .build()

    private fun inlineResource(bitmap: Bitmap): ResourceBuilders.ImageResource {
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray()
        }
        return ResourceBuilders.ImageResource.Builder()
            .setInlineResource(ResourceBuilders.InlineImageResource.Builder().setData(bytes).build())
            .build()
    }

    private fun resourcesVersion(snapshot: StateSnapshot): String = "media-${snapshot.artHash ?: "none"}"
}
