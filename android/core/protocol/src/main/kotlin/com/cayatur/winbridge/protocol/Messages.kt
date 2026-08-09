package com.cayatur.winbridge.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

object MessageTypes {
    const val STATE_HOST = "state.host"
    const val STATE_MEDIA = "state.media"
    const val STATE_SYSTEM = "state.system"
    const val STATE_VOLUME = "state.volume"
    const val EVENT_PEER = "evt.peer"
    const val ERROR = "error"
    const val PONG = "pong"
    const val PAIR_COMPLETE = "pair.complete"

    const val AUTH = "auth"
    const val SUBSCRIBE = "sub"
    const val REQUEST_STATE = "req.state"
    const val REQUEST_BLOB = "req.blob"
    const val COMMAND_MEDIA = "cmd.media"
    const val COMMAND_VOLUME = "cmd.volume"
    const val COMMAND_POWER = "cmd.power"
    const val PING = "ping"
}

val ProtocolJson = Json {
    ignoreUnknownKeys = true      // forward compatibility with a newer host
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class Hello(
    @SerialName("v") val version: Int = 1,
    val deviceId: String,
    val name: String,
    val platform: String = "android",
    val mode: String = "session",
    val ephPub: String,
    val nonce: String,
)

@Serializable
data class HelloAck(
    @SerialName("v") val version: Int = 1,
    val deviceId: String = "",
    val name: String = "",
    val platform: String = "windows",
    val ephPub: String = "",
    val nonce: String = "",
    val confirm: String = "",
)

@Serializable
data class PowerCaps(
    val lock: Boolean = true,
    val sleep: Boolean = false,
    val hibernate: Boolean = false,
    val shutdown: Boolean = true,
    val restart: Boolean = true,
    val logoff: Boolean = true,
    @SerialName("display_off") val displayOff: Boolean = true,
)

@Serializable
data class HostState(
    @SerialName("t") val type: String = MessageTypes.STATE_HOST,
    val name: String = "",
    val os: String = "",
    val uptimeSec: Long = 0,
    val caps: PowerCaps = PowerCaps(),
)

@Serializable
data class MediaState(
    @SerialName("t") val type: String = MessageTypes.STATE_MEDIA,
    val session: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val appId: String? = null,
    val playing: Boolean = false,
    val posMs: Long = 0,
    val durMs: Long = 0,
    val canNext: Boolean = false,
    val canPrev: Boolean = false,
    val canSeek: Boolean = false,
    val artHash: String? = null,
)

@Serializable
data class RamInfo(val usedMb: Long = 0, val totalMb: Long = 0)

@Serializable
data class GpuInfo(val name: String = "", val pct: Double = 0.0)

@Serializable
data class NetInfo(val upBps: Long = 0, val downBps: Long = 0)

@Serializable
data class DiskInfo(val name: String = "", val usedGb: Double = 0.0, val totalGb: Double = 0.0)

@Serializable
data class BatteryInfo(
    val present: Boolean = false,
    val pct: Int = 0,
    val charging: Boolean = false,
    val status: String = "unknown",
    val minutesLeft: Int = -1,
)

@Serializable
data class SystemState(
    @SerialName("t") val type: String = MessageTypes.STATE_SYSTEM,
    val cpu: Double = 0.0,
    val ram: RamInfo = RamInfo(),
    val gpu: List<GpuInfo> = emptyList(),
    val net: NetInfo = NetInfo(),
    val disk: List<DiskInfo> = emptyList(),
    val battery: BatteryInfo = BatteryInfo(),
)

@Serializable
data class VolumeState(
    @SerialName("t") val type: String = MessageTypes.STATE_VOLUME,
    val level: Int = 0,
    val muted: Boolean = false,
)

@Serializable
data class BtPeer(val mac: String = "", val uuid: String = "")

@Serializable
data class LanPeer(val hosts: List<String> = emptyList(), val port: Int = 0)

@Serializable
data class PeerEvent(
    @SerialName("t") val type: String = MessageTypes.EVENT_PEER,
    val bt: BtPeer? = null,
    val lan: LanPeer? = null,
)

@Serializable
data class ErrorMessage(
    @SerialName("t") val type: String = MessageTypes.ERROR,
    val code: String = "",
    val detail: String? = null,
)

@Serializable
data class PairComplete(
    @SerialName("t") val type: String = MessageTypes.PAIR_COMPLETE,
    val psk: String = "",
)

@Serializable
data class AuthMessage(
    @SerialName("t") val type: String = MessageTypes.AUTH,
    val confirm: String,
)

@Serializable
data class SubscribeMessage(
    @SerialName("t") val type: String = MessageTypes.SUBSCRIBE,
    val rates: Map<String, Int>,
)

@Serializable
data class RequestState(@SerialName("t") val type: String = MessageTypes.REQUEST_STATE)

@Serializable
data class BlobRequest(
    @SerialName("t") val type: String = MessageTypes.REQUEST_BLOB,
    val id: String,
)

@Serializable
data class MediaCommand(
    @SerialName("t") val type: String = MessageTypes.COMMAND_MEDIA,
    val action: String,
    val posMs: Long = 0,
)

@Serializable
data class VolumeCommand(
    @SerialName("t") val type: String = MessageTypes.COMMAND_VOLUME,
    val action: String,
    val level: Int = 0,
)

@Serializable
data class PowerCommand(
    @SerialName("t") val type: String = MessageTypes.COMMAND_POWER,
    val action: String,
    val delaySec: Int = 0,
)

@Serializable
data class PingMessage(
    @SerialName("t") val type: String = MessageTypes.PING,
    val echo: Long,
)

@Serializable
data class PongMessage(
    @SerialName("t") val type: String = MessageTypes.PONG,
    val echo: Long = 0,
)

/** Reads just the discriminator so the receive loop can route without guessing. */
fun readMessageType(utf8: ByteArray): String {
    val element = ProtocolJson.parseToJsonElement(utf8.decodeToString())
    return (element as? JsonObject)?.get("t")?.jsonPrimitive?.content
        ?: throw ProtocolException("message has no \"t\" field")
}
