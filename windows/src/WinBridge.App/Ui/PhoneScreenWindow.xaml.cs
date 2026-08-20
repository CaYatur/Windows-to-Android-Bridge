using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using WinBridge.App.Server;
using WinBridge.Core.Protocol;

namespace WinBridge.App.Ui;

/// <summary>
/// Shows the phone screen and sends touches back.
///
/// Tiles are painted into one <see cref="WriteableBitmap"/> that lives for the
/// session. Building a fresh bitmap per frame would be simpler and would also
/// hand the garbage collector several megabytes a second to walk, which shows up
/// as exactly the stutter this whole feature is trying not to have.
/// </summary>
public partial class PhoneScreenWindow : Window
{
    private readonly IPeerLink _link;
    private readonly Stopwatch _clock = Stopwatch.StartNew();

    private WriteableBitmap? _canvas;
    private int _tileWidth = 64;
    private int _tileHeight = 64;
    private int _columns;
    private byte[] _tilePixels = [];

    private bool _dragging;
    private uint _lastPresented;
    private int _framesSinceReport;
    private long _bytesSinceReport;
    private DateTime _lastReport = DateTime.UtcNow;

    public PhoneScreenWindow(IPeerLink link)
    {
        _link = link;
        InitializeComponent();

        Title = $"{link.PeerName} — phone screen";
        LblOverlay.Text = "Waiting for the phone to allow screen sharing.\n"
                          + "Android asks for that once per session; it cannot be granted from here.";

        Closed += (_, _) => Closing?.Invoke();
    }

    /// <summary>Raised when the window goes away, so the service can stop the stream.</summary>
    public event Action? Closing;

    public void OnInfo(StreamInfo info)
    {
        if (!info.Active)
        {
            LblOverlay.Text = info.Reason ?? "The phone stopped sharing its screen.";
            LblStatus.Text = "Stopped";
            return;
        }

        LblStatus.Text = $"{info.Width}×{info.Height}";
        LblOverlay.Text = "";

        if (_canvas is not null && _canvas.PixelWidth == info.Width && _canvas.PixelHeight == info.Height)
        {
            _tileWidth = info.TileWidth;
            _tileHeight = info.TileHeight;
            _columns = info.Columns;
            return;
        }

        _tileWidth = Math.Max(1, info.TileWidth);
        _tileHeight = Math.Max(1, info.TileHeight);
        _columns = Math.Max(1, info.Columns);

        _canvas = new WriteableBitmap(
            Math.Max(1, info.Width), Math.Max(1, info.Height), 96, 96, PixelFormats.Bgra32, null);
        Surface.Source = _canvas;
        _tilePixels = new byte[_tileWidth * _tileHeight * 4];
    }

    public void OnPacket(in MediaPacket packet)
    {
        if (_canvas is null) return;

        if (packet.Flags.HasFlag(MediaFlags.Keyframe))
        {
            // Only the first packet of a frame carries this. Clearing on every
            // packet of a split frame would wipe the tiles just painted.
            var blank = new byte[_canvas.PixelWidth * 4];
            for (int row = 0; row < _canvas.PixelHeight; row++)
                _canvas.WritePixels(new Int32Rect(0, row, _canvas.PixelWidth, 1), blank, _canvas.PixelWidth * 4, 0);
        }

        _bytesSinceReport += packet.Payload.Length;

        foreach (var (index, jpeg) in TileCodec.ReadTiles(packet.Payload))
        {
            try { PaintTile(index, jpeg); }
            catch { /* a torn tile is one square, not a reason to drop the stream */ }
        }

        if (packet.Flags.HasFlag(MediaFlags.EndOfFrame))
        {
            _framesSinceReport++;
            _lastPresented = packet.TimestampMs;
            ReportStats();
        }
    }

    private void PaintTile(ushort index, ReadOnlyMemory<byte> jpeg)
    {
        if (_canvas is null || _columns == 0) return;

        int column = index % _columns;
        int row = index / _columns;
        int x = column * _tileWidth;
        int y = row * _tileHeight;
        if (x >= _canvas.PixelWidth || y >= _canvas.PixelHeight) return;

        using var stream = new MemoryStream(jpeg.ToArray(), writable: false);
        var frame = BitmapFrame.Create(stream, BitmapCreateOptions.None, BitmapCacheOption.OnLoad);

        var converted = new FormatConvertedBitmap(frame, PixelFormats.Bgra32, null, 0);
        int width = Math.Min(converted.PixelWidth, _canvas.PixelWidth - x);
        int height = Math.Min(converted.PixelHeight, _canvas.PixelHeight - y);
        if (width <= 0 || height <= 0) return;

        int stride = converted.PixelWidth * 4;
        int needed = stride * converted.PixelHeight;
        if (_tilePixels.Length < needed) _tilePixels = new byte[needed];

        converted.CopyPixels(_tilePixels, stride, 0);
        _canvas.WritePixels(new Int32Rect(x, y, width, height), _tilePixels, stride, 0);
    }

