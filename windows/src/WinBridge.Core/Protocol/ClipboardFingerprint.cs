using System.Security.Cryptography;
using System.Text;

namespace WinBridge.Core.Protocol;

/// <summary>
/// How a clipboard is identified on the wire.
///
/// Both machines fingerprint the same text, and each recognises its own words
/// coming back by comparing the two strings — so the encoding is part of the
/// protocol, not a detail either side is free to pick. It lives here, next to
/// the message definitions and covered by the same vector tests, because when it
/// was a private helper in each app they drifted: hex on the PC, base64 on the
/// phone. Sixteen identical bytes, spelled two different ways, so no comparison
/// ever matched — and every clipboard the phone sent was applied here, seen as a
/// fresh copy, and sent straight back to the phone.
/// </summary>
public static class ClipboardFingerprint
{
    /// <summary>First 16 bytes of SHA-256, lowercase hex.</summary>
    public static string Of(ReadOnlySpan<byte> data) =>
        Convert.ToHexString(SHA256.HashData(data).AsSpan(0, 16)).ToLowerInvariant();

    public static string Of(string text) => Of(Encoding.UTF8.GetBytes(text));
}
