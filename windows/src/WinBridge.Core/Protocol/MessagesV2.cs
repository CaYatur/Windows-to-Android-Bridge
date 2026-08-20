using System.Text.Json.Serialization;

namespace WinBridge.Core.Protocol;

/// <summary>
/// Message names added in protocol v2. Kept beside the v1 names rather than
/// renumbering anything: an older phone talking to a newer host simply never
/// sends or understands these, and both sides ignore unknown "t" values.
/// </summary>
public static class MessageTypesV2
{
    // capability exchange -----------------------------------------------------
    public const string HostFeatures = "evt.features";
    public const string ClientFeatures = "cl.features";

    // clipboard ---------------------------------------------------------------
    public const string ClipboardSet = "cb.set";
    public const string ClipboardGet = "cb.get";

    // file transfer -----------------------------------------------------------
    public const string XferOffer = "xfer.offer";
    public const string XferAccept = "xfer.accept";
    public const string XferReject = "xfer.reject";
    public const string XferProgress = "xfer.progress";
    public const string XferDone = "xfer.done";
    public const string XferCancel = "xfer.cancel";

    // screen mirroring --------------------------------------------------------
    public const string ScreenList = "screen.list";
    public const string ScreenTargets = "screen.targets";
    public const string StreamStart = "stream.start";
    public const string StreamStop = "stream.stop";
    public const string StreamInfo = "stream.info";
    public const string StreamConfig = "stream.config";
    public const string StreamStats = "stream.stats";

    // audio -------------------------------------------------------------------
    public const string AudioStart = "audio.start";
    public const string AudioStop = "audio.stop";
    public const string AudioInfo = "audio.info";
    public const string AudioDevices = "audio.devices";
    public const string AudioRoute = "audio.route";

    // remote input ------------------------------------------------------------
    public const string InputMouse = "input.mouse";
    public const string InputKey = "input.key";
    public const string InputText = "input.text";
    public const string InputTouch = "input.touch";
    public const string InputGesture = "input.gesture";
    public const string InputNav = "input.nav";
    public const string InputScroll = "input.scroll";

    // automations -------------------------------------------------------------
    public const string AutoList = "auto.list";
    public const string AutoCatalog = "auto.catalog";
    public const string AutoGet = "auto.get";
    public const string AutoDef = "auto.def";
    public const string AutoSave = "auto.save";
    public const string AutoSaved = "auto.saved";
    public const string AutoDelete = "auto.delete";
    public const string AutoRun = "auto.run";
    public const string AutoEvent = "auto.event";
    public const string AutoResult = "auto.result";
    public const string AutoCancel = "auto.cancel";
    public const string AutoLog = "auto.log";

    // notification mirroring --------------------------------------------------
    public const string NotifPost = "notif.post";
    public const string NotifRemove = "notif.remove";
    public const string NotifAction = "notif.action";
    public const string NotifDismiss = "notif.dismiss";
    public const string NotifSync = "notif.sync";
    public const string NotifState = "notif.state";

    // machine introspection and small conveniences ----------------------------
    public const string SysWindows = "sys.windows";
    public const string SysWindowList = "sys.windowlist";
    public const string SysWindow = "sys.window";
    public const string SysProcesses = "sys.processes";
    public const string SysProcessList = "sys.processlist";
    public const string SysProcess = "sys.process";
    public const string SysDescribe = "sys.describe";
    public const string SysDescription = "sys.description";
    public const string SysNotify = "sys.notify";
    public const string SysOpen = "sys.open";
    public const string PhoneRing = "phone.ring";
    public const string PhoneState = "phone.state";
}

// ---------------------------------------------------------------------------
// Capability exchange
// ---------------------------------------------------------------------------

public sealed record ClipboardCaps
{
    [JsonPropertyName("send")] public bool Send { get; init; }
    [JsonPropertyName("receive")] public bool Receive { get; init; }
    [JsonPropertyName("maxBytes")] public int MaxBytes { get; init; } = 256 * 1024;
}

