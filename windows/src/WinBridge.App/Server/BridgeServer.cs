using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using WinBridge.App.Carriers;
using WinBridge.App.Providers;
using WinBridge.App.Storage;
using WinBridge.Core.Protocol;

namespace WinBridge.App.Server;

/// <summary>
/// Owns the carriers, the providers and the live sessions, and is the only place
/// that decides whether an incoming connection is allowed to proceed.
/// </summary>
public sealed class BridgeServer(BridgeStore store) : IAsyncDisposable
{
    private readonly MediaProvider _media = new();
    private readonly SystemMetricsProvider _metrics = new();
    private readonly VolumeProvider _volume = new();
    private readonly PowerProvider _power = new();

    private readonly ConcurrentDictionary<Guid, ClientSession> _sessions = new();
    private readonly List<ICarrier> _carriers = [];

    private DiscoveryResponder? _discovery;
    private CancellationTokenSource? _cts;
    private string? _btMac;

    public PairingService Pairing { get; } = new();
    public BridgeStore Store => store;
    public PowerCaps Caps => _power.Caps;

    public IReadOnlyCollection<ClientSession> Sessions => _sessions.Values.ToArray();

    public event Action? SessionsChanged;
    public event Action<string>? Log;

    /// <summary>
    /// Every step is independently guarded. A machine with no Bluetooth radio,
    /// or one where the media session API misbehaves, must still end up with a
    /// working LAN listener — one broken subsystem taking the whole bridge down
    /// with it is the failure mode that looks like "it just doesn't work".
    /// </summary>
    public async Task StartAsync()
    {
        _cts = new CancellationTokenSource();
        var ct = _cts.Token;
        var settings = store.Settings;

        try
        {
            await _media.InitializeAsync();
            Emit("media session attached");
        }
        catch (Exception ex) { Emit($"media session unavailable: {ex.Message}"); }

        if (settings.BluetoothEnabled)
        {
            try
            {
                var bluetooth = new BluetoothCarrier();
                bluetooth.StatusChanged += s => Emit($"bluetooth: {s}");
                bluetooth.Connected += OnCarrierConnection;
                await bluetooth.StartAsync(ct);
                _carriers.Add(bluetooth);
                _btMac = await BluetoothCarrier.ClassicAddressAsync();
                Emit($"bluetooth address {_btMac ?? "unknown"}");
            }
            catch (Exception ex) { Emit($"bluetooth failed to start: {ex.Message}"); }
        }

        if (settings.LanEnabled)
        {
            try
            {
                var tcp = new TcpCarrier(settings.TcpPort);
                tcp.StatusChanged += s => Emit($"lan: {s}");
                tcp.Connected += OnCarrierConnection;
                await tcp.StartAsync(ct);
                _carriers.Add(tcp);
            }
            catch (Exception ex) { Emit($"lan failed to start: {ex.Message}"); }

            try
            {
                _discovery = new DiscoveryResponder(settings.DiscoveryPort, () => new DiscoveryInfo(
                    Environment.MachineName, settings.DeviceId, settings.TcpPort, "1"));
                _discovery.Start(ct);
                Emit($"discovery responder on udp/{settings.DiscoveryPort}");
            }
            catch (Exception ex) { Emit($"discovery failed to start: {ex.Message}"); }
        }

        Emit($"started with {_carriers.Count} carrier(s)");
    }

    private void Emit(string message)
    {
        Diagnostics.Log.Write(message);
        Log?.Invoke(message);
    }

    public PairingOffer OpenPairing(PairingMethod method)
    {
        var settings = store.Settings;
        var offer = Pairing.Open(
            method,
            settings.DeviceId,
            Environment.MachineName,
            TcpCarrier.LocalAddresses(),
            settings.TcpPort,
            _btMac);

        Log?.Invoke($"pairing open for {PairingService.Window.TotalSeconds:0}s ({method})");

        // Close the window on a timer as well as on success, so an abandoned
        // pairing does not leave the machine accepting new devices.
        _ = Task.Run(async () =>
        {
            await Task.Delay(PairingService.Window + TimeSpan.FromSeconds(1));
            if (Pairing.IsOpen) return;
            Pairing.Close();
        });

        return offer;
    }

