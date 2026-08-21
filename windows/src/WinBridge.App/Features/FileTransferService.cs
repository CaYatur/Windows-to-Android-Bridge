using System.Collections.Concurrent;
using System.IO;
using System.Security.Cryptography;
using WinBridge.App.Server;
using WinBridge.App.Storage;
using WinBridge.Core.Protocol;

namespace WinBridge.App.Features;

/// <summary>
/// Chunked file transfer in both directions.
///
/// Files ride the bulk lane, so a 2 GB video cannot starve the heartbeat or make
/// a tap on the mirrored screen wait — see <see cref="ProtocolSession"/>. The
/// send loop awaits each chunk, which means the disk read is paced by the link
/// rather than by how fast the file can be read into memory.
/// </summary>
public sealed class FileTransferService(BridgeStore store)
{
    /// <summary>
    /// 48 KB after framing overhead sits under the 64 KB most stacks are happy
    /// to move in one write, and small enough that cancelling feels immediate.
    /// </summary>
    public const int ChunkSize = 48 * 1024;

    private readonly ConcurrentDictionary<uint, Outgoing> _outgoing = new();
    private readonly ConcurrentDictionary<uint, Incoming> _incoming = new();
    private int _nextId = Random.Shared.Next(1, 1 << 20);

    public event Action<string>? Log;

    /// <summary>Raised for the UI: id, name, bytes done, total, incoming?</summary>
    public event Action<TransferProgress>? Progress;

    /// <summary>Asked before accepting a file when auto-accept is off. Returns the folder, or null to refuse.</summary>
    public Func<XferOffer, string, Task<string?>>? AskToAccept { get; set; }

    public IReadOnlyCollection<TransferProgress> Active =>
    [
        .. _outgoing.Values.Select(o => o.Snapshot()),
        .. _incoming.Values.Select(i => i.Snapshot()),
    ];

    // ---- sending ----------------------------------------------------------

    /// <summary>
    /// Offers a batch. Sends the offers immediately and streams each file once
    /// the phone accepts it, so the user sees the whole batch straight away
    /// instead of one file appearing at a time.
    /// </summary>
    public async Task<int> SendAsync(IPeerLink link, IReadOnlyList<string> paths, CancellationToken ct)
    {
        var files = new List<(string Path, string Relative, long Size)>();
        foreach (string path in paths)
        {
            if (Directory.Exists(path)) CollectFolder(path, files);
            else if (File.Exists(path)) files.Add((path, Path.GetFileName(path), new FileInfo(path).Length));
        }

        if (files.Count == 0) return 0;

        uint batch = (uint)Interlocked.Increment(ref _nextId);
        for (int index = 0; index < files.Count; index++)
        {
            var (path, relative, size) = files[index];
            uint id = (uint)Interlocked.Increment(ref _nextId);

            var outgoing = new Outgoing(id, path, relative, size, link);
            _outgoing[id] = outgoing;

            await link.SendJsonAsync(new XferOffer
            {
                Id = id,
                Name = Path.GetFileName(path),
                Path = relative,
                Size = size,
                Mime = GuessMime(path),
                Batch = batch,
                BatchIndex = index,
                BatchCount = files.Count,
            }, ct);
        }

        Log?.Invoke($"offered {files.Count} file(s) to {link.PeerName}");
        return files.Count;
    }

