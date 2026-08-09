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
}

public sealed record BridgeSettings
{
    [JsonPropertyName("deviceId")] public string DeviceId { get; init; } = Guid.NewGuid().ToString();
    [JsonPropertyName("tcpPort")] public int TcpPort { get; init; } = 8737;
    [JsonPropertyName("discoveryPort")] public int DiscoveryPort { get; init; } = 8738;
    [JsonPropertyName("bluetoothEnabled")] public bool BluetoothEnabled { get; init; } = true;
    [JsonPropertyName("lanEnabled")] public bool LanEnabled { get; init; } = true;
    [JsonPropertyName("preferBluetooth")] public bool PreferBluetooth { get; init; } = true;
    [JsonPropertyName("allowRemotePairing")] public bool AllowRemotePairing { get; init; }
    [JsonPropertyName("startWithWindows")] public bool StartWithWindows { get; init; } = true;
    [JsonPropertyName("firstRunDone")] public bool FirstRunDone { get; init; }
    [JsonPropertyName("language")] public string Language { get; init; } = "auto";
    [JsonPropertyName("devices")] public List<PairedDevice> Devices { get; init; } = [];
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
                return JsonSerializer.Deserialize<BridgeSettings>(File.ReadAllText(_path))
                       ?? new BridgeSettings();
        }
        catch
        {
            // A corrupt settings file must not stop the app from starting; the
            // user re-pairs, which is recoverable, whereas a crash loop is not.
        }
        var fresh = new BridgeSettings();
        Save(fresh);
        return fresh;
    }

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
