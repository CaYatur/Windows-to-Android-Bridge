package com.cayatur.winbridge

import android.app.Application
import com.cayatur.winbridge.data.SecureStore
import com.cayatur.winbridge.net.BluetoothCarrier
import com.cayatur.winbridge.net.BridgeClient
import com.cayatur.winbridge.net.BridgeState
import com.cayatur.winbridge.net.TcpCarrier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

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

    companion object {
        lateinit var instance: WinBridgeApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
