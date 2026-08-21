package com.cayatur.winbridge.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Message names added in protocol v2. Kept beside the v1 names rather than
 * renumbering anything: an older host talking to a newer phone simply never
 * sends or understands these, and both sides ignore unknown "t" values.
 */
object MessageTypesV2 {
    // capability exchange
    const val HOST_FEATURES = "evt.features"
    const val CLIENT_FEATURES = "cl.features"

    // clipboard
    const val CLIPBOARD_SET = "cb.set"
    const val CLIPBOARD_GET = "cb.get"

    // file transfer
    const val XFER_OFFER = "xfer.offer"
    const val XFER_ACCEPT = "xfer.accept"
    const val XFER_REJECT = "xfer.reject"
    const val XFER_PROGRESS = "xfer.progress"
    const val XFER_DONE = "xfer.done"
    const val XFER_CANCEL = "xfer.cancel"

    // screen mirroring
    const val SCREEN_LIST = "screen.list"
    const val SCREEN_TARGETS = "screen.targets"
    const val STREAM_START = "stream.start"
    const val STREAM_STOP = "stream.stop"
    const val STREAM_INFO = "stream.info"
    const val STREAM_CONFIG = "stream.config"
    const val STREAM_STATS = "stream.stats"

    // audio
    const val AUDIO_START = "audio.start"
    const val AUDIO_STOP = "audio.stop"
    const val AUDIO_INFO = "audio.info"
    const val AUDIO_DEVICES = "audio.devices"
    const val AUDIO_ROUTE = "audio.route"

    // remote input
    const val INPUT_MOUSE = "input.mouse"
    const val INPUT_KEY = "input.key"
    const val INPUT_TEXT = "input.text"
    const val INPUT_TOUCH = "input.touch"
    const val INPUT_GESTURE = "input.gesture"
    const val INPUT_NAV = "input.nav"
    const val INPUT_SCROLL = "input.scroll"

    // automations
    const val AUTO_LIST = "auto.list"
    const val AUTO_CATALOG = "auto.catalog"
    const val AUTO_GET = "auto.get"
    const val AUTO_DEF = "auto.def"
    const val AUTO_SAVE = "auto.save"
    const val AUTO_SAVED = "auto.saved"
    const val AUTO_DELETE = "auto.delete"
    const val AUTO_RUN = "auto.run"
    const val AUTO_EVENT = "auto.event"
    const val AUTO_RESULT = "auto.result"
    const val AUTO_CANCEL = "auto.cancel"
    const val AUTO_LOG = "auto.log"

    // notification mirroring
    const val NOTIF_POST = "notif.post"
    const val NOTIF_REMOVE = "notif.remove"
    const val NOTIF_ACTION = "notif.action"
    const val NOTIF_DISMISS = "notif.dismiss"
    const val NOTIF_SYNC = "notif.sync"
    const val NOTIF_STATE = "notif.state"

    // machine introspection and small conveniences
    const val SYS_WINDOWS = "sys.windows"
    const val SYS_WINDOW_LIST = "sys.windowlist"
    const val SYS_WINDOW = "sys.window"
    const val SYS_PROCESSES = "sys.processes"
    const val SYS_PROCESS_LIST = "sys.processlist"
    const val SYS_PROCESS = "sys.process"
    const val SYS_DESCRIBE = "sys.describe"
    const val SYS_DESCRIPTION = "sys.description"
    const val SYS_NOTIFY = "sys.notify"
    const val SYS_OPEN = "sys.open"
    const val PHONE_RING = "phone.ring"
    const val PHONE_STATE = "phone.state"
}

// ---------------------------------------------------------------------------
// Capability exchange
// ---------------------------------------------------------------------------

@Serializable
data class ClipboardCaps(
    val send: Boolean = false,
    val receive: Boolean = false,
    val maxBytes: Int = 256 * 1024,
)

@Serializable
data class FileCaps(
    val enabled: Boolean = false,
    val maxChunk: Int = 64 * 1024,
    val autoAccept: Boolean = false,
)

@Serializable
data class ScreenCaps(
    val send: Boolean = false,
    val receive: Boolean = false,
    val codecs: List<String> = listOf("jpeg-tiles"),
    val targets: Int = 0,
    /** False over a carrier that cannot carry it — RFCOMM, in practice. */
    val carrierOk: Boolean = true,
)

@Serializable
data class AudioCaps(
    val playback: Boolean = false,
    val mic: Boolean = false,
    val formats: List<String> = listOf("pcm_s16le"),
    val carrierOk: Boolean = true,
)

@Serializable
data class InputCaps(
    val send: Boolean = false,
    val receive: Boolean = false,
    val reason: String? = null,
)

