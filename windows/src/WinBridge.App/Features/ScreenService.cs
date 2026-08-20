using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using WinBridge.App.Server;
using WinBridge.App.Storage;
using WinBridge.Core.Protocol;

namespace WinBridge.App.Features;

/// <summary>
/// Runs one screen stream per connected phone: capture, pack, adapt, stop.
///
/// The adaptation loop is here rather than baked into constants because the two
/// things that decide what this link can carry — how fast the phone decodes and
/// how much the Wi-Fi will take — are not knowable from this side. The receiver
/// reports what it is actually seeing and the encoder walks its settings toward
/// what fits.
/// </summary>
public sealed class ScreenService(BridgeStore store) : IDisposable
{
    private readonly ConcurrentDictionary<IPeerLink, Stream> _streams = new();

    public event Action<string>? Log;

    /// <summary>
    /// Raised when a stream resizes itself. Adaptation can change the frame size
    /// mid-session, and a receiver still painting into the old canvas would show
    /// tiles landing in the wrong places.
    /// </summary>
    public event Action<IPeerLink, StreamInfo>? GeometryChanged;

    public int TargetCount => ScreenCapture.Targets().Count;

    public List<ScreenTarget> Targets() => ScreenCapture.Targets();

    public StreamInfo Start(IPeerLink link, StreamStart request)
    {
        var settings = store.Settings;

        if (!settings.Screen.Share)
            return Refused(request, "screen sharing is off in Windows settings");

        // Refusing beats degrading here: RFCOMM gives roughly a megabit, a
        // usable stream needs several, and a mirror that updates twice a second
        // reads as a broken feature rather than a slow one.
        if (settings.Screen.LanOnly && link.Carrier == "bluetooth")
            return Refused(request, "mirroring needs the LAN link; Bluetooth cannot carry it");

        Stop(link);

        var stream = new Stream(link, store)
        {
            OnGeometry = info => GeometryChanged?.Invoke(link, info),
            Target = request.Target,
            Quality = Clamp(request.Quality, 5, 100, settings.Screen.Quality),
            MaxFps = Clamp(request.MaxFps, 1, 60, settings.Screen.MaxFps),
            MaxEdge = request.MaxEdge > 0 ? request.MaxEdge : settings.Screen.MaxEdge,
            Cursor = request.Cursor && settings.Screen.Cursor,
            Interact = request.Interact && settings.Input.Accept,
        };

        _streams[link] = stream;
        stream.Run(OnStreamEnded);

        Log?.Invoke($"mirroring {request.Target ?? "primary display"} to {link.PeerName}");
        return stream.Describe();
    }

    private static int Clamp(int requested, int low, int high, int fallback) =>
        requested <= 0 ? fallback : Math.Clamp(requested, low, high);

    private StreamInfo Refused(StreamStart request, string reason)
    {
        Log?.Invoke($"screen stream refused: {reason}");
        return new StreamInfo { Stream = request.Stream, Active = false, Reason = reason };
    }

    private void OnStreamEnded(Stream stream, string? reason)
    {
        _streams.TryRemove(stream.Link, out _);
        if (reason is not null) Log?.Invoke($"mirror to {stream.Link.PeerName} stopped: {reason}");
    }

    public void Stop(IPeerLink link)
    {
        if (_streams.TryRemove(link, out var stream)) stream.Cancel();
    }

    public StreamInfo? Configure(IPeerLink link, StreamConfig config)
    {
        if (!_streams.TryGetValue(link, out var stream)) return null;
        stream.Apply(config);
        return stream.Describe();
    }

    public void OnStats(IPeerLink link, StreamStats stats)
    {
        if (_streams.TryGetValue(link, out var stream)) stream.OnStats(stats);
    }

    public bool IsInteractive(IPeerLink link) =>
        _streams.TryGetValue(link, out var stream) && stream.Interact;

    /// <summary>Maps a normalised point from the streamed surface back to desktop pixels.</summary>
    public bool TryMapToDesktop(IPeerLink link, double x, double y, out int screenX, out int screenY)
    {
        screenX = screenY = 0;
        if (!_streams.TryGetValue(link, out var stream)) return false;

        var bounds = ScreenCapture.Resolve(stream.Target);
        screenX = bounds.Left + (int)Math.Round(Math.Clamp(x, 0, 1) * (bounds.Width - 1));
        screenY = bounds.Top + (int)Math.Round(Math.Clamp(y, 0, 1) * (bounds.Height - 1));
        return true;
    }

