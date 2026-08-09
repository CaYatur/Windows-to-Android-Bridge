using System.IO;

namespace WinBridge.App.Carriers;

public sealed record CarrierConnection(
    string Carrier,
    Stream Stream,
    string RemoteDescription,
    IDisposable? Resource)
{
    public void CloseResource()
    {
        try { Stream.Dispose(); } catch { }
        try { Resource?.Dispose(); } catch { }
    }
}

/// <summary>
/// A carrier hands us connected byte streams and nothing more. Everything above
/// this line — framing, crypto, messages — is identical whether the bytes came
/// over Bluetooth or TCP.
/// </summary>
public interface ICarrier : IAsyncDisposable
{
    string Name { get; }
    bool IsRunning { get; }
    string? Status { get; }

    event Action<CarrierConnection>? Connected;
    event Action<string>? StatusChanged;

    Task StartAsync(CancellationToken ct);
    Task StopAsync();
}

/// <summary>
/// Joins a read half and a write half into one Stream, which is what the
/// protocol layer expects. WinRT sockets hand back separate input and output
/// streams, so this is the adapter that makes them look like everything else.
/// </summary>
public sealed class DuplexStream(Stream reader, Stream writer, IDisposable? owner = null) : Stream
{
    public override bool CanRead => true;
    public override bool CanWrite => true;
    public override bool CanSeek => false;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override int Read(byte[] buffer, int offset, int count) =>
        reader.Read(buffer, offset, count);

    public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default) =>
        reader.ReadAsync(buffer, ct);

    public override void Write(byte[] buffer, int offset, int count) =>
        writer.Write(buffer, offset, count);

    public override ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken ct = default) =>
        writer.WriteAsync(buffer, ct);

    public override void Flush() => writer.Flush();
    public override Task FlushAsync(CancellationToken ct) => writer.FlushAsync(ct);

    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            try { reader.Dispose(); } catch { }
            try { writer.Dispose(); } catch { }
            try { owner?.Dispose(); } catch { }
        }
        base.Dispose(disposing);
    }
}
