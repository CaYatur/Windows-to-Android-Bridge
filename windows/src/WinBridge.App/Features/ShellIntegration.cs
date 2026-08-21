using System.IO;
using System.Threading;
using Microsoft.Win32;
using WinBridge.App.Storage;

namespace WinBridge.App.Features;

/// <summary>
/// The Explorer right-click entry, and the hand-off from the copy of the app
/// Explorer launches to the one already running.
///
/// Explorer starts a separate process for every selected item, which for a
/// single-instance app means the extra copies have to pass their paths to the
/// live one and exit. That hand-off is a spool directory plus a named event
/// rather than a pipe: a pipe needs the server up before the client writes, and
/// the whole point is that the second process should not care whether it lost
/// the race. Files land on disk, the event nudges, and a short debounce
/// coalesces a multi-select into one batch instead of fifteen transfers.
/// </summary>
public static class ShellIntegration
{
    private const string VerbKey = @"Software\Classes\*\shell\WinBridge.SendToPhone";
    private const string FolderVerbKey = @"Software\Classes\Directory\shell\WinBridge.SendToPhone";
    private const string BackgroundVerbKey = @"Software\Classes\Directory\Background\shell\WinBridge.SendToPhone";
    private const string EventName = @"Local\WinBridge.Outbox";
    private const string PairingEventName = @"Local\WinBridge.ShowPairing";

    private static string SpoolDirectory => Path.Combine(BridgeStore.DefaultDirectory, "outbox");

    public static void Register(bool enabled, string label = "Send to phone (WinBridge)")
    {
        try
        {
            if (!enabled)
            {
                Registry.CurrentUser.DeleteSubKeyTree(VerbKey, throwOnMissingSubKey: false);
                Registry.CurrentUser.DeleteSubKeyTree(FolderVerbKey, throwOnMissingSubKey: false);
                Registry.CurrentUser.DeleteSubKeyTree(BackgroundVerbKey, throwOnMissingSubKey: false);
                return;
            }

            string exe = Environment.ProcessPath ?? "";
            if (string.IsNullOrEmpty(exe)) return;

            Write(VerbKey, label, exe, "\"%1\"");
            Write(FolderVerbKey, label, exe, "\"%1\"");
            Write(BackgroundVerbKey, label, exe, "\"%V\"");
        }
        catch
        {
            // A policy-locked profile can refuse HKCU writes. The in-app picker
            // and the share sheet still work, so this is not worth failing over.
        }
    }

    private static void Write(string path, string label, string exe, string argument)
    {
        using var key = Registry.CurrentUser.CreateSubKey(path);
        key.SetValue(null, label);
        key.SetValue("Icon", $"\"{exe}\",0");

        using var command = key.CreateSubKey("command");
        command.SetValue(null, $"\"{exe}\" --send {argument}");
    }

    /// <summary>
    /// Asks the already-running copy to open the pairing window.
    ///
    /// Without this, `WinBridge.exe --pair` while the tray app is running exits
    /// at the single-instance check and does nothing at all — which is exactly
    /// when someone would reach for it.
    /// </summary>
    public static bool RequestPairing()
    {
        try
        {
            using var signal = new EventWaitHandle(false, EventResetMode.AutoReset, PairingEventName);
            signal.Set();
            return true;
        }
        catch { return false; }
    }

    /// <summary>Watches for that request from the running instance.</summary>
    public static void WatchPairingRequests(Action onRequest, CancellationToken ct)
    {
        var thread = new Thread(() =>
        {
            using var signal = new EventWaitHandle(false, EventResetMode.AutoReset, PairingEventName);
            while (!ct.IsCancellationRequested)
            {
                if (signal.WaitOne(TimeSpan.FromSeconds(1))) onRequest();
            }
        })
        {
            IsBackground = true,
            Name = "winbridge-pairing-signal",
        };
        thread.Start();
    }

    /// <summary>Called by the copy Explorer launched. Returns immediately.</summary>
    public static void Spool(IEnumerable<string> paths)
    {
        var wanted = paths.Where(p => File.Exists(p) || Directory.Exists(p)).ToArray();
        if (wanted.Length == 0) return;

        try
        {
            Directory.CreateDirectory(SpoolDirectory);
            File.WriteAllLines(Path.Combine(SpoolDirectory, $"{Guid.NewGuid():N}.txt"), wanted);

            using var signal = new EventWaitHandle(false, EventResetMode.AutoReset, EventName);
            signal.Set();
        }
        catch { }
    }

    /// <summary>
    /// Watches the spool from the running instance. The debounce is what turns
    /// "fifteen processes started at once" back into one batch the user
    /// recognises as the selection they made.
    /// </summary>
    public static void Watch(Action<IReadOnlyList<string>> onFiles, CancellationToken ct)
    {
        var thread = new Thread(() =>
        {
            using var signal = new EventWaitHandle(false, EventResetMode.AutoReset, EventName);

            while (!ct.IsCancellationRequested)
            {
                if (!signal.WaitOne(TimeSpan.FromSeconds(1))) continue;

                // Explorer fires the rest of the selection within milliseconds.
                Thread.Sleep(400);

                var collected = Drain();
                if (collected.Count > 0) onFiles(collected);
            }
        })
        {
            IsBackground = true,
            Name = "winbridge-outbox",
        };
        thread.Start();
    }

    private static List<string> Drain()
    {
        var collected = new List<string>();
        try
        {
            if (!Directory.Exists(SpoolDirectory)) return collected;

            foreach (string file in Directory.EnumerateFiles(SpoolDirectory, "*.txt"))
            {
                try
                {
                    collected.AddRange(File.ReadAllLines(file));
                    File.Delete(file);
                }
                catch { }
            }
        }
        catch { }

        return [.. collected.Distinct(StringComparer.OrdinalIgnoreCase)];
    }
}
