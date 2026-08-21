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

    private bool _dragging;
    private uint _lastPresented;
    private int _framesPresented;
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
        _blank = null;
    }

    /// <summary>One tile, already decoded to BGRA and ready to blit.</summary>
    public readonly record struct DecodedTile(int X, int Y, int Width, int Height, byte[] Pixels, int Stride);

    /// <summary>
    /// Paints a frame that was decoded elsewhere.
    ///
    /// JPEG decoding used to happen here, on the UI thread, once per tile. At
    /// thirty frames a second with dozens of changed tiles that is the whole
    /// frame budget spent before anything is drawn — which looks exactly like
    /// network lag and is not.
    /// </summary>
    public void Present(IReadOnlyList<DecodedTile> tiles, bool keyframe, uint timestampMs, int bytes)
    {
        var target = _canvas;
        if (target is null) return;

        _bytesSinceReport += bytes;

        if (keyframe)
        {
            // One write, not one per row: clearing a 720-row canvas a row at a
            // time is 720 dirty rectangles for the compositor to reconcile.
            int stride = target.PixelWidth * 4;
            _blank ??= new byte[stride * target.PixelHeight];
            target.WritePixels(new Int32Rect(0, 0, target.PixelWidth, target.PixelHeight), _blank, stride, 0);
        }

        foreach (var tile in tiles)
        {
            if (tile.X >= target.PixelWidth || tile.Y >= target.PixelHeight) continue;
            int width = Math.Min(tile.Width, target.PixelWidth - tile.X);
            int height = Math.Min(tile.Height, target.PixelHeight - tile.Y);
            if (width <= 0 || height <= 0) continue;

            target.WritePixels(new Int32Rect(tile.X, tile.Y, width, height), tile.Pixels, tile.Stride, 0);
        }

        _framesPresented++;
        _lastPresented = timestampMs;
        ReportStats();
    }

    /// <summary>Where a tile index lands, given the geometry from stream.info.</summary>
    public (int X, int Y) TileOrigin(int index) =>
        _columns == 0 ? (0, 0) : ((index % _columns) * _tileWidth, (index / _columns) * _tileHeight);

    private byte[]? _blank;

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
        int frames = _framesPresented - _framesSinceReport;
        _framesSinceReport = _framesPresented;

        _ = _link.SendJsonAsync(new StreamStats
        {
            Stream = StreamIds.Name(StreamIds.PhoneScreen),
            Fps = Math.Round(frames / seconds, 1),
            Kbps = Math.Round(_bytesSinceReport * 8 / seconds / 1000, 1),
            LatencyMs = (int)Math.Max(0, _clock.ElapsedMilliseconds - _lastPresented),
        }, CancellationToken.None);

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

    private DateTime _lastMoveSent = DateTime.MinValue;

    private void OnSurfaceMove(object sender, MouseEventArgs e)
    {
        if (!_dragging || !Interactive || !TryNormalise(e, out double x, out double y)) return;

        // Roughly 60 Hz. A move per mouse event is several hundred a second,
        // and the phone dispatches each one as a gesture segment — the queue
        // never drains and the drag arrives as a stutter.
        if ((DateTime.UtcNow - _lastMoveSent).TotalMilliseconds < 16) return;
        _lastMoveSent = DateTime.UtcNow;

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
