package com.cayatur.winbridge.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.cayatur.winbridge.protocol.ErrorMessage
import com.cayatur.winbridge.protocol.HostState
import com.cayatur.winbridge.protocol.MediaState
import com.cayatur.winbridge.protocol.SystemState
import com.cayatur.winbridge.protocol.VolumeState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED }

data class ConnectionInfo(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val carrier: CarrierKind? = null,
    val hostName: String? = null,
    val detail: String? = null,
)

/**
 * Single source of truth for everything the UI, the widgets and the watch read.
 *
 * Album art is cached on disk by content hash and never evicted by track
 * change: the hash identifies the image, so a cover already fetched is never
 * requested again — which is what keeps the feature usable over Bluetooth.
 */
class BridgeState(context: Context) {

    private val artDirectory = File(context.cacheDir, "art").apply { mkdirs() }
    private val artMemory = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > 12
    }

    private val _connection = MutableStateFlow(ConnectionInfo())
    val connection: StateFlow<ConnectionInfo> = _connection.asStateFlow()

    private val _host = MutableStateFlow<HostState?>(null)
    val host: StateFlow<HostState?> = _host.asStateFlow()

    private val _media = MutableStateFlow<MediaState?>(null)
    val media: StateFlow<MediaState?> = _media.asStateFlow()

    private val _system = MutableStateFlow<SystemState?>(null)
    val system: StateFlow<SystemState?> = _system.asStateFlow()

    private val _volume = MutableStateFlow<VolumeState?>(null)
    val volume: StateFlow<VolumeState?> = _volume.asStateFlow()

    private val _art = MutableStateFlow<Pair<String, Bitmap>?>(null)
    val art: StateFlow<Pair<String, Bitmap>?> = _art.asStateFlow()

    val errors = MutableSharedFlow<ErrorMessage>(extraBufferCapacity = 8)

    @Volatile
    private var lastPongAt: Long = System.currentTimeMillis()

    fun setConnecting(carrier: CarrierKind) {
        _connection.value = ConnectionInfo(ConnectionPhase.CONNECTING, carrier)
    }

    fun setConnected(carrier: CarrierKind, hostName: String) {
        lastPongAt = System.currentTimeMillis()
        _connection.value = ConnectionInfo(ConnectionPhase.CONNECTED, carrier, hostName)
    }

    fun setDisconnected(detail: String?) {
        _connection.value = ConnectionInfo(ConnectionPhase.DISCONNECTED, detail = detail)
        _system.value = null
    }

    fun onHost(state: HostState) { _host.value = state }
    fun onSystem(state: SystemState) { _system.value = state }
    fun onVolume(state: VolumeState) { _volume.value = state }

    fun onMedia(state: MediaState) {
        _media.value = state
        state.artHash?.let { hash -> loadArt(hash)?.let { _art.value = hash to it } }
    }

    fun onError(error: ErrorMessage) { errors.tryEmit(error) }

    fun onPong() { lastPongAt = System.currentTimeMillis() }
    fun msSincePong(): Long = System.currentTimeMillis() - lastPongAt

    // ---- art cache ---------------------------------------------------------

    fun hasArt(hash: String): Boolean =
        synchronized(artMemory) { artMemory.containsKey(hash) } || artFile(hash).exists()

    fun onArt(hash: String, jpeg: ByteArray) {
        runCatching { artFile(hash).writeBytes(jpeg) }
        loadArt(hash)?.let { _art.value = hash to it }
    }

    fun loadArt(hash: String): Bitmap? {
        synchronized(artMemory) { artMemory[hash]?.let { return it } }

        val file = artFile(hash)
        if (!file.exists()) return null

        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        synchronized(artMemory) { artMemory[hash] = bitmap }
        return bitmap
    }

    /** Hashes are hex from the host, but never trust a peer-supplied filename. */
    private fun artFile(hash: String) = File(artDirectory, hash.filter { it.isLetterOrDigit() }.take(64) + ".jpg")
}
