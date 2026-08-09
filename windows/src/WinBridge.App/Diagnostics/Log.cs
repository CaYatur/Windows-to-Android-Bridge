using System.IO;

namespace WinBridge.App.Diagnostics;

/// <summary>
/// A rolling file log next to the settings.
///
/// A tray app has nowhere to print to, so without this there is no way for a
/// user — or for us — to find out why a connection failed on a machine we
/// cannot attach a debugger to.
/// </summary>
public static class Log
{
    private const long MaxBytes = 512 * 1024;

    private static readonly Lock Gate = new();
    private static readonly string Path =
        System.IO.Path.Combine(Storage.BridgeStore.DefaultDirectory, "winbridge.log");

    public static event Action<string>? Written;

    public static void Write(string message)
    {
        var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}  {message}";
        Written?.Invoke($"{DateTime.Now:HH:mm:ss}  {message}");

        lock (Gate)
        {
            try
            {
                Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);

                // Keep one previous generation so a crash loop cannot erase the
                // evidence of what happened before it started looping.
                var info = new FileInfo(Path);
                if (info.Exists && info.Length > MaxBytes)
                    File.Move(Path, Path + ".1", overwrite: true);

                File.AppendAllText(Path, line + Environment.NewLine);
            }
            catch { /* logging must never be the thing that breaks the app */ }
        }
    }

    public static void Write(string message, Exception ex) =>
        Write($"{message}: {ex.GetType().Name}: {ex.Message}");

    public static string LogPath => Path;
}
