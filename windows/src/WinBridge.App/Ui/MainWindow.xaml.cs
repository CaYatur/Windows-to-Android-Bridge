using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using WinBridge.App.Carriers;
using WinBridge.App.Localization;
using WinBridge.App.Server;

namespace WinBridge.App.Ui;

public partial class MainWindow : Window
{
    private readonly DispatcherTimer _refresh = new() { Interval = TimeSpan.FromSeconds(1) };
    private bool _loading = true;

    public MainWindow()
    {
        InitializeComponent();
        ApplyStrings();
        LoadSettings();

        lock (App.RecentLog) LogText.Text = string.Join(Environment.NewLine, App.RecentLog);

        _refresh.Tick += (_, _) => RefreshStatus();
        _refresh.Start();

        RefreshStatus();
        RefreshDevices();

        Closed += (_, _) => _refresh.Stop();
    }

    private void ApplyStrings()
    {
        Title = Strings.Get("window.title");
        TabStatus.Header = Strings.Get("tab.status");
        TabDevices.Header = Strings.Get("tab.devices");
        TabSettings.Header = Strings.Get("tab.settings");
        TabLog.Header = Strings.Get("tab.log");

        LblConnections.Text = Strings.Get("status.connections");
        LblHost.Text = Strings.Get("status.host");
        LblPaired.Text = Strings.Get("devices.paired");
        BtnPair.Content = Strings.Get("tray.pair");

        ChkStartup.Content = Strings.Get("settings.startup");
        ChkBluetooth.Content = Strings.Get("settings.bluetooth");
        ChkLan.Content = Strings.Get("settings.lan");
        ChkPreferBt.Content = Strings.Get("settings.preferbt");
        ChkRemotePairing.Content = Strings.Get("settings.remotepairing");
        LblRemoteHint.Text = Strings.Get("settings.remotepairing.hint");
        LblPort.Text = Strings.Get("settings.port");
        LblLanguage.Text = Strings.Get("settings.language");
        LblRestartNote.Text = Strings.Get("settings.restartnote");
    }

    private void LoadSettings()
    {
        var s = App.Store.Settings;
        ChkStartup.IsChecked = s.StartWithWindows;
        ChkBluetooth.IsChecked = s.BluetoothEnabled;
        ChkLan.IsChecked = s.LanEnabled;
        ChkPreferBt.IsChecked = s.PreferBluetooth;
        ChkRemotePairing.IsChecked = s.AllowRemotePairing;
        TxtPort.Text = s.TcpPort.ToString();

        CmbLanguage.Items.Clear();
        CmbLanguage.Items.Add(Strings.Get("settings.language.auto"));
        CmbLanguage.Items.Add("English");
        CmbLanguage.Items.Add("Türkçe");
        CmbLanguage.SelectedIndex = s.Language switch { "en" => 1, "tr" => 2, _ => 0 };

        LoadFeatureSettings(s);

        _loading = false;
    }

    private void RefreshStatus()
    {
        ConnectionsPanel.Children.Clear();
        var sessions = App.Server.Sessions;

        if (sessions.Count == 0)
        {
            ConnectionsPanel.Children.Add(Dim(Strings.Get("status.none")));
        }
        else
        {
            foreach (var session in sessions)
            {
                var row = new StackPanel { Margin = new Thickness(0, 0, 0, 8) };
                row.Children.Add(new TextBlock
                {
                    Text = session.PeerName,
                    FontFamily = new FontFamily("Segoe UI Semibold"),
                });

                string carrier = session.Carrier == "bluetooth"
                    ? Strings.Get("status.bluetooth")
                    : Strings.Get("status.lan");
                var elapsed = DateTimeOffset.UtcNow - session.ConnectedAt;
                row.Children.Add(Dim($"{Strings.Format("status.carrier", carrier)} · {elapsed:hh\\:mm\\:ss}"));
                ConnectionsPanel.Children.Add(row);
            }
        }

        HostPanel.Children.Clear();
        HostPanel.Children.Add(new TextBlock
        {
            Text = Environment.MachineName,
            FontFamily = new FontFamily("Segoe UI Semibold"),
            Margin = new Thickness(0, 0, 0, 6),
        });

        var addresses = TcpCarrier.LocalAddresses();
        HostPanel.Children.Add(Dim($"{Strings.Get("status.addresses")}: " +
            (addresses.Count > 0
                ? string.Join(", ", addresses.Select(a => $"{a}:{App.Store.Settings.TcpPort}"))
                : "—")));
    }

