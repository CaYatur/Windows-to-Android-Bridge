using System.Diagnostics;
using WinBridge.App.Providers;
using WinBridge.Core.Protocol;

namespace WinBridge.App.Server;

/// <summary>
/// One connected phone.
///
/// Two loops run concurrently: a receive loop handling commands, and a push loop
/// sending state at whatever rate the client asked for. The client owns the
/// rate — the foreground UI wants 1 Hz, a widget wants 30 s — because only the
/// phone knows who is actually looking.
/// </summary>
public sealed class ClientSession(
    ProtocolSession session,
    string carrier,
    MediaProvider media,
    SystemMetricsProvider metrics,
    VolumeProvider volume,
    PowerProvider power,
    Func<PeerEvent> describePeer)
{
    private readonly Dictionary<string, int> _rates = new()
    {
        ["media"] = 0,      // 0 == push on change only
        ["system"] = 2000,
        ["volume"] = 0,
    };

    private readonly SemaphoreSlim _rateLock = new(1, 1);
    private DateTime _lastSystemPush = DateTime.MinValue;
    private DateTime _lastMediaPush = DateTime.MinValue;

    private MediaState? _lastMedia;
    private VolumeState? _lastVolume;
    private volatile bool _mediaDirty = true;

    public string PeerName => session.PeerName;
    public string PeerDeviceId => session.PeerDeviceId;
    public string Carrier => carrier;
    public DateTimeOffset ConnectedAt { get; } = DateTimeOffset.UtcNow;

    public event Action<string>? Log;

    public async Task RunAsync(CancellationToken ct)
    {
        void OnMediaChanged() => _mediaDirty = true;
        media.Changed += OnMediaChanged;

        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct);
        try
        {
            await session.SendJsonAsync(HostSnapshot(), linked.Token);
            await session.SendJsonAsync(describePeer(), linked.Token);

            var receive = ReceiveLoopAsync(linked.Token);
            var push = PushLoopAsync(linked.Token);

            await Task.WhenAny(receive, push);
            linked.Cancel();
            await Task.WhenAll(
                receive.ContinueWith(_ => { }, TaskScheduler.Default),
                push.ContinueWith(_ => { }, TaskScheduler.Default));
        }
        finally
        {
            media.Changed -= OnMediaChanged;
        }
    }

    private HostState HostSnapshot() => new()
    {
        Name = Environment.MachineName,
        Os = Environment.OSVersion.VersionString,
        UptimeSec = Environment.TickCount64 / 1000,
        Caps = power.Caps,
    };

    private async Task ReceiveLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            InboundMessage message;
            try { message = await session.ReceiveAsync(ct); }
            catch (OperationCanceledException) { return; }
            catch (Exception ex) { Log?.Invoke($"receive ended: {ex.Message}"); return; }

            try { await HandleAsync(message, ct); }
            catch (Exception ex) { Log?.Invoke($"handling {message.JsonType} failed: {ex.Message}"); }
        }
    }

    private async Task HandleAsync(InboundMessage message, CancellationToken ct)
    {
        switch (message.JsonType)
        {
            case MessageTypes.Subscribe:
            {
                var sub = message.As<SubscribeMessage>();
                await _rateLock.WaitAsync(ct);
                try
                {
                    foreach (var (stream, ms) in sub.Rates) _rates[stream] = ms;
                }
                finally { _rateLock.Release(); }

                // A rate change usually means a screen just opened; answer now
                // rather than making the user watch a stale value tick over.
                await PushSystemAsync(ct);
                await PushMediaAsync(ct, force: true);
                await PushVolumeAsync(ct, force: true);
                break;
            }

            case MessageTypes.RequestState:
                await session.SendJsonAsync(HostSnapshot(), ct);
                await PushSystemAsync(ct);
                await PushMediaAsync(ct, force: true);
                await PushVolumeAsync(ct, force: true);
                break;

            case MessageTypes.RequestBlob:
            {
                var request = message.As<BlobRequest>();
                if (request.Id.StartsWith("art:", StringComparison.Ordinal))
                {
                    byte[]? art = media.GetArt(request.Id[4..]);
                    if (art is not null) await session.SendBlobAsync(request.Id, art, ct);
                    else await SendErrorAsync("blob_not_found", request.Id, ct);
                }
                break;
            }

            case MessageTypes.CommandMedia:
            {
                var cmd = message.As<MediaCommand>();
                bool ok = await media.ControlAsync(cmd.Action, cmd.PosMs);
                if (!ok) await SendErrorAsync("media_command_failed", cmd.Action, ct);
                _mediaDirty = true;
                break;
            }

            case MessageTypes.CommandVolume:
            {
                var cmd = message.As<VolumeCommand>();
                bool ok = cmd.Action switch
                {
                    "set" => volume.SetLevel(cmd.Level),
                    "mute" => volume.SetMuted(true),
                    "unmute" => volume.SetMuted(false),
                    _ => false,
                };
                if (!ok) await SendErrorAsync("volume_command_failed", cmd.Action, ct);
                await PushVolumeAsync(ct, force: true);
                break;
            }

            case MessageTypes.CommandPower:
            {
                var cmd = message.As<PowerCommand>();
                if (!IsAllowed(cmd.Action))
                {
                    await SendErrorAsync("power_unsupported", cmd.Action, ct);
                    break;
                }
                Log?.Invoke($"{session.PeerName} requested {cmd.Action}");
                if (!power.Execute(cmd.Action, cmd.DelaySec, out string? error))
                    await SendErrorAsync("power_command_failed", error ?? cmd.Action, ct);
                break;
            }

            case MessageTypes.Ping:
                await session.SendJsonAsync(new PongMessage { Echo = message.As<PingMessage>().Echo }, ct);
                break;
        }
    }

    private bool IsAllowed(string action) => action switch
    {
        "lock" => power.Caps.Lock,
        "sleep" => power.Caps.Sleep,
        "hibernate" => power.Caps.Hibernate,
        "shutdown" => power.Caps.Shutdown,
        "restart" => power.Caps.Restart,
        "logoff" => power.Caps.Logoff,
        "display_off" => power.Caps.DisplayOff,
        _ => false,
    };

    private Task SendErrorAsync(string code, string? detail, CancellationToken ct) =>
        session.SendJsonAsync(new ErrorMessage { Code = code, Detail = detail }, ct);

    private async Task PushLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await PushSystemAsync(ct);
                await PushMediaAsync(ct);
                await PushVolumeAsync(ct);
            }
            catch (OperationCanceledException) { return; }
            catch (Exception ex) { Log?.Invoke($"push failed: {ex.Message}"); return; }

            // 250 ms is the scheduling granularity, not the send rate: each
            // stream still only goes out when its own interval has elapsed.
            try { await Task.Delay(250, ct); }
            catch (OperationCanceledException) { return; }
        }
    }

    private async Task PushSystemAsync(CancellationToken ct)
    {
        int rate = Rate("system");
        if (rate < 0) return;
        if (rate > 0 && (DateTime.UtcNow - _lastSystemPush).TotalMilliseconds < rate) return;

        _lastSystemPush = DateTime.UtcNow;
        await session.SendJsonAsync(metrics.Sample(), ct);
    }

    private async Task PushMediaAsync(CancellationToken ct, bool force = false)
    {
        int rate = Rate("media");
        if (rate < 0) return;

        bool timeElapsed = rate > 0 && (DateTime.UtcNow - _lastMediaPush).TotalMilliseconds >= rate;
        if (!force && !_mediaDirty && !timeElapsed) return;

        _mediaDirty = false;
        _lastMediaPush = DateTime.UtcNow;

        var state = await media.ReadAsync();

        // Position advances on its own; resending an otherwise identical state
        // every tick would waste an RFCOMM round trip for nothing.
        if (!force && _lastMedia is not null && SameExceptPosition(_lastMedia, state)) return;

        _lastMedia = state;
        await session.SendJsonAsync(state, ct);
    }

    private static bool SameExceptPosition(MediaState a, MediaState b) =>
        a.Title == b.Title && a.Artist == b.Artist && a.Album == b.Album &&
        a.Playing == b.Playing && a.ArtHash == b.ArtHash && a.AppId == b.AppId &&
        a.CanNext == b.CanNext && a.CanPrev == b.CanPrev;

    private async Task PushVolumeAsync(CancellationToken ct, bool force = false)
    {
        if (Rate("volume") < 0) return;

        var state = volume.Read();
        if (!force && _lastVolume is not null &&
            _lastVolume.Level == state.Level && _lastVolume.Muted == state.Muted)
            return;

        _lastVolume = state;
        await session.SendJsonAsync(state, ct);
    }

    private int Rate(string stream)
    {
        _rateLock.Wait();
        try { return _rates.TryGetValue(stream, out int ms) ? ms : 0; }
        finally { _rateLock.Release(); }
    }
}
