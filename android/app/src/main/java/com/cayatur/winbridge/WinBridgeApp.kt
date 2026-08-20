package com.cayatur.winbridge

import android.app.Application
import com.cayatur.winbridge.data.SecureStore
import com.cayatur.winbridge.feature.FileTransfer
import com.cayatur.winbridge.feature.Notices
import com.cayatur.winbridge.feature.PhoneSink
import com.cayatur.winbridge.feature.Ringer
import com.cayatur.winbridge.feature.Shortcuts
import com.cayatur.winbridge.feature.VoiceCommands
import com.cayatur.winbridge.net.BluetoothCarrier
import com.cayatur.winbridge.net.BridgeClient
import com.cayatur.winbridge.net.BridgeState
import com.cayatur.winbridge.net.TcpCarrier
import com.cayatur.winbridge.wear.WearPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

class WinBridgeApp : Application() {

    val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val store: SecureStore by lazy { SecureStore(this) }
    val state: BridgeState by lazy { BridgeState(this) }

    val client: BridgeClient by lazy {
        BridgeClient(
            store = store,
            state = state,
            carriers = listOf(
                BluetoothCarrier(this, macProvider = { store.hostBtMac }),
                TcpCarrier(
                    hostsProvider = { store.hostLanHosts },
                    portProvider = { store.hostLanPort },
                ),
            ),
            scope = scope,
        )
    }

    val files: FileTransfer by lazy { FileTransfer(this, store, client, scope) }
    val ringer: Ringer by lazy { Ringer(this) }
    val voice: VoiceCommands by lazy { VoiceCommands(this) }
    private val sink: PhoneSink by lazy { PhoneSink(this, this) }

    /** The most recent screenshot the PC sent, for the describe screen. */
    @Volatile
    var lastScreenshot: ByteArray? = null

    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)

    /**
     * Re-sends the capability set. Called whenever a switch moves, so the PC
     * greys out what is now off instead of offering a button that does nothing.
     */
    fun announceFeatures() = scope.launch { client.announceFeatures() }

    override fun onCreate() {
        super.onCreate()
        instance = this

        Notices.ensureChannels(this)

        // A trigger token exists from first run rather than being generated on
        // demand: an exported receiver whose secret is empty until someone opens
        // a settings screen is an exported receiver with no secret.
        if (store.triggerToken.isNullOrBlank()) {
            store.triggerToken = UUID.randomUUID().toString().replace("-", "").take(24)
        }

        client.sink = sink
        client.describeFeatures = { sink.describe() }

        // Automations become launcher shortcuts, which is what makes them
        // reachable from an assistant routine or the home screen.
        scope.launch {
            state.automations.collectLatest { catalog ->
                Shortcuts.publish(this@WinBridgeApp, catalog?.items.orEmpty(), store.publishShortcuts)
                WearPublisher.publishAutomations(
                    this@WinBridgeApp,
                    catalog?.items.orEmpty(),
                    catalog?.shellEnabled == true,
                )
            }
        }

        // The watch asks the PC what is on screen and expects the answer back on
        // its own wrist, not on the phone it relayed through.
        scope.launch {
            state.description.collectLatest { answer ->
                if (answer == null) return@collectLatest
                WearPublisher.publishAnswer(
                    this@WinBridgeApp,
                    listOfNotNull(answer.title, answer.text?.take(400)).joinToString(". "),
                )
            }
        }
    }

    companion object {
        lateinit var instance: WinBridgeApp
            private set
    }
}
