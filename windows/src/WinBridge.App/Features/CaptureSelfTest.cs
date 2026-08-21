using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Text;
using WinBridge.Core.Protocol;

namespace WinBridge.App.Features;

/// <summary>
/// Runs the whole screen path in one process and writes the result to a PNG:
/// capture, tile, pack into media packets, parse them back, and repaint.
///
/// This exists because the two ends of that path normally live on different
/// machines, so a fault in the middle shows up as "the mirror looks wrong on my
/// phone" — a report with no way to tell whether the sender, the wire or the
/// receiver is at fault. Reassembling locally answers that in one step, and it
/// is the only way to check the multi-tile case without a second device: a frame
/// with a single changed tile renders correctly even when the packing is broken.
///
/// Invoked with <c>WinBridge.exe --selftest-capture out.png</c>. Runs before the
/// single-instance check, so it works while the tray app is already running.
/// </summary>
public static class CaptureSelfTest
{
    public static int Run(string outputPath)
    {
        var report = new StringBuilder();
        try
        {
            using var capture = new ScreenCapture();
            var bounds = ScreenCapture.Resolve(null);

            var planned = ScreenCapture.PlanGeometry(bounds, 1280);
            report.AppendLine($"display   {bounds.Width}x{bounds.Height}");
            report.AppendLine($"planned   {planned.Width}x{planned.Height}, {planned.Columns}x{planned.Rows} tiles");

            // Two passes. The first is the keyframe — every tile — which is what
            // exercises packing across several packets. The second proves the
            // differ suppresses what did not move.
            var packets = new List<MediaPacket>();
            int firstCount = Encode(capture, bounds, packets, force: true);
            report.AppendLine($"keyframe  {firstCount} tiles in {packets.Count} packet(s)");

            if (capture.Columns != planned.Columns || capture.Rows != planned.Rows)
            {
                report.AppendLine(
                    $"MISMATCH  planned {planned.Columns}x{planned.Rows} but captured " +
                    $"{capture.Columns}x{capture.Rows} — the receiver would place tiles wrongly");
            }

            var second = new List<MediaPacket>();
            int secondCount = Encode(capture, bounds, second, force: false);
            report.AppendLine($"delta     {secondCount} tiles in {second.Count} packet(s)");

            int keyframes = packets.Count(p => p.Flags.HasFlag(MediaFlags.Keyframe));
            report.AppendLine($"keyframe flags: {keyframes} (must be 1 — it clears the receiver canvas)");

            // Repaint exactly as the receiver does, from the packet bytes alone.
            using var canvas = new Bitmap(capture.Width, capture.Height, PixelFormat.Format32bppRgb);
            int painted = Paint(canvas, packets) + Paint(canvas, second);
            report.AppendLine($"repainted {painted} tiles");

            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(outputPath))!);
            canvas.Save(outputPath, ImageFormat.Png);
            report.AppendLine($"wrote     {outputPath}");

            bool ok = firstCount > 0 && painted == firstCount + secondCount && keyframes == 1;
            report.AppendLine(ok ? "RESULT    ok" : "RESULT    FAILED");

            File.WriteAllText(Path.ChangeExtension(outputPath, ".txt"), report.ToString());
            return ok ? 0 : 1;
        }
        catch (Exception ex)
        {
            report.AppendLine($"RESULT    threw: {ex}");
            try { File.WriteAllText(Path.ChangeExtension(outputPath, ".txt"), report.ToString()); } catch { }
            return 2;
        }
    }

    private static int Encode(ScreenCapture capture, Rectangle bounds, List<MediaPacket> into, bool force)
    {
        var packet = new MemoryStream(TileCodec.MaxPacketBytes);
        bool keyframeSent = false;
        uint seq = 0;

        int changed = capture.CaptureChanged(bounds, 1280, 70, drawCursor: false, forceAll: force,
            (index, jpeg) =>
            {
                if (packet.Length > 0 && packet.Length + jpeg.Length + 6 > TileCodec.MaxPacketBytes)
                {
                    into.Add(Pack(packet, seq++, force && !keyframeSent, last: false));
                    keyframeSent = true;
                }
                TileCodec.WriteTile(packet, index, jpeg);
            });

        if (changed > 0) into.Add(Pack(packet, seq, force && !keyframeSent, last: true));
        return changed;
    }

    private static MediaPacket Pack(MemoryStream packet, uint seq, bool keyframe, bool last)
    {
        var flags = MediaFlags.None;
        if (keyframe) flags |= MediaFlags.Keyframe;
        if (last) flags |= MediaFlags.EndOfFrame;

        var built = new MediaPacket(MediaKind.Video, StreamIds.PcScreen, seq, 0, flags, packet.ToArray());
        packet.SetLength(0);
        return built;
    }

    private static int Paint(Bitmap canvas, List<MediaPacket> packets)
    {
        int painted = 0;
        int columns = (canvas.Width + ScreenCapture.TileSize - 1) / ScreenCapture.TileSize;

        using var graphics = Graphics.FromImage(canvas);
        foreach (var packet in packets)
        {
            // Round-tripped through the wire encoding on purpose: parsing the
            // bytes back is the half a receiver actually performs.
            byte[] wire = packet.ToBytes();
            var parsed = MediaPacket.Parse(wire);

            if (parsed.Flags.HasFlag(MediaFlags.Keyframe)) graphics.Clear(Color.Black);

            foreach (var (index, jpeg) in TileCodec.ReadTiles(parsed.Payload))
            {
                using var stream = new MemoryStream(jpeg.ToArray(), writable: false);
                using var tile = new Bitmap(stream);
                graphics.DrawImage(
                    tile,
                    (index % columns) * ScreenCapture.TileSize,
                    (index / columns) * ScreenCapture.TileSize,
                    tile.Width,
                    tile.Height);
                painted++;
            }
        }
        return painted;
    }
}
