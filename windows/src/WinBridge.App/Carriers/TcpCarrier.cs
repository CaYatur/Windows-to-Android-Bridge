using System.Net;
using System.Net.Sockets;

namespace WinBridge.App.Carriers;

/// <summary>
/// TCP listener for LAN and, if the user forwards the port, remote access.
///
/// TCP_NODELAY is set on every accepted socket. Our traffic is a stream of small
/// control frames; leaving Nagle on batches them into 40 ms clumps and is the
/// single most noticeable source of "why is this laggy".
/// </summary>
public sealed class TcpCarrier(int port) : ICarrier
{
    private TcpListener? _listener;
    private CancellationTokenSource? _cts;
    private Task? _acceptLoop;

    public string Name => "lan";
    public bool IsRunning => _listener is not null;
    public string? Status { get; private set; }

    public event Action<CarrierConnection>? Connected;
    public event Action<string>? StatusChanged;

    public Task StartAsync(CancellationToken ct)
    {
        if (_listener is not null) return Task.CompletedTask;

        try
        {
            _listener = new TcpListener(IPAddress.Any, port);
            _listener.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            _listener.Start();

            _cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            _acceptLoop = Task.Run(() => AcceptLoopAsync(_cts.Token), _cts.Token);

            Report($"listening on port {port}");
        }
        catch (Exception ex)
        {
            _listener = null;
            Report($"could not listen on port {port}: {ex.Message}");
        }
        return Task.CompletedTask;
    }

    private async Task AcceptLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _listener is not null)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(ct);
            }
            catch (OperationCanceledException) { return; }
            catch (ObjectDisposedException) { return; }
            catch (SocketException)
            {
                // A transient accept failure should not kill the listener.
                await Task.Delay(200, ct).ConfigureAwait(false);
                continue;
            }

            try
            {
                client.NoDelay = true;
                var remote = client.Client.RemoteEndPoint?.ToString() ?? "?";
                Connected?.Invoke(new CarrierConnection(Name, client.GetStream(), remote, client));
            }
            catch
            {
                try { client.Dispose(); } catch { }
            }
        }
    }

    /// <summary>
    /// Addresses a phone on the same network could actually reach us at.
    ///
    /// Machines with Hyper-V, WSL, VirtualBox or a VPN carry extra adapters
    /// whose addresses look perfectly valid and are unreachable from anywhere
    /// else. Handing those to the phone costs a connection timeout per address
    /// before it gets to a real one, so they are filtered here rather than
    /// discovered the slow way at runtime.
    ///
    /// The test is whether the interface has a default gateway: the interface
    /// that can reach the phone is, by definition, one that can reach off the
    /// machine. Virtual switches have none.
    /// </summary>
    public static List<string> LocalAddresses()
    {
        var routable = new List<string>();
        var fallback = new List<string>();

        try
        {
            foreach (var nic in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;

                var type = nic.NetworkInterfaceType;
                if (type is System.Net.NetworkInformation.NetworkInterfaceType.Loopback
                         or System.Net.NetworkInformation.NetworkInterfaceType.Tunnel)
                    continue;

                var properties = nic.GetIPProperties();
                bool hasGateway = properties.GatewayAddresses
                    .Any(g => g.Address is not null &&
                              g.Address.AddressFamily == AddressFamily.InterNetwork &&
                              !g.Address.Equals(IPAddress.Any));

                foreach (var addr in properties.UnicastAddresses)
                {
                    if (addr.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    if (IPAddress.IsLoopback(addr.Address)) continue;

                    var text = addr.Address.ToString();
                    if (hasGateway) routable.Add(text); else fallback.Add(text);
                }
            }
        }
        catch { }

        // If nothing has a gateway we are probably on an isolated network the
        // phone shares, so offering the rest is better than offering nothing.
        return routable.Count > 0 ? routable : fallback;
    }

    /// <summary>
    /// RFC1918 / link-local check. Pairing refuses connections from outside these
    /// ranges unless the user has explicitly allowed remote pairing.
    /// </summary>
    public static bool IsPrivateAddress(IPAddress address)
    {
        if (IPAddress.IsLoopback(address)) return true;
        if (address.AddressFamily != AddressFamily.InterNetwork) return address.IsIPv6LinkLocal;

        var b = address.GetAddressBytes();
        return b[0] switch
        {
            10 => true,
            172 => b[1] >= 16 && b[1] <= 31,
            192 => b[1] == 168,
            169 => b[1] == 254,
            _ => false,
        };
    }

    private void Report(string status)
    {
        Status = status;
        StatusChanged?.Invoke(status);
    }

    public async Task StopAsync()
    {
        try { _cts?.Cancel(); } catch { }
        try { _listener?.Stop(); } catch { }
        _listener = null;

        if (_acceptLoop is not null)
        {
            try { await _acceptLoop.ConfigureAwait(false); } catch { }
            _acceptLoop = null;
        }
        _cts?.Dispose();
        _cts = null;
        Report("stopped");
    }

    public async ValueTask DisposeAsync() => await StopAsync();
}
