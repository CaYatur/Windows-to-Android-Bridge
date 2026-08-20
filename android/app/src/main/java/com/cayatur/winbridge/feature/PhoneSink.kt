package com.cayatur.winbridge.feature

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.net.BridgeSink
import com.cayatur.winbridge.net.TAG
import com.cayatur.winbridge.protocol.AudioCaps
import com.cayatur.winbridge.protocol.AudioStart
import com.cayatur.winbridge.protocol.AudioStop
import com.cayatur.winbridge.protocol.ClipboardCaps
import com.cayatur.winbridge.protocol.ClipboardMessage
import com.cayatur.winbridge.protocol.FeatureSet
import com.cayatur.winbridge.protocol.FileCaps
import com.cayatur.winbridge.protocol.InboundMessage
import com.cayatur.winbridge.protocol.InputCaps
import com.cayatur.winbridge.protocol.InputGesture
import com.cayatur.winbridge.protocol.InputKey
import com.cayatur.winbridge.protocol.InputNav
import com.cayatur.winbridge.protocol.InputScroll
import com.cayatur.winbridge.protocol.InputText
import com.cayatur.winbridge.protocol.InputTouch
import com.cayatur.winbridge.protocol.MediaPacket
import com.cayatur.winbridge.protocol.MessageTypesV2
import com.cayatur.winbridge.protocol.NotifActionCommand
import com.cayatur.winbridge.protocol.PhoneRing
import com.cayatur.winbridge.protocol.ScreenCaps
import com.cayatur.winbridge.protocol.StreamIds
import com.cayatur.winbridge.protocol.StreamStart
import com.cayatur.winbridge.protocol.SysNotify
import com.cayatur.winbridge.protocol.XferAccept
import com.cayatur.winbridge.protocol.XferChunk
import com.cayatur.winbridge.protocol.XferDone
import com.cayatur.winbridge.protocol.XferOffer
import com.cayatur.winbridge.protocol.XferReject
import com.cayatur.winbridge.service.CaptureService
import com.cayatur.winbridge.service.NotificationRelay
import com.cayatur.winbridge.service.RemoteInputService
import com.cayatur.winbridge.ui.PcScreenActivity
import com.cayatur.winbridge.ui.ProjectionRequestActivity

/**
 * Everything the PC asks this phone to actually do.
 *
 * One class rather than handlers scattered through the services, so that the
 * complete list of what a paired PC can make this phone do is readable in one
 * sitting. Every entry that can surprise someone is gated on a setting the user
 * turned on, and the gate is checked here rather than deeper down, where it
 * would be easy to add a caller that skips it.
 */
