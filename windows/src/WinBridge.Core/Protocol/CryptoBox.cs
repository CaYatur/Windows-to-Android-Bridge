using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace WinBridge.Core.Protocol;

/// <summary>
/// Session crypto: ephemeral ECDH P-256 authenticated by the pairing PSK,
/// AES-256-GCM per direction with a strictly increasing counter.
/// See docs/PROTOCOL.md §2.
/// </summary>
public sealed class CryptoBox : IDisposable
{
    public const int PskLength = 32;
    public const int NonceLength = 16;
    private const int TagLength = 16;
    private const int CounterLength = 8;

    private readonly AesGcm _send;
    private readonly AesGcm _recv;
    private readonly byte[] _sendNoncePrefix;
    private readonly byte[] _recvNoncePrefix;

    private ulong _sendCounter;
    private ulong _recvCounter;

    public byte[] ConfirmLocal { get; }
    public byte[] ConfirmPeer { get; }

    private CryptoBox(byte[] sendKey, byte[] recvKey, byte[] sendPrefix, byte[] recvPrefix,
                      byte[] confirmLocal, byte[] confirmPeer)
    {
        _send = new AesGcm(sendKey, TagLength);
        _recv = new AesGcm(recvKey, TagLength);
        _sendNoncePrefix = sendPrefix;
        _recvNoncePrefix = recvPrefix;
        ConfirmLocal = confirmLocal;
        ConfirmPeer = confirmPeer;
    }

    /// <summary>
    /// Every value the key schedule produces. Exposed so that cross-language
    /// test vectors can pin the intermediate steps: when the Kotlin side
    /// disagrees, knowing whether it diverged at Z, at the transcript or at the
    /// HKDF expansion is the difference between a one-line fix and an afternoon.
    /// </summary>
    public sealed record KeySchedule(
        byte[] Z, byte[] KeyC2S, byte[] KeyS2C,
        byte[] NonceC2S, byte[] NonceS2C,
        byte[] ConfirmServer, byte[] ConfirmClient);

    public static KeySchedule ComputeSchedule(
        ECDiffieHellman selfEphemeral,
        byte[] peerPublicPoint,
        byte[] nonceClient,
        byte[] nonceServer,
        byte[] psk,
        byte[] transcript)
    {
        if (peerPublicPoint.Length != 65 || peerPublicPoint[0] != 0x04)
            throw new ProtocolException("peer public key is not an uncompressed P-256 point");

        using var peer = ECDiffieHellman.Create(new ECParameters
        {
            Curve = ECCurve.NamedCurves.nistP256,
            Q = new ECPoint
            {
                X = peerPublicPoint[1..33],
                Y = peerPublicPoint[33..65],
            },
        });

        // Raw agreement: the X coordinate, unhashed. This is what JCA's
        // KeyAgreement("ECDH").generateSecret() returns, so both sides match.
        // DeriveKeyMaterial would hash it and silently diverge.
        byte[] z = selfEphemeral.DeriveRawSecretAgreement(peer.PublicKey);

        var salt = new byte[nonceClient.Length + nonceServer.Length];
        nonceClient.CopyTo(salt, 0);
        nonceServer.CopyTo(salt, nonceClient.Length);

        var ikm = new byte[z.Length + psk.Length];
        z.CopyTo(ikm, 0);
        psk.CopyTo(ikm, z.Length);

        byte[] prk = HKDF.Extract(HashAlgorithmName.SHA256, ikm, salt);
        CryptographicOperations.ZeroMemory(ikm);

        byte[] kC2S = Expand(prk, "winbridge/v1/key/c2s", 32);
        byte[] kS2C = Expand(prk, "winbridge/v1/key/s2c", 32);
        byte[] nC2S = Expand(prk, "winbridge/v1/nonce/c2s", 4);
        byte[] nS2C = Expand(prk, "winbridge/v1/nonce/s2c", 4);
        byte[] kCfm = Expand(prk, "winbridge/v1/confirm", 32);
        CryptographicOperations.ZeroMemory(prk);

        byte[] confirmServer = Confirm(kCfm, "server", transcript);
        byte[] confirmClient = Confirm(kCfm, "client", transcript);
        CryptographicOperations.ZeroMemory(kCfm);

        return new KeySchedule(z, kC2S, kS2C, nC2S, nS2C, confirmServer, confirmClient);
    }

