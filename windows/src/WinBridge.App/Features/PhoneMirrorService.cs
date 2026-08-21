using System.Collections.Concurrent;
using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using WinBridge.App.Server;
using WinBridge.App.Storage;
using WinBridge.App.Ui;
using WinBridge.Core.Protocol;

namespace WinBridge.App.Features;

/// <summary>
/// The receiving half of phone-to-PC mirroring: one viewer window per phone.
///
/// Windows is the server in this protocol, but for this one stream it is the
/// client — the phone captures and this side decodes. Keeping that inversion in
/// its own service rather than in the session router is what stops the router
/// from slowly becoming a video player.
/// </summary>
public sealed class PhoneMirrorService(BridgeStore store)
{
    private readonly ConcurrentDictionary<IPeerLink, PhoneScreenWindow> _windows = new();

    /// <summary>
    /// Frames waiting to be decoded.
    ///
    /// Bounded and small: decoding is the slow half, and a backlog here is
    /// latency the viewer sees. Dropping the oldest keeps the picture current,
    /// which is the whole point of a mirror.
    /// </summary>
    private readonly BlockingCollection<Pending> _pending = new(boundedCapacity: 4);
    private Thread? _decoder;

    private readonly record struct Pending(IPeerLink Link, MediaPacket Packet);

    /// <summary>Marshals onto the WPF dispatcher; every window touch has to go through this.</summary>
    public Func<Action, Task> OnUiThread { get; set; } = action => { action(); return Task.CompletedTask; };

    public event Action<string>? Log;

    public bool IsOpen(IPeerLink link) => _windows.ContainsKey(link);

    /// <summary>
    /// Opens the viewer and asks the phone to start sending.
    ///
    /// The request may sit unanswered for a while: Android puts a consent dialog
    /// in front of screen capture on every session and nothing on this side can
    /// dismiss it, which is why the window opens immediately with an explanation
    /// rather than after the first frame.
    /// </summary>
    public async Task OpenAsync(IPeerLink link, CancellationToken ct)
    {
        if (!store.Settings.Screen.ViewPhone)
        {
            Log?.Invoke("viewing the phone screen is turned off in settings");
            return;
        }

        if (_windows.TryGetValue(link, out var existing))
        {
            await OnUiThread(() => { existing.Show(); existing.Activate(); });
            return;
        }

        PhoneScreenWindow? window = null;
        await OnUiThread(() =>
        {
            window = new PhoneScreenWindow(link);
            window.Closing += () => Close(link);
            window.Show();
        });

        if (window is null) return;
        _windows[link] = window;
        StartDecoder();

        await link.SendJsonAsync(new StreamStart
        {
            Stream = StreamIds.Name(StreamIds.PhoneScreen),
            MaxFps = store.Settings.Screen.MaxFps,
            Quality = store.Settings.Screen.Quality,
            MaxEdge = store.Settings.Screen.MaxEdge,
            Interact = true,
            Audio = store.Settings.Audio.FromPhone,
        }, ct);
    }

    public void OnInfo(IPeerLink link, StreamInfo info)
    {
        if (!_windows.TryGetValue(link, out var window)) return;
        _ = OnUiThread(() => window.OnInfo(info));
    }

    public void OnPacket(IPeerLink link, MediaPacket packet)
    {
        if (!_windows.ContainsKey(link)) return;

        // Copied because the payload is a slice of a buffer the receive loop is
        // about to reuse, and the decode happens later on another thread.
        var copy = new MediaPacket(
            packet.Kind, packet.Stream, packet.Seq, packet.TimestampMs, packet.Flags,
            packet.Payload.ToArray());

        if (_pending.TryAdd(new Pending(link, copy))) return;

        // Full: throw away the oldest rather than block the receive loop, which
        // also carries the heartbeat and every command.
        if (_pending.TryTake(out _)) _pending.TryAdd(new Pending(link, copy));
    }

    private void StartDecoder()
    {
        if (_decoder is not null) return;

        _decoder = new Thread(DecodeLoop)
        {
            IsBackground = true,
            Name = "winbridge-phone-decode",
        };
        _decoder.Start();
    }

    /// <summary>
    /// Decodes tiles and hands the UI thread finished pixels.
    ///
    /// Decoding on the dispatcher — which is where this started — spends the
    /// entire frame budget before anything is painted, and looks exactly like
    /// network lag while being nothing of the sort.
    /// </summary>
    private void DecodeLoop()
    {
        var tiles = new List<PhoneScreenWindow.DecodedTile>(64);

        foreach (var item in _pending.GetConsumingEnumerable())
        {
            if (!_windows.TryGetValue(item.Link, out var window)) continue;

            tiles.Clear();
            var packet = item.Packet;

            foreach (var (index, jpeg) in TileCodec.ReadTiles(packet.Payload))
            {
                try
                {
                    using var stream = new MemoryStream(jpeg.ToArray(), writable: false);
                    var frame = BitmapFrame.Create(stream, BitmapCreateOptions.None, BitmapCacheOption.OnLoad);
                    var converted = new FormatConvertedBitmap(frame, PixelFormats.Bgra32, null, 0);

                    int stride = converted.PixelWidth * 4;
                    var pixels = new byte[stride * converted.PixelHeight];
                    converted.CopyPixels(pixels, stride, 0);

                    var (x, y) = window.TileOrigin(index);
                    tiles.Add(new PhoneScreenWindow.DecodedTile(
                        x, y, converted.PixelWidth, converted.PixelHeight, pixels, stride));
                }
                catch
                {
                    // A torn tile is one square, not a reason to drop the stream.
                }
            }

            if (tiles.Count == 0 && !packet.Flags.HasFlag(MediaFlags.Keyframe)) continue;

            var batch = tiles.ToArray();
            bool keyframe = packet.Flags.HasFlag(MediaFlags.Keyframe);
            uint timestamp = packet.TimestampMs;
            int bytes = packet.Payload.Length;

            _ = OnUiThread(() => window.Present(batch, keyframe, timestamp, bytes));
        }
    }

    public void Close(IPeerLink link)
    {
        if (!_windows.TryRemove(link, out var window)) return;

        _ = link.SendJsonAsync(
            new StreamStop { Stream = StreamIds.Name(StreamIds.PhoneScreen) }, CancellationToken.None);
        _ = OnUiThread(() => { if (window.IsLoaded) window.Close(); });
    }

    public void CloseAll()
    {
        foreach (var link in _windows.Keys.ToArray()) Close(link);
        _pending.CompleteAdding();
    }
}
