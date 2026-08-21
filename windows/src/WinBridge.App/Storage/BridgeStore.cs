using System.IO;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace WinBridge.App.Storage;

public sealed record PairedDevice
{
    [JsonPropertyName("deviceId")] public string DeviceId { get; init; } = "";
    [JsonPropertyName("name")] public string Name { get; init; } = "";
    [JsonPropertyName("platform")] public string Platform { get; init; } = "";
    /// <summary>Base64. Encrypted at rest via DPAPI; see <see cref="BridgeStore"/>.</summary>
    [JsonPropertyName("psk")] public string Psk { get; init; } = "";
    [JsonPropertyName("btMac")] public string? BtMac { get; init; }
    [JsonPropertyName("lastSeen")] public DateTimeOffset LastSeen { get; init; }

    /// <summary>
    /// Trust is per device, not global. A phone the user carries and a tablet
    /// left on a desk should not get the same authority just because both were
    /// once paired, so the automation trust decision is stored here.
    /// </summary>
    [JsonPropertyName("trusted")] public bool Trusted { get; init; }

    /// <summary>Feature names this device is denied even when the global switch is on.</summary>
    [JsonPropertyName("denied")] public List<string> Denied { get; init; } = [];
}

public sealed record ClipboardSettings
{
    /// <summary>Push this machine clipboard to the phone. Off until asked for.</summary>
    [JsonPropertyName("toPhone")] public bool ToPhone { get; init; }
    /// <summary>Apply the phone clipboard here. Off until asked for.</summary>
    [JsonPropertyName("fromPhone")] public bool FromPhone { get; init; }
    [JsonPropertyName("maxBytes")] public int MaxBytes { get; init; } = 256 * 1024;
    /// <summary>Mirror copied images as well as text.</summary>
    [JsonPropertyName("images")] public bool Images { get; init; }
    /// <summary>Show a tray toast when a clipboard arrives, so it is never silent.</summary>
    [JsonPropertyName("notify")] public bool Notify { get; init; } = true;
}

public sealed record FileSettings
{
    [JsonPropertyName("enabled")] public bool Enabled { get; init; } = true;
    [JsonPropertyName("folder")] public string? Folder { get; init; }
    /// <summary>Accept without asking, up to <see cref="AutoAcceptMaxMb"/>.</summary>
    [JsonPropertyName("autoAccept")] public bool AutoAccept { get; init; }
    [JsonPropertyName("autoAcceptMaxMb")] public int AutoAcceptMaxMb { get; init; } = 64;
    [JsonPropertyName("openFolderWhenDone")] public bool OpenFolderWhenDone { get; init; }
    /// <summary>Register the Explorer right-click entry for sending to the phone.</summary>
    [JsonPropertyName("shellMenu")] public bool ShellMenu { get; init; } = true;
    [JsonPropertyName("hotkey")] public string? Hotkey { get; init; } = "Ctrl+Alt+S";
}

public sealed record ScreenSettings
{
    /// <summary>Let a paired phone view this screen.</summary>
    [JsonPropertyName("share")] public bool Share { get; init; } = true;
    /// <summary>Let a paired phone drive the mouse and keyboard while viewing.</summary>
    [JsonPropertyName("interact")] public bool Interact { get; init; }
    [JsonPropertyName("audio")] public bool Audio { get; init; } = true;
    [JsonPropertyName("quality")] public int Quality { get; init; } = 70;
    [JsonPropertyName("maxFps")] public int MaxFps { get; init; } = 30;
    [JsonPropertyName("maxEdge")] public int MaxEdge { get; init; } = 1280;
    /// <summary>
    /// RFCOMM tops out around a megabit; a screen stream over it is not slow,
    /// it is unusable. Refusing rather than degrading keeps the failure honest.
    /// </summary>
    [JsonPropertyName("lanOnly")] public bool LanOnly { get; init; } = true;
    /// <summary>Show the phone screen in a desktop window.</summary>
    [JsonPropertyName("viewPhone")] public bool ViewPhone { get; init; } = true;
    [JsonPropertyName("cursor")] public bool Cursor { get; init; } = true;
}