    public void Dispose()
    {
        foreach (var stream in _streams.Values) stream.Cancel();
        _streams.Clear();
    }

    /// <summary>One running capture loop.</summary>
    private sealed class Stream(IPeerLink link, BridgeStore store)
    {
        private readonly CancellationTokenSource _stop = new();
        private readonly ScreenCapture _capture = new();
        private readonly Stopwatch _clock = Stopwatch.StartNew();

        private uint _seq;
        private int _reportedWidth;
        private int _reportedHeight;
        private long _droppedSinceCheck;
        private DateTime _lastAdapt = DateTime.UtcNow;
        private DateTime _lastGoodSince = DateTime.UtcNow;
        private volatile StreamStats? _lastStats;

        public IPeerLink Link => link;
        public Action<StreamInfo>? OnGeometry { get; init; }
        public string? Target { get; set; }
        public int Quality { get; set; } = 70;
        public int MaxFps { get; set; } = 30;
        public int MaxEdge { get; set; } = 1280;
        public bool Cursor { get; set; } = true;
        public bool Interact { get; set; }

        private static readonly int[] EdgeLadder = [480, 640, 800, 960, 1280, 1600, 1920];

        public void Run(Action<Stream, string?> onEnded)
        {
            // A dedicated thread, not the pool: this loop runs at up to 60 Hz
            // for as long as someone is watching, and parking a pool thread on
            // it for minutes starves everything else the app wants to do.
            var thread = new Thread(() =>
            {
                string? reason = null;
                try { Loop(); }
                catch (OperationCanceledException) { }
                catch (Exception ex) { reason = ex.Message; }
                finally
                {
                    _capture.Dispose();
                    onEnded(this, reason);
                }
            })
            {
                IsBackground = true,
                Name = "winbridge-screen",
                Priority = ThreadPriority.AboveNormal,
            };
            thread.Start();
        }

        private void Loop()
        {
            var token = _stop.Token;
            bool first = true;

            while (!token.IsCancellationRequested)
            {
                var frameStart = _clock.Elapsed;
                var bounds = ScreenCapture.Resolve(Target);

                var tiles = _capture.CaptureChanged(bounds, MaxEdge, Quality, Cursor, forceAll: first);
                bool keyframe = first;
                first = false;

                if (_capture.Width != _reportedWidth || _capture.Height != _reportedHeight)
                {
                    _reportedWidth = _capture.Width;
                    _reportedHeight = _capture.Height;
                    OnGeometry?.Invoke(Describe());
                }

                if (tiles.Count > 0) Send(tiles, keyframe, token);

                Adapt();

                int budget = Math.Max(1, 1000 / Math.Max(1, MaxFps));
                int spent = (int)(_clock.Elapsed - frameStart).TotalMilliseconds;
                int rest = budget - spent;
                if (rest > 0) token.WaitHandle.WaitOne(rest);
            }
        }

        private void Send(List<(ushort Index, byte[] Jpeg, int Length)> tiles, bool keyframe, CancellationToken token)
        {
            var packet = new MemoryStream(TileCodec.MaxPacketBytes);
            var packed = new List<ushort>();
            uint timestamp = (uint)_clock.ElapsedMilliseconds;

            for (int index = 0; index < tiles.Count; index++)
            {
                var (tileIndex, jpeg, length) = tiles[index];

                if (packet.Length > 0 && packet.Length + length + 6 > TileCodec.MaxPacketBytes)
                {
                    Flush(packet, packed, timestamp, keyframe, last: false, token);
                }

                TileCodec.WriteTile(packet, tileIndex, jpeg.AsSpan(0, length));
                packed.Add(tileIndex);
            }

            Flush(packet, packed, timestamp, keyframe, last: true, token);
        }

        private void Flush(MemoryStream packet, List<ushort> packed, uint timestamp, bool keyframe, bool last, CancellationToken token)
        {
            if (packet.Length == 0 && !last) return;
            if (packet.Length == 0) return;

            var flags = MediaFlags.None;
            if (keyframe) flags |= MediaFlags.Keyframe;
            if (last) flags |= MediaFlags.EndOfFrame;

            bool sent = link.TrySendMedia(new MediaPacket(
                MediaKind.Video, StreamIds.PcScreen, _seq++, timestamp, flags,
                packet.ToArray()));

            if (!sent)
            {
                // The tiles in a dropped packet were marked clean by the differ,
                // so without this they would stay stale on the phone until
                // something happened to change them again — which for a static
                // toolbar could be never.
                _droppedSinceCheck++;
                foreach (ushort index in packed) _capture.Forget(index);
            }

            packet.SetLength(0);
            packed.Clear();
        }

