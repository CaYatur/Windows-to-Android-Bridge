using System.Text.Json;
using System.Text.Json.Serialization;

namespace WinBridge.Core.Protocol;

public static class MessageTypes
{
    // server -> client
    public const string StateHost = "state.host";
    public const string StateMedia = "state.media";
    public const string StateSystem = "state.system";
    public const string StateVolume = "state.volume";
    public const string EventPeer = "evt.peer";
    public const string Error = "error";
    public const string Pong = "pong";

    // client -> server
    public const string Auth = "auth";
    public const string Subscribe = "sub";
    public const string RequestState = "req.state";
    public const string RequestBlob = "req.blob";
    public const string CommandMedia = "cmd.media";
    public const string CommandVolume = "cmd.volume";
    public const string CommandPower = "cmd.power";
    public const string Ping = "ping";
}

public sealed record Hello
{
    [JsonPropertyName("v")] public int Version { get; init; } = 1;
    [JsonPropertyName("deviceId")] public string DeviceId { get; init; } = "";
    [JsonPropertyName("name")] public string Name { get; init; } = "";
    [JsonPropertyName("platform")] public string Platform { get; init; } = "";
    [JsonPropertyName("mode")] public string Mode { get; init; } = "session"; // or "pair"
    [JsonPropertyName("ephPub")] public string EphPub { get; init; } = "";
    [JsonPropertyName("nonce")] public string Nonce { get; init; } = "";
}

public sealed record HelloAck
{
    [JsonPropertyName("v")] public int Version { get; init; } = 1;
    [JsonPropertyName("deviceId")] public string DeviceId { get; init; } = "";
    [JsonPropertyName("name")] public string Name { get; init; } = "";
    [JsonPropertyName("platform")] public string Platform { get; init; } = "windows";
    [JsonPropertyName("ephPub")] public string EphPub { get; init; } = "";
    [JsonPropertyName("nonce")] public string Nonce { get; init; } = "";
    [JsonPropertyName("confirm")] public string Confirm { get; init; } = "";
}

public sealed record PowerCaps
{
    [JsonPropertyName("lock")] public bool Lock { get; init; } = true;
    [JsonPropertyName("sleep")] public bool Sleep { get; init; }
    [JsonPropertyName("hibernate")] public bool Hibernate { get; init; }
    [JsonPropertyName("shutdown")] public bool Shutdown { get; init; } = true;
    [JsonPropertyName("restart")] public bool Restart { get; init; } = true;
    [JsonPropertyName("logoff")] public bool Logoff { get; init; } = true;
    [JsonPropertyName("display_off")] public bool DisplayOff { get; init; } = true;
}

public sealed record HostState
{
    [JsonPropertyName("t")] public string Type => MessageTypes.StateHost;
    [JsonPropertyName("name")] public string Name { get; init; } = "";
    [JsonPropertyName("os")] public string Os { get; init; } = "";
    [JsonPropertyName("uptimeSec")] public long UptimeSec { get; init; }
    [JsonPropertyName("caps")] public PowerCaps Caps { get; init; } = new();
}

public sealed record MediaState
{
    [JsonPropertyName("t")] public string Type => MessageTypes.StateMedia;
    [JsonPropertyName("session")] public string? Session { get; init; }
    [JsonPropertyName("title")] public string? Title { get; init; }
    [JsonPropertyName("artist")] public string? Artist { get; init; }
    [JsonPropertyName("album")] public string? Album { get; init; }
    [JsonPropertyName("appId")] public string? AppId { get; init; }
    [JsonPropertyName("playing")] public bool Playing { get; init; }
    [JsonPropertyName("posMs")] public long PosMs { get; init; }
    [JsonPropertyName("durMs")] public long DurMs { get; init; }
    [JsonPropertyName("canNext")] public bool CanNext { get; init; }
    [JsonPropertyName("canPrev")] public bool CanPrev { get; init; }
    [JsonPropertyName("canSeek")] public bool CanSeek { get; init; }
    [JsonPropertyName("artHash")] public string? ArtHash { get; init; }
}

public sealed record RamInfo
{
    [JsonPropertyName("usedMb")] public long UsedMb { get; init; }
    [JsonPropertyName("totalMb")] public long TotalMb { get; init; }
}

public sealed record GpuInfo
{
    [JsonPropertyName("name")] public string Name { get; init; } = "";
    [JsonPropertyName("pct")] public double Pct { get; init; }
}

public sealed record NetInfo
{
    [JsonPropertyName("upBps")] public long UpBps { get; init; }
    [JsonPropertyName("downBps")] public long DownBps { get; init; }
}

public sealed record DiskInfo
{
    [JsonPropertyName("name")] public string Name { get; init; } = "";
    [JsonPropertyName("usedGb")] public double UsedGb { get; init; }
    [JsonPropertyName("totalGb")] public double TotalGb { get; init; }
}

