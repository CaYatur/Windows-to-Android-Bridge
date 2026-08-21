using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using WinBridge.Core.Protocol;
using Forms = System.Windows.Forms;

namespace WinBridge.App.Features;

/// <summary>
/// Grabs the desktop, scales it, and turns the parts that changed into JPEG
/// tiles.
///
/// Intra-only tile coding rather than H.264, and that is a latency decision, not
/// a shortcut. A hardware H.264 encoder buffers frames to build references; the
/// bytes are far smaller but the first byte of a frame leaves later. Tiles have
/// no reference state at all, so a frame is on the wire as soon as it is
/// grabbed, and a still desktop with a blinking cursor costs a few hundred bytes
/// because only the tiles whose hash moved are sent. What it costs is bandwidth
/// on full-screen video, which is why the streams are LAN-only and the quality
/// adapts.
/// </summary>
public sealed class ScreenCapture : IDisposable
{
    /// <summary>
    /// 64 px squares. Smaller tiles track small changes more tightly but add
    /// six bytes of header and a JPEG header each; larger ones resend more
    /// unchanged pixels every time a cursor crosses them.
    /// </summary>
    public const int TileSize = 64;

    private Bitmap? _grab;
    private Bitmap? _scaled;
    private Bitmap? _tile;
    private readonly MemoryStream _jpegBuffer = new(64 * 1024);
    private ulong[] _hashes = [];
    private byte[] _pixels = [];
    private readonly HashSet<int> _forced = [];