    /// <summary>
    /// Tells the sender what is actually arriving. It adapts from this rather
    /// than from its own guesses, because only this side knows how old a frame
    /// was by the time it was painted.
    /// </summary>
    private void ReportStats()
    {
        var elapsed = DateTime.UtcNow - _lastReport;
        if (elapsed.TotalMilliseconds < 1000) return;

        double seconds = elapsed.TotalSeconds;
        _ = _link.SendJsonAsync(new StreamStats
        {
            Stream = StreamIds.Name(StreamIds.PhoneScreen),
            Fps = Math.Round(_framesSinceReport / seconds, 1),
            Kbps = Math.Round(_bytesSinceReport * 8 / seconds / 1000, 1),
            LatencyMs = (int)Math.Max(0, _clock.ElapsedMilliseconds - _lastPresented),
        }, CancellationToken.None);

        _framesSinceReport = 0;
        _bytesSinceReport = 0;
        _lastReport = DateTime.UtcNow;
    }

    // ---- input back to the phone -------------------------------------------

    private bool Interactive => ChkInteract.IsChecked == true && _canvas is not null;

    private bool TryNormalise(MouseEventArgs e, out double x, out double y)
    {
        x = y = 0;
        if (_canvas is null) return false;

        var position = e.GetPosition(Surface);

        // The image is letterboxed by Stretch="Uniform", so the rendered picture
        // is smaller than the control. Using the control size here would put
        // every touch in the wrong place by the size of the bars.
        double scale = Math.Min(Surface.ActualWidth / _canvas.PixelWidth, Surface.ActualHeight / _canvas.PixelHeight);
        double drawnWidth = _canvas.PixelWidth * scale;
        double drawnHeight = _canvas.PixelHeight * scale;
        double offsetX = (Surface.ActualWidth - drawnWidth) / 2;
        double offsetY = (Surface.ActualHeight - drawnHeight) / 2;

        x = (position.X - offsetX) / drawnWidth;
        y = (position.Y - offsetY) / drawnHeight;
        return x is >= 0 and <= 1 && y is >= 0 and <= 1;
    }

    private void OnSurfaceDown(object sender, MouseButtonEventArgs e)
    {
        Surface.Focus();
        if (!Interactive || !TryNormalise(e, out double x, out double y)) return;

        _dragging = true;
        Surface.CaptureMouse();
        Send(new InputTouch { Action = "down", X = x, Y = y });
    }

    private void OnSurfaceMove(object sender, MouseEventArgs e)
    {
        if (!_dragging || !Interactive || !TryNormalise(e, out double x, out double y)) return;
        Send(new InputTouch { Action = "move", X = x, Y = y });
    }

    private void OnSurfaceUp(object sender, MouseButtonEventArgs e)
    {
        if (!_dragging) return;
        _dragging = false;
        Surface.ReleaseMouseCapture();

        if (!Interactive || !TryNormalise(e, out double x, out double y)) return;
        Send(new InputTouch { Action = "up", X = x, Y = y });
    }

    private void OnSurfaceWheel(object sender, MouseWheelEventArgs e)
    {
        if (!Interactive || !TryNormalise(e, out double x, out double y)) return;

        // A wheel notch becomes a short flick. Android has no wheel, so the only
        // way to scroll something is to describe the gesture that would.
        Send(new InputScroll { X = x, Y = y, Dy = e.Delta / 120.0 * 0.18 });
    }

    private void OnNav(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: string action })
            Send(new InputNav { Action = action });
    }

    private void OnAudioToggled(object sender, RoutedEventArgs e)
    {
        bool wanted = ChkAudio.IsChecked == true;
        Send(wanted
            ? new AudioStart { Stream = StreamIds.Name(StreamIds.PhoneAudio) }
            : (object)new AudioStop { Stream = StreamIds.Name(StreamIds.PhoneAudio) });
    }

    protected override void OnPreviewKeyDown(KeyEventArgs e)
    {
        base.OnPreviewKeyDown(e);
        if (!Interactive || !Surface.IsKeyboardFocusWithin) return;

        string? code = e.Key switch
        {
            Key.Back => "back",
            Key.Escape => "back",
            Key.Enter => "enter",
            Key.Tab => "tab",
            _ => null,
        };

        if (code is not null)
        {
            Send(new InputKey { Code = code });
            e.Handled = true;
        }
    }

    protected override void OnTextInput(TextCompositionEventArgs e)
    {
        base.OnTextInput(e);
        if (!Interactive || string.IsNullOrEmpty(e.Text)) return;

        // Typed straight through rather than key by key: the phone injects this
        // via its input method, which handles anything a keyboard can produce.
        Send(new InputText { Text = e.Text });
        e.Handled = true;
    }

    private void Send<T>(T message) => _ = _link.SendJsonAsync(message, CancellationToken.None);
}