public sealed record AudioSettings
{
    /// <summary>Send what this machine is playing to the phone.</summary>
    [JsonPropertyName("toPhone")] public bool ToPhone { get; init; }
    /// <summary>Play what the phone is playing through this machine.</summary>
    [JsonPropertyName("fromPhone")] public bool FromPhone { get; init; }
    /// <summary>Send this machine microphone to the phone.</summary>
    [JsonPropertyName("micToPhone")] public bool MicToPhone { get; init; }
    /// <summary>Receive the phone microphone here.</summary>
    [JsonPropertyName("micFromPhone")] public bool MicFromPhone { get; init; }
    [JsonPropertyName("rate")] public int Rate { get; init; } = 48000;
    [JsonPropertyName("channels")] public int Channels { get; init; } = 2;
    [JsonPropertyName("frameMs")] public int FrameMs { get; init; } = 20;
    /// <summary>Endpoint to capture loopback from; null means the default output.</summary>
    [JsonPropertyName("loopbackDevice")] public string? LoopbackDevice { get; init; }
    /// <summary>Endpoint to capture the local microphone from.</summary>
    [JsonPropertyName("captureDevice")] public string? CaptureDevice { get; init; }
    /// <summary>
    /// Where phone audio is played. Point this at a virtual cable input and the
    /// phone microphone becomes selectable as a recording device in other apps —
    /// which is as close to a virtual audio device as anything that does not
    /// ship a signed kernel driver can get.
    /// </summary>
    [JsonPropertyName("renderDevice")] public string? RenderDevice { get; init; }
    [JsonPropertyName("micRenderDevice")] public string? MicRenderDevice { get; init; }
    [JsonPropertyName("lanOnly")] public bool LanOnly { get; init; } = true;
}

public sealed record AutomationSettings
{
    [JsonPropertyName("enabled")] public bool Enabled { get; init; } = true;
    /// <summary>Let the phone create and edit automations, subject to approval.</summary>
    [JsonPropertyName("authoring")] public bool Authoring { get; init; } = true;
    /// <summary>
    /// Shell steps are off until the user turns them on here, in front of the
    /// warning. Nothing a phone sends can flip this.
    /// </summary>
    [JsonPropertyName("shell")] public bool Shell { get; init; }
    /// <summary>"strict" — confirm every run; "trusted" — allowlisted commands run unattended.</summary>
    [JsonPropertyName("trustMode")] public string TrustMode { get; init; } = "strict";
    /// <summary>Command names or paths that a trusted device may run without a prompt.</summary>
    [JsonPropertyName("allowlist")] public List<string> Allowlist { get; init; } = [];
    [JsonPropertyName("allowElevated")] public bool AllowElevated { get; init; }
    [JsonPropertyName("allowNetwork")] public bool AllowNetwork { get; init; }
    [JsonPropertyName("allowFileWrite")] public bool AllowFileWrite { get; init; }
    [JsonPropertyName("maxRuntimeMs")] public int MaxRuntimeMs { get; init; } = 120_000;
    [JsonPropertyName("maxSteps")] public int MaxSteps { get; init; } = 500;
    [JsonPropertyName("maxLoopIterations")] public int MaxLoopIterations { get; init; } = 1000;
    [JsonPropertyName("maxOutputBytes")] public int MaxOutputBytes { get; init; } = 64 * 1024;
    /// <summary>Refuse everything at once, without unpicking individual settings.</summary>
    [JsonPropertyName("panicStop")] public bool PanicStop { get; init; }
}

public sealed record NotificationSettings
{
    /// <summary>Off by default: this one reads every notification on the phone.</summary>
    [JsonPropertyName("enabled")] public bool Enabled { get; init; }
    [JsonPropertyName("showToasts")] public bool ShowToasts { get; init; } = true;
    [JsonPropertyName("allowReply")] public bool AllowReply { get; init; } = true;
    [JsonPropertyName("skipOngoing")] public bool SkipOngoing { get; init; } = true;
    [JsonPropertyName("blocked")] public List<string> BlockedPackages { get; init; } = [];
}

public sealed record InputSettings
{
    /// <summary>Accept mouse, keyboard and text events from the phone.</summary>
    [JsonPropertyName("accept")] public bool Accept { get; init; }
    /// <summary>Ignore remote input while the workstation is locked.</summary>
    [JsonPropertyName("requireUnlocked")] public bool RequireUnlocked { get; init; } = true;
}

