package com.cayatur.winbridge.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.cayatur.winbridge.protocol.AudioDevices
import com.cayatur.winbridge.protocol.AudioInfo
import com.cayatur.winbridge.protocol.AutoCatalog
import com.cayatur.winbridge.protocol.AutoDefinition
import com.cayatur.winbridge.protocol.AutoEvent
import com.cayatur.winbridge.protocol.AutoLog
import com.cayatur.winbridge.protocol.AutoResult
import com.cayatur.winbridge.protocol.AutoSaved
import com.cayatur.winbridge.protocol.Description
import com.cayatur.winbridge.protocol.ErrorMessage
import com.cayatur.winbridge.protocol.FeatureSet
import com.cayatur.winbridge.protocol.HostState
import com.cayatur.winbridge.protocol.MediaState
import com.cayatur.winbridge.protocol.ProcessList
import com.cayatur.winbridge.protocol.ScreenTargets
import com.cayatur.winbridge.protocol.StreamInfo
import com.cayatur.winbridge.protocol.SystemState
import com.cayatur.winbridge.protocol.VolumeState
import com.cayatur.winbridge.protocol.WindowList
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

    // ---- 0.2.0 -------------------------------------------------------------
    //
    // State flows for what the UI observes; shared flows for the things that are
    // events rather than values — an automation finishing is not a state anyone
    // wants to still be looking at ten minutes later.

    private val _hostFeatures = MutableStateFlow<FeatureSet?>(null)
    val hostFeatures: StateFlow<FeatureSet?> = _hostFeatures.asStateFlow()

    private val _pcStream = MutableStateFlow<StreamInfo?>(null)
    val pcStream: StateFlow<StreamInfo?> = _pcStream.asStateFlow()

    private val _screenTargets = MutableStateFlow<ScreenTargets?>(null)
    val screenTargets: StateFlow<ScreenTargets?> = _screenTargets.asStateFlow()

    private val _audioInfo = MutableStateFlow<Map<String, AudioInfo>>(emptyMap())
    val audioInfo: StateFlow<Map<String, AudioInfo>> = _audioInfo.asStateFlow()

    private val _audioDevices = MutableStateFlow<AudioDevices?>(null)
    val audioDevices: StateFlow<AudioDevices?> = _audioDevices.asStateFlow()

    private val _automations = MutableStateFlow<AutoCatalog?>(null)
    val automations: StateFlow<AutoCatalog?> = _automations.asStateFlow()

    private val _automationDraft = MutableStateFlow<AutoDefinition?>(null)
    val automationDraft: StateFlow<AutoDefinition?> = _automationDraft.asStateFlow()

    private val _automationLog = MutableStateFlow<AutoLog?>(null)
    val automationLog: StateFlow<AutoLog?> = _automationLog.asStateFlow()

    private val _windows = MutableStateFlow<WindowList?>(null)
    val windows: StateFlow<WindowList?> = _windows.asStateFlow()

    private val _processes = MutableStateFlow<ProcessList?>(null)
    val processes: StateFlow<ProcessList?> = _processes.asStateFlow()

    private val _description = MutableStateFlow<Description?>(null)
    val description: StateFlow<Description?> = _description.asStateFlow()

    val automationEvents = MutableSharedFlow<AutoEvent>(extraBufferCapacity = 64)
    val automationResults = MutableSharedFlow<AutoResult>(extraBufferCapacity = 8)
    val automationSaves = MutableSharedFlow<AutoSaved>(extraBufferCapacity = 8)

    /** Toasts and one-off messages the PC asked us to show. */
    val notices = MutableSharedFlow<Pair<String, String?>>(extraBufferCapacity = 8)

    fun onHostFeatures(features: FeatureSet) { _hostFeatures.value = features }
    fun onPcStream(info: StreamInfo) { _pcStream.value = info }
    fun onScreenTargets(targets: ScreenTargets) { _screenTargets.value = targets }
    fun onAudioInfo(info: AudioInfo) { _audioInfo.value = _audioInfo.value + (info.stream to info) }
    fun onAudioDevices(devices: AudioDevices) { _audioDevices.value = devices }
    fun onAutomations(catalog: AutoCatalog) { _automations.value = catalog }
    fun onAutomationDraft(definition: AutoDefinition) { _automationDraft.value = definition }
    fun onAutomationLog(log: AutoLog) { _automationLog.value = log }
    fun onWindows(list: WindowList) { _windows.value = list }
    fun onProcesses(list: ProcessList) { _processes.value = list }
    fun onDescription(description: Description) { _description.value = description }

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
        _pcStream.value = null
        _audioInfo.value = emptyMap()
        _hostFeatures.value = null
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