    private void RefreshDevices()
    {
        DevicesPanel.Children.Clear();
        var devices = App.Store.Settings.Devices;

        if (devices.Count == 0)
        {
            DevicesPanel.Children.Add(Dim(Strings.Get("devices.none")));
            return;
        }

        foreach (var device in devices.OrderByDescending(d => d.LastSeen))
        {
            var card = new Border
            {
                Background = (Brush)FindResource("Card"),
                CornerRadius = new CornerRadius(8),
                Padding = new Thickness(14),
                Margin = new Thickness(0, 0, 0, 10),
            };

            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var info = new StackPanel();
            info.Children.Add(new TextBlock
            {
                Text = device.Name,
                FontFamily = new FontFamily("Segoe UI Semibold"),
            });
            info.Children.Add(Dim(Strings.Format("devices.lastseen", device.LastSeen.LocalDateTime.ToString("g"))));
            Grid.SetColumn(info, 0);
            grid.Children.Add(info);

            var forget = new Button
            {
                Content = Strings.Get("devices.forget"),
                Style = (Style)FindResource("Subtle"),
                VerticalAlignment = VerticalAlignment.Center,
            };
            string id = device.DeviceId, name = device.Name;
            forget.Click += (_, _) =>
            {
                var answer = MessageBox.Show(
                    Strings.Format("devices.forget.confirm", name),
                    Strings.Get("app.name"),
                    MessageBoxButton.YesNo, MessageBoxImage.Question);
                if (answer != MessageBoxResult.Yes) return;

                App.Store.ForgetDevice(id);
                RefreshDevices();
            };
            Grid.SetColumn(forget, 1);
            grid.Children.Add(forget);

            card.Child = grid;
            DevicesPanel.Children.Add(card);
        }
    }

    private TextBlock Dim(string text) => new()
    {
        Text = text,
        Style = (Style)FindResource("Dim"),
    };

    public void StartPairing()
    {
        var dialog = new PairingWindow { Owner = this };
        dialog.ShowDialog();
        RefreshDevices();
    }

    private void OnPairClick(object sender, RoutedEventArgs e) => StartPairing();

    private void OnSettingChanged(object sender, RoutedEventArgs e)
    {
        if (_loading) return;

        App.Store.Update(s => s with
        {
            StartWithWindows = ChkStartup.IsChecked == true,
            BluetoothEnabled = ChkBluetooth.IsChecked == true,
            LanEnabled = ChkLan.IsChecked == true,
            PreferBluetooth = ChkPreferBt.IsChecked == true,
            AllowRemotePairing = ChkRemotePairing.IsChecked == true,
        });

        App.ApplyAutostart(ChkStartup.IsChecked == true);
        App.Server.ApplySettings();
    }


    private void LoadFeatureSettings(Storage.BridgeSettings s)
    {
        ChkClipToPhone.IsChecked = s.Clipboard.ToPhone;
        ChkClipFromPhone.IsChecked = s.Clipboard.FromPhone;
        ChkClipImages.IsChecked = s.Clipboard.Images;

        ChkFiles.IsChecked = s.Files.Enabled;
        ChkFilesAuto.IsChecked = s.Files.AutoAccept;
        ChkShellMenu.IsChecked = s.Files.ShellMenu;
        TxtFolder.Text = App.Store.DownloadFolder;

        ChkScreenShare.IsChecked = s.Screen.Share;
        ChkScreenAudio.IsChecked = s.Screen.Audio;
        ChkViewPhone.IsChecked = s.Screen.ViewPhone;
        ChkInput.IsChecked = s.Input.Accept;

        ChkAudioToPhone.IsChecked = s.Audio.ToPhone;
        ChkAudioFromPhone.IsChecked = s.Audio.FromPhone;
        ChkMicToPhone.IsChecked = s.Audio.MicToPhone;
        ChkMicFromPhone.IsChecked = s.Audio.MicFromPhone;

        ChkNotifications.IsChecked = s.Notifications.Enabled;
        ChkNotifToasts.IsChecked = s.Notifications.ShowToasts;
        ChkNotifReply.IsChecked = s.Notifications.AllowReply;

        ChkLockOnAway.IsChecked = s.Presence.LockOnAway;

        ChkAutomations.IsChecked = s.Automation.Enabled;
        ChkAutoAuthoring.IsChecked = s.Automation.Authoring;
        ChkShell.IsChecked = s.Automation.Shell;
        ChkAllowElevated.IsChecked = s.Automation.AllowElevated;
        ChkAllowNetwork.IsChecked = s.Automation.AllowNetwork;
        ChkAllowFileWrite.IsChecked = s.Automation.AllowFileWrite;
        ChkPanic.IsChecked = s.Automation.PanicStop;
        CmbTrustMode.SelectedIndex = s.Automation.TrustMode == "trusted" ? 1 : 0;
        TxtAllowlist.Text = string.Join(Environment.NewLine, s.Automation.Allowlist);

        LoadAudioDevices(s.Audio.RenderDevice);
        RefreshAuditLog();
    }

    /// <summary>
    /// Lists render endpoints. The first entry is the system default rather than
    /// a named device, so someone who plugs in headphones does not find their
    /// phone audio still going to the speakers they were using last week.
    /// </summary>
    private void LoadAudioDevices(string? selected)
    {
        CmbRenderDevice.Items.Clear();
        CmbRenderDevice.Items.Add(new ComboBoxItem { Content = "System default", Tag = "" });

        int index = 0;
        foreach (var device in App.Server.Audio.Devices().Where(d => d.Flow == "render"))
        {
            CmbRenderDevice.Items.Add(new ComboBoxItem { Content = device.Name, Tag = device.Id });
            index++;
            if (device.Id == selected) CmbRenderDevice.SelectedIndex = index;
        }

        if (CmbRenderDevice.SelectedIndex < 0) CmbRenderDevice.SelectedIndex = 0;
    }