/**
 * What this side is willing and able to do right now. Sent after auth by both
 * ends and re-sent whenever a setting changes, so the peer greys out what is off
 * rather than offering a button that silently does nothing.
 */
@Serializable
data class FeatureSet(
    @SerialName("t") val type: String = MessageTypesV2.CLIENT_FEATURES,
    @SerialName("v") val version: Int = 2,
    val clipboard: ClipboardCaps = ClipboardCaps(),
    val files: FileCaps = FileCaps(),
    val screen: ScreenCaps = ScreenCaps(),
    val audio: AudioCaps = AudioCaps(),
    val input: InputCaps = InputCaps(),
    val automations: Boolean = false,
    val shell: Boolean = false,
    val notifications: Boolean = false,
    val describe: Boolean = false,
    val ring: Boolean = false,
)

// ---------------------------------------------------------------------------
// Clipboard
// ---------------------------------------------------------------------------

@Serializable
data class ClipboardMessage(
    @SerialName("t") val type: String = MessageTypesV2.CLIPBOARD_SET,
    @SerialName("fmt") val format: String = "text",
    val text: String? = null,
    val mime: String? = null,
    val bytes: String? = null,
    val hash: String? = null,
    val label: String? = null,
)

@Serializable
data class ClipboardRequest(
    @SerialName("t") val type: String = MessageTypesV2.CLIPBOARD_GET,
)

// ---------------------------------------------------------------------------
// File transfer
// ---------------------------------------------------------------------------

@Serializable
data class XferOffer(
    @SerialName("t") val type: String = MessageTypesV2.XFER_OFFER,
    val id: Int = 0,
    val name: String = "",
    val size: Long = 0,
    val mime: String? = null,
    val sha256: String? = null,
    val batch: Int = 0,
    val batchIndex: Int = 0,
    val batchCount: Int = 1,
    val path: String? = null,
)

@Serializable
data class XferAccept(
    @SerialName("t") val type: String = MessageTypesV2.XFER_ACCEPT,
    val id: Int = 0,
    val offset: Long = 0,
)

@Serializable
data class XferReject(
    @SerialName("t") val type: String = MessageTypesV2.XFER_REJECT,
    val id: Int = 0,
    val reason: String = "",
)

@Serializable
data class XferProgress(
    @SerialName("t") val type: String = MessageTypesV2.XFER_PROGRESS,
    val id: Int = 0,
    val bytes: Long = 0,
    val bps: Long = 0,
)

@Serializable
data class XferDone(
    @SerialName("t") val type: String = MessageTypesV2.XFER_DONE,
    val id: Int = 0,
    val ok: Boolean = false,
    val sha256: String? = null,
    val error: String? = null,
    val savedAs: String? = null,
)

@Serializable
data class XferCancel(
    @SerialName("t") val type: String = MessageTypesV2.XFER_CANCEL,
    val id: Int = 0,
    val reason: String? = null,
)

// ---------------------------------------------------------------------------
// Screen mirroring
// ---------------------------------------------------------------------------

@Serializable
data class ScreenTarget(
    val id: String = "",
    val name: String = "",
    val kind: String = "monitor",
    @SerialName("w") val width: Int = 0,
    @SerialName("h") val height: Int = 0,
    val primary: Boolean = false,
)

@Serializable
data class ScreenTargets(
    @SerialName("t") val type: String = MessageTypesV2.SCREEN_TARGETS,
    val items: List<ScreenTarget> = emptyList(),
)

@Serializable
data class ScreenListRequest(
    @SerialName("t") val type: String = MessageTypesV2.SCREEN_LIST,
)

@Serializable
data class StreamStart(
    @SerialName("t") val type: String = MessageTypesV2.STREAM_START,
    val stream: String = "",
    val codec: String = "jpeg-tiles",
    val maxFps: Int = 30,
    val quality: Int = 70,
    val maxEdge: Int = 1280,
    val target: String? = null,
    val audio: Boolean = false,
    val interact: Boolean = false,
    val cursor: Boolean = true,
)

@Serializable
data class StreamStop(
    @SerialName("t") val type: String = MessageTypesV2.STREAM_STOP,
    val stream: String = "",
)

@Serializable
data class StreamInfo(
    @SerialName("t") val type: String = MessageTypesV2.STREAM_INFO,
    val stream: String = "",
    val active: Boolean = false,
    @SerialName("w") val width: Int = 0,
    @SerialName("h") val height: Int = 0,
    @SerialName("tileW") val tileWidth: Int = 0,
    @SerialName("tileH") val tileHeight: Int = 0,
    @SerialName("cols") val columns: Int = 0,
    @SerialName("rows") val rows: Int = 0,
    val codec: String = "jpeg-tiles",
    val target: String? = null,
    val interact: Boolean = false,
    val reason: String? = null,
)

