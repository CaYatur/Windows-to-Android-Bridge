package com.cayatur.winbridge.net

import android.util.Log
import com.cayatur.winbridge.data.SecureStore
import com.cayatur.winbridge.protocol.AudioDevices
import com.cayatur.winbridge.protocol.AudioInfo
import com.cayatur.winbridge.protocol.AudioStart
import com.cayatur.winbridge.protocol.AudioStop
import com.cayatur.winbridge.protocol.AutoCatalog
import com.cayatur.winbridge.protocol.AutoDefinition
import com.cayatur.winbridge.protocol.AutoEvent
import com.cayatur.winbridge.protocol.AutoLog
import com.cayatur.winbridge.protocol.AutoResult
import com.cayatur.winbridge.protocol.AutoSaved
import com.cayatur.winbridge.protocol.BlobRequest
import com.cayatur.winbridge.protocol.ClipboardMessage
import com.cayatur.winbridge.protocol.Description
import com.cayatur.winbridge.protocol.ErrorMessage
import com.cayatur.winbridge.protocol.FeatureSet
import com.cayatur.winbridge.protocol.MediaKind
import com.cayatur.winbridge.protocol.MediaPacket
import com.cayatur.winbridge.protocol.MessageTypesV2
import com.cayatur.winbridge.protocol.NotifActionCommand
import com.cayatur.winbridge.protocol.NotifDismiss
import com.cayatur.winbridge.protocol.PhoneRing
import com.cayatur.winbridge.protocol.ProcessList
import com.cayatur.winbridge.protocol.ScreenTargets
import com.cayatur.winbridge.protocol.StreamInfo
import com.cayatur.winbridge.protocol.StreamStart
import com.cayatur.winbridge.protocol.StreamStop
import com.cayatur.winbridge.protocol.SysNotify
import com.cayatur.winbridge.protocol.SysOpen
import com.cayatur.winbridge.protocol.WindowList
import com.cayatur.winbridge.protocol.XferAccept
import com.cayatur.winbridge.protocol.XferChunk
import com.cayatur.winbridge.protocol.XferDone
import com.cayatur.winbridge.protocol.XferOffer
import com.cayatur.winbridge.protocol.XferReject
import com.cayatur.winbridge.protocol.HostState
import com.cayatur.winbridge.protocol.InnerType
import com.cayatur.winbridge.protocol.StreamIds
import com.cayatur.winbridge.protocol.LocalIdentity
import com.cayatur.winbridge.protocol.MediaCommand
import com.cayatur.winbridge.protocol.MediaState
import com.cayatur.winbridge.protocol.MessageTypes
import com.cayatur.winbridge.protocol.PeerEvent
import com.cayatur.winbridge.protocol.PingMessage
import com.cayatur.winbridge.protocol.PowerCommand
import com.cayatur.winbridge.protocol.RequestState
import com.cayatur.winbridge.protocol.ProtocolSession
import com.cayatur.winbridge.protocol.SubscribeMessage
import com.cayatur.winbridge.protocol.SystemState
import com.cayatur.winbridge.protocol.VolumeCommand
import com.cayatur.winbridge.protocol.VolumeState
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Keeps exactly one link to the PC alive.
 *
 * Reconnection is event-driven rather than polled: the service pokes [wake]
 * when the network or the Bluetooth bond changes, and the backoff exists only
 * for the case where nothing external tells us anything.
 */
