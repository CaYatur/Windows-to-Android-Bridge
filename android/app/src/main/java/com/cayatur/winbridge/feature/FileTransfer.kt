package com.cayatur.winbridge.feature

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.cayatur.winbridge.R
import com.cayatur.winbridge.data.SecureStore
import com.cayatur.winbridge.net.BridgeClient
import com.cayatur.winbridge.net.TAG
import com.cayatur.winbridge.protocol.XferAccept
import com.cayatur.winbridge.protocol.XferChunk
import com.cayatur.winbridge.protocol.XferDone
import com.cayatur.winbridge.protocol.XferFlags
import com.cayatur.winbridge.protocol.XferOffer
import com.cayatur.winbridge.protocol.XferReject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

data class TransferProgress(
    val id: Int,
    val name: String,
    val bytes: Long,
    val total: Long,
    val incoming: Boolean,
    val done: Boolean = false,
    val error: String? = null,
)

/**
 * Files in both directions.
 *
 * Sending reads through a [android.content.ContentResolver] rather than a path,
 * because that is what the share sheet actually gives you and because scoped
 * storage means an app has no business holding paths to other apps' files.
 *
 * Receiving writes through MediaStore on Android 10 and later, so the result
 * lands in Downloads where the file manager and every other app can see it —
 * something a file written into app-private storage would not be.
 */
