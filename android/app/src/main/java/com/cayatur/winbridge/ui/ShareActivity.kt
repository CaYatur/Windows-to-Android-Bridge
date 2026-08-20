package com.cayatur.winbridge.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.feature.ClipboardBridge
import com.cayatur.winbridge.service.BridgeService
import kotlinx.coroutines.launch

/**
 * The share-sheet target.
 *
 * Files are read through the URIs the sending app granted us, not through paths:
 * a share can come from a cloud provider with no file on disk at all, and under
 * scoped storage a path would be unreadable even when there is one.
 *
 * Shared text goes to the clipboard route instead of being written to a file,
 * because "share this link to my PC" means paste it there, not save a .txt.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = WinBridgeApp.instance
        BridgeService.start(this)

        val uris = collectUris()
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)

        when {
            uris.isNotEmpty() -> {
                app.files.send(uris)
                toast(
                    if (uris.size == 1) getString(R.string.transfer_outgoing, name(uris[0]))
                    else getString(R.string.transfer_sending_count, uris.size, app.state.host.value?.name ?: "PC"),
                )
            }

            !text.isNullOrBlank() -> {
                val clip = ClipboardBridge.build(text)
                app.scope.launch { app.client.sendMessage(clip) }
                toast(getString(R.string.clipboard_sent))
            }

            else -> toast(getString(R.string.clipboard_empty))
        }

        finish()
        overridePendingTransition(0, 0)
    }

    private fun collectUris(): List<Uri> {
        val intent = intent ?: return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(parcelable(intent, Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> parcelableList(intent, Intent.EXTRA_STREAM)
            else -> emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun parcelable(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            intent.getParcelableExtra(key)
        }

    @Suppress("DEPRECATION")
    private fun parcelableList(intent: Intent, key: String): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, Uri::class.java) ?: emptyList()
        } else {
            intent.getParcelableArrayListExtra<Uri>(key) ?: emptyList()
        }

    private fun name(uri: Uri): String = uri.lastPathSegment ?: "file"

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
