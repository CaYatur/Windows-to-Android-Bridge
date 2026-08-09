package com.cayatur.winbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cayatur.winbridge.data.SecureStore

/**
 * Brings the link back after a reboot, but only for a phone that is actually
 * paired — starting a foreground service on a fresh install would put up a
 * permanent notification for a connection that cannot exist.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SecureStore(context).isPaired) return
        BridgeService.start(context)
    }
}
