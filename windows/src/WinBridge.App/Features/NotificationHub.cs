using System.Collections.Concurrent;
using WinBridge.App.Server;
using WinBridge.App.Storage;
using WinBridge.Core.Protocol;

namespace WinBridge.App.Features;

/// <summary>One mirrored phone notification, plus where it came from.</summary>
public sealed record MirroredNotification(NotifPost Post, IPeerLink Link, DateTimeOffset Arrived);

/// <summary>
/// Holds the phone notifications currently on screen and puts them in front of
/// the user.
///
/// Balloon tips through the existing tray icon rather than WinRT toasts: a WinRT
/// toast needs a registered AUMID and a Start Menu shortcut pointing at it, and
/// an app that also ships as a portable folder cannot rely on either being
/// there. A balloon works from the icon that is already in the tray.
/// </summary>
public sealed class NotificationHub(BridgeStore store)
{
    private readonly ConcurrentDictionary<string, MirroredNotification> _live = new();

    /// <summary>Wired by the app to the tray icon: title, text, level.</summary>
    public Action<string, string?, string>? ShowToast { get; set; }

    public event Action? Changed;
    public event Action<string>? Log;

    public IReadOnlyList<MirroredNotification> Live =>
        [.. _live.Values.OrderByDescending(n => n.Arrived)];

    public bool Enabled => store.Settings.Notifications.Enabled;

    public void Toast(string title, string? text, string level = "info") =>
        ShowToast?.Invoke(title, text, level);

    public void OnPost(IPeerLink link, NotifPost post)
    {
        var settings = store.Settings.Notifications;
        if (!settings.Enabled) return;

        // Ongoing notifications are the persistent ones — a music player, a
        // navigation session, a running download. Mirroring them means a toast
        // every time a progress bar moves, so they are skipped by default and
        // still listed in the panel.
        if (post.Ongoing && settings.SkipOngoing)
        {
            _live[post.Key] = new MirroredNotification(post, link, DateTimeOffset.Now);
            Changed?.Invoke();
            return;
        }

        if (settings.BlockedPackages.Contains(post.Package, StringComparer.OrdinalIgnoreCase)) return;

        bool isNew = !_live.ContainsKey(post.Key);
        _live[post.Key] = new MirroredNotification(post, link, DateTimeOffset.Now);
        Changed?.Invoke();

        // Only toast on arrival. Android reposts a notification whenever any
        // field changes, and toasting each repost turns one message into a
        // stutter of identical popups.
        if (isNew && settings.ShowToasts)
            Toast($"{post.AppName}: {post.Title}".Trim(), post.Text, "info");
    }

    public void OnRemoved(string key)
    {
        if (_live.TryRemove(key, out _)) Changed?.Invoke();
    }

    public void Clear(IPeerLink link)
    {
        foreach (var (key, value) in _live)
            if (ReferenceEquals(value.Link, link)) _live.TryRemove(key, out _);
        Changed?.Invoke();
    }

    public async Task ReplyAsync(string key, int actionIndex, string? text, CancellationToken ct)
    {
        if (!_live.TryGetValue(key, out var notification))
        {
            Log?.Invoke("that notification is no longer on the phone");
            return;
        }

        if (!store.Settings.Notifications.AllowReply && text is not null)
        {
            Log?.Invoke("replying from the PC is turned off");
            return;
        }

        await notification.Link.SendJsonAsync(
            new NotifActionCommand { Key = key, Index = actionIndex, Text = text }, ct);
    }

    public async Task DismissAsync(string key, CancellationToken ct)
    {
        if (!_live.TryGetValue(key, out var notification)) return;
        await notification.Link.SendJsonAsync(new NotifDismiss { Key = key }, ct);
        OnRemoved(key);
    }
}
