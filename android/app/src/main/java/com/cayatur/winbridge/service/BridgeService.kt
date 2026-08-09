package com.cayatur.winbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.cayatur.winbridge.MainActivity
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.net.ConnectionPhase
import com.cayatur.winbridge.net.TAG
import com.cayatur.winbridge.widget.WidgetRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Holds the link while the app is not in the foreground.
 *
 * Reconnection is driven by events, not by a polling timer: a network becoming
 * available or a Bluetooth device connecting pokes the client immediately, so
 * coming home to Wi-Fi reconnects in about a second rather than whenever the
 * next backoff tick happens to land.
 */
class BridgeService : Service() {

    private val app get() = application as WinBridgeApp

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var bluetoothReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        // Android kills the process if startForeground does not land within a
        // few seconds of onCreate, so the notification goes up before anything
        // that could block.
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.status_connecting)))

        registerNetworkCallback()
        registerBluetoothReceiver()

        app.client.start()

        app.scope.launch {
            app.state.connection.collectLatest { info ->
                val text = when (info.phase) {
                    ConnectionPhase.CONNECTED ->
                        getString(R.string.status_connected_to, info.hostName ?: "PC")
                    ConnectionPhase.CONNECTING -> getString(R.string.status_connecting)
                    ConnectionPhase.DISCONNECTED -> getString(R.string.status_disconnected)
                }
                notificationManager().notify(NOTIFICATION_ID, buildNotification(text))
                WidgetRepository.publish(this@BridgeService, app.state)
            }
        }

        // Media changes are worth repainting immediately; system metrics are
        // not — the launcher throttles widget updates anyway, and redrawing a
        // widget once a second to move a CPU bar nobody is looking at is a
        // straightforward way to drain a battery.
        app.scope.launch {
            app.state.media.collectLatest { WidgetRepository.publish(this@BridgeService, app.state) }
        }
        app.scope.launch {
            while (true) {
                delay(WIDGET_REFRESH_MS)
                if (WidgetRepository.anyPlaced(this@BridgeService)) {
                    WidgetRepository.publish(this@BridgeService, app.state)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        app.client.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "network available — retrying now")
                app.client.wake()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        runCatching { manager.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun registerBluetoothReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothAdapter.ACTION_STATE_CHANGED,
                    -> {
                        Log.i(TAG, "bluetooth state changed — retrying now")
                        app.client.wake()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
        }.onSuccess { bluetoothReceiver = receiver }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    override fun onDestroy() {
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        }
        bluetoothReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "winbridge.link"
        private const val NOTIFICATION_ID = 1
        private const val WIDGET_REFRESH_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }
}
