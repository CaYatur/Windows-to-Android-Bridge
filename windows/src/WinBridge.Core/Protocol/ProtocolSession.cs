using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace WinBridge.Core.Protocol;

public sealed record LocalIdentity(string DeviceId, string Name, string Platform);

public readonly record struct InboundMessage(InnerType Inner, string? JsonType, byte[] Body)
{
    public T As<T>() => Json.Deserialize<T>(Body);
}

/// <summary>
/// Drives the handshake and then the encrypted message channel over any stream.
/// Windows always acts as the server; the Kotlin mirror in
/// core/protocol implements the client half against the same spec.
/// </summary>
public sealed class ProtocolSession : IDisposable
{
    private readonly Stream _stream;
    private readonly CryptoBox _crypto;
    private readonly SemaphoreSlim _writeLock = new(1, 1);

    public string PeerDeviceId { get; }
    public string PeerName { get; }
    public string PeerPlatform { get; }
    public string PeerMode { get; }

    private ProtocolSession(Stream stream, CryptoBox crypto, Hello peerHello)
    {
        _stream = stream;
        _crypto = crypto;
        PeerDeviceId = peerHello.DeviceId;
        PeerName = peerHello.Name;
        PeerPlatform = peerHello.Platform;
        PeerMode = peerHello.Mode;
    }

    /// <summary>
    /// Server side of the handshake. <paramref name="resolveKey"/> receives the
    /// whole HELLO — not just the device id — so the caller can distinguish a
    /// normal session from a pairing attempt and answer with the stored key, the
    /// key currently offered by an open pairing window, or null to refuse.
    /// Returning null for an unknown device is what keeps the listener safe to
    /// expose.
    /// </summary>
    public static async Task<ProtocolSession> AcceptAsync(
        Stream stream,
        LocalIdentity me,
        Func<Hello, byte[]?> resolveKey,
        CancellationToken ct)
    {
        var frame = await Framing.ReadAsync(stream, ct).ConfigureAwait(false);
        if (frame.Type != FrameType.Hello)
            throw new ProtocolException($"expected HELLO, got {frame.Type}");

        var hello = Json.Deserialize<Hello>(frame.Payload);
        if (hello.Version != 1)
            throw new ProtocolException($"unsupported protocol version {hello.Version}");

        byte[]? psk = resolveKey(hello);
        if (psk is null)
            throw new ProtocolException($"device {hello.DeviceId} is not paired");

        byte[] clientEphPub = Convert.FromBase64String(hello.EphPub);
        byte[] clientNonce = Convert.FromBase64String(hello.Nonce);
        if (clientNonce.Length != CryptoBox.NonceLength)
            throw new ProtocolException("bad client nonce length");

        using var ecdh = ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);
        byte[] serverEphPub = ExportPoint(ecdh);
        byte[] serverNonce = CryptoBox.RandomBytes(CryptoBox.NonceLength);

        byte[] transcript = ComputeTranscript(
            hello.DeviceId, clientEphPub, clientNonce,
            me.DeviceId, serverEphPub, serverNonce);

        var crypto = CryptoBox.Derive(
            ecdh, clientEphPub, clientNonce, serverNonce, psk, transcript, isServer: true);

        var ack = new HelloAck
        {
            DeviceId = me.DeviceId,
            Name = me.Name,
            EphPub = Convert.ToBase64String(serverEphPub),
            Nonce = Convert.ToBase64String(serverNonce),
            Confirm = Convert.ToBase64String(crypto.ConfirmLocal),
        };
        await Framing.WriteAsync(stream, FrameType.HelloAck, Json.Serialize(ack), ct)
            .ConfigureAwait(false);

        var session = new ProtocolSession(stream, crypto, hello);

        // The client must prove it derived the same secrets before we expose
        // anything about this machine.
        var authFrame = await session.ReceiveAsync(ct).ConfigureAwait(false);
        if (authFrame.JsonType != MessageTypes.Auth)
            throw new ProtocolException($"expected auth, got {authFrame.JsonType}");

