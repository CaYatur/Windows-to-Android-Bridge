using System.Buffers.Binary;

namespace WinBridge.Core.Protocol;

/// <summary>
/// Which lane a frame is queued on. One socket carries control, media and bulk
/// traffic, so a strict priority queue in front of the writer is what keeps a
/// touch event from queueing behind a 60 KB screen tile batch or a file chunk.
/// </summary>
public enum SendLane
{
    /// <summary>Commands, input, heartbeat, state. Never dropped, always first.</summary>
    Control = 0,
    /// <summary>Screen tiles and audio. Dropped under pressure — stale is worse than absent.</summary>
    Media = 1,
    /// <summary>File chunks. Never dropped, but always last and flow-controlled.</summary>
    Bulk = 2,
}

public static class MediaKind
{
    public const byte Video = 1;
    public const byte Audio = 2;
}

/// <summary>
/// Stream identifiers are fixed rather than negotiated: both ends need to agree
/// on what "stream 3" means before any control message can be exchanged about
/// it, and there are few enough of them that a registry buys nothing.
/// </summary>
public static class StreamIds
{
    public const byte PcScreen = 0;
    public const byte PhoneScreen = 1;
    public const byte PcAudio = 2;      // what the PC is playing (WASAPI loopback)
    public const byte PhoneAudio = 3;   // what the phone is playing (AudioPlaybackCapture)
    public const byte PcMic = 4;
    public const byte PhoneMic = 5;

    public static string Name(byte id) => id switch
    {
        PcScreen => "pc.screen",
        PhoneScreen => "phone.screen",
        PcAudio => "pc.audio",
        PhoneAudio => "phone.audio",
        PcMic => "pc.mic",
        PhoneMic => "phone.mic",
        _ => $"stream{id}",
    };

    public static byte FromName(string name) => name switch
    {
        "pc.screen" => PcScreen,
        "phone.screen" => PhoneScreen,
        "pc.audio" => PcAudio,
        "phone.audio" => PhoneAudio,
        "pc.mic" => PcMic,
        "phone.mic" => PhoneMic,
        _ => 0xFF,
    };
}

[Flags]
public enum MediaFlags : byte
{
    None = 0,
    /// <summary>Every tile of the frame is present; the receiver may reset its canvas.</summary>
    Keyframe = 1,
    /// <summary>Last packet of this frame — the receiver may present now.</summary>
    EndOfFrame = 2,
}

/// <summary>
/// One real-time packet. Header is 11 bytes: kind, stream, seq, timestamp, flags.
/// Deliberately not JSON — at 60 packets a second the parse cost and the byte
/// overhead both matter.
/// </summary>
public readonly record struct MediaPacket(
    byte Kind, byte Stream, uint Seq, uint TimestampMs, MediaFlags Flags, ReadOnlyMemory<byte> Payload)
{
    public const int HeaderSize = 11;

    public byte[] ToBytes()
    {
        var body = new byte[1 + HeaderSize + Payload.Length];
        body[0] = (byte)InnerType.Media;
        body[1] = Kind;
        body[2] = Stream;
        BinaryPrimitives.WriteUInt32BigEndian(body.AsSpan(3), Seq);
        BinaryPrimitives.WriteUInt32BigEndian(body.AsSpan(7), TimestampMs);
        body[11] = (byte)Flags;
        Payload.Span.CopyTo(body.AsSpan(1 + HeaderSize));
        return body;
    }

    /// <param name="plaintext">The decrypted inner frame, including the leading inner type byte.</param>
    public static MediaPacket Parse(ReadOnlyMemory<byte> plaintext)
    {
        if (plaintext.Length < 1 + HeaderSize) throw new ProtocolException("truncated media packet");
        var span = plaintext.Span;
        return new MediaPacket(
            span[1],
            span[2],
            BinaryPrimitives.ReadUInt32BigEndian(span[3..]),
            BinaryPrimitives.ReadUInt32BigEndian(span[7..]),
            (MediaFlags)span[11],
            plaintext[(1 + HeaderSize)..]);
    }
}

[Flags]
public enum XferFlags : byte
{
    None = 0,
    Last = 1,
}

/// <summary>One chunk of a file transfer.</summary>
public readonly record struct XferChunk(uint TransferId, uint Seq, XferFlags Flags, ReadOnlyMemory<byte> Data)
{
    public const int HeaderSize = 9;

    public byte[] ToBytes()
    {
        var body = new byte[1 + HeaderSize + Data.Length];
        body[0] = (byte)InnerType.Xfer;
        BinaryPrimitives.WriteUInt32BigEndian(body.AsSpan(1), TransferId);
        BinaryPrimitives.WriteUInt32BigEndian(body.AsSpan(5), Seq);
        body[9] = (byte)Flags;
        Data.Span.CopyTo(body.AsSpan(1 + HeaderSize));
        return body;
    }

    public static XferChunk Parse(ReadOnlyMemory<byte> plaintext)
    {
        if (plaintext.Length < 1 + HeaderSize) throw new ProtocolException("truncated xfer chunk");
        var span = plaintext.Span;
        return new XferChunk(
            BinaryPrimitives.ReadUInt32BigEndian(span[1..]),
            BinaryPrimitives.ReadUInt32BigEndian(span[5..]),
            (XferFlags)span[9],
            plaintext[(1 + HeaderSize)..]);
    }
}

/// <summary>
/// A screen frame is a list of changed tiles. Only the tiles whose content hash
/// moved are sent, which is what makes an intra-only codec affordable: a desktop
/// with a blinking cursor costs a few hundred bytes a frame, and there is no
/// reference-frame latency to pay on either side.
/// </summary>
public static class TileCodec
{
    /// <summary>Keep a single packet comfortably under the MTU-amplified cost of a stall.</summary>
    public const int MaxPacketBytes = 48 * 1024;

    public static void WriteTile(Stream destination, ushort index, ReadOnlySpan<byte> jpeg)
    {
        Span<byte> header = stackalloc byte[6];
        BinaryPrimitives.WriteUInt16BigEndian(header, index);
        BinaryPrimitives.WriteUInt32BigEndian(header[2..], (uint)jpeg.Length);
        destination.Write(header);
        destination.Write(jpeg);
    }

    public static IEnumerable<(ushort Index, ReadOnlyMemory<byte> Jpeg)> ReadTiles(ReadOnlyMemory<byte> payload)
    {
        int offset = 0;
        while (offset + 6 <= payload.Length)
        {
            ushort index = BinaryPrimitives.ReadUInt16BigEndian(payload.Span[offset..]);
            uint length = BinaryPrimitives.ReadUInt32BigEndian(payload.Span[(offset + 2)..]);
            offset += 6;
            if (length > (uint)(payload.Length - offset)) yield break;
            yield return (index, payload.Slice(offset, (int)length));
            offset += (int)length;
        }
    }
}