public sealed record FileCaps
{
    [JsonPropertyName("enabled")] public bool Enabled { get; init; }
    [JsonPropertyName("maxChunk")] public int MaxChunk { get; init; } = 64 * 1024;
    [JsonPropertyName("autoAccept")] public bool AutoAccept { get; init; }
}

public sealed record ScreenCaps
{
    /// <summary>This side can originate a screen stream.</summary>
    [JsonPropertyName("send")] public bool Send { get; init; }
    /// <summary>This side can display a screen stream.</summary>
    [JsonPropertyName("receive")] public bool Receive { get; init; }
    [JsonPropertyName("codecs")] public List<string> Codecs { get; init; } = ["jpeg-tiles"];
    [JsonPropertyName("targets")] public int Targets { get; init; }
    /// <summary>False over a carrier that cannot carry it — RFCOMM, in practice.</summary>
    [JsonPropertyName("carrierOk")] public bool CarrierOk { get; init; } = true;
}

public sealed record AudioCaps
{
    [JsonPropertyName("playback")] public bool Playback { get; init; }
    [JsonPropertyName("mic")] public bool Mic { get; init; }
    [JsonPropertyName("formats")] public List<string> Formats { get; init; } = ["pcm_s16le"];
    [JsonPropertyName("carrierOk")] public bool CarrierOk { get; init; } = true;
}

public sealed record InputCaps
{
    /// <summary>This side can send input events to the peer.</summary>
    [JsonPropertyName("send")] public bool Send { get; init; }
    /// <summary>This side can inject received input events locally.</summary>
    [JsonPropertyName("receive")] public bool Receive { get; init; }
    /// <summary>Why injection is unavailable, when it is — accessibility service off, say.</summary>
    [JsonPropertyName("reason")] public string? Reason { get; init; }
}

/// <summary>
/// What this side is willing and able to do right now. Sent after auth by both
/// ends and re-sent whenever a setting changes, so the peer greys out what is
/// off rather than offering a button that silently does nothing — the same
/// principle already applied to power capabilities in v1.
/// </summary>
public sealed record FeatureSet
{
    [JsonPropertyName("t")] public string Type { get; init; } = MessageTypesV2.HostFeatures;
    [JsonPropertyName("v")] public int Version { get; init; } = 2;
    [JsonPropertyName("clipboard")] public ClipboardCaps Clipboard { get; init; } = new();
    [JsonPropertyName("files")] public FileCaps Files { get; init; } = new();
    [JsonPropertyName("screen")] public ScreenCaps Screen { get; init; } = new();
    [JsonPropertyName("audio")] public AudioCaps Audio { get; init; } = new();
    [JsonPropertyName("input")] public InputCaps Input { get; init; } = new();
    [JsonPropertyName("automations")] public bool Automations { get; init; }
    [JsonPropertyName("shell")] public bool Shell { get; init; }
    [JsonPropertyName("notifications")] public bool Notifications { get; init; }
    [JsonPropertyName("describe")] public bool Describe { get; init; }
    [JsonPropertyName("ring")] public bool Ring { get; init; }
}

// ---------------------------------------------------------------------------
// Clipboard
// ---------------------------------------------------------------------------

public sealed record ClipboardMessage
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.ClipboardSet;
    /// <summary>"text", "uri" or "image".</summary>
    [JsonPropertyName("fmt")] public string Format { get; init; } = "text";
    [JsonPropertyName("text")] public string? Text { get; init; }
    [JsonPropertyName("mime")] public string? Mime { get; init; }
    /// <summary>Base64, for image payloads. Text never travels here.</summary>
    [JsonPropertyName("bytes")] public string? Bytes { get; init; }
    /// <summary>SHA-256 prefix, so an echo of what we just sent is recognised and dropped.</summary>
    [JsonPropertyName("hash")] public string? Hash { get; init; }
    [JsonPropertyName("label")] public string? Label { get; init; }
}

