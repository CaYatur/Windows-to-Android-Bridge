using System.IO;
using System.Windows;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using QRCoder;
using WinBridge.App.Localization;
using WinBridge.App.Server;

namespace WinBridge.App.Ui;

public partial class PairingWindow : Window
{
    private readonly DispatcherTimer _tick = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly PairingOffer _offer;
    private int _deviceCountAtStart;

    public PairingWindow()
    {
        InitializeComponent();

        Title = Strings.Get("pair.title");
        LblScan.Text = Strings.Get("pair.scan");
        LblPin.Text = Strings.Get("pair.pin");
        LblBluetoothNote.Text = Strings.Get("pair.bluetoothnote");
        BtnClose.Content = Strings.Get("pair.close");

        _deviceCountAtStart = App.Store.Settings.Devices.Count;

        // QR carries the key itself, so it never crosses the network. The PIN is
        // shown alongside only as a fallback for phones that cannot scan.
        _offer = App.Server.OpenPairing(PairingMethod.Qr);
        LblPinValue.Text = string.Empty;
        LblPin.Visibility = Visibility.Collapsed;

        QrImage.Source = RenderQr(_offer.QrPayload);

        _tick.Tick += OnTick;
        _tick.Start();
        UpdateCountdown();

        Closed += (_, _) =>
        {
            _tick.Stop();
            if (App.Server.Pairing.IsOpen) App.Server.Pairing.Close();
        };
    }

    private void OnTick(object? sender, EventArgs e)
    {
        UpdateCountdown();

        // A new entry in the store is the signal that a phone completed the
        // handshake; the pairing service closes itself at that point.
        var devices = App.Store.Settings.Devices;
        if (devices.Count > _deviceCountAtStart)
        {
            _deviceCountAtStart = devices.Count;
            var newest = devices.OrderByDescending(d => d.LastSeen).First();
            LblResult.Text = Strings.Format("pair.success", newest.Name);
            LblExpires.Text = string.Empty;
            _tick.Stop();
        }
    }

    private void UpdateCountdown()
    {
        int seconds = (int)Math.Max(0, (_offer.ExpiresAt - DateTimeOffset.UtcNow).TotalSeconds);
        LblExpires.Text = seconds > 0
            ? Strings.Format("pair.expires", seconds)
            : Strings.Get("pair.expired");

        if (seconds == 0)
        {
            QrImage.Opacity = 0.25;
            _tick.Stop();
        }
    }

    private static BitmapImage RenderQr(string payload)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(10);

        var image = new BitmapImage();
        using var stream = new MemoryStream(png);
        image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.StreamSource = stream;
        image.EndInit();
        image.Freeze();
        return image;
    }

    private void OnCloseClick(object sender, RoutedEventArgs e) => Close();
}