public sealed record BatteryInfo
{
    [JsonPropertyName("present")] public bool Present { get; init; }
    [JsonPropertyName("pct")] public int Pct { get; init; }
    [JsonPropertyName("charging")] public bool Charging { get; init; }
    /// <summary>normal | low | critical | charging | full | unknown</summary>
    [JsonPropertyName("status")] public string Status { get; init; } = "unknown";
    [JsonPropertyName("minutesLeft")] public int MinutesLeft { get; init; } = -1;
}

public sealed record SystemState
{
    [JsonPropertyName("t")] public string Type => MessageTypes.StateSystem;
    [JsonPropertyName("cpu")] public double Cpu { get; init; }
    [JsonPropertyName("ram")] public RamInfo Ram { get; init; } = new();
    [JsonPropertyName("gpu")] public List<GpuInfo> Gpu { get; init; } = [];
    [JsonPropertyName("net")] public NetInfo Net { get; init; } = new();
    [JsonPropertyName("disk")] public List<DiskInfo> Disk { get; init; } = [];
    [JsonPropertyName("battery")] public BatteryInfo Battery { get; init; } = new();
}

public sealed record VolumeState
{
    [JsonPropertyName("t")] public string Type => MessageTypes.StateVolume;
    [JsonPropertyName("level")] public int Level { get; init; }
    [JsonPropertyName("muted")] public bool Muted { get; init; }
}

public sealed record BtPeer
{
    [JsonPropertyName("mac")] public string Mac { get; init; } = "";
    [JsonPropertyName("uuid")] public string Uuid { get; init; } = "";
}

public sealed record LanPeer
{
    [JsonPropertyName("hosts")] public List<string> Hosts { get; init; } = [];
    [JsonPropertyName("port")] public int Port { get; init; }
}

public sealed record PeerEvent
{
    [JsonPropertyName("t")] public string Type => MessageTypes.EventPeer;
    [JsonPropertyName("bt")] public BtPeer? Bt { get; init; }
    [JsonPropertyName("lan")] public LanPeer? Lan { get; init; }
}

public sealed record ErrorMessage
{
    [JsonPropertyName("t")] public string Type => MessageTypes.Error;
    [JsonPropertyName("code")] public string Code { get; init; } = "";
    [JsonPropertyName("detail")] public string? Detail { get; init; }
}

public sealed record SubscribeMessage
{
    [JsonPropertyName("t")] public string Type => MessageTypes.Subscribe;
    /// <summary>Stream name to interval in ms. 0 = on change only, -1 = unsubscribe.</summary>
    [JsonPropertyName("rates")] public Dictionary<string, int> Rates { get; init; } = [];
}

public sealed record BlobRequest
{
    [JsonPropertyName("t")] public string Type => MessageTypes.RequestBlob;
    [JsonPropertyName("id")] public string Id { get; init; } = "";
}

public sealed record MediaCommand
{
    [JsonPropertyName("t")] public string Type => MessageTypes.CommandMedia;
    [JsonPropertyName("action")] public string Action { get; init; } = "";
    [JsonPropertyName("posMs")] public long PosMs { get; init; }
}

public sealed record VolumeCommand
{
    [JsonPropertyName("t")] public string Type => MessageTypes.CommandVolume;
    [JsonPropertyName("action")] public string Action { get; init; } = "";
    [JsonPropertyName("level")] public int Level { get; init; }
}

public sealed record PowerCommand
{
    [JsonPropertyName("t")] public string Type => MessageTypes.CommandPower;
    [JsonPropertyName("action")] public string Action { get; init; } = "";
    [JsonPropertyName("delaySec")] public int DelaySec { get; init; }
}

public sealed record PingMessage
{
    [JsonPropertyName("t")] public string Type => MessageTypes.Ping;
    [JsonPropertyName("echo")] public long Echo { get; init; }
}

public sealed record PongMessage
{
    [JsonPropertyName("t")] public string Type => MessageTypes.Pong;
    [JsonPropertyName("echo")] public long Echo { get; init; }
}

public static class Json
{
    public static readonly JsonSerializerOptions Options = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = null,
    };

    public static byte[] Serialize<T>(T value) =>
        JsonSerializer.SerializeToUtf8Bytes(value, Options);

    public static T Deserialize<T>(ReadOnlySpan<byte> utf8) =>
        JsonSerializer.Deserialize<T>(utf8, Options)
        ?? throw new ProtocolException($"could not deserialize {typeof(T).Name}");

    /// <summary>Reads just the "t" discriminator so the dispatcher can route.</summary>
    public static string ReadType(ReadOnlySpan<byte> utf8)
    {
        var reader = new Utf8JsonReader(utf8);
        while (reader.Read())
        {
            if (reader.TokenType != JsonTokenType.PropertyName) continue;
            if (!reader.ValueTextEquals("t")) continue;
            reader.Read();
            return reader.GetString() ?? "";
        }
        throw new ProtocolException("message has no \"t\" field");
    }
}