class BridgeClient(
    private val store: SecureStore,
    private val state: BridgeState,
    private val carriers: List<Carrier>,
    private val scope: CoroutineScope,
) {
    /**
     * Where messages that need action rather than storage go. Set once by the
     * application; a no-op default keeps unit tests and the pairing screen from
     * needing the whole service graph.
     */
    var sink: BridgeSink = object : BridgeSink {}

    /** What this phone will accept, sent after auth and whenever a setting changes. */
    var describeFeatures: (() -> FeatureSet)? = null

    private val session = AtomicReference<ProtocolSession?>(null)
    private val sendLock = Mutex()

    private var loop: Job? = null
    private var wakeSignal: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Set by the UI: 1 Hz while a screen is open, 30 s when only widgets care. */
    @Volatile
    var systemRateMs: Int = WIDGET_RATE_MS

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch(Dispatchers.IO) { runForever() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        closeSession()
    }

    /** Called when something changed that makes a retry worth trying right now. */
    fun wake() {
        wakeSignal?.complete(Unit)
    }

    private suspend fun runForever() {
        var attempt = 0

        while (scope.isActive) {
            if (!store.isPaired) {
                state.setDisconnected("not paired")
                waitForWake(30_000)
                continue
            }

            val ordered = orderedCarriers()
            var connected = false

            for (carrier in ordered) {
                if (!carrier.isConfigured()) continue
                state.setConnecting(carrier.kind)
                try {
                    runSession(carrier)
                    connected = true
                    attempt = 0
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "${carrier.kind} attempt failed: ${e.message}")
                    state.setDisconnected(e.message ?: e.javaClass.simpleName)
                }
            }

            if (!connected) attempt++

            // Full jitter: without it, a phone and a PC waking together retry in
            // lockstep and keep colliding.
            val backoff = minOf(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl minOf(attempt, 6))
            waitForWake(Random.nextLong(500, backoff.coerceAtLeast(1000)))
        }
    }

    private fun orderedCarriers(): List<Carrier> {
        // Bluetooth is opt-in from 0.2.0. Leaving it in the rotation while
        // disabled would still cost a connect attempt and its timeout on every
        // retry, which is exactly the delay someone would blame on Wi-Fi.
        val usable = carriers.filter { it.kind != CarrierKind.BLUETOOTH || store.bluetoothEnabled }
        val ordered = if (store.preferBluetooth) {
            usable.sortedBy { if (it.kind == CarrierKind.BLUETOOTH) 0 else 1 }
        } else {
            usable.sortedBy { if (it.kind == CarrierKind.LAN) 0 else 1 }
        }
        return ordered.ifEmpty { carriers.filter { it.kind == CarrierKind.LAN } }
    }

    private suspend fun waitForWake(timeoutMs: Long) {
        val signal = kotlinx.coroutines.CompletableDeferred<Unit>()
        wakeSignal = signal
        try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { signal.await() }
        } finally {
            wakeSignal = null
        }
    }

    private suspend fun runSession(carrier: Carrier) = withContext(Dispatchers.IO) {
        val psk = store.psk ?: throw IllegalStateException("no pairing key")

        carrier.open().use { link ->
            val connected = ProtocolSession.connect(
                link.input, link.output,
                LocalIdentity(store.deviceId, android.os.Build.MODEL ?: "Android"),
                psk,
            )
            session.set(connected)
            state.setConnected(carrier.kind, connected.peerName)
            Log.i(TAG, "session up over ${carrier.kind} to ${connected.peerName}")

            val heartbeat = launch { heartbeat() }
            try {
                subscribe()
                announceFeatures()
                receiveLoop(connected)
            } finally {
                heartbeat.cancel()
                closeSession()
                state.setDisconnected("link closed")
            }
        }
    }

    private suspend fun receiveLoop(active: ProtocolSession) {
        while (currentCoroutineIsActive()) {
            val message = active.receive()

            // Binary lanes first: these arrive tens of times a second and must
            // not walk a switch over sixty string literals to be recognised.
            when (message.inner) {
                InnerType.BLOB -> {
                    val id = message.blobId
                    val payload = message.body.copyOfRange(0, message.body.size)
                    if (id != null && id.startsWith("art:")) state.onArt(id.removePrefix("art:"), payload)
                    else if (id != null) sink.onBlob(id, payload)
                    continue
                }
                InnerType.MEDIA -> {
                    val packet = message.asMedia()
                    if (packet.kind == MediaKind.VIDEO) sink.onVideo(packet) else sink.onAudio(packet)
                    continue
                }
                InnerType.XFER -> {
                    sink.onFileChunk(message.asXfer())
                    continue
                }
                InnerType.JSON -> Unit
            }

            when (message.jsonType) {
                MessageTypes.STATE_HOST -> state.onHost(message.decode<HostState>())
                MessageTypes.STATE_MEDIA -> {
                    val media = message.decode<MediaState>()
                    state.onMedia(media)
                    // Only ask for art we have never seen; the cache is keyed by
                    // content hash and kept permanently.
                    media.artHash?.takeIf { !state.hasArt(it) }?.let { requestArt(it) }
                }
                MessageTypes.STATE_SYSTEM -> state.onSystem(message.decode<SystemState>())
                MessageTypes.STATE_VOLUME -> state.onVolume(message.decode<VolumeState>())
                MessageTypes.EVENT_PEER -> onPeer(message.decode<PeerEvent>())
                MessageTypes.PONG -> state.onPong()
                MessageTypes.ERROR -> {
                    val error = message.decode<ErrorMessage>()
                    Log.w(TAG, "host error ${error.code}: ${error.detail}")
                    state.onError(error)
                }

                // ---- capabilities and clipboard -----------------------------
                MessageTypesV2.HOST_FEATURES -> state.onHostFeatures(message.decode<FeatureSet>())
                MessageTypesV2.CLIPBOARD_SET -> sink.onClipboard(message.decode<ClipboardMessage>())
                MessageTypesV2.CLIPBOARD_GET -> sink.onClipboardRequested()

                // ---- files ---------------------------------------------------
                MessageTypesV2.XFER_OFFER -> sink.onFileOffer(message.decode<XferOffer>())
                MessageTypesV2.XFER_ACCEPT -> sink.onFileAccept(message.decode<XferAccept>())
                MessageTypesV2.XFER_REJECT -> sink.onFileReject(message.decode<XferReject>())
                MessageTypesV2.XFER_DONE -> sink.onFileDone(message.decode<XferDone>())
                MessageTypesV2.XFER_PROGRESS -> Unit

                // ---- streams -------------------------------------------------
                MessageTypesV2.SCREEN_TARGETS -> state.onScreenTargets(message.decode<ScreenTargets>())
                MessageTypesV2.STREAM_INFO -> {
                    val info = message.decode<StreamInfo>()
                    if (info.stream == StreamIds.name(StreamIds.PC_SCREEN)) state.onPcStream(info)
                }
                MessageTypesV2.STREAM_START -> sink.onStartScreenShare(message.decode<StreamStart>())
                MessageTypesV2.STREAM_STOP -> sink.onStopScreenShare()
                MessageTypesV2.AUDIO_START -> sink.onStartAudio(message.decode<AudioStart>())
                MessageTypesV2.AUDIO_STOP -> sink.onStopAudio(message.decode<AudioStop>())
                MessageTypesV2.AUDIO_INFO -> {
                    val info = message.decode<AudioInfo>()
                    state.onAudioInfo(info)
                    // Also to the sink: this is what actually opens playback.
                    sink.onAudioInfo(info)
                }
                MessageTypesV2.AUDIO_DEVICES -> state.onAudioDevices(message.decode<AudioDevices>())

                // ---- input the PC wants injected here -------------------------
                MessageTypesV2.INPUT_TOUCH,
                MessageTypesV2.INPUT_GESTURE,
                MessageTypesV2.INPUT_SCROLL,
                MessageTypesV2.INPUT_NAV,
                MessageTypesV2.INPUT_KEY,
                MessageTypesV2.INPUT_TEXT,
                -> sink.onInput(message)

                // ---- automations ---------------------------------------------
                MessageTypesV2.AUTO_CATALOG -> state.onAutomations(message.decode<AutoCatalog>())
                MessageTypesV2.AUTO_DEF -> state.onAutomationDraft(message.decode<AutoDefinition>())
                MessageTypesV2.AUTO_SAVED -> state.automationSaves.tryEmit(message.decode<AutoSaved>())
                MessageTypesV2.AUTO_EVENT -> state.automationEvents.tryEmit(message.decode<AutoEvent>())
                MessageTypesV2.AUTO_RESULT -> state.automationResults.tryEmit(message.decode<AutoResult>())
                MessageTypesV2.AUTO_LOG -> state.onAutomationLog(message.decode<AutoLog>())

                // ---- notifications the PC is acting on ------------------------
                MessageTypesV2.NOTIF_ACTION -> sink.onNotificationAction(message.decode<NotifActionCommand>())
                MessageTypesV2.NOTIF_DISMISS -> sink.onNotificationDismiss(message.decode<NotifDismiss>().key)
                MessageTypesV2.NOTIF_SYNC -> sink.onNotificationSync()

                // ---- machine answers and small favours ------------------------
                MessageTypesV2.SYS_WINDOW_LIST -> state.onWindows(message.decode<WindowList>())
                MessageTypesV2.SYS_PROCESS_LIST -> state.onProcesses(message.decode<ProcessList>())
                MessageTypesV2.SYS_DESCRIPTION -> state.onDescription(message.decode<Description>())
                MessageTypesV2.SYS_NOTIFY -> {
                    val notify = message.decode<SysNotify>()
                    state.notices.tryEmit(notify.title to notify.text)
                    sink.onToast(notify)
                }
                MessageTypesV2.SYS_OPEN -> sink.onOpen(message.decode<SysOpen>().target)
                MessageTypesV2.PHONE_RING -> sink.onRing(message.decode<PhoneRing>())

                else -> Log.d(TAG, "ignored ${message.jsonType}")
            }
        }
    }

    private fun currentCoroutineIsActive() = scope.isActive && session.get() != null

    /**
     * Cross-transport provisioning: the host tells us how to reach it the other
     * way, so pairing once over Bluetooth also sets up LAN, and the reverse.
     */
    private fun onPeer(peer: PeerEvent) {
        peer.bt?.mac?.takeIf { it.isNotBlank() }?.let { store.hostBtMac = it }
        peer.lan?.let { lan ->
            if (lan.hosts.isNotEmpty()) store.hostLanHosts = lan.hosts
            if (lan.port > 0) store.hostLanPort = lan.port
        }
        Log.i(TAG, "peer info updated: bt=${store.hostBtMac} lan=${store.hostLanHosts}")
    }

    private suspend fun heartbeat() {
        while (true) {
            delay(HEARTBEAT_MS)
            val active = session.get() ?: return
            if (state.msSincePong() > DEAD_AFTER_MS) {
                Log.w(TAG, "no pong in ${state.msSincePong()}ms — dropping the link")
                runCatching { active.sayGoodbye() }
                closeSession()
                return
            }
            runCatching { sendLock.withLock { active.sendJson(PingMessage(echo = System.currentTimeMillis())) } }
        }
    }

    private suspend fun subscribe() = send {
        it.sendJson(
            SubscribeMessage(
                rates = mapOf("media" to 0, "system" to systemRateMs, "volume" to 0),
            ),
        )
    }

    suspend fun setSystemRate(ms: Int) {
        if (systemRateMs == ms) return
        systemRateMs = ms
        subscribe()
    }

    suspend fun requestArt(hash: String) = send { it.sendJson(BlobRequest(id = "art:$hash")) }

    /** Forces the host to resend everything, e.g. after a setting changed. */
    suspend fun requestFullState() = send { it.sendJson(RequestState()) }

    suspend fun mediaCommand(action: String, posMs: Long = 0) =
        send { it.sendJson(MediaCommand(action = action, posMs = posMs)) }

    suspend fun volumeCommand(action: String, level: Int = 0) =
        send { it.sendJson(VolumeCommand(action = action, level = level)) }

    suspend fun powerCommand(action: String, delaySec: Int = 0) =
        send { it.sendJson(PowerCommand(action = action, delaySec = delaySec)) }

    // ---- 0.2.0 ---------------------------------------------------------------

    val isConnected: Boolean get() = session.get() != null

    /** Anything with a `t` field. Keeps the caller from needing a method per message. */
    suspend inline fun <reified T> sendMessage(message: T) = sendEncoded(
        com.cayatur.winbridge.protocol.ProtocolJson.encodeToString(message).encodeToByteArray(),
    )

    suspend fun sendEncoded(json: ByteArray) = send { it.sendJsonBytes(json) }

    suspend fun announceFeatures() {
        val features = describeFeatures?.invoke() ?: return
        sendMessage(features)
    }

    /**
     * Queues a real-time packet. Not suspending and not mutex-guarded: the
     * session has its own bounded media lane that drops rather than blocks, and
     * making a capture loop wait on a shared lock would rebuild the very backlog
     * that lane exists to prevent.
     */
    fun trySendMedia(packet: MediaPacket): Boolean =
        session.get()?.trySendMedia(packet) ?: false

    /** Blocking on purpose: the bulk lane paces a transfer against the link. */
    suspend fun sendXfer(chunk: XferChunk) = withContext(Dispatchers.IO) {
        val active = session.get() ?: return@withContext
        runCatching { active.sendXfer(chunk) }.onFailure {
            Log.w(TAG, "xfer send failed: ${it.message}")
        }
        Unit
    }

    suspend fun sendBlob(id: String, bytes: ByteArray) = send { it.sendBlob(id, bytes) }

    private suspend fun send(block: (ProtocolSession) -> Unit) {
        val active = session.get() ?: return
        withContext(Dispatchers.IO) {
            sendLock.withLock {
                runCatching { block(active) }.onFailure {
                    Log.w(TAG, "send failed: ${it.message}")
                    closeSession()
                }
            }
        }
    }

    private fun closeSession() {
        session.getAndSet(null)
    }

    private companion object {
        const val HEARTBEAT_MS = 5_000L
        const val DEAD_AFTER_MS = 15_000L
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val WIDGET_RATE_MS = 5_000
    }
}