    /// <summary>Derives the session from a completed key exchange.</summary>
    /// <param name="isServer">
    /// Determines key direction. Both sides derive the same four secrets; the
    /// server sends with s2c and receives with c2s, the client the other way.
    /// </param>
    public static CryptoBox Derive(
        ECDiffieHellman selfEphemeral,
        byte[] peerPublicPoint,
        byte[] nonceClient,
        byte[] nonceServer,
        byte[] psk,
        byte[] transcript,
        bool isServer)
    {
        var s = ComputeSchedule(selfEphemeral, peerPublicPoint, nonceClient, nonceServer, psk, transcript);
        CryptographicOperations.ZeroMemory(s.Z);

        return isServer
            ? new CryptoBox(s.KeyS2C, s.KeyC2S, s.NonceS2C, s.NonceC2S, s.ConfirmServer, s.ConfirmClient)
            : new CryptoBox(s.KeyC2S, s.KeyS2C, s.NonceC2S, s.NonceS2C, s.ConfirmClient, s.ConfirmServer);
    }

    private static byte[] Expand(byte[] prk, string info, int length) =>
        HKDF.Expand(HashAlgorithmName.SHA256, prk, length, Encoding.UTF8.GetBytes(info));

    private static byte[] Confirm(byte[] key, string role, byte[] transcript)
    {
        var data = new byte[role.Length + transcript.Length];
        Encoding.UTF8.GetBytes(role).CopyTo(data, 0);
        transcript.CopyTo(data, role.Length);
        return HMACSHA256.HashData(key, data);
    }

    /// <summary>Seals a plaintext into a SECURE frame payload: counter || ciphertext || tag.</summary>
    public byte[] Seal(ReadOnlySpan<byte> plaintext)
    {
        if (_sendCounter == ulong.MaxValue)
            throw new ProtocolException("send counter exhausted; session must be re-established");

        ulong counter = ++_sendCounter;

        var output = new byte[CounterLength + plaintext.Length + TagLength];
        BinaryPrimitives.WriteUInt64BigEndian(output, counter);

        Span<byte> nonce = stackalloc byte[12];
        _sendNoncePrefix.CopyTo(nonce);
        BinaryPrimitives.WriteUInt64BigEndian(nonce[4..], counter);

        _send.Encrypt(
            nonce,
            plaintext,
            output.AsSpan(CounterLength, plaintext.Length),
            output.AsSpan(CounterLength + plaintext.Length, TagLength));

        return output;
    }

    /// <summary>
    /// Opens a SECURE frame payload. Rejects any counter that does not strictly
    /// increase, which is what makes replay and reordering attacks unusable.
    /// </summary>
    public byte[] Open(ReadOnlySpan<byte> framePayload)
    {
        if (framePayload.Length < CounterLength + TagLength)
            throw new ProtocolException("secure frame too short");

        ulong counter = BinaryPrimitives.ReadUInt64BigEndian(framePayload);
        if (counter <= _recvCounter)
            throw new ProtocolException($"replayed or reordered frame (counter {counter} <= {_recvCounter})");

        int cipherLength = framePayload.Length - CounterLength - TagLength;

        Span<byte> nonce = stackalloc byte[12];
        _recvNoncePrefix.CopyTo(nonce);
        BinaryPrimitives.WriteUInt64BigEndian(nonce[4..], counter);

        var plaintext = new byte[cipherLength];
        _recv.Decrypt(
            nonce,
            framePayload.Slice(CounterLength, cipherLength),
            framePayload.Slice(CounterLength + cipherLength, TagLength),
            plaintext);

        _recvCounter = counter;
        return plaintext;
    }

    public static byte[] RandomBytes(int length) => RandomNumberGenerator.GetBytes(length);

    public static bool ConstantTimeEquals(ReadOnlySpan<byte> a, ReadOnlySpan<byte> b) =>
        CryptographicOperations.FixedTimeEquals(a, b);

    public void Dispose()
    {
        _send.Dispose();
        _recv.Dispose();
    }
}
