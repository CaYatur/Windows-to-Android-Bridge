using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace WinBridge.App.Carriers;

/// <summary>
/// Answers "is there a WinBridge host on this network?" so the phone does not
/// have to be told an IP address by hand.
///
/// The reply carries only what a phone needs to attempt a connection: a name,
/// the port, and the device id. No pairing key and no Bluetooth address — an
/// unauthenticated broadcast responder is not the place to hand out either, and
/// an unpaired device is refused at the handshake anyway.
/// </summary>
public sealed class DiscoveryResponder(int port, Func<DiscoveryInfo> describe) : IAsyncDisposable
{
    private const string ProbeMagic = "WINBRIDGE-DISCOVER-V1";

    private UdpClient? _udp;
    private CancellationTokenSource? _cts;
    private Task? _loop;

    public void Start(CancellationToken ct)
    {
        if (_udp is not null) return;
        try
        {
            _udp = new UdpClient(AddressFamily.InterNetwork);
            _udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            _udp.Client.Bind(new IPEndPoint(IPAddress.Any, port));
            _udp.EnableBroadcast = true;

            _cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            _loop = Task.Run(() => LoopAsync(_cts.Token), _cts.Token);
        }
        catch
        {
            _udp = null; // discovery is a convenience; manual entry still works
        }
    }

    private async Task LoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _udp is not null)
        {
            UdpReceiveResult received;
            try { received = await _udp.ReceiveAsync(ct); }
            catch (OperationCanceledException) { return; }
            catch (ObjectDisposedException) { return; }
            catch { continue; }

            try
            {
                if (Encoding.UTF8.GetString(received.Buffer).Trim() != ProbeMagic) continue;
                if (!TcpCarrier.IsPrivateAddress(received.RemoteEndPoint.Address)) continue;

                byte[] reply = JsonSerializer.SerializeToUtf8Bytes(describe());
                await _udp.SendAsync(reply, reply.Length, received.RemoteEndPoint);
            }
            catch { /* one bad probe should not stop the responder */ }
        }
    }

    public async ValueTask DisposeAsync()
    {
        try { _cts?.Cancel(); } catch { }
        try { _udp?.Dispose(); } catch { }
        _udp = null;
        if (_loop is not null)
        {
            try { await _loop; } catch { }
            _loop = null;
        }
        _cts?.Dispose();
        _cts = null;
    }
}

public sealed record DiscoveryInfo(string Name, string DeviceId, int Port, string Version);