public sealed record PresenceSettings
{
    /// <summary>Lock this machine when the paired phone goes away.</summary>
    [JsonPropertyName("lockOnAway")] public bool LockOnAway { get; init; }
    [JsonPropertyName("lockDelaySec")] public int LockDelaySec { get; init; } = 30;
    /// <summary>Warn when the phone battery drops below this. 0 disables it.</summary>
    [JsonPropertyName("lowBatteryPct")] public int LowBatteryPct { get; init; } = 15;
}

public sealed record BridgeSettings
{
    /// <summary>
    /// Bumped when a stored default has to change for existing installs rather
    /// than only for new ones. Without it a default flipped in code silently
    /// does nothing for everybody who already ran the app.
    /// </summary>
    [JsonPropertyName("schema")] public int Schema { get; init; }

    [JsonPropertyName("deviceId")] public string DeviceId { get; init; } = Guid.NewGuid().ToString();
    [JsonPropertyName("tcpPort")] public int TcpPort { get; init; } = 8737;
    [JsonPropertyName("discoveryPort")] public int DiscoveryPort { get; init; } = 8738;

    /// <summary>
    /// Off by default from 0.2.0. Bluetooth is the right carrier for presence
    /// and control, but it cannot carry mirroring, audio or a file of any size,
    /// and leaving a radio listening for a link most of these features cannot
    /// use is not a good default.
    /// </summary>
    [JsonPropertyName("bluetoothEnabled")] public bool BluetoothEnabled { get; init; }

    [JsonPropertyName("lanEnabled")] public bool LanEnabled { get; init; } = true;
    [JsonPropertyName("preferBluetooth")] public bool PreferBluetooth { get; init; }
    [JsonPropertyName("allowRemotePairing")] public bool AllowRemotePairing { get; init; }
    [JsonPropertyName("startWithWindows")] public bool StartWithWindows { get; init; } = true;
    [JsonPropertyName("firstRunDone")] public bool FirstRunDone { get; init; }
    [JsonPropertyName("language")] public string Language { get; init; } = "auto";
    [JsonPropertyName("devices")] public List<PairedDevice> Devices { get; init; } = [];

    [JsonPropertyName("clipboard")] public ClipboardSettings Clipboard { get; init; } = new();
    [JsonPropertyName("files")] public FileSettings Files { get; init; } = new();
    [JsonPropertyName("screen")] public ScreenSettings Screen { get; init; } = new();
    [JsonPropertyName("audio")] public AudioSettings Audio { get; init; } = new();
    [JsonPropertyName("automation")] public AutomationSettings Automation { get; init; } = new();
    [JsonPropertyName("notifications")] public NotificationSettings Notifications { get; init; } = new();
    [JsonPropertyName("input")] public InputSettings Input { get; init; } = new();
    [JsonPropertyName("presence")] public PresenceSettings Presence { get; init; } = new();

    public const int CurrentSchema = 2;
}

/// <summary>
/// Settings and pairing keys on disk.
///
/// Pairing keys are wrapped with DPAPI (CurrentUser scope) before being written,
/// so a copy of the JSON taken off the machine — a stray backup, a synced
/// folder — does not hand over the keys that authenticate the phone.
/// </summary>
public sealed class BridgeStore
{
    private static readonly byte[] DpapiEntropy = "winbridge/v1/psk"u8.ToArray();

    private readonly string _path;
    private readonly Lock _gate = new();
    private BridgeSettings _settings;

