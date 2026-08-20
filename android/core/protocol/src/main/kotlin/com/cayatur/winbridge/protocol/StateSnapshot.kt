package com.cayatur.winbridge.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A flattened view of everything a glanceable surface needs.
 *
 * Home screen widgets, the tile on a watch and the watch app all render from
 * this. It lives here rather than in the phone app because the watch module
 * has to deserialize exactly the same bytes off the Wearable Data Layer, and
 * two hand-kept copies of a wire type is how they drift.
 */
@Serializable
data class StateSnapshot(
    val connected: Boolean = false,
    val hostName: String? = null,
    val carrier: String? = null,

    val title: String? = null,
    val artist: String? = null,
    val playing: Boolean = false,
    val artHash: String? = null,
    val posMs: Long = 0,
    val durMs: Long = 0,
    val canNext: Boolean = false,
    val canPrev: Boolean = false,

    val cpu: Int = 0,
    val gpu: Int = 0,
    val ramUsedMb: Long = 0,
    val ramTotalMb: Long = 0,
    val netDownBps: Long = 0,
    val netUpBps: Long = 0,

    val batteryPresent: Boolean = false,
    val batteryPct: Int = 0,
    val batteryCharging: Boolean = false,
    val batteryStatus: String = "unknown",

    val volume: Int = 0,
    val muted: Boolean = false,

    val updatedAt: Long = 0,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String?): StateSnapshot =
            if (text.isNullOrBlank()) StateSnapshot()
            else runCatching { json.decodeFromString<StateSnapshot>(text) }.getOrDefault(StateSnapshot())

        fun encode(snapshot: StateSnapshot): String =
            json.encodeToString(serializer(), snapshot)
    }
}

/** Paths shared by the phone and the watch over the Wearable Data Layer. */
object WearPaths {
    const val STATE = "/winbridge/state"
    const val COMMAND = "/winbridge/cmd"
    const val STATE_KEY = "snapshot"

    /**
     * The automation list, on its own data item rather than folded into the
     * state snapshot: the snapshot changes every second while metrics tick, and
     * re-syncing an unchanged automation list at that rate would be battery
     * spent on nothing.
     */
    const val AUTOMATIONS = "/winbridge/automations"
    const val AUTOMATIONS_KEY = "automations"

    /** Replies the watch shows after a command: text the phone sends back. */
    const val ANSWER = "/winbridge/answer"
    const val ANSWER_KEY = "answer"
}
