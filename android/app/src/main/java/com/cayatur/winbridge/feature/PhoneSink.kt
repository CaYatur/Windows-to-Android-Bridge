package com.cayatur.winbridge.feature

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.net.BridgeSink
import com.cayatur.winbridge.net.TAG
import com.cayatur.winbridge.protocol.AudioCaps
import com.cayatur.winbridge.protocol.AudioInfo
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
        if (!app.store.clipboardFromPc) {
            Log.i(TAG, "clipboard arrived from the PC, but receiving is off on this phone")
            return
        }
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
        if (!app.store.clipboardToPc) {
            Log.i(TAG, "PC asked for the clipboard, but sending to the PC is off on this phone")
            return
        }
        // Reading needs focus we do not have in the background, so this walks
        // the ladder rather than assuming any one rung is available.
        ClipboardBridge.push(context) { clip -> app.launch { app.client.sendMessage(clip) } }
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

    /**
     * The PC asking this phone to produce one of its own streams.
     *
     * Only phone-owned streams are acted on here. The PC never asks us to open a
     * sink — the listener decides, and when this phone is the listener it asks
     * and then opens from the `audio.info` that comes back.
     */
    override fun onStartAudio(request: AudioStart) {
        when (StreamIds.fromName(request.stream)) {
            StreamIds.PHONE_MIC -> {
                if (!app.store.micToPc) {
                    refuse(request.stream, "the phone microphone is off on the phone")
                    return
                }
                CaptureService.startMicrophone(context, request.rate, request.channels)
            }

            StreamIds.PHONE_AUDIO -> {
                // Capturing what other apps are playing needs an active
                // MediaProjection, which only exists while the screen is being
                // shared. Saying so beats going quiet.
                if (!app.store.screenShareAudio) {
                    refuse(request.stream, "phone audio sharing is off on the phone")
                } else if (!RemoteInputService.sessionOpen) {
                    refuse(request.stream, "start screen sharing first; Android ties app audio capture to it")
                }
            }

            else -> Unit
        }
    }

    override fun onStopAudio(request: AudioStop) {
        when (StreamIds.fromName(request.stream)) {
            StreamIds.PHONE_MIC -> context.startService(
                Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP_MIC),
            )
            StreamIds.PHONE_AUDIO -> Unit
            else -> Unit
        }
    }

    /**
     * The PC telling us how one of its streams turned out. Opening the sink from
     * here rather than from our own request means the format is the one the PC
     * actually got, and that a stream stopping on that side closes this one.
     */
    override fun onAudioInfo(info: AudioInfo) {
        val stream = StreamIds.fromName(info.stream)
        if (stream != StreamIds.PC_AUDIO && stream != StreamIds.PC_MIC) return

        if (!info.active) {
            AudioPlayback.stop(stream)
            if (info.reason != null) Log.i(TAG, "${info.stream} stopped: ${info.reason}")
            return
        }

        val wanted = when (stream) {
            StreamIds.PC_AUDIO -> app.store.audioFromPc || PcScreenActivity.active != null
            else -> app.store.micFromPc
        }
        if (!wanted) return

        AudioPlayback.start(
            stream,
            rate = if (info.rate > 0) info.rate else 48000,
            channels = if (info.channels > 0) info.channels else 2,
            voiceCall = stream == StreamIds.PC_MIC,
        )
    }

    private fun refuse(stream: String, reason: String) {
        Log.i(TAG, "audio refused ($stream): $reason")
        app.launch {
            runCatching {
                app.client.sendMessage(AudioInfo(stream = stream, active = false, reason = reason))
            }
        }
    }

    /**
     * Asks the PC for the streams this phone wants to hear, and stops the ones
     * it no longer does. Called whenever a switch moves.
     */
    fun reconcileAudio() {
        request(StreamIds.PC_AUDIO, app.store.audioFromPc)
        request(StreamIds.PC_MIC, app.store.micFromPc)
    }

    private fun request(stream: Byte, wanted: Boolean) {
        val name = StreamIds.name(stream)
        app.launch {
            runCatching {
                if (wanted) {
                    app.client.sendMessage(AudioStart(stream = name))
                } else {
                    AudioPlayback.stop(stream)
                    app.client.sendMessage(AudioStop(stream = name))
                }
            }
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