    public static string DefaultDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "WinBridge");

    public BridgeStore(string? path = null)
    {
        _path = path ?? Path.Combine(DefaultDirectory, "settings.json");
        _settings = Load();
    }

    public BridgeSettings Settings
    {
        get { lock (_gate) return _settings; }
    }

    public void Update(Func<BridgeSettings, BridgeSettings> mutate)
    {
        lock (_gate)
        {
            _settings = mutate(_settings);
            Save(_settings);
        }
    }

    public byte[]? ResolvePsk(string deviceId)
    {
        lock (_gate)
        {
            var device = _settings.Devices.FirstOrDefault(d => d.DeviceId == deviceId);
            if (device is null) return null;
            try { return Unprotect(Convert.FromBase64String(device.Psk)); }
            catch { return null; }
        }
    }

    public void SavePairing(string deviceId, string name, string platform, byte[] psk, string? btMac = null)
    {
        Update(current =>
        {
            var devices = current.Devices.Where(d => d.DeviceId != deviceId).ToList();
            devices.Add(new PairedDevice
            {
                DeviceId = deviceId,
                Name = name,
                Platform = platform,
                Psk = Convert.ToBase64String(Protect(psk)),
                BtMac = btMac,
                LastSeen = DateTimeOffset.UtcNow,
            });
            return current with { Devices = devices };
        });
    }

    public void ForgetDevice(string deviceId) =>
        Update(current => current with
        {
            Devices = current.Devices.Where(d => d.DeviceId != deviceId).ToList(),
        });

    public void TouchDevice(string deviceId, string? btMac = null) =>
        Update(current => current with
        {
            Devices = current.Devices
                .Select(d => d.DeviceId == deviceId
                    ? d with { LastSeen = DateTimeOffset.UtcNow, BtMac = btMac ?? d.BtMac }
                    : d)
                .ToList(),
        });

    private BridgeSettings Load()
    {
        try
        {
            if (File.Exists(_path))
            {
                var loaded = JsonSerializer.Deserialize<BridgeSettings>(File.ReadAllText(_path));
                if (loaded is not null) return Migrate(loaded);
            }
        }
        catch
        {
            // A corrupt settings file must not stop the app from starting; the
            // user re-pairs, which is recoverable, whereas a crash loop is not.
        }
        var fresh = new BridgeSettings { Schema = BridgeSettings.CurrentSchema };
        Save(fresh);
        return fresh;
    }

    /// <summary>
    /// Applies default changes to installs that already have a settings file.
    /// A default changed only in code reaches nobody who has run the app before,
    /// which is the silent half of a change like turning Bluetooth off.
    /// </summary>
    private BridgeSettings Migrate(BridgeSettings loaded)
    {
        if (loaded.Schema >= BridgeSettings.CurrentSchema) return loaded;

        var migrated = loaded;

        if (loaded.Schema < 2)
        {
            // 0.2.0: Bluetooth becomes opt-in. It cannot carry mirroring, audio
            // or files, so it is now the exception rather than the default.
            migrated = migrated with { BluetoothEnabled = false, PreferBluetooth = false };
            Migrations.Add("bluetooth turned off (0.2.0 default change; re-enable in Settings)");
        }

        migrated = migrated with { Schema = BridgeSettings.CurrentSchema };
        Save(migrated);
        return migrated;
    }

    /// <summary>Human-readable notes about what the migration changed, for the log and the UI.</summary>
    public List<string> Migrations { get; } = [];

    /// <summary>Where received files land unless the user picked somewhere else.</summary>
    public string DownloadFolder
    {
        get
        {
            string? configured = Settings.Files.Folder;
            if (!string.IsNullOrWhiteSpace(configured)) return configured;
            return Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                "Downloads", "WinBridge");
        }
    }

    public bool IsTrusted(string deviceId)
    {
        lock (_gate)
            return _settings.Devices.FirstOrDefault(d => d.DeviceId == deviceId)?.Trusted ?? false;
    }

    public void SetTrusted(string deviceId, bool trusted) =>
        Update(current => current with
        {
            Devices = current.Devices
                .Select(d => d.DeviceId == deviceId ? d with { Trusted = trusted } : d)
                .ToList(),
        });

    private void Save(BridgeSettings settings)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            var json = JsonSerializer.Serialize(settings, new JsonSerializerOptions { WriteIndented = true });

            // Write-then-replace so a crash mid-write cannot leave a truncated file.
            var temp = _path + ".tmp";
            File.WriteAllText(temp, json);
            File.Move(temp, _path, overwrite: true);
        }
        catch { /* read-only profile or disk full; the app still works this session */ }
    }

    private static byte[] Protect(byte[] data) =>
        ProtectedData.Protect(data, DpapiEntropy, DataProtectionScope.CurrentUser);

    private static byte[] Unprotect(byte[] data) =>
        ProtectedData.Unprotect(data, DpapiEntropy, DataProtectionScope.CurrentUser);
}