public sealed record ClipboardRequest
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.ClipboardGet;
}

// ---------------------------------------------------------------------------
// File transfer
// ---------------------------------------------------------------------------

public sealed record XferOffer
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.XferOffer;
    [JsonPropertyName("id")] public uint Id { get; init; }
    [JsonPropertyName("name")] public string Name { get; init; } = "";
    [JsonPropertyName("size")] public long Size { get; init; }
    [JsonPropertyName("mime")] public string? Mime { get; init; }
    /// <summary>Hex SHA-256 of the whole file when the sender could afford to compute it.</summary>
    [JsonPropertyName("sha256")] public string? Sha256 { get; init; }
    [JsonPropertyName("batch")] public uint Batch { get; init; }
    [JsonPropertyName("batchIndex")] public int BatchIndex { get; init; }
    [JsonPropertyName("batchCount")] public int BatchCount { get; init; } = 1;
    /// <summary>Relative path inside a folder transfer; null for a loose file.</summary>
    [JsonPropertyName("path")] public string? Path { get; init; }
}

public sealed record XferAccept
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.XferAccept;
    [JsonPropertyName("id")] public uint Id { get; init; }
    /// <summary>Byte offset to resume from; 0 for a fresh transfer.</summary>
    [JsonPropertyName("offset")] public long Offset { get; init; }
}

public sealed record XferReject
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.XferReject;
    [JsonPropertyName("id")] public uint Id { get; init; }
    [JsonPropertyName("reason")] public string Reason { get; init; } = "";
}

public sealed record XferProgress
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.XferProgress;
    [JsonPropertyName("id")] public uint Id { get; init; }
    [JsonPropertyName("bytes")] public long Bytes { get; init; }
    [JsonPropertyName("bps")] public long BytesPerSecond { get; init; }
}

public sealed record XferDone
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.XferDone;
    [JsonPropertyName("id")] public uint Id { get; init; }
    [JsonPropertyName("ok")] public bool Ok { get; init; }
    [JsonPropertyName("sha256")] public string? Sha256 { get; init; }
    [JsonPropertyName("error")] public string? Error { get; init; }
    [JsonPropertyName("savedAs")] public string? SavedAs { get; init; }
}

public sealed record XferCancel
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.XferCancel;
    [JsonPropertyName("id")] public uint Id { get; init; }
    [JsonPropertyName("reason")] public string? Reason { get; init; }
}

// ---------------------------------------------------------------------------
// Screen mirroring
// ---------------------------------------------------------------------------

public sealed record ScreenTarget
{
    [JsonPropertyName("id")] public string Id { get; init; } = "";
    [JsonPropertyName("name")] public string Name { get; init; } = "";
    /// <summary>"monitor", "window", or "all" for the whole virtual desktop.</summary>
    [JsonPropertyName("kind")] public string Kind { get; init; } = "monitor";
    [JsonPropertyName("w")] public int Width { get; init; }
    [JsonPropertyName("h")] public int Height { get; init; }
    [JsonPropertyName("primary")] public bool Primary { get; init; }
}

public sealed record ScreenTargets
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.ScreenTargets;
    [JsonPropertyName("items")] public List<ScreenTarget> Items { get; init; } = [];
}

public sealed record ScreenListRequest
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.ScreenList;
}

public sealed record StreamStart
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.StreamStart;
    [JsonPropertyName("stream")] public string Stream { get; init; } = "";
    [JsonPropertyName("codec")] public string Codec { get; init; } = "jpeg-tiles";
    [JsonPropertyName("maxFps")] public int MaxFps { get; init; } = 30;
    /// <summary>1..100. The encoder lowers it under pressure and reports what it used.</summary>
    [JsonPropertyName("quality")] public int Quality { get; init; } = 70;
    /// <summary>Longest edge in pixels; 0 means native.</summary>
    [JsonPropertyName("maxEdge")] public int MaxEdge { get; init; } = 1280;
    [JsonPropertyName("target")] public string? Target { get; init; }
    /// <summary>Start the matching audio stream alongside.</summary>
    [JsonPropertyName("audio")] public bool Audio { get; init; }
    /// <summary>Accept input events for this stream.</summary>
    [JsonPropertyName("interact")] public bool Interact { get; init; }
    [JsonPropertyName("cursor")] public bool Cursor { get; init; } = true;
}

