package com.cayatur.winbridge.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.protocol.StreamIds
import com.cayatur.winbridge.protocol.StreamInfo
import com.cayatur.winbridge.service.CaptureService
import kotlinx.coroutines.launch

/**
 * Asks for screen-capture consent and hands the grant to the capture service.
 *
 * Consent can only be requested from an Activity, and Android asks again every
 * session — there is no way to remember it, for the obvious reason. So this is a
 * transparent, animation-free activity that exists purely to put the system
 * dialog on screen and pass the result on.
 *
 * If the user says no, the PC is told why rather than being left watching a
 * blank window: "they declined" is a much better answer than nothing at all.
 */
class ProjectionRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = WinBridgeApp.instance
        if (!app.store.allowScreenShare) {
            refuse("screen sharing is off on the phone")
            return
        }

        maxFps = intent.getIntExtra(EXTRA_MAX_FPS, 30)
        quality = intent.getIntExtra(EXTRA_QUALITY, 60)
        maxEdge = intent.getIntExtra(EXTRA_MAX_EDGE, 1080)

        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST)
    }

    @Deprecated("Deprecated in favour of the result APIs, which a plain Activity cannot use here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST) return

        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, R.string.capture_denied, Toast.LENGTH_SHORT).show()
            refuse("declined on the phone")
            return
        }

        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            putExtra(CaptureService.EXTRA_MAX_FPS, maxFps)
            putExtra(CaptureService.EXTRA_QUALITY, quality)
            putExtra(CaptureService.EXTRA_MAX_EDGE, maxEdge)
        }
        startForegroundService(intent)

        finish()
        overridePendingTransition(0, 0)
    }

    private fun refuse(reason: String) {
        val app = WinBridgeApp.instance
        app.scope.launch {
            runCatching {
                app.client.sendMessage(
                    StreamInfo(
                        stream = StreamIds.name(StreamIds.PHONE_SCREEN),
                        active = false,
                        reason = reason,
                    ),
                )
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    private var maxFps = 30
    private var quality = 60
    private var maxEdge = 1080

    companion object {
        private const val REQUEST = 4711

        const val EXTRA_MAX_FPS = "maxFps"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_MAX_EDGE = "maxEdge"

        fun ask(context: android.content.Context, maxFps: Int, quality: Int, maxEdge: Int) {
            val intent = Intent(context, ProjectionRequestActivity::class.java).apply {
                putExtra(EXTRA_MAX_FPS, maxFps)
                putExtra(EXTRA_QUALITY, quality)
                putExtra(EXTRA_MAX_EDGE, maxEdge)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
        }
    }
}