class PhoneSink(
    private val context: Context,
    private val app: WinBridgeApp,
) : BridgeSink {

    // ---- clipboard ---------------------------------------------------------

    override fun onClipboard(clip: ClipboardMessage) {
        if (!app.store.clipboardFromPc) return
        if (ClipboardBridge.isEcho(clip)) return
        val text = clip.text ?: return

        // The chain, in order of how invisible each step is. Direct is silent,
        // the relay flashes nothing but needs to start an activity, and the
        // notification always works because a tap is a user gesture.
        if (app.store.clipboardAutoApply && ClipboardBridge.applyDirect(context, clip)) {
            ClipboardBridge.remember(clip)
            Notices.simple(
                context, Notices.CHANNEL_CLIPBOARD, Notices.ID_CLIPBOARD,
                context.getString(com.cayatur.winbridge.R.string.clipboard_from_pc, clip.label ?: "PC"),
                text.take(120),
            )
            return
        }

        if (app.store.clipboardAutoApply) {
            ClipboardBridge.applyViaActivity(context, clip)
            return
        }

        Notices.clipboardArrived(context, text, clip.label)
    }

    override fun onClipboardRequested() {
        if (!app.store.clipboardToPc) return
        val clip = ClipboardBridge.readDirect(context)
        if (clip != null) {
            ClipboardBridge.remember(clip)
            app.launch { app.client.sendMessage(clip) }
        } else {
            // Reading needs focus we do not have in the background, so the relay
            // borrows it for a frame.
            ClipboardBridge.sendViaActivity(context)
        }
    }

    // ---- files -------------------------------------------------------------

    override fun onFileOffer(offer: XferOffer) = app.files.onOffer(offer)
    override fun onFileAccept(accept: XferAccept) = app.files.onAccepted(accept)
    override fun onFileChunk(chunk: XferChunk) = app.files.onChunk(chunk)
    override fun onFileDone(done: XferDone) = app.files.onDone(done)
    override fun onFileReject(reject: XferReject) = app.files.onRejected(reject)

    // ---- media -------------------------------------------------------------

    override fun onVideo(packet: MediaPacket) {
        PcScreenActivity.active?.onPacket(packet)
    }

    override fun onAudio(packet: MediaPacket) = AudioPlayback.feed(packet)

    override fun onStartScreenShare(request: StreamStart) {
        if (StreamIds.fromName(request.stream) != StreamIds.PHONE_SCREEN) return

        if (!app.store.allowScreenShare) {
            Log.i(TAG, "screen share refused: turned off on the phone")
            return
        }

        // Android asks the user every session and there is no way to remember
        // the answer, so this always goes through the consent activity.
        ProjectionRequestActivity.ask(context, request.maxFps, request.quality, request.maxEdge)
    }

    override fun onStopScreenShare() = CaptureService.stop(context)

    override fun onStartAudio(request: AudioStart) {
        when (StreamIds.fromName(request.stream)) {
            // The PC wants this phone microphone.
            StreamIds.PHONE_MIC -> {
                if (!app.store.micToPc) return
                CaptureService.startMicrophone(context, request.rate, request.channels)
            }

            // The PC is about to send its output or its microphone here.
            StreamIds.PC_AUDIO -> {
                if (!app.store.audioFromPc && PcScreenActivity.active == null) return
                AudioPlayback.start(StreamIds.PC_AUDIO, request.rate, request.channels)
            }

            StreamIds.PC_MIC -> {
                if (!app.store.micFromPc) return
                AudioPlayback.start(StreamIds.PC_MIC, request.rate, request.channels, voiceCall = true)
            }

            else -> Unit
        }
    }

    override fun onStopAudio(request: AudioStop) {
        when (val stream = StreamIds.fromName(request.stream)) {
            StreamIds.PHONE_MIC -> context.startService(
                Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP_MIC),
            )
            else -> AudioPlayback.stop(stream)
        }
    }

    // ---- input -------------------------------------------------------------

    override fun onInput(message: InboundMessage) {
        // Three gates. The setting is the user's decision, the accessibility
        // service is Android's, and the open session means the PC is actually
        // showing this screen to somebody — a phone being driven by a PC nobody
        // is looking at is not a feature.
        if (!app.store.allowRemoteInput) return

        val service = RemoteInputService.current()
        if (service == null) {
            Log.i(TAG, "input ignored: the accessibility service is not enabled")
            return
        }
        if (!RemoteInputService.sessionOpen) {
            Log.i(TAG, "input ignored: no mirroring session is open")
            return
        }

        when (message.jsonType) {
            MessageTypesV2.INPUT_TOUCH -> service.touch(message.decode<InputTouch>())
            MessageTypesV2.INPUT_GESTURE -> service.gesture(message.decode<InputGesture>())
            MessageTypesV2.INPUT_SCROLL -> service.scroll(message.decode<InputScroll>())
            MessageTypesV2.INPUT_NAV -> service.navigate(message.decode<InputNav>())
            MessageTypesV2.INPUT_KEY -> service.key(message.decode<InputKey>())
            MessageTypesV2.INPUT_TEXT -> service.text(message.decode<InputText>())
        }
    }

    // ---- notifications and favours -----------------------------------------

    override fun onNotificationAction(command: NotifActionCommand) {
        NotificationRelay.current()?.act(command)
    }

    override fun onNotificationDismiss(key: String) {
        NotificationRelay.current()?.dismiss(key)
    }

    override fun onNotificationSync() {
        NotificationRelay.current()?.syncAll()
    }

    override fun onToast(notify: SysNotify) {
        Notices.simple(
            context, Notices.CHANNEL_ALERTS, Notices.ID_AUTOMATION,
            notify.title, notify.text,
        )
    }

    override fun onOpen(target: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(target))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "could not open $target: ${it.message}") }
    }

    override fun onRing(request: PhoneRing) {
        if (!app.store.allowRing) return
        if (request.action == "stop") app.ringer.stop() else app.ringer.start(request.seconds)
    }

    override fun onBlob(id: String, bytes: ByteArray) {
        // Screenshots the PC pushed in answer to a describe request.
        if (id.startsWith("shot:")) app.lastScreenshot = bytes
    }

    // ---- what this phone will accept ---------------------------------------

    fun describe(): FeatureSet {
        val store = app.store
        val injectable = RemoteInputService.isEnabled()

        return FeatureSet(
            type = MessageTypesV2.CLIENT_FEATURES,
            clipboard = ClipboardCaps(
                send = store.clipboardToPc,
                receive = store.clipboardFromPc,
            ),
            files = FileCaps(
                enabled = store.fileTransferEnabled,
                maxChunk = 48 * 1024,
                autoAccept = store.fileAutoAccept,
            ),
            screen = ScreenCaps(
                send = store.allowScreenShare,
                receive = store.viewPcEnabled,
                targets = 1,
                carrierOk = true,
            ),
            audio = AudioCaps(
                playback = store.audioFromPc,
                mic = store.micToPc || store.micFromPc,
            ),
            input = InputCaps(
                send = store.viewPcInteract,
                receive = store.allowRemoteInput && injectable,
                reason = when {
                    !store.allowRemoteInput -> "turned off on the phone"
                    !injectable -> "the accessibility service is not enabled"
                    else -> null
                },
            ),
            automations = store.automationsEnabled,
            notifications = store.notificationMirror && NotificationRelay.isGranted(context),
            describe = false,
            ring = store.allowRing,
        )
    }
}