public sealed record StreamStop
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.StreamStop;
    [JsonPropertyName("stream")] public string Stream { get; init; } = "";
}

public sealed record StreamInfo
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.StreamInfo;
    [JsonPropertyName("stream")] public string Stream { get; init; } = "";
    [JsonPropertyName("active")] public bool Active { get; init; }
    [JsonPropertyName("w")] public int Width { get; init; }
    [JsonPropertyName("h")] public int Height { get; init; }
    [JsonPropertyName("tileW")] public int TileWidth { get; init; }
    [JsonPropertyName("tileH")] public int TileHeight { get; init; }
    [JsonPropertyName("cols")] public int Columns { get; init; }
    [JsonPropertyName("rows")] public int Rows { get; init; }
    [JsonPropertyName("codec")] public string Codec { get; init; } = "jpeg-tiles";
    [JsonPropertyName("target")] public string? Target { get; init; }
    [JsonPropertyName("interact")] public bool Interact { get; init; }
    /// <summary>Populated when active is false: why it stopped, or would not start.</summary>
    [JsonPropertyName("reason")] public string? Reason { get; init; }
}

public sealed record StreamConfig
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.StreamConfig;
    [JsonPropertyName("stream")] public string Stream { get; init; } = "";
    [JsonPropertyName("maxFps")] public int? MaxFps { get; init; }
    [JsonPropertyName("quality")] public int? Quality { get; init; }
    [JsonPropertyName("maxEdge")] public int? MaxEdge { get; init; }
    [JsonPropertyName("target")] public string? Target { get; init; }
    [JsonPropertyName("interact")] public bool? Interact { get; init; }
    [JsonPropertyName("cursor")] public bool? Cursor { get; init; }
}

/// <summary>
/// The receiver reporting how the stream is actually going. The sender adapts
/// from this rather than from its own guesses: only the receiver knows how long
/// a frame took to arrive and decode.
/// </summary>
public sealed record StreamStats
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.StreamStats;
    [JsonPropertyName("stream")] public string Stream { get; init; } = "";
    [JsonPropertyName("fps")] public double Fps { get; init; }
    [JsonPropertyName("kbps")] public double Kbps { get; init; }
    [JsonPropertyName("rttMs")] public int RttMs { get; init; }
    [JsonPropertyName("decodeMs")] public double DecodeMs { get; init; }
    [JsonPropertyName("dropped")] public long Dropped { get; init; }
    /// <summary>Age of the newest presented frame. The number the user feels.</summary>
    [JsonPropertyName("latencyMs")] public int LatencyMs { get; init; }
}

// ---------------------------------------------------------------------------
// Audio
// ---------------------------------------------------------------------------

public sealed record AudioStart
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.AudioStart;
    [JsonPropertyName("stream")] public string Stream { get; init; } = "";
    [JsonPropertyName("rate")] public int Rate { get; init; } = 48000;
    [JsonPropertyName("channels")] public int Channels { get; init; } = 2;
    [JsonPropertyName("format")] public string Format { get; init; } = "pcm_s16le";
    [JsonPropertyName("frameMs")] public int FrameMs { get; init; } = 20;
    /// <summary>Endpoint to capture from or render to; null means the default device.</summary>
    [JsonPropertyName("device")] public string? Device { get; init; }
}

public sealed record AudioStop
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.AudioStop;
    [JsonPropertyName("stream")] public string Stream { get; init; } = "";
}

