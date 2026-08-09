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

    /// <summary>Addresses a phone on the same network could reach us at.</summary>
    public static List<string> LocalAddresses()
    {
        var result = new List<string>();
        try
        {
            foreach (var nic in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback) continue;

                foreach (var addr in nic.GetIPProperties().UnicastAddresses)
                {
                    if (addr.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    if (IPAddress.IsLoopback(addr.Address)) continue;
                    result.Add(addr.Address.ToString());
                }
            }
        }
        catch { }
        return result;
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
