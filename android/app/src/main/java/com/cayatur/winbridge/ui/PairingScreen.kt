package com.cayatur.winbridge.ui

import android.util.Base64
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.net.TAG
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors

@Serializable
private data class QrLan(val hosts: List<String> = emptyList(), val port: Int = 8737)

@Serializable
private data class QrBt(val mac: String = "", val uuid: String = "")

@Serializable
private data class QrPayload(
    val v: Int = 1,
    val psk: String = "",
    val id: String = "",
    val name: String = "",
    val lan: QrLan? = null,
    val bt: QrBt? = null,
)

private val qrJson = Json { ignoreUnknownKeys = true }

/**
 * Scans the pairing code shown by the Windows tray.
 *
 * The code carries the pre-shared key itself, so the key never travels over the
 * network — the camera is the channel. That is what makes this path free of the
 * dictionary-attack caveat the numeric fallback carries.
 */
@Composable
fun PairingScreen(onPaired: (String) -> Unit, onCancel: () -> Unit) {
    val app = WinBridgeApp.instance
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var message by remember { mutableStateOf<String?>(null) }
    var handled by remember { mutableStateOf(false) }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text(stringResource(R.string.pair_title)) },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.control_cancel)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.pair_camera_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )

            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)

                    providerFuture.addListener({
                        val provider = providerFuture.get()

                        val preview = androidx.camera.core.Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        analysis.setAnalyzer(analysisExecutor) { image ->
                            if (handled) { image.close(); return@setAnalyzer }
                            val text = decodeQr(image)
                            image.close()
                            if (text == null) return@setAnalyzer

                            handled = true
                            ContextCompat.getMainExecutor(ctx).execute {
                                when (val result = applyPayload(text)) {
                                    null -> {
                                        message = ctx.getString(R.string.pair_bad_code)
                                        handled = false
                                    }
                                    else -> onPaired(result)
                                }
                            }
                        }

                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                            )
                        }.onFailure { Log.w(TAG, "camera bind failed: ${it.message}") }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
            )

            message?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/** Returns the host name on success, or null if this was not our code. */
internal fun applyPayload(text: String): String? {
    val payload = runCatching { qrJson.decodeFromString<QrPayload>(text) }.getOrNull() ?: return null
    if (payload.v != 1 || payload.psk.isBlank() || payload.id.isBlank()) return null

    val key = runCatching { Base64.decode(payload.psk, Base64.DEFAULT) }.getOrNull() ?: return null
    if (key.size != 32) return null

    val store = WinBridgeApp.instance.store
    store.psk = key
    store.hostDeviceId = payload.id
    store.hostName = payload.name
    payload.bt?.mac?.takeIf { it.isNotBlank() }?.let { store.hostBtMac = it }
    payload.lan?.let {
        store.hostLanHosts = it.hosts
        store.hostLanPort = it.port
    }

    WinBridgeApp.instance.client.wake()
    return payload.name.ifBlank { "PC" }
}

private val reader = MultiFormatReader().apply {
    setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)))
}

private fun decodeQr(image: ImageProxy): String? {
    // The Y plane alone is enough for QR and avoids a full YUV->RGB conversion
    // on every frame.
    val plane = image.planes[0]
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }

    val source = PlanarYUVLuminanceSource(
        data, plane.rowStride, image.height,
        0, 0, minOf(plane.rowStride, image.width), image.height, false,
    )

    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (e: Exception) {
        null
    } finally {
        reader.reset()
    }
}
