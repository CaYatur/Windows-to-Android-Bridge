using System.IO;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace WinBridge.App.Carriers;

/// <summary>
/// RFCOMM server over classic Bluetooth.
///
/// Verified working from an unpackaged desktop process — see ADR-2. The one
/// thing callers must respect: <see cref="ClassicAddressAsync"/> returns the
/// BR/EDR address, and that is the address the phone has to dial. Android will
/// happily show this same PC under an LE identity address in its bonded list,
/// and RFCOMM to that address never connects.
/// </summary>
public sealed class BluetoothCarrier : ICarrier
{
    public static readonly Guid ServiceUuid = Guid.Parse("b6b3a8f1-6f1a-4a5e-9c2d-7e4f1a2b3c4d");
    private const string ServiceName = "WinBridge";

    private RfcommServiceProvider? _provider;
    private StreamSocketListener? _listener;

    public string Name => "bluetooth";
    public bool IsRunning => _provider is not null;
    public string? Status { get; private set; }

    public event Action<CarrierConnection>? Connected;
    public event Action<string>? StatusChanged;

    public async Task StartAsync(CancellationToken ct)
    {
        if (_provider is not null) return;

        try
        {
            var adapter = await BluetoothAdapter.GetDefaultAsync();
            if (adapter is null) { Report("no Bluetooth adapter"); return; }
            if (!adapter.IsClassicSupported) { Report("adapter has no classic Bluetooth support"); return; }

            _provider = await RfcommServiceProvider.CreateAsync(RfcommServiceId.FromUuid(ServiceUuid));

            _listener = new StreamSocketListener();
            _listener.ConnectionReceived += OnConnectionReceived;
            await _listener.BindServiceNameAsync(
                _provider.ServiceId.AsString(),
                SocketProtectionLevel.BluetoothEncryptionAllowNullAuthentication);

            WriteServiceName(_provider);
            _provider.StartAdvertising(_listener, radioDiscoverable: true);

            Report("advertising");
        }
        catch (Exception ex)
        {
            _provider = null;
            _listener = null;
            Report($"unavailable: {ex.Message}");
        }
    }

    /// <summary>SDP attribute 0x0100 so the service shows a name, not a bare UUID.</summary>
    private static void WriteServiceName(RfcommServiceProvider provider)
    {
        try
        {
            var writer = new DataWriter();
            writer.WriteByte(0x25);                       // SDP text8 type descriptor
            writer.WriteByte((byte)ServiceName.Length);
            writer.UnicodeEncoding = UnicodeEncoding.Utf8;
            writer.WriteString(ServiceName);
            provider.SdpRawAttributes.Add(0x0100, writer.DetachBuffer());
        }
        catch { /* cosmetic only */ }
    }

    private void OnConnectionReceived(
        StreamSocketListener sender, StreamSocketListenerConnectionReceivedEventArgs args)
    {
        try
        {
            var socket = args.Socket;
            var remote = socket.Information.RemoteHostName?.DisplayName ?? "bluetooth peer";

            Stream read = socket.InputStream.AsStreamForRead();
            Stream write = socket.OutputStream.AsStreamForWrite();
            var duplex = new DuplexStream(read, write, socket);

            Connected?.Invoke(new CarrierConnection(Name, duplex, remote, socket));
        }
        catch (Exception ex)
        {
            Report($"inbound connection failed: {ex.Message}");
        }
    }

    /// <summary>The classic BR/EDR address, formatted AA:BB:CC:11:22:33.</summary>
    public static async Task<string?> ClassicAddressAsync()
    {
        try
        {
            var adapter = await BluetoothAdapter.GetDefaultAsync();
            if (adapter is null) return null;

            byte[] bytes = BitConverter.GetBytes(adapter.BluetoothAddress);
            return string.Join(":", bytes.Take(6).Reverse().Select(b => b.ToString("X2")));
        }
        catch { return null; }
    }

    private void Report(string status)
    {
        Status = status;
        StatusChanged?.Invoke(status);
    }

    public Task StopAsync()
    {
        try { _provider?.StopAdvertising(); } catch { }
        try { _listener?.Dispose(); } catch { }
        _provider = null;
        _listener = null;
        Report("stopped");
        return Task.CompletedTask;
    }

    public async ValueTask DisposeAsync() => await StopAsync();
}