public sealed record AudioInfo
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.AudioInfo;
    [JsonPropertyName("stream")] public string Stream { get; init; } = "";
    [JsonPropertyName("active")] public bool Active { get; init; }
    [JsonPropertyName("rate")] public int Rate { get; init; }
    [JsonPropertyName("channels")] public int Channels { get; init; }
    [JsonPropertyName("format")] public string Format { get; init; } = "pcm_s16le";
    [JsonPropertyName("frameMs")] public int FrameMs { get; init; }
    [JsonPropertyName("device")] public string? Device { get; init; }
    [JsonPropertyName("reason")] public string? Reason { get; init; }
}

public sealed record AudioDevice
{
    [JsonPropertyName("id")] public string Id { get; init; } = "";
    [JsonPropertyName("name")] public string Name { get; init; } = "";
    /// <summary>"render" or "capture".</summary>
    [JsonPropertyName("flow")] public string Flow { get; init; } = "render";
    [JsonPropertyName("default")] public bool IsDefault { get; init; }
}

public sealed record AudioDevices
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.AudioDevices;
    [JsonPropertyName("items")] public List<AudioDevice> Items { get; init; } = [];
}

public sealed record AudioRoute
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.AudioRoute;
    [JsonPropertyName("stream")] public string Stream { get; init; } = "";
    [JsonPropertyName("device")] public string? Device { get; init; }
}

// ---------------------------------------------------------------------------
// Remote input
// ---------------------------------------------------------------------------

/// <summary>
/// Coordinates are normalised 0..1 against the streamed surface, never pixels:
/// the sender is looking at a scaled copy and must not have to know the
/// receiver resolution, DPI, or which monitor the pointer landed on.
/// </summary>
public sealed record InputMouse
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.InputMouse;
    /// <summary>"move", "down", "up", "click", "double" or "wheel".</summary>
    [JsonPropertyName("action")] public string Action { get; init; } = "move";
    [JsonPropertyName("x")] public double X { get; init; }
    [JsonPropertyName("y")] public double Y { get; init; }
    /// <summary>"left", "right", "middle", "x1" or "x2".</summary>
    [JsonPropertyName("button")] public string Button { get; init; } = "left";
    [JsonPropertyName("delta")] public int Delta { get; init; }
    [JsonPropertyName("hdelta")] public int HorizontalDelta { get; init; }
    /// <summary>Relative motion in surface units, for trackpad mode.</summary>
    [JsonPropertyName("dx")] public double Dx { get; init; }
    [JsonPropertyName("dy")] public double Dy { get; init; }
    [JsonPropertyName("relative")] public bool Relative { get; init; }
}

public sealed record InputKey
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.InputKey;
    /// <summary>"down", "up" or "tap".</summary>
    [JsonPropertyName("action")] public string Action { get; init; } = "tap";
    /// <summary>Portable name: "a", "enter", "f5", "volumeup", and so on.</summary>
    [JsonPropertyName("code")] public string Code { get; init; } = "";
    /// <summary>Any of "ctrl", "alt", "shift", "win".</summary>
    [JsonPropertyName("mods")] public List<string> Mods { get; init; } = [];
    [JsonPropertyName("repeat")] public int Repeat { get; init; } = 1;
}

public sealed record InputText
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.InputText;
    [JsonPropertyName("text")] public string Text { get; init; } = "";
}

public sealed record InputTouch
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.InputTouch;
    /// <summary>"down", "move", "up" or "cancel".</summary>
    [JsonPropertyName("action")] public string Action { get; init; } = "down";
    [JsonPropertyName("pointer")] public int Pointer { get; init; }
    [JsonPropertyName("x")] public double X { get; init; }
    [JsonPropertyName("y")] public double Y { get; init; }
    [JsonPropertyName("pressure")] public double Pressure { get; init; } = 1.0;
}

public sealed record GesturePoint
{
    [JsonPropertyName("x")] public double X { get; init; }
    [JsonPropertyName("y")] public double Y { get; init; }
    [JsonPropertyName("t")] public int AtMs { get; init; }
}