    private void OnCarrierConnection(CarrierConnection connection)
    {
        _ = Task.Run(() => HandleConnectionAsync(connection));
    }

    private async Task HandleConnectionAsync(CarrierConnection connection)
    {
        var ct = _cts?.Token ?? CancellationToken.None;
        var id = Guid.NewGuid();
        ProtocolSession? session = null;

        try
        {
            bool pairingAllowedHere = IsPairingAllowedFrom(connection);
            bool pairedThisConnection = false;
            string helloMode = "session";

            session = await ProtocolSession.AcceptAsync(
                connection.Stream,
                new LocalIdentity(store.Settings.DeviceId, Environment.MachineName, "windows"),
                hello =>
                {
                    helloMode = hello.Mode;

                    byte[]? known = store.ResolvePsk(hello.DeviceId);
                    if (known is not null) return known;

                    if (!Pairing.IsOpen || !pairingAllowedHere) return null;

                    pairedThisConnection = true;
                    return Pairing.OfferedKey(hello);
                },
                ct);

            if (pairedThisConnection)
            {
                byte[]? real = Pairing.RealKey();
                if (real is null) throw new ProtocolException("pairing window closed mid-handshake");

                // PIN pairing authenticates with the digits, so the durable key
                // still has to be delivered over the now-encrypted channel.
                if (Pairing.IsPinMethod)
                    await session.SendJsonAsync(
                        new PairCompleteMessage { Psk = Convert.ToBase64String(real) }, ct);

                store.SavePairing(session.PeerDeviceId, session.PeerName, session.PeerPlatform, real);
                Pairing.Close();
                Log?.Invoke($"paired with {session.PeerName}");
            }
            else
            {
                store.TouchDevice(session.PeerDeviceId);
            }

            var client = new ClientSession(
                session, connection.Carrier, _media, _metrics, _volume, _power, DescribePeer);
            client.Log += m => Log?.Invoke($"[{session.PeerName}] {m}");

            _sessions[id] = client;
            SessionsChanged?.Invoke();
            Log?.Invoke($"{session.PeerName} connected over {connection.Carrier}");

            await client.RunAsync(ct);
        }
        catch (ProtocolException ex)
        {
            Log?.Invoke($"refused {connection.RemoteDescription}: {ex.Message}");
        }
        catch (Exception ex)
        {
            Log?.Invoke($"{connection.RemoteDescription} dropped: {ex.Message}");
        }
        finally
        {
            if (_sessions.TryRemove(id, out var gone))
            {
                Log?.Invoke($"{gone.PeerName} disconnected");
                SessionsChanged?.Invoke();
            }
            session?.Dispose();
            connection.CloseResource();
        }
    }

    /// <summary>
    /// Bluetooth requires physical proximity and an OS-level bond, so pairing
    /// over it is always allowed. TCP might be reachable from the internet if the
    /// user forwarded the port, so pairing there is restricted to private source
    /// addresses unless the user explicitly opted in.
    /// </summary>
    private bool IsPairingAllowedFrom(CarrierConnection connection)
    {
        if (connection.Carrier != "lan") return true;
        if (store.Settings.AllowRemotePairing) return true;

        if (!IPEndPoint.TryParse(connection.RemoteDescription, out var endpoint))
            return false;

        bool ok = TcpCarrier.IsPrivateAddress(endpoint.Address);
        if (!ok) Log?.Invoke($"pairing refused from non-local address {endpoint.Address}");
        return ok;
    }

    private PeerEvent DescribePeer() => new()
    {
        Bt = _btMac is null ? null : new BtPeer
        {
            Mac = _btMac,
            Uuid = BluetoothCarrier.ServiceUuid.ToString(),
        },
        Lan = new LanPeer
        {
            Hosts = TcpCarrier.LocalAddresses(),
            Port = store.Settings.TcpPort,
        },
    };

    public async ValueTask DisposeAsync()
    {
        try { _cts?.Cancel(); } catch { }

        foreach (var carrier in _carriers)
        {
            try { await carrier.DisposeAsync(); } catch { }
        }
        _carriers.Clear();

        if (_discovery is not null) await _discovery.DisposeAsync();
        await _media.DisposeAsync();
        _metrics.Dispose();
        _volume.Dispose();
        _cts?.Dispose();
    }
}
