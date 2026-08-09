using System.Security.Cryptography;
using Windows.Graphics.Imaging;
using Windows.Media.Control;
using Windows.Storage.Streams;
using WinBridge.Core.Protocol;

namespace WinBridge.App.Providers;

/// <summary>
/// Reads and controls whatever is playing through the system media transport
/// controls — Spotify, browsers, players, anything that registers a session.
///
/// Art is downscaled to 256px JPEG and identified by content hash. Over RFCOMM
/// that is the difference between a usable feature and a stall: a raw 1000px
/// PNG cover is megabytes, this is 10–20 KB, and the phone only ever asks for a
/// hash it has never seen.
/// </summary>
public sealed class MediaProvider : IAsyncDisposable
{
    private const int ArtMaxEdge = 256;

    private GlobalSystemMediaTransportControlsSessionManager? _manager;
    private GlobalSystemMediaTransportControlsSession? _session;

    private readonly Dictionary<string, byte[]> _artCache = new();
    private readonly Lock _artGate = new();

    // Art is rendered per *track*, not per read. GSMTC raises a change event on
    // every position update, so keying this on anything finer would re-decode,
    // re-scale, re-encode and re-hash the cover several times a second for a
    // picture that has not changed.
    private string? _artTrackKey;
    private string? _artHashForTrack;

    /// <summary>Raised when anything about the current media changed.</summary>
    public event Action? Changed;

    public async Task InitializeAsync()
    {
        _manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
        _manager.CurrentSessionChanged += OnCurrentSessionChanged;
        AttachToCurrentSession();
    }

    private void OnCurrentSessionChanged(
        GlobalSystemMediaTransportControlsSessionManager sender, CurrentSessionChangedEventArgs args)
    {
        AttachToCurrentSession();
        Changed?.Invoke();
    }

    private void AttachToCurrentSession()
    {
        DetachSession();
        _session = _manager?.GetCurrentSession();
        if (_session is null) return;

        _session.MediaPropertiesChanged += OnSessionChanged;
        _session.PlaybackInfoChanged += OnSessionChanged;
        _session.TimelinePropertiesChanged += OnSessionChanged;
    }

    private void DetachSession()
    {
        if (_session is null) return;
        try
        {
            _session.MediaPropertiesChanged -= OnSessionChanged;
            _session.PlaybackInfoChanged -= OnSessionChanged;
            _session.TimelinePropertiesChanged -= OnSessionChanged;
        }
        catch { /* the session may already be gone */ }
        _session = null;
    }

    private void OnSessionChanged(object sender, object args) => Changed?.Invoke();

    public async Task<MediaState> ReadAsync()
    {
        var session = _session;
        if (session is null) return new MediaState();

        try
        {
            var playback = session.GetPlaybackInfo();
            var timeline = session.GetTimelineProperties();
            var props = await session.TryGetMediaPropertiesAsync();

            string trackKey = string.Join('',
                session.SourceAppUserModelId, props?.Title, props?.Artist, props?.AlbumTitle);

            string? artHash;
            if (trackKey == _artTrackKey)
            {
                artHash = _artHashForTrack;
            }
            else
            {
                artHash = null;
                if (props?.Thumbnail is not null)
                {
                    byte[]? jpeg = await RenderThumbnailAsync(props.Thumbnail);
                    if (jpeg is not null)
                    {
                        artHash = Convert.ToHexString(SHA256.HashData(jpeg))[..32].ToLowerInvariant();
                        lock (_artGate) _artCache[artHash] = jpeg;
                    }
                }
                _artTrackKey = trackKey;
                _artHashForTrack = artHash;
            }

            return new MediaState
            {
                Session = session.SourceAppUserModelId,
                Title = props?.Title,
                Artist = props?.Artist,
                Album = props?.AlbumTitle,
                AppId = session.SourceAppUserModelId,
                Playing = playback.PlaybackStatus ==
                          GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing,
                PosMs = (long)timeline.Position.TotalMilliseconds,
                DurMs = (long)(timeline.EndTime - timeline.StartTime).TotalMilliseconds,
                CanNext = playback.Controls.IsNextEnabled,
                CanPrev = playback.Controls.IsPreviousEnabled,
                CanSeek = playback.Controls.IsPlaybackPositionEnabled,
                ArtHash = artHash,
            };
        }
        catch
        {
            // Sessions disappear mid-read all the time (app closing, track
            // switching). An empty state is the honest answer, not a crash.
            return new MediaState();
        }
    }

    public byte[]? GetArt(string hash)
    {
        lock (_artGate) return _artCache.TryGetValue(hash, out var data) ? data : null;
    }

    private static async Task<byte[]?> RenderThumbnailAsync(IRandomAccessStreamReference reference)
    {
        try
        {
            using IRandomAccessStreamWithContentType source = await reference.OpenReadAsync();
            var decoder = await BitmapDecoder.CreateAsync(source);

            uint width = decoder.PixelWidth, height = decoder.PixelHeight;
            if (width == 0 || height == 0) return null;

            double scale = Math.Min(1.0, (double)ArtMaxEdge / Math.Max(width, height));
            uint targetWidth = Math.Max(1, (uint)Math.Round(width * scale));
            uint targetHeight = Math.Max(1, (uint)Math.Round(height * scale));

            var transform = new BitmapTransform
            {
                ScaledWidth = targetWidth,
                ScaledHeight = targetHeight,
                InterpolationMode = BitmapInterpolationMode.Fant,
            };

            PixelDataProvider pixels = await decoder.GetPixelDataAsync(
                BitmapPixelFormat.Bgra8,
                BitmapAlphaMode.Ignore,
                transform,
                ExifOrientationMode.RespectExifOrientation,
                ColorManagementMode.DoNotColorManage);

            using var output = new InMemoryRandomAccessStream();
            var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.JpegEncoderId, output);
            encoder.SetPixelData(
                BitmapPixelFormat.Bgra8, BitmapAlphaMode.Ignore,
                targetWidth, targetHeight, 96, 96, pixels.DetachPixelData());
            await encoder.FlushAsync();

            var buffer = new byte[output.Size];
            using var reader = new DataReader(output.GetInputStreamAt(0));
            await reader.LoadAsync((uint)output.Size);
            reader.ReadBytes(buffer);
            return buffer;
        }
        catch
        {
            return null;
        }
    }

    public async Task<bool> ControlAsync(string action, long positionMs)
    {
        var session = _session;
        if (session is null) return false;

        try
        {
            return action switch
            {
                "play" => await session.TryPlayAsync(),
                "pause" => await session.TryPauseAsync(),
                "toggle" => await session.TryTogglePlayPauseAsync(),
                "next" => await session.TrySkipNextAsync(),
                "prev" => await session.TrySkipPreviousAsync(),
                // WinRT wants 100-ns ticks here, not milliseconds.
                "seek" => await session.TryChangePlaybackPositionAsync(positionMs * 10_000),
                _ => false,
            };
        }
        catch { return false; }
    }

    public ValueTask DisposeAsync()
    {
        DetachSession();
        if (_manager is not null)
        {
            try { _manager.CurrentSessionChanged -= OnCurrentSessionChanged; } catch { }
            _manager = null;
        }
        return ValueTask.CompletedTask;
    }
}