    private void RefreshAuditLog()
    {
        var runs = App.Server.Automations.Store.RecentRuns(25);
        LblAuditLog.Text = runs.Count == 0
            ? "Nothing has run yet."
            : string.Join(Environment.NewLine, runs.Select(r =>
                $"{r.At.LocalDateTime:yyyy-MM-dd HH:mm:ss}  {(r.Ok ? "ok " : "FAIL")}  {r.Name}  ({r.Device}, {r.DurationMs} ms)"
                + (r.Detail is null ? "" : $"  — {r.Detail}")));
    }

    private void OnFeatureChanged(object sender, RoutedEventArgs e)
    {
        if (_loading) return;

        var allowlist = TxtAllowlist.Text
            .Split(['\n', '\r'], StringSplitOptions.RemoveEmptyEntries)
            .Select(line => line.Trim())
            .Where(line => line.Length > 0)
            .ToList();

        string folder = TxtFolder.Text.Trim();

        App.Store.Update(s => s with
        {
            Clipboard = s.Clipboard with
            {
                ToPhone = ChkClipToPhone.IsChecked == true,
                FromPhone = ChkClipFromPhone.IsChecked == true,
                Images = ChkClipImages.IsChecked == true,
            },
            Files = s.Files with
            {
                Enabled = ChkFiles.IsChecked == true,
                AutoAccept = ChkFilesAuto.IsChecked == true,
                ShellMenu = ChkShellMenu.IsChecked == true,
                Folder = folder.Length == 0 ? null : folder,
            },
            Screen = s.Screen with
            {
                Share = ChkScreenShare.IsChecked == true,
                Audio = ChkScreenAudio.IsChecked == true,
                ViewPhone = ChkViewPhone.IsChecked == true,
                Interact = ChkInput.IsChecked == true,
            },
            Input = s.Input with { Accept = ChkInput.IsChecked == true },
            Audio = s.Audio with
            {
                ToPhone = ChkAudioToPhone.IsChecked == true,
                FromPhone = ChkAudioFromPhone.IsChecked == true,
                MicToPhone = ChkMicToPhone.IsChecked == true,
                MicFromPhone = ChkMicFromPhone.IsChecked == true,
            },
            Notifications = s.Notifications with
            {
                Enabled = ChkNotifications.IsChecked == true,
                ShowToasts = ChkNotifToasts.IsChecked == true,
                AllowReply = ChkNotifReply.IsChecked == true,
            },
            Presence = s.Presence with { LockOnAway = ChkLockOnAway.IsChecked == true },
            Automation = s.Automation with
            {
                Enabled = ChkAutomations.IsChecked == true,
                Authoring = ChkAutoAuthoring.IsChecked == true,
                Shell = ChkShell.IsChecked == true,
                AllowElevated = ChkAllowElevated.IsChecked == true,
                AllowNetwork = ChkAllowNetwork.IsChecked == true,
                AllowFileWrite = ChkAllowFileWrite.IsChecked == true,
                PanicStop = ChkPanic.IsChecked == true,
                TrustMode = CmbTrustMode.SelectedIndex == 1 ? "trusted" : "strict",
                Allowlist = allowlist,
            },
        });

        Features.ShellIntegration.Register(ChkShellMenu.IsChecked == true);

        // Tells the running services and every connected phone about the change,
        // so a toggle takes effect now rather than at the next reconnect.
        App.Server.ApplySettings();
        RefreshAuditLog();
    }

    private void OnTrustModeChanged(object sender, SelectionChangedEventArgs e) =>
        OnFeatureChanged(sender, e);

    private void OnAudioDeviceChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loading) return;
        string id = (CmbRenderDevice.SelectedItem as ComboBoxItem)?.Tag as string ?? "";
        App.Store.Update(s => s with
        {
            Audio = s.Audio with { RenderDevice = id.Length == 0 ? null : id },
        });
        App.Server.ApplySettings();
    }

    private void OnPortChanged(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        if (!int.TryParse(TxtPort.Text, out int port) || port is < 1024 or > 65535)
        {
            TxtPort.Text = App.Store.Settings.TcpPort.ToString();
            return;
        }
        App.Store.Update(s => s with { TcpPort = port });
    }

    private void OnLanguageChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loading) return;

        string language = CmbLanguage.SelectedIndex switch { 1 => "en", 2 => "tr", _ => "auto" };
        App.Store.Update(s => s with { Language = language });
        Strings.Apply(language);
        ApplyStrings();
        RefreshStatus();
        RefreshDevices();
    }

    public void AppendLog(string line)
    {
        LogText.Text = LogText.Text.Length == 0 ? line : LogText.Text + Environment.NewLine + line;
        LogScroll.ScrollToEnd();
    }
}