@Serializable
data class StreamConfig(
    @SerialName("t") val type: String = MessageTypesV2.STREAM_CONFIG,
    val stream: String = "",
    val maxFps: Int? = null,
    val quality: Int? = null,
    val maxEdge: Int? = null,
    val target: String? = null,
    val interact: Boolean? = null,
    val cursor: Boolean? = null,
)

@Serializable
data class StreamStats(
    @SerialName("t") val type: String = MessageTypesV2.STREAM_STATS,
    val stream: String = "",
    val fps: Double = 0.0,
    val kbps: Double = 0.0,
    val rttMs: Int = 0,
    val decodeMs: Double = 0.0,
    val dropped: Long = 0,
    val latencyMs: Int = 0,
)

// ---------------------------------------------------------------------------
// Audio
// ---------------------------------------------------------------------------

@Serializable
data class AudioStart(
    @SerialName("t") val type: String = MessageTypesV2.AUDIO_START,
    val stream: String = "",
    val rate: Int = 48000,
    val channels: Int = 2,
    val format: String = "pcm_s16le",
    val frameMs: Int = 20,
    val device: String? = null,
)

@Serializable
data class AudioStop(
    @SerialName("t") val type: String = MessageTypesV2.AUDIO_STOP,
    val stream: String = "",
)

@Serializable
data class AudioInfo(
    @SerialName("t") val type: String = MessageTypesV2.AUDIO_INFO,
    val stream: String = "",
    val active: Boolean = false,
    val rate: Int = 0,
    val channels: Int = 0,
    val format: String = "pcm_s16le",
    val frameMs: Int = 0,
    val device: String? = null,
    val reason: String? = null,
)

@Serializable
data class AudioDevice(
    val id: String = "",
    val name: String = "",
    val flow: String = "render",
    @SerialName("default") val isDefault: Boolean = false,
)

@Serializable
data class AudioDevices(
    @SerialName("t") val type: String = MessageTypesV2.AUDIO_DEVICES,
    val items: List<AudioDevice> = emptyList(),
)

@Serializable
data class AudioRoute(
    @SerialName("t") val type: String = MessageTypesV2.AUDIO_ROUTE,
    val stream: String = "",
    val device: String? = null,
)

// ---------------------------------------------------------------------------
// Remote input
// ---------------------------------------------------------------------------

/**
 * Coordinates are normalised 0..1 against the streamed surface, never pixels:
 * the sender is looking at a scaled copy and must not have to know the receiver
 * resolution, DPI, or which monitor the pointer landed on.
 */
@Serializable
data class InputMouse(
    @SerialName("t") val type: String = MessageTypesV2.INPUT_MOUSE,
    val action: String = "move",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val button: String = "left",
    val delta: Int = 0,
    @SerialName("hdelta") val horizontalDelta: Int = 0,
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val relative: Boolean = false,
)

@Serializable
data class InputKey(
    @SerialName("t") val type: String = MessageTypesV2.INPUT_KEY,
    val action: String = "tap",
    val code: String = "",
    val mods: List<String> = emptyList(),
    val repeat: Int = 1,
)

@Serializable
data class InputText(
    @SerialName("t") val type: String = MessageTypesV2.INPUT_TEXT,
    val text: String = "",
)

@Serializable
data class InputTouch(
    @SerialName("t") val type: String = MessageTypesV2.INPUT_TOUCH,
    val action: String = "down",
    val pointer: Int = 0,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val pressure: Double = 1.0,
)

@Serializable
data class GesturePoint(
    val x: Double = 0.0,
    val y: Double = 0.0,
    @SerialName("t") val atMs: Int = 0,
)

@Serializable
data class InputGesture(
    @SerialName("t") val type: String = MessageTypesV2.INPUT_GESTURE,
    val kind: String = "tap",
    val points: List<GesturePoint> = emptyList(),
    val durationMs: Int = 60,
    val points2: List<GesturePoint> = emptyList(),
)

@Serializable
data class InputNav(
    @SerialName("t") val type: String = MessageTypesV2.INPUT_NAV,
    val action: String = "",
)

@Serializable
data class InputScroll(
    @SerialName("t") val type: String = MessageTypesV2.INPUT_SCROLL,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val dx: Double = 0.0,
    val dy: Double = 0.0,
)

// ---------------------------------------------------------------------------
// Notification mirroring
// ---------------------------------------------------------------------------

@Serializable
data class NotifAction(
    val index: Int = 0,
    val title: String = "",
    @SerialName("reply") val isReply: Boolean = false,
)

