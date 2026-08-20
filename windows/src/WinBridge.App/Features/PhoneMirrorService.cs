using System.Collections.Concurrent;
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
        if (!_windows.TryGetValue(link, out var window)) return;

        // Copied onto the dispatcher: the packet payload is a slice of a buffer
        // the receive loop is about to reuse, and the paint happens later.
        var copy = new MediaPacket(
            packet.Kind, packet.Stream, packet.Seq, packet.TimestampMs, packet.Flags,
            packet.Payload.ToArray());

        _ = OnUiThread(() => window.OnPacket(copy));
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
    }
}
