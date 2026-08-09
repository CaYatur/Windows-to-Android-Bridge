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