@Serializable
data class NotifPost(
    @SerialName("t") val type: String = MessageTypesV2.NOTIF_POST,
    val key: String = "",
    @SerialName("pkg") val packageName: String = "",
    @SerialName("app") val appName: String = "",
    val title: String? = null,
    val text: String? = null,
    @SerialName("sub") val subText: String? = null,
    @SerialName("big") val bigText: String? = null,
    val `when`: Long = 0,
    val ongoing: Boolean = false,
    val category: String? = null,
    val actions: List<NotifAction> = emptyList(),
    val iconHash: String? = null,
)

@Serializable
data class NotifRemove(
    @SerialName("t") val type: String = MessageTypesV2.NOTIF_REMOVE,
    val key: String = "",
)

@Serializable
data class NotifActionCommand(
    @SerialName("t") val type: String = MessageTypesV2.NOTIF_ACTION,
    val key: String = "",
    val index: Int = 0,
    val text: String? = null,
)

@Serializable
data class NotifDismiss(
    @SerialName("t") val type: String = MessageTypesV2.NOTIF_DISMISS,
    val key: String = "",
)

@Serializable
data class NotifSync(
    @SerialName("t") val type: String = MessageTypesV2.NOTIF_SYNC,
)

@Serializable
data class NotifState(
    @SerialName("t") val type: String = MessageTypesV2.NOTIF_STATE,
    val enabled: Boolean = false,
    val granted: Boolean = false,
    val count: Int = 0,
    val reason: String? = null,
)

// ---------------------------------------------------------------------------
// Machine introspection and conveniences
// ---------------------------------------------------------------------------

@Serializable
data class WindowInfo(
    val handle: Long = 0,
    val title: String = "",
    val process: String = "",
    val pid: Int = 0,
    val active: Boolean = false,
    val minimized: Boolean = false,
)

@Serializable
data class WindowList(
    @SerialName("t") val type: String = MessageTypesV2.SYS_WINDOW_LIST,
    val items: List<WindowInfo> = emptyList(),
)

@Serializable
data class WindowsRequest(
    @SerialName("t") val type: String = MessageTypesV2.SYS_WINDOWS,
)

@Serializable
data class WindowCommand(
    @SerialName("t") val type: String = MessageTypesV2.SYS_WINDOW,
    val action: String = "focus",
    val handle: Long = 0,
    val match: String? = null,
)

@Serializable
data class ProcessInfo(
    val pid: Int = 0,
    val name: String = "",
    val memMb: Long = 0,
    val cpu: Double = 0.0,
)

@Serializable
data class ProcessList(
    @SerialName("t") val type: String = MessageTypesV2.SYS_PROCESS_LIST,
    val items: List<ProcessInfo> = emptyList(),
)

@Serializable
data class ProcessesRequest(
    @SerialName("t") val type: String = MessageTypesV2.SYS_PROCESSES,
    val top: Int = 25,
)

@Serializable
data class ProcessCommand(
    @SerialName("t") val type: String = MessageTypesV2.SYS_PROCESS,
    val action: String = "kill",
    val pid: Int = 0,
)

@Serializable
data class DescribeRequest(
    @SerialName("t") val type: String = MessageTypesV2.SYS_DESCRIBE,
    val target: String? = null,
    val ocr: Boolean = true,
    val image: Boolean = false,
)

@Serializable
data class Description(
    @SerialName("t") val type: String = MessageTypesV2.SYS_DESCRIPTION,
    val title: String? = null,
    val process: String? = null,
    val text: String? = null,
    val windows: List<String> = emptyList(),
    @SerialName("w") val width: Int = 0,
    @SerialName("h") val height: Int = 0,
    val imageHash: String? = null,
    val reason: String? = null,
)

@Serializable
data class SysNotify(
    @SerialName("t") val type: String = MessageTypesV2.SYS_NOTIFY,
    val title: String = "",
    val text: String? = null,
    val level: String = "info",
)

@Serializable
data class SysOpen(
    @SerialName("t") val type: String = MessageTypesV2.SYS_OPEN,
    val target: String = "",
)

@Serializable
data class PhoneRing(
    @SerialName("t") val type: String = MessageTypesV2.PHONE_RING,
    val action: String = "start",
    val seconds: Int = 30,
)

@Serializable
data class PhoneState(
    @SerialName("t") val type: String = MessageTypesV2.PHONE_STATE,
    val model: String? = null,
    val battery: Int = 0,
    val charging: Boolean = false,
    val ringer: String? = null,
    val network: String? = null,
    val volume: Int = 0,
    val screenOn: Boolean = false,
)
