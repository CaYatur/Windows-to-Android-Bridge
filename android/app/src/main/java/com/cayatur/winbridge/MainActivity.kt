package com.cayatur.winbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cayatur.winbridge.feature.Shortcuts
import com.cayatur.winbridge.protocol.AutoRunRequest
import com.cayatur.winbridge.service.BridgeService
import com.cayatur.winbridge.ui.PairingScreen
import com.cayatur.winbridge.ui.MainScreen
import com.cayatur.winbridge.ui.SectionCard
import com.cayatur.winbridge.ui.WinBridgeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDebugPairing()
        setContent { WinBridgeTheme { Root() } }
        BridgeService.start(this)
        runRequestedAutomation(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runRequestedAutomation(intent)
    }

    /**
     * Handles a launcher shortcut for an automation.
     *
     * Shortcuts are how an assistant routine reaches this app: there is no API
     * that lets it hand us an arbitrary command, but it can open a shortcut by
     * name, and each approved automation publishes one.
     */
    private fun runRequestedAutomation(intent: Intent?) {
        val id = intent?.getStringExtra(Shortcuts.EXTRA_RUN_AUTOMATION) ?: return
        intent.removeExtra(Shortcuts.EXTRA_RUN_AUTOMATION)

        val app = WinBridgeApp.instance
        app.launch { runCatching { app.client.sendMessage(AutoRunRequest(id = id)) } }
    }

    /**
     * Debug-only hook so the whole stack can be exercised on a device without a
     * human pointing a camera at a screen:
     *
     *   adb shell am start -n com.cayatur.winbridge/.MainActivity \
     *       --es pair_payload '{"v":1,"psk":"…","id":"…","lan":{…}}'
     *
     * Compiled out of release builds — a release APK has no way to be paired
     * except by scanning a code the user is looking at.
     */
    private fun applyDebugPairing() {
        if (!BuildConfig.DEBUG) return
        val payload = intent?.getStringExtra("pair_payload") ?: return
        val name = com.cayatur.winbridge.ui.applyPayload(payload)
        android.util.Log.i("WinBridge", "debug pairing applied: ${name ?: "REJECTED"}")
        if (name != null) WinBridgeApp.instance.store.setupComplete = true
    }
}

private enum class Route { SETUP, MAIN, PAIRING }

@Composable
private fun Root() {
    val app = WinBridgeApp.instance
    var route by remember {
        mutableStateOf(if (app.store.setupComplete) Route.MAIN else Route.SETUP)
    }
    var pairedWith by remember { mutableStateOf<String?>(null) }

    when (route) {
        Route.SETUP -> SetupScreen(
            onScan = { route = Route.PAIRING },
            onFinish = {
                app.store.setupComplete = true
                route = Route.MAIN
            },
        )
        Route.MAIN -> MainScreen(onPair = { route = Route.PAIRING })
        Route.PAIRING -> PairingScreen(
            onPaired = { name ->
                pairedWith = name
                app.store.setupComplete = true
                route = Route.MAIN
            },
            onCancel = { route = if (app.store.setupComplete) Route.MAIN else Route.SETUP },
        )
    }

    pairedWith?.let { name ->
        AlertDialog(
            onDismissRequest = { pairedWith = null },
            title = { Text(stringResource(R.string.pair_success, name)) },
            confirmButton = {
                TextButton(onClick = { pairedWith = null }) {
                    Text(stringResource(R.string.setup_done))
                }
            },
        )
    }
}

/**
 * First-run walkthrough.
 *
 * The battery step is not boilerplate: without an exemption, Android stops the
 * foreground service in the background and the user sees exactly the dropped
 * connection this project exists to avoid. Phones with a vendor autostart list
 * get an extra card, because that toggle cannot be reached by intent on many
 * builds — telling the user where it is beats firing something that no-ops.
 */
@Composable
private fun SetupScreen(onScan: () -> Unit, onFinish: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val permissions = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        add(Manifest.permission.CAMERA)
    }

    var granted by remember {
        mutableStateOf(
            permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> granted = result.values.all { it } }

    val powerManager = context.getSystemService(PowerManager::class.java)
    var batteryExempt by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true)
    }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        batteryExempt = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    val hasVendorAutostart = remember {
        Build.MANUFACTURER.lowercase() in
            setOf("xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "vivo", "realme", "oneplus")
    }

    Scaffold { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SectionCard(stringResource(R.string.setup_welcome_title)) {
                Text(
                    stringResource(R.string.setup_welcome_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Button(onClick = onScan, enabled = granted, modifier = Modifier.padding(top = 14.dp)) {
                    Text(stringResource(R.string.setup_scan))
                }
            }

            SectionCard(stringResource(R.string.setup_permissions_title)) {
                Text(
                    stringResource(R.string.setup_permissions_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (!granted) {
                    Button(
                        onClick = { launcher.launch(permissions.toTypedArray()) },
                        modifier = Modifier.padding(top = 14.dp),
                    ) { Text(stringResource(R.string.setup_grant)) }
                }
            }

            if (!batteryExempt) {
                SectionCard(stringResource(R.string.setup_battery_title)) {
                    Text(
                        stringResource(R.string.setup_battery_body),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Button(
                        onClick = {
                            batteryLauncher.launch(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        },
                        modifier = Modifier.padding(top = 14.dp),
                    ) { Text(stringResource(R.string.setup_battery_action)) }
                }
            }

            if (hasVendorAutostart) {
                SectionCard(stringResource(R.string.setup_autostart_title)) {
                    Text(
                        stringResource(R.string.setup_autostart_body),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            TextButton(onClick = onFinish, modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.setup_skip))
            }
        }
    }
}