public sealed record InputGesture
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.InputGesture;
    /// <summary>"tap", "double", "long", "swipe", "path" or "pinch".</summary>
    [JsonPropertyName("kind")] public string Kind { get; init; } = "tap";
    [JsonPropertyName("points")] public List<GesturePoint> Points { get; init; } = [];
    [JsonPropertyName("durationMs")] public int DurationMs { get; init; } = 60;
    /// <summary>Second finger path, for pinch.</summary>
    [JsonPropertyName("points2")] public List<GesturePoint> Points2 { get; init; } = [];
}

public sealed record InputNav
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.InputNav;
    /// <summary>
    /// "back", "home", "recents", "notifications", "quicksettings", "lock",
    /// "power", "screenshot", "split" or "dismiss".
    /// </summary>
    [JsonPropertyName("action")] public string Action { get; init; } = "";
}

public sealed record InputScroll
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.InputScroll;
    [JsonPropertyName("x")] public double X { get; init; }
    [JsonPropertyName("y")] public double Y { get; init; }
    [JsonPropertyName("dx")] public double Dx { get; init; }
    [JsonPropertyName("dy")] public double Dy { get; init; }
}

// ---------------------------------------------------------------------------
// Notification mirroring
// ---------------------------------------------------------------------------

public sealed record NotifAction
{
    [JsonPropertyName("index")] public int Index { get; init; }
    [JsonPropertyName("title")] public string Title { get; init; } = "";
    [JsonPropertyName("reply")] public bool IsReply { get; init; }
}

public sealed record NotifPost
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.NotifPost;
    [JsonPropertyName("key")] public string Key { get; init; } = "";
    [JsonPropertyName("pkg")] public string Package { get; init; } = "";
    [JsonPropertyName("app")] public string AppName { get; init; } = "";
    [JsonPropertyName("title")] public string? Title { get; init; }
    [JsonPropertyName("text")] public string? Text { get; init; }
    [JsonPropertyName("sub")] public string? SubText { get; init; }
    [JsonPropertyName("big")] public string? BigText { get; init; }
    [JsonPropertyName("when")] public long When { get; init; }
    [JsonPropertyName("ongoing")] public bool Ongoing { get; init; }
    [JsonPropertyName("category")] public string? Category { get; init; }
    [JsonPropertyName("actions")] public List<NotifAction> Actions { get; init; } = [];
    /// <summary>Blob id of the app icon, fetched with req.blob exactly like album art.</summary>
    [JsonPropertyName("iconHash")] public string? IconHash { get; init; }
}

public sealed record NotifRemove
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.NotifRemove;
    [JsonPropertyName("key")] public string Key { get; init; } = "";
}

public sealed record NotifActionCommand
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.NotifAction;
    [JsonPropertyName("key")] public string Key { get; init; } = "";
    [JsonPropertyName("index")] public int Index { get; init; }
    /// <summary>Reply body, when the action carries a RemoteInput.</summary>
    [JsonPropertyName("text")] public string? Text { get; init; }
}

public sealed record NotifDismiss
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.NotifDismiss;
    [JsonPropertyName("key")] public string Key { get; init; } = "";
}

public sealed record NotifSync
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.NotifSync;
}

public sealed record NotifState
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.NotifState;
    [JsonPropertyName("enabled")] public bool Enabled { get; init; }
    [JsonPropertyName("granted")] public bool Granted { get; init; }
    [JsonPropertyName("count")] public int Count { get; init; }
    [JsonPropertyName("reason")] public string? Reason { get; init; }
}

// ---------------------------------------------------------------------------
// Machine introspection and conveniences
// ---------------------------------------------------------------------------

public sealed record WindowInfo
{
    [JsonPropertyName("handle")] public long Handle { get; init; }
    [JsonPropertyName("title")] public string Title { get; init; } = "";
    [JsonPropertyName("process")] public string Process { get; init; } = "";
    [JsonPropertyName("pid")] public int Pid { get; init; }
    [JsonPropertyName("active")] public bool Active { get; init; }
    [JsonPropertyName("minimized")] public bool Minimized { get; init; }
}

