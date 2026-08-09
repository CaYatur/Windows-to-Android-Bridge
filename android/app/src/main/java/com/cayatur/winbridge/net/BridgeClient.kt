package com.cayatur.winbridge.net

import android.util.Log
import com.cayatur.winbridge.data.SecureStore
import com.cayatur.winbridge.protocol.BlobRequest
import com.cayatur.winbridge.protocol.ErrorMessage
import com.cayatur.winbridge.protocol.HostState
import com.cayatur.winbridge.protocol.InnerType
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

    private fun orderedCarriers(): List<Carrier> =
        if (store.preferBluetooth) {
            carriers.sortedBy { if (it.kind == CarrierKind.BLUETOOTH) 0 else 1 }
        } else {
            carriers.sortedBy { if (it.kind == CarrierKind.LAN) 0 else 1 }
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

            if (message.inner == InnerType.BLOB) {
                message.blobId?.removePrefix("art:")?.let { state.onArt(it, message.body) }
                continue
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
        const val WIDGET_RATE_MS = 30_000
    }
}
