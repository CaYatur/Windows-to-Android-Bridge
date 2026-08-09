using System.Buffers.Binary;

namespace WinBridge.Core.Protocol;

public enum FrameType : byte
{
    Hello = 0x01,
    HelloAck = 0x02,
    Secure = 0x03,
    Bye = 0x04,
}

public enum InnerType : byte
{
    Json = 0x01,
    Blob = 0x02,
}

/// <summary>
/// Length-prefixed framing shared by both carriers. See docs/PROTOCOL.md §1.
/// A carrier only has to give us an ordered, reliable byte stream.
/// </summary>
public static class Framing
{
    public const int MaxFrameSize = 4 * 1024 * 1024;

    public static async ValueTask WriteAsync(
        Stream stream, FrameType type, ReadOnlyMemory<byte> payload, CancellationToken ct)
    {
        if (payload.Length + 1 > MaxFrameSize)
            throw new ProtocolException($"frame too large: {payload.Length + 1}");

        var header = new byte[5];
        BinaryPrimitives.WriteUInt32BigEndian(header, (uint)(payload.Length + 1));
        header[4] = (byte)type;

        await stream.WriteAsync(header, ct).ConfigureAwait(false);
        if (!payload.IsEmpty)
            await stream.WriteAsync(payload, ct).ConfigureAwait(false);
        await stream.FlushAsync(ct).ConfigureAwait(false);
    }

    public static async ValueTask<Frame> ReadAsync(Stream stream, CancellationToken ct)
    {
        var header = new byte[5];
        await ReadExactAsync(stream, header, ct).ConfigureAwait(false);

        uint len = BinaryPrimitives.ReadUInt32BigEndian(header);
        if (len == 0)
            throw new ProtocolException("zero-length frame");
        if (len > MaxFrameSize)
            throw new ProtocolException($"frame too large: {len}");

        var payload = new byte[len - 1];
        if (payload.Length > 0)
            await ReadExactAsync(stream, payload, ct).ConfigureAwait(false);

        return new Frame((FrameType)header[4], payload);
    }

    private static async ValueTask ReadExactAsync(Stream stream, Memory<byte> buffer, CancellationToken ct)
    {
        int read = 0;
        while (read < buffer.Length)
        {
            int n = await stream.ReadAsync(buffer[read..], ct).ConfigureAwait(false);
            if (n == 0) throw new EndOfStreamException("peer closed the connection");
            read += n;
        }
    }
}

public readonly record struct Frame(FrameType Type, byte[] Payload);

public sealed class ProtocolException(string message) : Exception(message);