    private static void CollectFolder(string root, List<(string, string, long)> into)
    {
        string parent = Path.GetDirectoryName(root.TrimEnd(Path.DirectorySeparatorChar)) ?? root;
        foreach (string file in Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories))
        {
            // Relative to the folder's parent, so the folder itself is recreated
            // on the other side rather than its contents being dumped loose.
            string relative = Path.GetRelativePath(parent, file).Replace('\\', '/');
            into.Add((file, relative, new FileInfo(file).Length));
        }
    }

    public async Task OnAcceptedAsync(XferAccept accept, CancellationToken ct)
    {
        if (!_outgoing.TryGetValue(accept.Id, out var outgoing)) return;
        outgoing.Cancellation = CancellationTokenSource.CreateLinkedTokenSource(ct);

        _ = Task.Run(async () =>
        {
            try
            {
                await StreamAsync(outgoing, accept.Offset, outgoing.Cancellation.Token);
            }
            catch (OperationCanceledException)
            {
                Log?.Invoke($"{outgoing.Name}: cancelled");
            }
            catch (Exception ex)
            {
                Log?.Invoke($"{outgoing.Name}: {ex.Message}");
                await Safe(() => outgoing.Link.SendJsonAsync(
                    new XferDone { Id = outgoing.Id, Ok = false, Error = ex.Message }, CancellationToken.None));
            }
            finally
            {
                _outgoing.TryRemove(outgoing.Id, out _);
                Progress?.Invoke(outgoing.Snapshot(done: true));
            }
        }, ct);
    }

    private async Task StreamAsync(Outgoing outgoing, long offset, CancellationToken ct)
    {
        await using var file = new FileStream(
            outgoing.Path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite,
            ChunkSize, FileOptions.SequentialScan);

        if (offset > 0 && offset < file.Length) file.Seek(offset, SeekOrigin.Begin);
        outgoing.Sent = file.Position;

        using var hash = SHA256.Create();
        var buffer = new byte[ChunkSize];
        uint seq = 0;
        var started = DateTime.UtcNow;
        var lastReport = DateTime.UtcNow;

        while (true)
        {
            int read = await file.ReadAsync(buffer.AsMemory(), ct);
            bool last = read == 0 || file.Position >= file.Length;

            if (read > 0) hash.TransformBlock(buffer, 0, read, null, 0);

            // Awaited on purpose: the bulk lane completes a chunk only once it
            // is on the wire, so this loop is throttled by the link and never
            // reads the whole file into a queue.
            await outgoing.Link.SendXferAsync(
                new XferChunk(outgoing.Id, seq++, last ? XferFlags.Last : XferFlags.None,
                    buffer.AsMemory(0, read)),
                ct);

            outgoing.Sent += read;

            if ((DateTime.UtcNow - lastReport).TotalMilliseconds > 400 || last)
            {
                lastReport = DateTime.UtcNow;
                double seconds = Math.Max(0.001, (DateTime.UtcNow - started).TotalSeconds);
                outgoing.BytesPerSecond = (long)((outgoing.Sent - offset) / seconds);
                Progress?.Invoke(outgoing.Snapshot());
            }

            if (last) break;
        }

        hash.TransformFinalBlock([], 0, 0);
        await outgoing.Link.SendJsonAsync(new XferDone
        {
            Id = outgoing.Id,
            Ok = true,
            Sha256 = Convert.ToHexString(hash.Hash!).ToLowerInvariant(),
        }, ct);

        Log?.Invoke($"sent {outgoing.Name} ({outgoing.Sent} bytes)");
    }

    public void OnRejected(XferReject reject)
    {
        if (_outgoing.TryRemove(reject.Id, out var outgoing))
            Log?.Invoke($"{outgoing.Name}: refused by the phone ({reject.Reason})");
    }

    // ---- receiving --------------------------------------------------------

    public async Task OnOfferAsync(IPeerLink link, XferOffer offer, CancellationToken ct)
    {
        var settings = store.Settings;
        if (!settings.Files.Enabled)
        {
            await link.SendJsonAsync(new XferReject { Id = offer.Id, Reason = "disabled" }, ct);
            return;
        }

        string folder = store.DownloadFolder;
        bool small = offer.Size <= settings.Files.AutoAcceptMaxMb * 1024L * 1024L;

        if (!(settings.Files.AutoAccept && small))
        {
            string? chosen = AskToAccept is null ? null : await AskToAccept(offer, folder);
            if (chosen is null)
            {
                await link.SendJsonAsync(new XferReject { Id = offer.Id, Reason = "declined" }, ct);
                return;
            }
            folder = chosen;
        }

        string target;
        try
        {
            target = ResolveTarget(folder, offer);
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
        }
        catch (Exception ex)
        {
            await link.SendJsonAsync(new XferReject { Id = offer.Id, Reason = ex.Message }, ct);
            return;
        }

        var incoming = new Incoming(offer.Id, target, offer.Name, offer.Size, offer.Sha256, link);
        _incoming[offer.Id] = incoming;

        await link.SendJsonAsync(new XferAccept { Id = offer.Id, Offset = 0 }, ct);
        Log?.Invoke($"receiving {offer.Name} -> {target}");
    }

    /// <summary>
    /// Works out where a file lands, refusing anything that tries to escape the
    /// chosen folder. The relative path comes from the phone, so "../../.." in
    /// it has to be treated as hostile even when the phone is trusted — the
    /// path may have come from a share sheet and some other app wrote it.
    /// </summary>
    private static string ResolveTarget(string folder, XferOffer offer)
    {
        string root = Path.GetFullPath(folder);
        string relative = offer.Path ?? offer.Name;

        // Strip drive letters, UNC prefixes and leading separators before
        // combining, so only the tail is ever honoured.
        relative = relative.Replace('\\', '/').TrimStart('/');
        var parts = relative.Split('/', StringSplitOptions.RemoveEmptyEntries)
            .Where(part => part != "." && part != "..")
            .Select(Sanitise)
            .ToArray();

        if (parts.Length == 0) parts = [Sanitise(offer.Name)];

        string candidate = Path.GetFullPath(Path.Combine(root, Path.Combine(parts)));
        if (!candidate.StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) &&
            !candidate.Equals(root, StringComparison.OrdinalIgnoreCase))
            throw new IOException("the transfer tried to write outside the download folder");

        return Unique(candidate);
    }

    private static string Sanitise(string part)
    {
        var invalid = Path.GetInvalidFileNameChars();
        string cleaned = new([.. part.Select(c => invalid.Contains(c) ? '_' : c)]);
        return string.IsNullOrWhiteSpace(cleaned) ? "file" : cleaned.Trim();
    }

    /// <summary>Never silently overwrites: an accidental resend must not eat the original.</summary>
    private static string Unique(string path)
    {
        if (!File.Exists(path)) return path;

        string directory = Path.GetDirectoryName(path)!;
        string stem = Path.GetFileNameWithoutExtension(path);
        string extension = Path.GetExtension(path);

        for (int n = 2; n < 10_000; n++)
        {
            string candidate = Path.Combine(directory, $"{stem} ({n}){extension}");
            if (!File.Exists(candidate)) return candidate;
        }
        return Path.Combine(directory, $"{stem} ({Guid.NewGuid():N}){extension}");
    }

    public async Task OnChunkAsync(XferChunk chunk, CancellationToken ct)
    {
        if (!_incoming.TryGetValue(chunk.TransferId, out var incoming)) return;

        try
        {
            await incoming.WriteAsync(chunk.Data, ct);

            if ((DateTime.UtcNow - incoming.LastReport).TotalMilliseconds > 400)
            {
                incoming.LastReport = DateTime.UtcNow;
                Progress?.Invoke(incoming.Snapshot());
                await incoming.Link.SendJsonAsync(
                    new XferProgress { Id = incoming.Id, Bytes = incoming.Received }, ct);
            }

            if (chunk.IsLast()) await FinishAsync(incoming, ct);
        }
        catch (Exception ex)
        {
            Log?.Invoke($"{incoming.Name}: {ex.Message}");
            _incoming.TryRemove(incoming.Id, out _);
            incoming.Abort();
            await Safe(() => incoming.Link.SendJsonAsync(
                new XferDone { Id = incoming.Id, Ok = false, Error = ex.Message }, CancellationToken.None));
        }
    }

    private async Task FinishAsync(Incoming incoming, CancellationToken ct)
    {
        _incoming.TryRemove(incoming.Id, out _);
        string? saved = incoming.Complete(out string? error);

        Progress?.Invoke(incoming.Snapshot(done: true));
        await incoming.Link.SendJsonAsync(new XferDone
        {
            Id = incoming.Id,
            Ok = saved is not null,
            Error = error,
            SavedAs = saved,
            Sha256 = incoming.Digest,
        }, ct);

        if (saved is not null) Log?.Invoke($"saved {saved}");
        Completed?.Invoke(saved, incoming.Name, error);
    }

    /// <summary>Raised when a received file lands, so the tray can offer to open it.</summary>
    public event Action<string?, string, string?>? Completed;

    public void OnDone(XferDone done)
    {
        if (_outgoing.TryRemove(done.Id, out var outgoing) && !done.Ok)
            Log?.Invoke($"{outgoing.Name}: the phone reported {done.Error}");
    }

    public void Cancel(uint id)
    {
        if (_outgoing.TryRemove(id, out var outgoing)) outgoing.Cancellation?.Cancel();
        if (_incoming.TryRemove(id, out var incoming)) incoming.Abort();
    }

    public void CancelAllFor(IPeerLink link)
    {
        foreach (var (id, outgoing) in _outgoing)
            if (ReferenceEquals(outgoing.Link, link)) Cancel(id);
        foreach (var (id, incoming) in _incoming)
            if (ReferenceEquals(incoming.Link, link)) Cancel(id);
    }

    private static async Task Safe(Func<Task> action)
    {
        try { await action(); } catch { /* the link is going away anyway */ }
    }

    private static string? GuessMime(string path) => Path.GetExtension(path).ToLowerInvariant() switch
    {
        ".jpg" or ".jpeg" => "image/jpeg",
        ".png" => "image/png",
        ".gif" => "image/gif",
        ".webp" => "image/webp",
        ".pdf" => "application/pdf",
        ".txt" or ".log" or ".md" => "text/plain",
        ".mp3" => "audio/mpeg",
        ".mp4" => "video/mp4",
        ".zip" => "application/zip",
        ".apk" => "application/vnd.android.package-archive",
        _ => null,
    };

    // ---- transfer state ---------------------------------------------------

    private sealed class Outgoing(uint id, string path, string relative, long size, IPeerLink link)
    {
        public uint Id => id;
        public string Path => path;
        public string Name => System.IO.Path.GetFileName(path);
        public string Relative => relative;
        public long Size => size;
        public IPeerLink Link => link;
        public long Sent;
        public long BytesPerSecond;
        public CancellationTokenSource? Cancellation;

        public TransferProgress Snapshot(bool done = false) =>
            new(Id, Name, Sent, Size, Incoming: false, done, BytesPerSecond, link.PeerName);
    }

    private sealed class Incoming
    {
        private readonly FileStream _stream;
        private readonly SHA256 _hash = SHA256.Create();
        private readonly string _temporary;

        public Incoming(uint id, string target, string name, long size, string? expected, IPeerLink link)
        {
            Id = id;
            Target = target;
            Name = name;
            Size = size;
            Expected = expected;
            Link = link;

            // Written to a sibling temp file and moved at the end, so an
            // interrupted transfer never leaves something that looks complete.
            _temporary = target + ".wbpart";
            _stream = new FileStream(_temporary, FileMode.Create, FileAccess.Write, FileShare.None, ChunkSize);
        }

        public uint Id { get; }
        public string Target { get; }
        public string Name { get; }
        public long Size { get; }
        public string? Expected { get; }
        public IPeerLink Link { get; }
        public long Received { get; private set; }
        public DateTime LastReport { get; set; } = DateTime.UtcNow;
        public string? Digest { get; private set; }

        public async Task WriteAsync(ReadOnlyMemory<byte> data, CancellationToken ct)
        {
            if (data.Length == 0) return;
            await _stream.WriteAsync(data, ct);
            _hash.TransformBlock(data.ToArray(), 0, data.Length, null, 0);
            Received += data.Length;
        }

        public string? Complete(out string? error)
        {
            error = null;
            try
            {
                _stream.Flush(true);
                _stream.Dispose();
                _hash.TransformFinalBlock([], 0, 0);
                Digest = Convert.ToHexString(_hash.Hash!).ToLowerInvariant();

                if (Expected is not null &&
                    !Digest.Equals(Expected, StringComparison.OrdinalIgnoreCase))
                {
                    error = "checksum mismatch";
                    File.Delete(_temporary);
                    return null;
                }

                File.Move(_temporary, Target, overwrite: true);
                return Target;
            }
            catch (Exception ex)
            {
                error = ex.Message;
                return null;
            }
        }

        public void Abort()
        {
            try { _stream.Dispose(); } catch { }
            try { if (File.Exists(_temporary)) File.Delete(_temporary); } catch { }
        }

        public TransferProgress Snapshot(bool done = false) =>
            new(Id, Name, Received, Size, Incoming: true, done, 0, Link.PeerName);
    }
}

public readonly record struct TransferProgress(
    uint Id, string Name, long Bytes, long Total, bool Incoming, bool Done, long BytesPerSecond, string Peer);

internal static class XferChunkExtensions
{
    public static bool IsLast(this XferChunk chunk) => chunk.Flags.HasFlag(XferFlags.Last);
}