        public void OnStats(StreamStats stats) => _lastStats = stats;

        /// <summary>
        /// Walks quality, then frame rate, then resolution down while the link
        /// is behind, and back up once it has been clean for a while. In that
        /// order because it is the order a viewer minds least: softer edges are
        /// less noticeable than stutter, and stutter is less bad than a picture
        /// that is too small to read.
        /// </summary>
        private void Adapt()
        {
            if ((DateTime.UtcNow - _lastAdapt).TotalMilliseconds < 1000) return;
            _lastAdapt = DateTime.UtcNow;

            long dropped = Interlocked.Exchange(ref _droppedSinceCheck, 0);
            var stats = _lastStats;
            bool behind = dropped > 0 || (stats is not null && stats.LatencyMs > 250);

            if (behind)
            {
                _lastGoodSince = DateTime.UtcNow;

                if (Quality > 35) Quality -= 10;
                else if (MaxFps > 12) MaxFps -= 6;
                else
                {
                    int at = Array.IndexOf(EdgeLadder, NearestEdge(MaxEdge));
                    if (at > 0) { MaxEdge = EdgeLadder[at - 1]; _capture.Invalidate(); }
                }
                return;
            }

            // Only climb after a sustained clean stretch. Reacting to one good
            // second produces a stream that oscillates instead of settling.
            if ((DateTime.UtcNow - _lastGoodSince).TotalSeconds < 5) return;
            _lastGoodSince = DateTime.UtcNow;

            var ceiling = store.Settings.Screen;
            if (MaxEdge < ceiling.MaxEdge)
            {
                int at = Array.IndexOf(EdgeLadder, NearestEdge(MaxEdge));
                if (at >= 0 && at + 1 < EdgeLadder.Length && EdgeLadder[at + 1] <= ceiling.MaxEdge)
                {
                    MaxEdge = EdgeLadder[at + 1];
                    _capture.Invalidate();
                    return;
                }
            }
            if (MaxFps < ceiling.MaxFps) { MaxFps = Math.Min(ceiling.MaxFps, MaxFps + 6); return; }
            if (Quality < ceiling.Quality) Quality = Math.Min(ceiling.Quality, Quality + 10);
        }

        private static int NearestEdge(int value)
        {
            int best = EdgeLadder[0];
            foreach (int candidate in EdgeLadder)
                if (Math.Abs(candidate - value) < Math.Abs(best - value)) best = candidate;
            return best;
        }

        public void Apply(StreamConfig config)
        {
            if (config.Quality is int quality) Quality = Math.Clamp(quality, 5, 100);
            if (config.MaxFps is int fps) MaxFps = Math.Clamp(fps, 1, 60);
            if (config.MaxEdge is int edge) { MaxEdge = Math.Max(240, edge); _capture.Invalidate(); }
            if (config.Cursor is bool cursor) Cursor = cursor;
            if (config.Interact is bool interact) Interact = interact && store.Settings.Input.Accept;
            if (config.Target is not null && config.Target != Target)
            {
                Target = config.Target;
                _capture.Invalidate();
            }
        }

        public StreamInfo Describe()
        {
            // Before the first grab the capture has no geometry yet, so plan it
            // rather than telling the phone the screen is zero by zero.
            var planned = _capture.Width > 0
                ? (_capture.Width, _capture.Height, _capture.Columns, _capture.Rows)
                : ScreenCapture.PlanGeometry(ScreenCapture.Resolve(Target), MaxEdge);

            return new StreamInfo
            {
                Stream = StreamIds.Name(StreamIds.PcScreen),
                Active = !_stop.IsCancellationRequested,
                Width = planned.Item1,
                Height = planned.Item2,
                TileWidth = ScreenCapture.TileSize,
                TileHeight = ScreenCapture.TileSize,
                Columns = planned.Item3,
                Rows = planned.Item4,
                Target = Target,
                Interact = Interact,
            };
        }

        public void Cancel()
        {
            try { _stop.Cancel(); } catch { }
        }
    }
}