    private static readonly ImageCodecInfo JpegCodec =
        ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);

    public int Width { get; private set; }
    public int Height { get; private set; }
    public int Columns { get; private set; }
    public int Rows { get; private set; }

    /// <summary>Forces every tile into the next frame — after a resize, or when a viewer joins.</summary>
    public void Invalidate() => _hashes = [];

    /// <summary>
    /// Puts one tile back in the next frame. Used when a packet was dropped: the
    /// differ has already recorded those tiles as sent, so without this a tile
    /// that never changes again — a toolbar, a wallpaper edge — would stay stale
    /// on the receiver for the rest of the session.
    /// </summary>
    public void Forget(int index)
    {
        lock (_forced) _forced.Add(index);
    }

    /// <summary>
    /// The geometry a capture of <paramref name="source"/> would produce, without
    /// capturing anything. The receiver needs to size its canvas before the first
    /// frame arrives, and asking it to infer that from tile indices would be
    /// guesswork.
    /// </summary>
    public static (int Width, int Height, int Columns, int Rows) PlanGeometry(Rectangle source, int maxEdge)
    {
        int width = source.Width;
        int height = source.Height;

        if (maxEdge > 0 && Math.Max(width, height) > maxEdge)
        {
            double factor = (double)maxEdge / Math.Max(width, height);
            width = Math.Max(TileSize, (int)Math.Round(source.Width * factor / 2) * 2);
            height = Math.Max(TileSize, (int)Math.Round(source.Height * factor / 2) * 2);
        }

        return (width, height, (width + TileSize - 1) / TileSize, (height + TileSize - 1) / TileSize);
    }

    /// <summary>Receives one encoded tile. The span is only valid for the call.</summary>
    public delegate void TileSink(ushort index, ReadOnlySpan<byte> jpeg);

    /// <summary>
    /// Captures <paramref name="source"/> and hands each changed tile to
    /// <paramref name="sink"/>. Returns how many tiles changed — zero is the
    /// common case and costs one grab plus a hash sweep.
    ///
    /// A sink rather than a returned list, because the JPEG encoder writes into
    /// one reused buffer: collecting the tiles first would hand the caller a
    /// list of entries that all alias that buffer, so every tile but the last
    /// would be written to the wire as a truncated prefix of the wrong image.
    /// A single-tile frame would still look right, which is the kind of bug
    /// that survives a casual look and then breaks the moment anything scrolls.
    /// </summary>
    public int CaptureChanged(
        Rectangle source, int maxEdge, int quality, bool drawCursor, bool forceAll, TileSink sink)
    {
        EnsureBuffers(source, maxEdge);

        using (var graphics = Graphics.FromImage(_grab!))
        {
            graphics.CopyFromScreen(source.Left, source.Top, 0, 0, source.Size, CopyPixelOperation.SourceCopy);
            if (drawCursor) DrawCursor(graphics, source);
        }

        if (_scaled is not null && (_scaled.Width != _grab!.Width || _scaled.Height != _grab.Height))
        {
            using var graphics = Graphics.FromImage(_scaled);
            // Bilinear, not bicubic: at these sizes the visible difference is
            // slight and bicubic costs several milliseconds per frame, which is
            // the whole latency budget for one hop.
            graphics.InterpolationMode = InterpolationMode.Bilinear;
            graphics.PixelOffsetMode = PixelOffsetMode.HighSpeed;
            graphics.SmoothingMode = SmoothingMode.None;
            graphics.DrawImage(_grab, 0, 0, _scaled.Width, _scaled.Height);
        }

        Bitmap frame = _scaled ?? _grab!;
        return DiffTiles(frame, quality, forceAll || _hashes.Length == 0, sink);
    }

    private void EnsureBuffers(Rectangle source, int maxEdge)
    {
        if (_grab is null || _grab.Width != source.Width || _grab.Height != source.Height)
        {
            _grab?.Dispose();
            _grab = new Bitmap(Math.Max(1, source.Width), Math.Max(1, source.Height), PixelFormat.Format32bppRgb);
            _scaled?.Dispose();
            _scaled = null;
            Invalidate();
        }

        int targetWidth = source.Width;
        int targetHeight = source.Height;

        if (maxEdge > 0 && Math.Max(source.Width, source.Height) > maxEdge)
        {
            double factor = (double)maxEdge / Math.Max(source.Width, source.Height);
            // Rounded to even numbers: an odd width leaves a one-pixel column in
            // the last tile column that JPEG chroma subsampling handles badly.
            targetWidth = Math.Max(TileSize, (int)Math.Round(source.Width * factor / 2) * 2);
            targetHeight = Math.Max(TileSize, (int)Math.Round(source.Height * factor / 2) * 2);
        }

        if (_scaled is null || _scaled.Width != targetWidth || _scaled.Height != targetHeight)
        {
            _scaled?.Dispose();
            _scaled = targetWidth == source.Width && targetHeight == source.Height
                ? null
                : new Bitmap(targetWidth, targetHeight, PixelFormat.Format32bppRgb);
            Invalidate();
        }

        Width = targetWidth;
        Height = targetHeight;
        Columns = (Width + TileSize - 1) / TileSize;
        Rows = (Height + TileSize - 1) / TileSize;
    }

    private int DiffTiles(Bitmap frame, int quality, bool forceAll, TileSink sink)
    {
        int changed = 0;
        int tiles = Columns * Rows;
        if (_hashes.Length != tiles) { _hashes = new ulong[tiles]; forceAll = true; }

        HashSet<int> retry;
        lock (_forced)
        {
            retry = _forced.Count == 0 ? [] : [.. _forced];
            _forced.Clear();
        }

        var bounds = new Rectangle(0, 0, frame.Width, frame.Height);
        var data = frame.LockBits(bounds, ImageLockMode.ReadOnly, PixelFormat.Format32bppRgb);
        try
        {
            int stride = Math.Abs(data.Stride);
            int needed = stride * frame.Height;
            if (_pixels.Length < needed) _pixels = new byte[needed];
            Marshal.Copy(data.Scan0, _pixels, 0, needed);

            for (int row = 0; row < Rows; row++)
            {
                for (int column = 0; column < Columns; column++)
                {
                    int x = column * TileSize;
                    int y = row * TileSize;
                    int width = Math.Min(TileSize, frame.Width - x);
                    int height = Math.Min(TileSize, frame.Height - y);

                    ulong hash = HashTile(_pixels, stride, x, y, width, height);
                    int index = row * Columns + column;
                    if (!forceAll && _hashes[index] == hash && !retry.Contains(index)) continue;

                    _hashes[index] = hash;
                    byte[] jpeg = EncodeTile(stride, x, y, width, height, quality, out int length);

                    // Consumed here, before the buffer is reused for the next tile.
                    sink((ushort)index, jpeg.AsSpan(0, length));
                    changed++;
                }
            }
        }
        finally
        {
            frame.UnlockBits(data);
        }

        return changed;
    }

    /// <summary>
    /// FNV-1a over the tile, sampling every fourth pixel.
    ///
    /// Sampling matters: hashing every byte of a 1280x720 frame is ~3.7 MB of
    /// reads per frame and at 30 fps that is real CPU for a comparison that is
    /// thrown away. Every fourth pixel still catches text carets and cursors —
    /// the smallest things that actually move — and quarters the cost.
    /// </summary>
    private static ulong HashTile(byte[] pixels, int stride, int x, int y, int width, int height)
    {
        const ulong Prime = 1099511628211;
        ulong hash = 14695981039346656037;

        for (int row = 0; row < height; row++)
        {
            int offset = (y + row) * stride + x * 4;
            for (int column = 0; column < width; column += 4)
            {
                int at = offset + column * 4;
                hash = (hash ^ pixels[at]) * Prime;
                hash = (hash ^ pixels[at + 1]) * Prime;
                hash = (hash ^ pixels[at + 2]) * Prime;
            }
        }
        return hash;
    }

    /// <summary>
    /// Builds one tile from the frame pixels already copied out in
    /// <see cref="DiffTiles"/> and encodes it as JPEG.
    ///
    /// Copied row by row rather than drawn with <c>Graphics.DrawImage</c>,
    /// because the frame bitmap is locked for the whole diff pass and GDI+
    /// refuses to draw from a locked bitmap — a mistake that throws on the very
    /// first frame rather than degrading, so it never reaches a user, but it
    /// also never reaches a test that only ever looks at geometry. It is faster
    /// too: no GDI+ round trip per tile.
    /// </summary>
    private byte[] EncodeTile(int stride, int x, int y, int width, int height, int quality, out int length)
    {
        bool exact = width == TileSize && height == TileSize;
        Bitmap target = exact
            ? (_tile ??= new Bitmap(TileSize, TileSize, PixelFormat.Format32bppRgb))
            // Edge tiles are narrower or shorter than the rest; encoding them at
            // their real size avoids sending a black border the receiver would
            // have to know to ignore.
            : new Bitmap(width, height, PixelFormat.Format32bppRgb);

        try
        {
            var region = new Rectangle(0, 0, width, height);
            var bits = target.LockBits(region, ImageLockMode.WriteOnly, PixelFormat.Format32bppRgb);
            try
            {
                int rowBytes = width * 4;
                for (int row = 0; row < height; row++)
                {
                    Marshal.Copy(
                        _pixels,
                        (y + row) * stride + x * 4,
                        bits.Scan0 + row * bits.Stride,
                        rowBytes);
                }
            }
            finally
            {
                target.UnlockBits(bits);
            }

            _jpegBuffer.SetLength(0);
            using var parameters = new EncoderParameters(1);
            parameters.Param[0] = new EncoderParameter(Encoder.Quality, (long)Math.Clamp(quality, 5, 100));
            target.Save(_jpegBuffer, JpegCodec, parameters);

            length = (int)_jpegBuffer.Length;
            return _jpegBuffer.GetBuffer();
        }
        finally
        {
            if (!exact) target.Dispose();
        }
    }

    /// <summary>
    /// CopyFromScreen does not include the pointer, and a mirrored desktop with
    /// no cursor is close to unusable — you cannot tell where a tap will land.
    /// </summary>
    private static void DrawCursor(Graphics graphics, Rectangle source)
    {
        var info = new CURSORINFO { cbSize = Marshal.SizeOf<CURSORINFO>() };
        if (!GetCursorInfo(ref info) || info.flags != CURSOR_SHOWING) return;

        IntPtr icon = CopyIcon(info.hCursor);
        if (icon == IntPtr.Zero) return;

        try
        {
            if (!GetIconInfo(icon, out ICONINFO iconInfo)) return;
            try
            {
                int x = info.ptScreenPos.x - source.Left - iconInfo.xHotspot;
                int y = info.ptScreenPos.y - source.Top - iconInfo.yHotspot;

                IntPtr hdc = graphics.GetHdc();
                try { DrawIconEx(hdc, x, y, icon, 0, 0, 0, IntPtr.Zero, DI_NORMAL); }
                finally { graphics.ReleaseHdc(hdc); }
            }
            finally
            {
                if (iconInfo.hbmMask != IntPtr.Zero) DeleteObject(iconInfo.hbmMask);
                if (iconInfo.hbmColor != IntPtr.Zero) DeleteObject(iconInfo.hbmColor);
            }
        }
        finally
        {
            DestroyIcon(icon);
        }
    }

    // ---- targets ----------------------------------------------------------

    public static List<ScreenTarget> Targets()
    {
        var targets = new List<ScreenTarget>();
        var virtualScreen = Forms.SystemInformation.VirtualScreen;

        if (Forms.Screen.AllScreens.Length > 1)
        {
            targets.Add(new ScreenTarget
            {
                Id = "all",
                Name = "All displays",
                Kind = "all",
                Width = virtualScreen.Width,
                Height = virtualScreen.Height,
            });
        }

        for (int index = 0; index < Forms.Screen.AllScreens.Length; index++)
        {
            var screen = Forms.Screen.AllScreens[index];
            targets.Add(new ScreenTarget
            {
                Id = $"m{index}",
                Name = screen.Primary ? $"Display {index + 1} (primary)" : $"Display {index + 1}",
                Kind = "monitor",
                Width = screen.Bounds.Width,
                Height = screen.Bounds.Height,
                Primary = screen.Primary,
            });
        }

        return targets;
    }

    /// <summary>Resolves a target id to desktop coordinates, falling back to the primary display.</summary>
    public static Rectangle Resolve(string? target)
    {
        if (target == "all") return Forms.SystemInformation.VirtualScreen;

        if (target is not null && target.StartsWith('m') &&
            int.TryParse(target[1..], out int index) &&
            index >= 0 && index < Forms.Screen.AllScreens.Length)
            return Forms.Screen.AllScreens[index].Bounds;

        return (Forms.Screen.PrimaryScreen ?? Forms.Screen.AllScreens[0]).Bounds;
    }

    public void Dispose()
    {
        _grab?.Dispose();
        _scaled?.Dispose();
        _tile?.Dispose();
        _jpegBuffer.Dispose();
    }

    // ---- interop ----------------------------------------------------------

    private const int CURSOR_SHOWING = 0x00000001;
    private const int DI_NORMAL = 0x0003;

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT { public int x; public int y; }

    [StructLayout(LayoutKind.Sequential)]
    private struct CURSORINFO
    {
        public int cbSize;
        public int flags;
        public IntPtr hCursor;
        public POINT ptScreenPos;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ICONINFO
    {
        public bool fIcon;
        public int xHotspot;
        public int yHotspot;
        public IntPtr hbmMask;
        public IntPtr hbmColor;
    }

    [DllImport("user32.dll")] private static extern bool GetCursorInfo(ref CURSORINFO info);
    [DllImport("user32.dll")] private static extern IntPtr CopyIcon(IntPtr cursor);
    [DllImport("user32.dll")] private static extern bool GetIconInfo(IntPtr icon, out ICONINFO info);
    [DllImport("user32.dll")] private static extern bool DestroyIcon(IntPtr icon);
    [DllImport("gdi32.dll")] private static extern bool DeleteObject(IntPtr handle);

    [DllImport("user32.dll")]
    private static extern bool DrawIconEx(
        IntPtr hdc, int x, int y, IntPtr icon, int width, int height,
        int step, IntPtr brush, int flags);
}
