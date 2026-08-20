package com.cayatur.winbridge.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The automations the watch is allowed to show.
 *
 * Deliberately not the full [Automation] model. A watch never edits one, and
 * pushing step trees across the Data Layer would spend battery syncing shell
 * commands to a device that has no way to read them. Name, id and risk is
 * everything the watch renders.
 */
@Serializable
data class WearAutomation(
    val id: String = "",
    val name: String = "",
    val risk: String = "safe",
    val confirm: Boolean = false,
)

@Serializable
data class WearAutomations(
    val items: List<WearAutomation> = emptyList(),
    val shellEnabled: Boolean = false,
    val updatedAt: Long = 0,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String?): WearAutomations =
            if (text.isNullOrBlank()) WearAutomations()
            else runCatching { json.decodeFromString<WearAutomations>(text) }.getOrDefault(WearAutomations())

        fun encode(value: WearAutomations): String = json.encodeToString(serializer(), value)
    }
}

/**
 * The command vocabulary the watch speaks to the phone.
 *
 * Strings rather than a sealed hierarchy because the Data Layer carries bytes
 * and both sides are separately installable: a watch app one version ahead
 * sending a verb the phone does not know should be ignored, not crash a
 * deserializer.
 */
object WearCommands {
    const val MEDIA = "media"
    const val VOLUME = "volume"
    const val POWER = "power"

    /** Run an automation by id. The phone applies its own gating; the PC applies more. */
    const val AUTOMATION = "auto"

    /** Free text from the watch recogniser, parsed on the phone. */
    const val VOICE = "voice"

    /** Relative pointer movement and clicks: "mouse:move:dx,dy", "mouse:click:left". */
    const val MOUSE = "mouse"

    /** A named key, optionally with modifiers: "key:f5", "key:tab+alt". */
    const val KEY = "key"

    /** Ask the phone to push its clipboard to the PC. */
    const val CLIPBOARD = "clip"

    /** Ask the PC what is on screen and have the answer read out. */
    const val DESCRIBE = "describe"

    /** Refresh whatever the watch is showing. */
    const val SYNC = "sync"
}
