package com.cayatur.winbridge.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.protocol.AutoRunRequest
import com.cayatur.winbridge.service.BridgeService
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs an automation from something outside the app — a home-screen widget, a
 * launcher shortcut, a watch chip.
 *
 * The awkward case is a cold one. Tapping a widget may be the thing that starts
 * our process, and at that instant there is no link to the PC; sending straight
 * away puts the request into a socket that does not exist yet and the button
 * looks dead. So the service is started first and the request waits, briefly,
 * for the link to come up. If it does not, the user is told rather than left
 * wondering whether the tap registered.
 */
object WidgetRun {

    suspend fun request(context: Context, automationId: String) {
        val app = WinBridgeApp.instance
        if (!app.client.isConnected) BridgeService.start(context)

        val connected = withTimeoutOrNull(CONNECT_WAIT_MS) {
            while (!app.client.isConnected) delay(150)
            true
        } ?: false

        if (!connected) {
            toast(context, context.getString(R.string.transfer_no_pc))
            return
        }

        app.client.sendMessage(AutoRunRequest(id = automationId))
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Long enough to cover starting the service and a LAN handshake, short
     * enough that a tap with the PC switched off does not feel like a hang.
     */
    private const val CONNECT_WAIT_MS = 6_000L
}
