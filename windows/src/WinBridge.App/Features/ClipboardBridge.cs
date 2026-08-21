using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media.Imaging;
using WinBridge.Core.Protocol;

namespace WinBridge.App.Features;

/// <summary>
/// Watches the Windows clipboard and applies clipboards arriving from the phone.
///
/// Polling the clipboard is the usual way this gets written and it is the wrong
/// one: it burns a timer forever, it misses anything copied and replaced between
/// ticks, and on Windows every read opens the clipboard, which fights with
/// whichever app is trying to write it. <c>AddClipboardFormatListener</c> gives
/// an event instead, so the cost is zero until something is actually copied.
/// </summary>
public sealed class ClipboardBridge : IDisposable
{
    private const int WM_CLIPBOARDUPDATE = 0x031D;
    private static readonly IntPtr HWND_MESSAGE = new(-3);

    private HwndSource? _source;
    private string? _lastSeenHash;

    /// <summary>
    /// What we most recently wrote ourselves. Applying a clipboard raises the
    /// same change event a human copy does, and without this the two machines
    /// hand the same string back and forth forever.
    /// </summary>
    private string? _lastAppliedHash;

    public event Action<ClipboardMessage>? Changed;
    public event Action<string>? Log;

    /// <summary>Set false to stop raising <see cref="Changed"/> without tearing the listener down.</summary>
    public bool Watching { get; set; }

    public bool IncludeImages { get; set; }

    public int MaxBytes { get; set; } = 256 * 1024;

    /// <summary>
    /// Must be called on the UI thread: the clipboard is an STA API, and the
    /// listener has to belong to a window with a message pump.
    /// </summary>
    public void Start()
    {
        if (_source is not null) return;

        var parameters = new HwndSourceParameters("WinBridgeClipboard")
        {
            // A message-only window: never shown, never in the taskbar, but it
            // has an HWND and a message queue, which is all the listener needs.
            ParentWindow = HWND_MESSAGE,
            Width = 1,
            Height = 1,
        };

        _source = new HwndSource(parameters);
        _source.AddHook(OnMessage);

        if (!AddClipboardFormatListener(_source.Handle))
            Log?.Invoke($"clipboard listener failed: {Marshal.GetLastWin32Error()}");
    }

    private IntPtr OnMessage(IntPtr hwnd, int message, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (message != WM_CLIPBOARDUPDATE) return IntPtr.Zero;

        handled = true;
        if (!Watching) return IntPtr.Zero;

        try
        {
            var snapshot = Read();
            if (snapshot is null) return IntPtr.Zero;

            // Two guards, not one: the first stops the ping-pong with the phone,
            // the second stops a re-copy of identical text from re-sending.
            if (snapshot.Hash == _lastAppliedHash) return IntPtr.Zero;
            if (snapshot.Hash == _lastSeenHash) return IntPtr.Zero;

            _lastSeenHash = snapshot.Hash;
            Changed?.Invoke(snapshot);
        }
        catch (Exception ex)
        {
            Log?.Invoke($"clipboard read failed: {ex.Message}");
        }

        return IntPtr.Zero;
    }

    /// <summary>Reads the current clipboard, or null when there is nothing we carry.</summary>
    public ClipboardMessage? Read()
    {
        // Another process can hold the clipboard open for a few milliseconds
        // after a copy. Failing here would drop the copy the user just made, so
        // a short retry is worth more than the code it costs.
        for (int attempt = 0; attempt < 5; attempt++)
        {
            try
            {
                if (Clipboard.ContainsText())
                {
                    string text = Clipboard.GetText();
                    if (string.IsNullOrEmpty(text)) return null;

                    byte[] utf8 = Encoding.UTF8.GetBytes(text);
                    if (utf8.Length > MaxBytes)
                    {
                        Log?.Invoke($"clipboard text is {utf8.Length} bytes, over the {MaxBytes} limit");
                        return null;
                    }

                    return new ClipboardMessage
                    {
                        Format = LooksLikeUri(text) ? "uri" : "text",
                        Text = text,
                        Hash = Fingerprint(utf8),
                        Label = Environment.MachineName,
                    };
                }

                if (IncludeImages && Clipboard.ContainsImage())
                {
                    var image = Clipboard.GetImage();
                    if (image is null) return null;

                    byte[] png = EncodePng(image);
                    if (png.Length > MaxBytes)
                    {
                        Log?.Invoke($"clipboard image is {png.Length} bytes, over the {MaxBytes} limit");
                        return null;
                    }

                    return new ClipboardMessage
                    {
                        Format = "image",
                        Mime = "image/png",
                        Bytes = Convert.ToBase64String(png),
                        Hash = Fingerprint(png),
                        Label = Environment.MachineName,
                    };
                }

                return null;
            }
            catch (COMException)
            {
                Thread.Sleep(20);
            }
        }

        Log?.Invoke("clipboard stayed locked by another process");
        return null;
    }

    /// <summary>Applies a clipboard received from the phone. UI thread only.</summary>
    public bool Apply(ClipboardMessage message)
    {
        try
        {
            if (message.Format == "image")
            {
                if (string.IsNullOrEmpty(message.Bytes)) return false;
                byte[] bytes = Convert.FromBase64String(message.Bytes);

                var decoder = BitmapFrame.Create(
                    new MemoryStream(bytes), BitmapCreateOptions.None, BitmapCacheOption.OnLoad);

                // Fingerprinted after a round trip through our own encoder,
                // because that is what Read will hand back a moment from now.
                // Hashing the bytes as they arrived compares two different PNGs
                // of the same picture, which never matches, and the image goes
                // round the loop for ever.
                _lastAppliedHash = Fingerprint(EncodePng(decoder));
                Clipboard.SetImage(decoder);
                return true;
            }

            if (string.IsNullOrEmpty(message.Text)) return false;

            // Fingerprinted here rather than taken from the message: this guard
            // is what stops the two machines handing the same string back and
            // forth for ever, and it must not depend on the other end having
            // computed it the same way.
            _lastAppliedHash = Fingerprint(Encoding.UTF8.GetBytes(message.Text));

            // SetText can throw if another app is mid-write; the retry keeps a
            // transient clash from losing the phone clipboard entirely.
            for (int attempt = 0; attempt < 5; attempt++)
            {
                try
                {
                    Clipboard.SetText(message.Text);
                    return true;
                }
                catch (COMException) { Thread.Sleep(20); }
            }
            return false;
        }
        catch (Exception ex)
        {
            Log?.Invoke($"clipboard apply failed: {ex.Message}");
            return false;
        }
    }

    private static byte[] EncodePng(BitmapSource image)
    {
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(image));
        using var stream = new MemoryStream();
        encoder.Save(stream);
        return stream.ToArray();
    }

    private static bool LooksLikeUri(string text) =>
        text.Length < 2048 &&
        !text.Contains('\n') &&
        Uri.TryCreate(text.Trim(), UriKind.Absolute, out var uri) &&
        (uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == Uri.UriSchemeHttps);

    /// <summary>Delegates to the protocol module, so both ends and the tests agree.</summary>
    public static string Fingerprint(byte[] data) => ClipboardFingerprint.Of(data);

    public void Dispose()
    {
        if (_source is null) return;
        try { RemoveClipboardFormatListener(_source.Handle); } catch { }
        _source.RemoveHook(OnMessage);
        _source.Dispose();
        _source = null;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool AddClipboardFormatListener(IntPtr hwnd);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RemoveClipboardFormatListener(IntPtr hwnd);
}