class FileTransfer(
    private val context: Context,
    private val store: SecureStore,
    private val client: BridgeClient,
    private val scope: CoroutineScope,
) {
    private val outgoing = ConcurrentHashMap<Int, Outgoing>()
    private val incoming = ConcurrentHashMap<Int, Incoming>()

    private val _transfers = MutableStateFlow<List<TransferProgress>>(emptyList())
    val transfers: StateFlow<List<TransferProgress>> = _transfers.asStateFlow()

    /** Chunk payload. Matches the host so neither side has to fragment the other. */
    private val chunkSize = 48 * 1024

    // ---- sending ----------------------------------------------------------

    fun send(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!client.isConnected) {
            Notices.simple(
                context, Notices.CHANNEL_TRANSFERS, Notices.ID_TRANSFER,
                context.getString(R.string.transfer_no_pc), null,
            )
            return
        }

        scope.launch(Dispatchers.IO) {
            val batch = Random.nextInt(1, Int.MAX_VALUE)
            uris.forEachIndexed { index, uri ->
                val (name, size) = describe(uri)
                val id = Random.nextInt(1, Int.MAX_VALUE)
                outgoing[id] = Outgoing(id, uri, name, size)

                runCatching {
                    client.sendMessage(
                        XferOffer(
                            id = id,
                            name = name,
                            size = size,
                            mime = context.contentResolver.getType(uri),
                            batch = batch,
                            batchIndex = index,
                            batchCount = uris.size,
                        ),
                    )
                }.onFailure { Log.w(TAG, "offer failed: ${it.message}") }
            }
        }
    }

    fun onAccepted(accept: XferAccept) {
        val transfer = outgoing[accept.id] ?: return
        scope.launch(Dispatchers.IO) { stream(transfer, accept.offset) }
    }

    private suspend fun stream(transfer: Outgoing, offset: Long) {
        try {
            context.contentResolver.openInputStream(transfer.uri).use { input ->
                if (input == null) throw IllegalStateException("could not open the file")
                if (offset > 0) input.skip(offset)

                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(chunkSize)
                var seq = 0
                var sent = offset

                while (true) {
                    val read = input.read(buffer)
                    val last = read <= 0

                    if (read > 0) digest.update(buffer, 0, read)

                    // Awaited: the bulk lane completes a chunk once it is on the
                    // wire, so this loop is paced by the link rather than by how
                    // fast the file can be read into memory.
                    client.sendXfer(
                        XferChunk(
                            transferId = transfer.id,
                            seq = seq++,
                            flags = if (last) XferFlags.LAST else XferFlags.NONE,
                            data = buffer,
                            dataOffset = 0,
                            dataLength = maxOf(0, read),
                        ),
                    )

                    if (read > 0) {
                        sent += read
                        publish(TransferProgress(transfer.id, transfer.name, sent, transfer.size, false))
                    }
                    if (last) break
                }

                client.sendMessage(
                    XferDone(
                        id = transfer.id,
                        ok = true,
                        sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                    ),
                )
                publish(TransferProgress(transfer.id, transfer.name, sent, transfer.size, false, done = true))
            }
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
            runCatching { client.sendMessage(XferDone(id = transfer.id, ok = false, error = e.message)) }
            publish(TransferProgress(transfer.id, transfer.name, 0, transfer.size, false, true, e.message))
        } finally {
            outgoing.remove(transfer.id)
        }
    }

    fun onRejected(reject: XferReject) {
        val transfer = outgoing.remove(reject.id) ?: return
        publish(TransferProgress(transfer.id, transfer.name, 0, transfer.size, false, true, reject.reason))
    }

    // ---- receiving --------------------------------------------------------

    fun onOffer(offer: XferOffer) {
        if (!store.fileTransferEnabled) {
            scope.launch { client.sendMessage(XferReject(id = offer.id, reason = "disabled")) }
            return
        }

        val big = offer.size > store.fileAutoAcceptMaxMb * 1024L * 1024L
        if (!store.fileAutoAccept && big) {
            // Nothing on this side can put a dialog in front of a user who is not
            // looking, so a large file the user did not opt into is refused with
            // a reason rather than silently written.
            scope.launch { client.sendMessage(XferReject(id = offer.id, reason = "too large for auto-accept")) }
            Notices.simple(
                context, Notices.CHANNEL_TRANSFERS, Notices.ID_TRANSFER,
                context.getString(R.string.transfer_failed, offer.name, "not accepted"), null,
            )
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val sink = openSink(offer.name)
                incoming[offer.id] = Incoming(offer.id, offer.name, offer.size, offer.sha256, sink.first, sink.second)
                client.sendMessage(XferAccept(id = offer.id, offset = 0))
                Notices.progress(context, Notices.ID_TRANSFER,
                    context.getString(R.string.transfer_incoming, offer.name), 0, offer.size)
            } catch (e: Exception) {
                Log.w(TAG, "cannot receive ${offer.name}: ${e.message}")
                client.sendMessage(XferReject(id = offer.id, reason = e.message ?: "cannot write"))
            }
        }
    }

    fun onChunk(chunk: XferChunk) {
        val transfer = incoming[chunk.transferId] ?: return
        try {
            if (chunk.dataLength > 0) {
                transfer.output.write(chunk.data, chunk.dataOffset, chunk.dataLength)
                transfer.digest.update(chunk.data, chunk.dataOffset, chunk.dataLength)
                transfer.received += chunk.dataLength
            }

            if (transfer.received - transfer.reported > 256 * 1024) {
                transfer.reported = transfer.received
                publish(TransferProgress(transfer.id, transfer.name, transfer.received, transfer.total, true))
                Notices.progress(context, Notices.ID_TRANSFER,
                    context.getString(R.string.transfer_incoming, transfer.name),
                    transfer.received, transfer.total)
            }

            if (chunk.isLast) finish(transfer)
        } catch (e: Exception) {
            incoming.remove(transfer.id)
            runCatching { transfer.output.close() }
            publish(TransferProgress(transfer.id, transfer.name, transfer.received, transfer.total, true, true, e.message))
            scope.launch { client.sendMessage(XferDone(id = transfer.id, ok = false, error = e.message)) }
        }
    }

    private fun finish(transfer: Incoming) {
        incoming.remove(transfer.id)
        runCatching { transfer.output.flush(); transfer.output.close() }

        val hex = transfer.digest.digest().joinToString("") { "%02x".format(it) }
        val mismatch = transfer.expected != null && !transfer.expected.equals(hex, ignoreCase = true)

        // Published to MediaStore only once the bytes are all there: an entry
        // visible mid-transfer is a half file other apps can open.
        if (!mismatch) publish(transfer.uri)

        publish(
            TransferProgress(
                transfer.id, transfer.name, transfer.received, transfer.total, true, true,
                if (mismatch) "checksum mismatch" else null,
            ),
        )
        Notices.clear(context, Notices.ID_TRANSFER)
        Notices.simple(
            context, Notices.CHANNEL_TRANSFERS, Notices.ID_TRANSFER,
            if (mismatch) context.getString(R.string.transfer_failed, transfer.name, "checksum")
            else context.getString(R.string.transfer_done, transfer.name),
            null,
        )

        scope.launch {
            client.sendMessage(XferDone(id = transfer.id, ok = !mismatch, sha256 = hex))
        }
    }

    fun onDone(done: XferDone) {
        if (!done.ok) Log.w(TAG, "the PC reported transfer ${done.id} failed: ${done.error}")
    }

    // ---- destinations -----------------------------------------------------

    /**
     * Returns a stream plus the MediaStore row to publish afterwards. On Android
     * 10 and later the file goes into Downloads/WinBridge with IS_PENDING set, so
     * nothing else can see a partial file; older versions get the public
     * downloads directory, which is all that exists there.
     */
    private fun openSink(name: String): Pair<OutputStream, Uri?> {
        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "file" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safe)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WinBridge")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
            ) ?: throw IllegalStateException("MediaStore refused the file")

            val stream = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("could not open the destination")
            return stream to uri
        }

        val folder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "WinBridge",
        ).apply { mkdirs() }

        var target = File(folder, safe)
        var counter = 2
        while (target.exists()) {
            val stem = safe.substringBeforeLast('.', safe)
            val extension = safe.substringAfterLast('.', "")
            target = File(folder, "$stem ($counter)" + if (extension.isEmpty()) "" else ".$extension")
            counter++
        }
        return target.outputStream() to null
    }

    private fun publish(uri: Uri?) {
        if (uri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            context.contentResolver.update(
                uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null,
            )
        }
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "file"
        var size = 0L

        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        return name to size
    }

    private fun publish(progress: TransferProgress) {
        _transfers.value = _transfers.value
            .filterNot { it.id == progress.id }
            .plus(progress)
            .takeLast(20)
    }

    private class Outgoing(val id: Int, val uri: Uri, val name: String, val size: Long)

    private class Incoming(
        val id: Int,
        val name: String,
        val total: Long,
        val expected: String?,
        val output: OutputStream,
        val uri: Uri?,
    ) {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var received: Long = 0
        var reported: Long = 0
    }
}