public sealed record WindowList
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.SysWindowList;
    [JsonPropertyName("items")] public List<WindowInfo> Items { get; init; } = [];
}

public sealed record WindowsRequest
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.SysWindows;
}

public sealed record WindowCommand
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.SysWindow;
    /// <summary>"focus", "minimize", "maximize", "restore" or "close".</summary>
    [JsonPropertyName("action")] public string Action { get; init; } = "focus";
    [JsonPropertyName("handle")] public long Handle { get; init; }
    /// <summary>Alternative to a handle: match by window title or process name.</summary>
    [JsonPropertyName("match")] public string? Match { get; init; }
}

public sealed record ProcessInfo
{
    [JsonPropertyName("pid")] public int Pid { get; init; }
    [JsonPropertyName("name")] public string Name { get; init; } = "";
    [JsonPropertyName("memMb")] public long MemoryMb { get; init; }
    [JsonPropertyName("cpu")] public double Cpu { get; init; }
}

public sealed record ProcessList
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.SysProcessList;
    [JsonPropertyName("items")] public List<ProcessInfo> Items { get; init; } = [];
}

public sealed record ProcessesRequest
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.SysProcesses;
    [JsonPropertyName("top")] public int Top { get; init; } = 25;
}

public sealed record ProcessCommand
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.SysProcess;
    [JsonPropertyName("action")] public string Action { get; init; } = "kill";
    [JsonPropertyName("pid")] public int Pid { get; init; }
}

public sealed record DescribeRequest
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.SysDescribe;
    [JsonPropertyName("target")] public string? Target { get; init; }
    /// <summary>Also run OCR over the capture, not just report window metadata.</summary>
    [JsonPropertyName("ocr")] public bool Ocr { get; init; } = true;
    /// <summary>Send the screenshot itself as a blob alongside the description.</summary>
    [JsonPropertyName("image")] public bool Image { get; init; }
}

public sealed record Description
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.SysDescription;
    [JsonPropertyName("title")] public string? Title { get; init; }
    [JsonPropertyName("process")] public string? Process { get; init; }
    [JsonPropertyName("text")] public string? Text { get; init; }
    [JsonPropertyName("windows")] public List<string> Windows { get; init; } = [];
    [JsonPropertyName("w")] public int Width { get; init; }
    [JsonPropertyName("h")] public int Height { get; init; }
    [JsonPropertyName("imageHash")] public string? ImageHash { get; init; }
    [JsonPropertyName("reason")] public string? Reason { get; init; }
}

public sealed record SysNotify
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.SysNotify;
    [JsonPropertyName("title")] public string Title { get; init; } = "";
    [JsonPropertyName("text")] public string? Text { get; init; }
    /// <summary>"info", "warn" or "error".</summary>
    [JsonPropertyName("level")] public string Level { get; init; } = "info";
}

public sealed record SysOpen
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.SysOpen;
    /// <summary>A URL, a file path, or a folder.</summary>
    [JsonPropertyName("target")] public string Target { get; init; } = "";
}

public sealed record PhoneRing
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.PhoneRing;
    /// <summary>"start" or "stop".</summary>
    [JsonPropertyName("action")] public string Action { get; init; } = "start";
    [JsonPropertyName("seconds")] public int Seconds { get; init; } = 30;
}

public sealed record PhoneState
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.PhoneState;
    [JsonPropertyName("model")] public string? Model { get; init; }
    [JsonPropertyName("battery")] public int Battery { get; init; }
    [JsonPropertyName("charging")] public bool Charging { get; init; }
    [JsonPropertyName("ringer")] public string? Ringer { get; init; }
    [JsonPropertyName("network")] public string? Network { get; init; }
    [JsonPropertyName("volume")] public int Volume { get; init; }
    [JsonPropertyName("screenOn")] public bool ScreenOn { get; init; }
}
