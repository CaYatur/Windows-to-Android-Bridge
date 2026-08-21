using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Threading;
using System.Windows;
using Microsoft.Win32;
using WinBridge.App.Features;
using WinBridge.App.Features.Automation;
using WinBridge.App.Localization;
using WinBridge.App.Server;
using WinBridge.App.Storage;
using WinBridge.App.Ui;
using Forms = System.Windows.Forms;

namespace WinBridge.App;

// Fully qualified: enabling both WPF and WinForms puts two `Application` types
// in scope, and the tray icon needs the WinForms one.
public partial class App : System.Windows.Application
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunValue = "WinBridge";

    private Mutex? _singleInstance;
    private Forms.NotifyIcon? _tray;
    private MainWindow? _window;
    private CancellationTokenSource? _shellWatch;

    public static BridgeStore Store { get; private set; } = null!;
    public static BridgeServer Server { get; private set; } = null!;
    public static readonly List<string> RecentLog = [];

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // Before the instance check, so it can be run while the tray app is
        // already listening on the port.
        if (e.Args.Contains("--selftest-capture"))
        {
            int code = Features.CaptureSelfTest.Run(
                ArgumentsAfter(e.Args, "--selftest-capture").FirstOrDefault()
                    ?? "capture-selftest.png");
            Shutdown(code);
            return;
        }

        // Explorer starts one process per selected item, so the paths are
        // spooled before the instance check: whichever copy wins the mutex picks
        // the whole batch up, and the rest can leave immediately.
        var sendPaths = ArgumentsAfter(e.Args, "--send");
        if (sendPaths.Count > 0) ShellIntegration.Spool(sendPaths);

        // A second copy would fight over the listening port and the RFCOMM
        // service record, so hand focus to the running one and leave.
        _singleInstance = new Mutex(true, @"Local\WinBridge.SingleInstance", out bool isFirst);
        if (!isFirst)
        {
            // Hand the request to the copy that owns the port instead of dying
            // silently, which is what --pair did before.
            if (e.Args.Contains("--pair")) ShellIntegration.RequestPairing();
            Shutdown();
            return;
        }

        // Anything that escapes below would otherwise kill a process with no
        // console and no window, leaving the user with a tray icon that never
        // appeared and nothing to look at.
        DispatcherUnhandledException += (_, args) =>
        {
            Diagnostics.Log.Write("unhandled UI exception", args.Exception);
            args.Handled = true;
        };
        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
        {
            if (args.ExceptionObject is Exception ex) Diagnostics.Log.Write("unhandled exception", ex);
        };

        Diagnostics.Log.Written += OnLogLine;
        Diagnostics.Log.Write($"starting {Environment.ProcessPath}");

        Store = new BridgeStore();
        Strings.Apply(Store.Settings.Language);
        ApplyAutostart(Store.Settings.StartWithWindows);

        Server = new BridgeServer(Store);
        Server.SessionsChanged += () => Dispatcher.Invoke(UpdateTrayTooltip);

        BuildTray();
        WireFeatures();

        try
        {
            await Server.StartAsync();
        }
        catch (Exception ex)
        {
            Diagnostics.Log.Write("could not start", ex);
        }

        // --pair opens the pairing window straight away, for a shortcut or a
        // script. It shows the same window the tray menu does; the key still
        // only ever appears on screen.
        if (e.Args.Contains("--pair"))
        {
            ShowPairing();
            return;
        }

        // Launched only to send something: no window, just the transfer.
        if (sendPaths.Count > 0 && Store.Settings.FirstRunDone) return;

        // The one time we surface ourselves uninvited is right after install,
        // so the user knows where the app went.
        if (!Store.Settings.FirstRunDone)
        {
            Store.Update(s => s with { FirstRunDone = true });
            ShowMainWindow();
        }
    }

    /// <summary>
    /// Connects the feature services to the things only the application owns:
    /// the tray icon, a dialog, and the Explorer hand-off.
    ///
    /// Until this runs, <c>Approve</c> is null and every automation that can
    /// execute anything is refused — which is the right default, but not a
    /// useful product.
    /// </summary>
    private void WireFeatures()
    {
        Server.Notifications.ShowToast = (title, text, level) => Dispatcher.BeginInvoke(() =>
        {
            if (_tray is null) return;
            _tray.BalloonTipTitle = title.Length > 60 ? title[..60] : title;
            _tray.BalloonTipText = text ?? "";
            _tray.BalloonTipIcon = level switch
            {
                "error" => Forms.ToolTipIcon.Error,
                "warn" => Forms.ToolTipIcon.Warning,
                _ => Forms.ToolTipIcon.Info,
            };
            _tray.ShowBalloonTip(5000);
        });

        Server.Automations.Approve = ApprovalWindow.AskAsync;

        Server.Files.AskToAccept = async (offer, folder) =>
        {
            string size = offer.Size >= 1024 * 1024
                ? $"{offer.Size / 1024.0 / 1024:0.#} MB"
                : $"{Math.Max(1, offer.Size / 1024)} KB";

            bool accept = await Dispatcher.InvokeAsync(() => MessageBox.Show(
                Strings.Format("xfer.incoming.body", offer.Name, size, folder),
                Strings.Get("xfer.incoming.title"),
                MessageBoxButton.YesNo,
                MessageBoxImage.Question) == MessageBoxResult.Yes).Task;

            return accept ? folder : null;
        };

        Server.Files.Completed += (saved, name, error) =>
        {
            if (saved is null) Server.Notifications.Toast(Strings.Get("xfer.failed"), $"{name}: {error}", "error");
            else Server.Notifications.Toast(Strings.Get("xfer.received"), Path.GetFileName(saved));

            if (saved is not null && Store.Settings.Files.OpenFolderWhenDone)
            {
                try
                {
                    System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{saved}\"");
                }
                catch { }
            }
        };

        ShellIntegration.Register(Store.Settings.Files.ShellMenu);

        _shellWatch = new CancellationTokenSource();
        ShellIntegration.Watch(SendToPhone, _shellWatch.Token);
        ShellIntegration.WatchPairingRequests(
            () => Dispatcher.BeginInvoke(ShowPairing), _shellWatch.Token);
    }

    /// <summary>Sends a batch to the phone that has been connected longest.</summary>
    public static void SendToPhone(IReadOnlyList<string> paths)
    {
        var session = Server.Primary;
        if (session is null)
        {
            Server.Notifications.Toast(Strings.Get("nophone.title"), Strings.Get("nophone.body"), "warn");
            return;
        }

        _ = Task.Run(async () =>
        {
            try
            {
                int count = await Server.Files.SendAsync(session, paths, CancellationToken.None);
                Server.Notifications.Toast(
                    Strings.Format("xfer.sending", session.PeerName),
                    count == 1 ? Path.GetFileName(paths[0]) : Strings.Format("xfer.items", count));
            }
            catch (Exception ex)
            {
                Server.Notifications.Toast(Strings.Get("xfer.failed"), ex.Message, "error");
            }
        });
    }

    private static List<string> ArgumentsAfter(string[] args, string flag)
    {
        int at = Array.IndexOf(args, flag);
        if (at < 0) return [];
        return [.. args.Skip(at + 1).Where(a => !a.StartsWith("--", StringComparison.Ordinal))];
    }

    private void OnLogLine(string line)
    {
        lock (RecentLog)
        {
            RecentLog.Add(line);
            if (RecentLog.Count > 300) RecentLog.RemoveAt(0);
        }
        Dispatcher.BeginInvoke(() => _window?.AppendLog(line));
    }

    private void BuildTray()
    {
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add(Strings.Get("tray.open"), null, (_, _) => ShowMainWindow());
        menu.Items.Add(Strings.Get("tray.pair"), null, (_, _) => ShowPairing());
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add(Strings.Get("tray.send"), null, (_, _) => PickAndSend());
        menu.Items.Add(Strings.Get("tray.phonescreen"), null, (_, _) => OpenPhoneScreen());
        menu.Items.Add(Strings.Get("tray.clipboard"), null, (_, _) => PushClipboard());
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add(Strings.Get("tray.exit"), null, (_, _) => ExitApp());

        _tray = new Forms.NotifyIcon
        {
            Icon = CreateTrayIcon(),
            Visible = true,
            ContextMenuStrip = menu,
            Text = Strings.Get("tray.tooltip.idle"),
        };
        _tray.DoubleClick += (_, _) => ShowMainWindow();
    }

    private void PickAndSend()
    {
        var dialog = new Microsoft.Win32.OpenFileDialog
        {
            Title = Strings.Get("tray.send"),
            Multiselect = true,
            CheckFileExists = true,
        };
        if (dialog.ShowDialog() == true) SendToPhone(dialog.FileNames);
    }

    private void OpenPhoneScreen()
    {
        var session = Server.Primary;
        if (session is null)
        {
            Server.Notifications.Toast(Strings.Get("nophone.title"), Strings.Get("nophone.body"), "warn");
            return;
        }
        _ = Server.PhoneMirror.OpenAsync(session, CancellationToken.None);
    }

    private void PushClipboard()
    {
        var session = Server.Primary;
        if (session is null) return;
        _ = session.SendClipboardAsync(CancellationToken.None);
    }

    private void UpdateTrayTooltip()
    {
        if (_tray is null) return;
        var sessions = Server.Sessions;

        // NotifyIcon.Text silently truncates past 63 characters.
        string text = sessions.Count == 0
            ? Strings.Get("tray.tooltip.idle")
            : Strings.Format("tray.tooltip.connected", string.Join(", ", sessions.Select(s => s.PeerName)));

        _tray.Text = text.Length > 62 ? text[..62] : text;
    }

    /// <summary>
    /// Drawn rather than shipped as a binary asset: it keeps the repo free of
    /// an opaque .ico and renders correctly at whatever DPI the tray asks for.
    /// </summary>
    private static Icon CreateTrayIcon()
    {
        using var bitmap = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bitmap))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);

            using var monitor = new SolidBrush(Color.FromArgb(255, 110, 86, 207));
            using var phone = new SolidBrush(Color.FromArgb(255, 232, 232, 236));

            g.FillRoundedRectangle(monitor, new Rectangle(2, 6, 18, 13), 3);
            g.FillRectangle(monitor, new Rectangle(9, 19, 4, 3));
            g.FillRoundedRectangle(phone, new Rectangle(21, 10, 9, 16), 2);
        }

        IntPtr handle = bitmap.GetHicon();
        try { return (Icon)Icon.FromHandle(handle).Clone(); }
        finally { DestroyIcon(handle); }
    }

    [System.Runtime.InteropServices.DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyIcon(IntPtr handle);

    public void ShowMainWindow()
    {
        if (_window is null || !_window.IsLoaded)
        {
            _window = new MainWindow();
            _window.Closed += (_, _) => _window = null;
        }
        _window.Show();
        if (_window.WindowState == WindowState.Minimized) _window.WindowState = WindowState.Normal;
        _window.Activate();
    }

    public void ShowPairing()
    {
        ShowMainWindow();
        _window?.StartPairing();
    }

    public static void ApplyAutostart(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: true);
            if (key is null) return;

            if (enabled)
            {
                string exe = Environment.ProcessPath
                             ?? Path.ChangeExtension(typeof(App).Assembly.Location, ".exe");
                key.SetValue(RunValue, $"\"{exe}\" --tray");
            }
            else
            {
                key.DeleteValue(RunValue, throwOnMissingValue: false);
            }
        }
        catch
        {
            // A locked-down profile can deny this; the app still runs, it just
            // will not come back on its own after a reboot.
        }
    }

    private void ExitApp()
    {
        if (_tray is not null)
        {
            _tray.Visible = false;
            _tray.Dispose();
            _tray = null;
        }
        Shutdown();
    }

    protected override async void OnExit(ExitEventArgs e)
    {
        _shellWatch?.Cancel();
        if (Server is not null) await Server.DisposeAsync();
        _tray?.Dispose();
        _singleInstance?.Dispose();
        base.OnExit(e);
    }
}

internal static class GraphicsExtensions
{
    public static void FillRoundedRectangle(this Graphics g, Brush brush, Rectangle bounds, int radius)
    {
        using var path = new System.Drawing.Drawing2D.GraphicsPath();
        int d = radius * 2;
        path.AddArc(bounds.X, bounds.Y, d, d, 180, 90);
        path.AddArc(bounds.Right - d, bounds.Y, d, d, 270, 90);
        path.AddArc(bounds.Right - d, bounds.Bottom - d, d, d, 0, 90);
        path.AddArc(bounds.X, bounds.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        g.FillPath(brush, path);
    }
}
