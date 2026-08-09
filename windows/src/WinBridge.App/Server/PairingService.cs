using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using WinBridge.Core.Protocol;

namespace WinBridge.App.Server;

public enum PairingMethod { Qr, Pin }

public sealed record PairingOffer(PairingMethod Method, string QrPayload, string Pin, DateTimeOffset ExpiresAt);

/// <summary>
/// The pairing window.
///
/// Everything here is about keeping the window small: it only opens when the
/// user asks from the tray, it closes after 60 seconds, it dies after five bad
/// attempts, and while open it still refuses connections from outside the local
/// network unless the user has deliberately allowed remote pairing. The LAN
/// listener is designed to be portforwardable, so "pairing is open" must never
/// be a state the machine can drift into on its own.
/// </summary>
public sealed class PairingService
{
    public static readonly TimeSpan Window = TimeSpan.FromSeconds(60);
    private const int MaxAttempts = 5;

    private readonly Lock _gate = new();

    private byte[]? _offeredPsk;
    private string? _pin;
    private PairingMethod _method;
    private DateTimeOffset _expiresAt;
    private int _attempts;

    public event Action? Changed;

    public bool IsOpen
    {
        get { lock (_gate) return _offeredPsk is not null && DateTimeOffset.UtcNow < _expiresAt; }
    }

    public DateTimeOffset ExpiresAt
    {
        get { lock (_gate) return _expiresAt; }
    }

    public PairingOffer Open(
        PairingMethod method,
        string deviceId,
        string hostName,
        IReadOnlyList<string> lanHosts,
        int port,
        string? btMac)
    {
        lock (_gate)
        {
            _method = method;
            _offeredPsk = RandomNumberGenerator.GetBytes(CryptoBox.PskLength);
            _pin = method == PairingMethod.Pin ? RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6") : "";
            _expiresAt = DateTimeOffset.UtcNow + Window;
            _attempts = 0;

            var payload = new QrPayload
            {
                Psk = Convert.ToBase64String(_offeredPsk),
                DeviceId = deviceId,
                Name = hostName,
                Lan = new QrLan { Hosts = [.. lanHosts], Port = port },
                Bt = btMac is null ? null : new QrBt { Mac = btMac, Uuid = Carriers.BluetoothCarrier.ServiceUuid.ToString() },
            };

            var offer = new PairingOffer(
                method,
                JsonSerializer.Serialize(payload, JsonOptions),
                _pin ?? "",
                _expiresAt);

            Changed?.Invoke();
            return offer;
        }
    }

    public void Close()
    {
        lock (_gate)
        {
            if (_offeredPsk is not null) CryptographicOperations.ZeroMemory(_offeredPsk);
            _offeredPsk = null;
            _pin = null;
            _expiresAt = DateTimeOffset.MinValue;
        }
        Changed?.Invoke();
    }

    /// <summary>
    /// The key to try for a HELLO that arrived while pairing is open. QR pairing
    /// presents the real key straight away; PIN pairing authenticates with the
    /// digits and receives the real key afterwards.
    /// </summary>
    public byte[]? OfferedKey(Hello hello)
    {
        lock (_gate)
        {
            if (_offeredPsk is null || DateTimeOffset.UtcNow >= _expiresAt) return null;
            if (_attempts >= MaxAttempts) return null;
            _attempts++;

            return _method == PairingMethod.Qr
                ? _offeredPsk
                : Encoding.UTF8.GetBytes(_pin!);
        }
    }

    /// <summary>The key the freshly paired device should store from here on.</summary>
    public byte[]? RealKey()
    {
        lock (_gate) return _offeredPsk?.ToArray();
    }

    public bool IsPinMethod
    {
        get { lock (_gate) return _method == PairingMethod.Pin; }
    }

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };
}

public sealed record QrPayload
{
    [JsonPropertyName("v")] public int Version { get; init; } = 1;
    [JsonPropertyName("psk")] public string Psk { get; init; } = "";
    [JsonPropertyName("id")] public string DeviceId { get; init; } = "";
    [JsonPropertyName("name")] public string Name { get; init; } = "";
    [JsonPropertyName("lan")] public QrLan? Lan { get; init; }
    [JsonPropertyName("bt")] public QrBt? Bt { get; init; }
}

public sealed record QrLan
{
    [JsonPropertyName("hosts")] public List<string> Hosts { get; init; } = [];
    [JsonPropertyName("port")] public int Port { get; init; }
}

public sealed record QrBt
{
    [JsonPropertyName("mac")] public string Mac { get; init; } = "";
    [JsonPropertyName("uuid")] public string Uuid { get; init; } = "";
}

public sealed record PairCompleteMessage
{
    [JsonPropertyName("t")] public string Type => "pair.complete";
    [JsonPropertyName("psk")] public string Psk { get; init; } = "";
}
