using WinBridge.Core.Protocol;

namespace WinBridge.App.Server;

/// <summary>
/// What a feature service is allowed to do with a connected phone.
///
/// Features take this rather than a <see cref="ClientSession"/> so that a
/// transfer, a stream or an automation can be driven from the tray or a shell
/// hook without those places knowing how sessions are stored — and so a feature
/// cannot reach back into session lifecycle and, say, close the link.
/// </summary>
public interface IPeerLink
{
    string PeerName { get; }
    string PeerDeviceId { get; }
    string Carrier { get; }

    Task SendJsonAsync<T>(T message, CancellationToken ct);
    Task SendBlobAsync(string id, ReadOnlyMemory<byte> data, CancellationToken ct);
    Task SendXferAsync(XferChunk chunk, CancellationToken ct);

    /// <summary>Returns false when the packet was dropped because the link is behind.</summary>
    bool TrySendMedia(in MediaPacket packet);
}