        var auth = authFrame.As<AuthMessage>();
        byte[] presented = Convert.FromBase64String(auth.Confirm);
        if (!CryptoBox.ConstantTimeEquals(presented, crypto.ConfirmPeer))
            throw new ProtocolException("client failed authentication");

        return session;
    }

    /// <summary>
    /// Client side of the handshake. Windows never uses this in production —
    /// Android is always the client — but it is the executable reference the
    /// Kotlin implementation is checked against, and it lets the whole protocol
    /// be tested over a loopback without a phone in the room.
    /// </summary>
    public static async Task<ProtocolSession> ConnectAsync(
        Stream stream,
        LocalIdentity me,
        byte[] psk,
        CancellationToken ct,
        string mode = "session")
    {
        using var ecdh = ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);
        byte[] clientEphPub = ExportPoint(ecdh);
        byte[] clientNonce = CryptoBox.RandomBytes(CryptoBox.NonceLength);

        var hello = new Hello
        {
            DeviceId = me.DeviceId,
            Name = me.Name,
            Platform = me.Platform,
            Mode = mode,
            EphPub = Convert.ToBase64String(clientEphPub),
            Nonce = Convert.ToBase64String(clientNonce),
        };
        await Framing.WriteAsync(stream, FrameType.Hello, Json.Serialize(hello), ct)
            .ConfigureAwait(false);

        var frame = await Framing.ReadAsync(stream, ct).ConfigureAwait(false);
        if (frame.Type != FrameType.HelloAck)
            throw new ProtocolException($"expected HELLO_ACK, got {frame.Type}");

        var ack = Json.Deserialize<HelloAck>(frame.Payload);
        byte[] serverEphPub = Convert.FromBase64String(ack.EphPub);
        byte[] serverNonce = Convert.FromBase64String(ack.Nonce);

        byte[] transcript = ComputeTranscript(
            me.DeviceId, clientEphPub, clientNonce,
            ack.DeviceId, serverEphPub, serverNonce);

        var crypto = CryptoBox.Derive(
            ecdh, serverEphPub, clientNonce, serverNonce, psk, transcript, isServer: false);

        // Verifying the server's confirm BEFORE sending ours is what stops us
        // leaking an authenticator to an impostor.
        byte[] presented = Convert.FromBase64String(ack.Confirm);
        if (!CryptoBox.ConstantTimeEquals(presented, crypto.ConfirmPeer))
            throw new ProtocolException("server failed authentication (wrong pairing key?)");

        var peerHello = new Hello
        {
            DeviceId = ack.DeviceId,
            Name = ack.Name,
            Platform = ack.Platform,
        };
        var session = new ProtocolSession(stream, crypto, peerHello);

        await session.SendJsonAsync(
            new AuthMessage { Confirm = Convert.ToBase64String(crypto.ConfirmLocal) }, ct)
            .ConfigureAwait(false);

        return session;
    }

    public static byte[] ExportPoint(ECDiffieHellman ecdh)
    {
        var p = ecdh.ExportParameters(false);
        var point = new byte[65];
        point[0] = 0x04;
        p.Q.X!.CopyTo(point, 1);
        p.Q.Y!.CopyTo(point, 33);
        return point;
    }

    /// <summary>
    /// Binds both sides' identities and key material into one hash. Defined over
    /// canonical, length-prefixed components rather than raw JSON bytes so that
    /// C# and Kotlin cannot disagree about serialization details.
    /// </summary>
    public static byte[] ComputeTranscript(
        string clientDeviceId, byte[] clientEphPub, byte[] clientNonce,
        string serverDeviceId, byte[] serverEphPub, byte[] serverNonce)
    {
        using var ms = new MemoryStream();
        WriteChunk(ms, Encoding.UTF8.GetBytes("winbridge/v1"));
        WriteChunk(ms, Encoding.UTF8.GetBytes(clientDeviceId));
        WriteChunk(ms, clientEphPub);
        WriteChunk(ms, clientNonce);
        WriteChunk(ms, Encoding.UTF8.GetBytes(serverDeviceId));
        WriteChunk(ms, serverEphPub);
        WriteChunk(ms, serverNonce);
        return SHA256.HashData(ms.ToArray());

        static void WriteChunk(Stream s, byte[] data)
        {
            Span<byte> len = stackalloc byte[2];
            BinaryPrimitives.WriteUInt16BigEndian(len, (ushort)data.Length);
            s.Write(len);
            s.Write(data);
        }
    }

    public async Task SendJsonAsync<T>(T message, CancellationToken ct)
    {
        byte[] json = Json.Serialize(message);
        var body = new byte[1 + json.Length];
        body[0] = (byte)InnerType.Json;
        json.CopyTo(body, 1);
        await SendSealedAsync(body, ct).ConfigureAwait(false);
    }

    public async Task SendBlobAsync(string id, ReadOnlyMemory<byte> data, CancellationToken ct)
    {
        byte[] idBytes = Encoding.UTF8.GetBytes(id);
        if (idBytes.Length > 255) throw new ProtocolException("blob id too long");

        var body = new byte[1 + 1 + idBytes.Length + data.Length];
        body[0] = (byte)InnerType.Blob;
        body[1] = (byte)idBytes.Length;
        idBytes.CopyTo(body, 2);
        data.Span.CopyTo(body.AsSpan(2 + idBytes.Length));
        await SendSealedAsync(body, ct).ConfigureAwait(false);
    }

    private async Task SendSealedAsync(byte[] plaintext, CancellationToken ct)
    {
        await _writeLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            byte[] sealedPayload = _crypto.Seal(plaintext);
            await Framing.WriteAsync(_stream, FrameType.Secure, sealedPayload, ct)
                .ConfigureAwait(false);
        }
        finally { _writeLock.Release(); }
    }

    public async Task<InboundMessage> ReceiveAsync(CancellationToken ct)
    {
        while (true)
        {
            var frame = await Framing.ReadAsync(_stream, ct).ConfigureAwait(false);

            if (frame.Type == FrameType.Bye)
                throw new EndOfStreamException("peer said goodbye");
            if (frame.Type != FrameType.Secure)
                throw new ProtocolException($"unexpected {frame.Type} after handshake");

            byte[] plaintext = _crypto.Open(frame.Payload);
            if (plaintext.Length < 1)
                throw new ProtocolException("empty secure frame");

            var inner = (InnerType)plaintext[0];
            switch (inner)
            {
                case InnerType.Json:
                {
                    byte[] body = plaintext[1..];
                    return new InboundMessage(inner, Json.ReadType(body), body);
                }
                case InnerType.Blob:
                {
                    if (plaintext.Length < 2) throw new ProtocolException("truncated blob frame");
                    int idLen = plaintext[1];
                    if (plaintext.Length < 2 + idLen) throw new ProtocolException("truncated blob id");
                    return new InboundMessage(inner, null, plaintext[(2 + idLen)..]);
                }
                default:
                    throw new ProtocolException($"unknown inner type 0x{plaintext[0]:X2}");
            }
        }
    }

    public async Task SayGoodbyeAsync(CancellationToken ct)
    {
        try { await Framing.WriteAsync(_stream, FrameType.Bye, ReadOnlyMemory<byte>.Empty, ct).ConfigureAwait(false); }
        catch { /* the link is going away regardless */ }
    }

    public void Dispose()
    {
        _crypto.Dispose();
        _writeLock.Dispose();
    }
}

public sealed record AuthMessage
{
    [System.Text.Json.Serialization.JsonPropertyName("t")]
    public string Type => MessageTypes.Auth;

    [System.Text.Json.Serialization.JsonPropertyName("confirm")]
    public string Confirm { get; init; } = "";
}
