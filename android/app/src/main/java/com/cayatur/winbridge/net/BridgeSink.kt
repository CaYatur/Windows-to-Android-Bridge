package com.cayatur.winbridge.net

import com.cayatur.winbridge.protocol.AudioStart
import com.cayatur.winbridge.protocol.AudioStop
import com.cayatur.winbridge.protocol.ClipboardMessage
import com.cayatur.winbridge.protocol.InboundMessage
import com.cayatur.winbridge.protocol.MediaPacket
import com.cayatur.winbridge.protocol.NotifActionCommand
import com.cayatur.winbridge.protocol.PhoneRing
import com.cayatur.winbridge.protocol.StreamStart
import com.cayatur.winbridge.protocol.SysNotify
import com.cayatur.winbridge.protocol.XferAccept
import com.cayatur.winbridge.protocol.XferChunk
import com.cayatur.winbridge.protocol.XferDone
import com.cayatur.winbridge.protocol.XferOffer
import com.cayatur.winbridge.protocol.XferReject

/**
 * The things arriving from the PC that need something *done* rather than
 * remembered.
 *
 * State — media, metrics, capability sets, automation catalogues — goes straight
 * into [BridgeState] where the UI can observe it. This interface is for the rest:
 * inject a touch, start a capture, write a file chunk to disk. Splitting them
 * keeps the receive loop from having to know about services, and keeps
 * [BridgeState] from having to know about anything that can fail.
 *
 * Every method is called on the receive loop, so implementations must return
 * quickly and hand slow work to a scope of their own.
 */
interface BridgeSink {

    fun onClipboard(clip: ClipboardMessage) {}
    fun onClipboardRequested() {}

    fun onFileOffer(offer: XferOffer) {}
    fun onFileAccept(accept: XferAccept) {}
    fun onFileChunk(chunk: XferChunk) {}
    fun onFileDone(done: XferDone) {}
    fun onFileReject(reject: XferReject) {}

    /** A screen frame from the PC. */
    fun onVideo(packet: MediaPacket) {}

    /** Audio from the PC — its output or its microphone. */
    fun onAudio(packet: MediaPacket) {}

    /** The PC asking this phone to start sharing its screen. */
    fun onStartScreenShare(request: StreamStart) {}
    fun onStopScreenShare() {}

    /** The PC asking for this phone microphone, or for its playback audio. */
    fun onStartAudio(request: AudioStart) {}
    fun onStopAudio(request: AudioStop) {}

    /** A touch, gesture, key, text or navigation event to inject locally. */
    fun onInput(message: InboundMessage) {}

    fun onNotificationAction(command: NotifActionCommand) {}
    fun onNotificationDismiss(key: String) {}
    fun onNotificationSync() {}

    fun onToast(notify: SysNotify) {}
    fun onOpen(target: String) {}
    fun onRing(request: PhoneRing) {}

    /** A blob the PC pushed — a screenshot, or an app icon. */
    fun onBlob(id: String, bytes: ByteArray) {}
}
