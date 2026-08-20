package com.cayatur.winbridge.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes one filled-in sample of every v2 message, serialised by the C# models
 * into `protocol-vectors.json`.
 *
 * A renamed JSON property between the two implementations does not fail loudly:
 * `ignoreUnknownKeys` swallows the sender side and the receiver quietly sees a
 * default. That produces bugs like "the phone always mirrors at 30 fps no matter
 * what I choose", which look like logic errors and are miserable to trace back
 * to a spelling. So every field that carries a decision is asserted here, and
 * the coverage check at the bottom fails the build if a message is added on the
 * C# side without a Kotlin mirror.
 */
class MessagesV2Test {

    private val samples: JsonObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("protocol-vectors.json")
            ?: error("protocol-vectors.json missing — regenerate it from WinBridge.Core.Tests")
        ProtocolJson.parseToJsonElement(stream.readBytes().decodeToString())
            .jsonObject["messagesV2"]!!.jsonObject
    }

    private val decoded = mutableSetOf<String>()

    private inline fun <reified T> decode(name: String): T {
        val element = samples[name] ?: error("no sample for $name — regenerate the vectors")
        decoded += name
        return ProtocolJson.decodeFromJsonElement(element)
    }

    @Test
    fun `capability exchange survives the crossing`() {
        val features = decode<FeatureSet>(MessageTypesV2.HOST_FEATURES)
        assertEquals(2, features.version)
        assertTrue(features.clipboard.send)
        assertEquals(1024, features.clipboard.maxBytes)
        assertEquals(32768, features.files.maxChunk)
        assertTrue(features.files.autoAccept)
        assertEquals(2, features.screen.targets)
        assertEquals(false, features.screen.carrierOk)
        assertTrue(features.audio.mic)
        assertEquals("ok", features.input.reason)
        assertTrue(features.automations && features.shell && features.notifications)
        assertTrue(features.describe && features.ring)
    }

    @Test
    fun `clipboard and file transfer decode`() {
        val clip = decode<ClipboardMessage>(MessageTypesV2.CLIPBOARD_SET)
        assertEquals("text", clip.format)
        assertEquals("merhaba", clip.text)
        assertEquals("abc123", clip.hash)
        decode<ClipboardRequest>(MessageTypesV2.CLIPBOARD_GET)

        val offer = decode<XferOffer>(MessageTypesV2.XFER_OFFER)
        assertEquals(9, offer.id)
        assertEquals(4096L, offer.size)
        assertEquals(2, offer.batchCount)
        assertEquals("sub/notes.txt", offer.path)

        assertEquals(1024L, decode<XferAccept>(MessageTypesV2.XFER_ACCEPT).offset)
        assertEquals("declined", decode<XferReject>(MessageTypesV2.XFER_REJECT).reason)
        assertEquals(999L, decode<XferProgress>(MessageTypesV2.XFER_PROGRESS).bps)
        assertEquals("C:/x/notes.txt", decode<XferDone>(MessageTypesV2.XFER_DONE).savedAs)
        assertEquals("user", decode<XferCancel>(MessageTypesV2.XFER_CANCEL).reason)
    }

    @Test
    fun `screen stream negotiation decodes every knob`() {
        val targets = decode<ScreenTargets>(MessageTypesV2.SCREEN_TARGETS)
        assertEquals(1920, targets.items.single().width)
        assertTrue(targets.items.single().primary)
        decode<ScreenListRequest>(MessageTypesV2.SCREEN_LIST)

        val start = decode<StreamStart>(MessageTypesV2.STREAM_START)
        assertEquals(45, start.maxFps)
        assertEquals(55, start.quality)
        assertEquals(1600, start.maxEdge)
        assertTrue(start.audio && start.interact)
        assertEquals(false, start.cursor)

        assertEquals("pc.screen", decode<StreamStop>(MessageTypesV2.STREAM_STOP).stream)

        val info = decode<StreamInfo>(MessageTypesV2.STREAM_INFO)
        assertEquals(64, info.tileWidth)
        assertEquals(25, info.columns)
        assertEquals(15, info.rows)
        assertTrue(info.interact)

        val config = decode<StreamConfig>(MessageTypesV2.STREAM_CONFIG)
        assertEquals(20, config.maxFps)
        assertEquals(false, config.interact)
        assertEquals(true, config.cursor)

        val stats = decode<StreamStats>(MessageTypesV2.STREAM_STATS)
        assertEquals(28.5, stats.fps, 0.001)
        assertEquals(14, stats.rttMs)
        assertEquals(55, stats.latencyMs)
    }

    @Test
    fun `audio negotiation decodes`() {
        val start = decode<AudioStart>(MessageTypesV2.AUDIO_START)
        assertEquals(44100, start.rate)
        assertEquals(1, start.channels)
        assertEquals(40, start.frameMs)
        assertEquals("dev1", start.device)

        decode<AudioStop>(MessageTypesV2.AUDIO_STOP)
        assertTrue(decode<AudioInfo>(MessageTypesV2.AUDIO_INFO).active)

        val devices = decode<AudioDevices>(MessageTypesV2.AUDIO_DEVICES)
        assertTrue(devices.items.single().isDefault)
        assertEquals("render", devices.items.single().flow)

        assertEquals("dev1", decode<AudioRoute>(MessageTypesV2.AUDIO_ROUTE).device)
    }

    @Test
    fun `input events decode`() {
        val mouse = decode<InputMouse>(MessageTypesV2.INPUT_MOUSE)
        assertEquals(-120, mouse.delta)
        assertEquals(3, mouse.horizontalDelta)
        assertTrue(mouse.relative)
        assertEquals(0.25, mouse.x, 0.0001)

        val key = decode<InputKey>(MessageTypesV2.INPUT_KEY)
        assertEquals(listOf("ctrl", "shift"), key.mods)
        assertEquals(2, key.repeat)

        assertEquals("gunaydin", decode<InputText>(MessageTypesV2.INPUT_TEXT).text)
        assertEquals(0.8, decode<InputTouch>(MessageTypesV2.INPUT_TOUCH).pressure, 0.0001)

        val gesture = decode<InputGesture>(MessageTypesV2.INPUT_GESTURE)
        assertEquals(2, gesture.points.size)
        assertEquals(200, gesture.points[1].atMs)
        assertEquals(1, gesture.points2.size)

        assertEquals("recents", decode<InputNav>(MessageTypesV2.INPUT_NAV).action)
        assertEquals(-0.1, decode<InputScroll>(MessageTypesV2.INPUT_SCROLL).dy, 0.0001)
    }

    @Test
    fun `notification mirroring decodes`() {
        val post = decode<NotifPost>(MessageTypesV2.NOTIF_POST)
        assertEquals("com.x", post.packageName)
        assertEquals("X", post.appName)
        assertEquals("big", post.bigText)
        assertEquals(1700000000000L, post.`when`)
        assertTrue(post.ongoing)
        assertTrue(post.actions.single().isReply)
        assertEquals("deadbeef", post.iconHash)

        assertEquals("k1", decode<NotifRemove>(MessageTypesV2.NOTIF_REMOVE).key)
        assertEquals("tamam", decode<NotifActionCommand>(MessageTypesV2.NOTIF_ACTION).text)
        decode<NotifDismiss>(MessageTypesV2.NOTIF_DISMISS)
        decode<NotifSync>(MessageTypesV2.NOTIF_SYNC)

        val state = decode<NotifState>(MessageTypesV2.NOTIF_STATE)
        assertTrue(state.enabled)
        assertEquals(false, state.granted)
        assertEquals(3, state.count)
    }

    @Test
    fun `machine introspection decodes`() {
        val windows = decode<WindowList>(MessageTypesV2.SYS_WINDOW_LIST)
        assertEquals(123L, windows.items.single().handle)
        assertEquals(42, windows.items.single().pid)
        decode<WindowsRequest>(MessageTypesV2.SYS_WINDOWS)
        assertEquals("Notepad", decode<WindowCommand>(MessageTypesV2.SYS_WINDOW).match)

        assertEquals(64L, decode<ProcessList>(MessageTypesV2.SYS_PROCESS_LIST).items.single().memMb)
        assertEquals(10, decode<ProcessesRequest>(MessageTypesV2.SYS_PROCESSES).top)
        assertEquals(42, decode<ProcessCommand>(MessageTypesV2.SYS_PROCESS).pid)

        assertTrue(decode<DescribeRequest>(MessageTypesV2.SYS_DESCRIBE).image)

        val description = decode<Description>(MessageTypesV2.SYS_DESCRIPTION)
        assertEquals("hello", description.text)
        assertEquals(listOf("a", "b"), description.windows)
        assertEquals(800, description.width)

        assertEquals("warn", decode<SysNotify>(MessageTypesV2.SYS_NOTIFY).level)
        assertTrue(decode<SysOpen>(MessageTypesV2.SYS_OPEN).target.isNotEmpty())
        assertEquals(15, decode<PhoneRing>(MessageTypesV2.PHONE_RING).seconds)

        val phone = decode<PhoneState>(MessageTypesV2.PHONE_STATE)
        assertEquals(55, phone.battery)
        assertTrue(phone.charging && phone.screenOn)
    }

    @Test
    fun `automations decode, including nested steps`() {
        decode<AutoListRequest>(MessageTypesV2.AUTO_LIST)

        val catalog = decode<AutoCatalog>(MessageTypesV2.AUTO_CATALOG)
        assertTrue(catalog.shellEnabled && catalog.deviceTrusted && catalog.authoringAllowed)
        assertEquals("trusted", catalog.trustMode)
        assertEquals(listOf("notepad.exe"), catalog.allowlist)
        assertEquals("shell", catalog.items.single().risk)
        assertEquals(5, catalog.items.single().stepCount)

        // Every step type the host advertises must be one this build knows how
        // to render, or the editor would offer an empty card.
        val known = setOf(
            StepTypes.SHELL, StepTypes.OPEN, StepTypes.WINDOW, StepTypes.PROCESS, StepTypes.KEY,
            StepTypes.TYPE_TEXT, StepTypes.MOUSE, StepTypes.MEDIA, StepTypes.VOLUME, StepTypes.POWER,
            StepTypes.CLIP_GET, StepTypes.CLIP_SET, StepTypes.NOTIFY, StepTypes.FILE, StepTypes.HTTP,
            StepTypes.DELAY, StepTypes.SET, StepTypes.IF, StepTypes.WHILE, StepTypes.REPEAT,
            StepTypes.FOREACH, StepTypes.BREAK, StepTypes.CONTINUE, StepTypes.RETURN, StepTypes.LOG,
            StepTypes.SCREENSHOT, StepTypes.DESCRIBE, StepTypes.PHONE_NOTIFY, StepTypes.PHONE_RING,
            StepTypes.PHONE_CLIP, StepTypes.CALL,
        )
        assertEquals(emptySet<String>(), catalog.stepTypes.toSet() - known)
        assertEquals(emptySet<String>(), known - catalog.stepTypes.toSet())

        decode<AutoGetRequest>(MessageTypesV2.AUTO_GET)

        val automation = decode<AutoDefinition>(MessageTypesV2.AUTO_DEF).automation!!
        assertEquals("Focus Chrome", automation.name)
        assertTrue(automation.confirmEachRun && automation.requireUnlocked && automation.approved)
        assertEquals("hash1", automation.bodyHash)
        assertEquals(mapOf("who" to "world"), automation.variables)
        assertEquals(5, automation.steps.size)

        val branch = automation.steps[1]
        assertEquals(StepTypes.IF, branch.type)
        assertEquals("battery < 20", branch.condition)
        assertEquals(StepTypes.NOTIFY, branch.then.single().type)
        assertEquals(StepTypes.DELAY, branch.otherwise.single().type)
        assertEquals(250.0, branch.otherwise.single().number, 0.001)

        val loop = automation.steps[2]
        assertEquals("item", loop.variable)
        assertEquals(StepTypes.LOG, loop.body2.single().type)

        val shell = automation.steps[3]
        assertEquals("powershell", shell.shell)
        assertEquals(listOf("-NoProfile"), shell.args)
        assertEquals(5000, shell.timeoutMs)
        assertTrue(shell.onErrorContinue)
        assertEquals("C:/", shell.workingDirectory)

        val http = automation.steps[4]
        assertEquals("POST", http.method)
        assertEquals(mapOf("X-A" to "b"), http.headers)

        decode<AutoSaveRequest>(MessageTypesV2.AUTO_SAVE)
        assertEquals("pending", decode<AutoSaved>(MessageTypesV2.AUTO_SAVED).state)
        decode<AutoDeleteRequest>(MessageTypesV2.AUTO_DELETE)

        val run = decode<AutoRunRequest>(MessageTypesV2.AUTO_RUN)
        assertTrue(run.dryRun)
        assertEquals(mapOf("who" to "world"), run.args)

        val event = decode<AutoEvent>(MessageTypesV2.AUTO_EVENT)
        assertEquals(2, event.stepIndex)
        assertEquals("shell", event.stepType)
        assertEquals("warn", event.level)

        val result = decode<AutoResult>(MessageTypesV2.AUTO_RESULT)
        assertTrue(result.ok)
        assertEquals(4, result.stepsRun)
        assertEquals(1234L, result.durationMs)

        decode<AutoCancelRequest>(MessageTypesV2.AUTO_CANCEL)
        assertEquals("Focus", decode<AutoLog>(MessageTypesV2.AUTO_LOG).items.single().name)
    }

    @Test
    fun `every message the host can send has a mirror here`() {
        // Runs the other tests so `decoded` is populated regardless of order.
        `capability exchange survives the crossing`()
        `clipboard and file transfer decode`()
        `screen stream negotiation decodes every knob`()
        `audio negotiation decodes`()
        `input events decode`()
        `notification mirroring decodes`()
        `machine introspection decodes`()
        `automations decode, including nested steps`()

        val missing = samples.keys - decoded
        assertEquals("v2 messages with no Kotlin mirror", emptySet<String>(), missing)
    }
}
