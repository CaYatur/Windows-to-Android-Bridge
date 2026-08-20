using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using WinBridge.Core.Protocol;

namespace WinBridge.App.Features;

/// <summary>
/// Answers "what is this machine doing right now" and acts on the answer:
/// windows, processes, a screenshot, and the text on screen.
/// </summary>
public sealed class SystemQueryService
{
    public event Action<string>? Log;

    // ---- windows ----------------------------------------------------------

    public List<WindowInfo> Windows()
    {
        var found = new List<WindowInfo>();
        IntPtr foreground = GetForegroundWindow();

        EnumWindows((handle, _) =>
        {
            if (!IsWindowVisible(handle)) return true;

            // Every desktop has dozens of visible-but-empty tool and message
            // windows. Titled, non-tool, top-level windows are what a person
            // means by "my open windows".
            int length = GetWindowTextLength(handle);
            if (length == 0) return true;

            long exStyle = GetWindowLongPtr(handle, GWL_EXSTYLE).ToInt64();
            if ((exStyle & WS_EX_TOOLWINDOW) != 0) return true;

            if (DwmGetWindowAttribute(handle, DWMWA_CLOAKED, out int cloaked, sizeof(int)) == 0 && cloaked != 0)
                return true;

            var title = new StringBuilder(length + 1);
            GetWindowText(handle, title, title.Capacity);

            GetWindowThreadProcessId(handle, out uint pid);
            string process = "";
            try { process = Process.GetProcessById((int)pid).ProcessName; } catch { }

            found.Add(new WindowInfo
            {
                Handle = handle.ToInt64(),
                Title = title.ToString(),
                Process = process,
                Pid = (int)pid,
                Active = handle == foreground,
                Minimized = IsIconic(handle),
            });
            return true;
        }, IntPtr.Zero);

        return found;
    }

    public bool Window(WindowCommand command, out string? error)
    {
        error = null;
        IntPtr handle = command.Handle != 0 ? new IntPtr(command.Handle) : Find(command.Match);

        if (handle == IntPtr.Zero)
        {
            error = command.Match is null ? "no such window" : $"no window matching \"{command.Match}\"";
            return false;
        }

        switch (command.Action)
        {
            case "focus":
                if (IsIconic(handle)) ShowWindow(handle, SW_RESTORE);
                // SetForegroundWindow is refused unless the calling thread owns
                // the foreground or is attached to it, which is why a bare call
                // often just flashes the taskbar button instead of raising.
                ForceForeground(handle);
                return true;

            case "minimize": ShowWindow(handle, SW_MINIMIZE); return true;
            case "maximize": ShowWindow(handle, SW_MAXIMIZE); return true;
            case "restore": ShowWindow(handle, SW_RESTORE); return true;
            case "close": PostMessage(handle, WM_CLOSE, IntPtr.Zero, IntPtr.Zero); return true;

            default:
                error = $"unknown window action \"{command.Action}\"";
                return false;
        }
    }

    /// <summary>Matches a window by title first, then by process name. Case-insensitive, substring.</summary>
    public IntPtr Find(string? match)
    {
        if (string.IsNullOrWhiteSpace(match)) return IntPtr.Zero;

        var windows = Windows();
        var byTitle = windows.FirstOrDefault(w =>
            w.Title.Contains(match, StringComparison.OrdinalIgnoreCase));
        if (byTitle is not null) return new IntPtr(byTitle.Handle);

        var byProcess = windows.FirstOrDefault(w =>
            w.Process.Contains(match, StringComparison.OrdinalIgnoreCase));
        return byProcess is null ? IntPtr.Zero : new IntPtr(byProcess.Handle);
    }

    private static void ForceForeground(IntPtr handle)
    {
        uint target = GetWindowThreadProcessId(handle, out _);
        uint current = GetCurrentThreadId();

        if (target != current) AttachThreadInput(current, target, true);
        try
        {
            BringWindowToTop(handle);
            SetForegroundWindow(handle);
            ShowWindow(handle, SW_SHOW);
        }
        finally
        {
            if (target != current) AttachThreadInput(current, target, false);
        }
    }

    // ---- processes ---------------------------------------------------------

    public List<ProcessInfo> Processes(int top)
    {
        var list = new List<ProcessInfo>();
        foreach (var process in Process.GetProcesses())
        {
            try
            {
                list.Add(new ProcessInfo
                {
                    Pid = process.Id,
                    Name = process.ProcessName,
                    MemoryMb = process.WorkingSet64 / (1024 * 1024),
                });
            }
            catch { /* exited between the enumeration and the read */ }
            finally { process.Dispose(); }
        }

        return [.. list.OrderByDescending(p => p.MemoryMb).Take(Math.Clamp(top, 1, 200))];
    }

    public bool KillProcess(int pid, out string? error)
    {
        error = null;
        try
        {
            using var process = Process.GetProcessById(pid);
            process.Kill();
            return true;
        }
        catch (Exception ex)
        {
            error = ex.Message;
            return false;
        }
    }

    // ---- screenshot and description ----------------------------------------

    public byte[]? Screenshot(string? target, int maxEdge = 1600, int quality = 80)
    {
        try
        {
            var bounds = ScreenCapture.Resolve(target);
            using var bitmap = new Bitmap(bounds.Width, bounds.Height, PixelFormat.Format32bppRgb);
            using (var graphics = Graphics.FromImage(bitmap))
                graphics.CopyFromScreen(bounds.Left, bounds.Top, 0, 0, bounds.Size);

            Bitmap output = bitmap;
            Bitmap? scaled = null;
            if (maxEdge > 0 && Math.Max(bounds.Width, bounds.Height) > maxEdge)
            {
                double factor = (double)maxEdge / Math.Max(bounds.Width, bounds.Height);
                scaled = new Bitmap(bitmap, (int)(bounds.Width * factor), (int)(bounds.Height * factor));
                output = scaled;
            }

            try
            {
                using var stream = new MemoryStream();
                var codec = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);
                using var parameters = new EncoderParameters(1);
                parameters.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, (long)quality);
                output.Save(stream, codec, parameters);
                return stream.ToArray();
            }
            finally { scaled?.Dispose(); }
        }
        catch (Exception ex)
        {
            Log?.Invoke($"screenshot failed: {ex.Message}");
            return null;
        }
    }

    /// <summary>
    /// What is on screen, in words.
    ///
    /// OCR runs on the Windows side deliberately. The alternative was bundling a
    /// recognition model into the phone app, several megabytes for something the
    /// host can already do offline through <c>Windows.Media.Ocr</c>. Doing it
    /// here also means the phone gets an answer whether or not it is mirroring.
    /// </summary>
    public async Task<Description> DescribeAsync(DescribeRequest request)
    {
        var windows = Windows();
        var active = windows.FirstOrDefault(w => w.Active);
        var bounds = ScreenCapture.Resolve(request.Target);

        string? text = null;
        string? reason = null;

        if (request.Ocr)
        {
            try { text = await OcrAsync(request.Target); }
            catch (Exception ex)
            {
                reason = $"OCR unavailable: {ex.Message}";
                Log?.Invoke(reason);
            }
        }

        return new Description
        {
            Title = active?.Title,
            Process = active?.Process,
            Text = text,
            Windows = [.. windows.Where(w => !w.Minimized).Select(w => w.Title).Take(20)],
            Width = bounds.Width,
            Height = bounds.Height,
            Reason = reason,
        };
    }

    /// <summary>
    /// Runs the built-in Windows recogniser over a screen grab.
    ///
    /// Guarded rather than assumed available: some Windows editions ship without
    /// an OCR language pack, and there the honest answer is window titles plus a
    /// reason, not an exception that takes the whole describe feature down.
    /// </summary>
    private static async Task<string?> OcrAsync(string? target)
    {
        var engine = global::Windows.Media.Ocr.OcrEngine.TryCreateFromUserProfileLanguages()
            ?? throw new NotSupportedException("no OCR language pack is installed");

        var bounds = ScreenCapture.Resolve(target);
        using var bitmap = new Bitmap(bounds.Width, bounds.Height, PixelFormat.Format32bppRgb);
        using (var graphics = Graphics.FromImage(bitmap))
            graphics.CopyFromScreen(bounds.Left, bounds.Top, 0, 0, bounds.Size);

        using var memory = new MemoryStream();
        bitmap.Save(memory, ImageFormat.Png);
        memory.Position = 0;

        using var random = new global::Windows.Storage.Streams.InMemoryRandomAccessStream();
        await memory.CopyToAsync(random.AsStreamForWrite());
        random.Seek(0);

        var decoder = await global::Windows.Graphics.Imaging.BitmapDecoder.CreateAsync(random);
        using var software = await decoder.GetSoftwareBitmapAsync();

        var result = await engine.RecognizeAsync(software);
        string text = result.Text;
        return string.IsNullOrWhiteSpace(text) ? null : text;
    }

    public bool Open(string target, out string? error)
    {
        error = null;
        try
        {
            Process.Start(new ProcessStartInfo(target) { UseShellExecute = true });
            return true;
        }
        catch (Exception ex)
        {
            error = ex.Message;
            return false;
        }
    }

    // ---- interop ------------------------------------------------------------

    private const int GWL_EXSTYLE = -20;
    private const long WS_EX_TOOLWINDOW = 0x00000080;
    private const int DWMWA_CLOAKED = 14;
    private const int SW_RESTORE = 9;
    private const int SW_MINIMIZE = 6;
    private const int SW_MAXIMIZE = 3;
    private const int SW_SHOW = 5;
    private const uint WM_CLOSE = 0x0010;

    private delegate bool EnumWindowsProc(IntPtr handle, IntPtr param);

    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr param);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr handle);
    [DllImport("user32.dll")] private static extern bool IsIconic(IntPtr handle);
    [DllImport("user32.dll")] private static extern int GetWindowTextLength(IntPtr handle);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr handle, StringBuilder text, int count);
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr handle);
    [DllImport("user32.dll")] private static extern bool BringWindowToTop(IntPtr handle);
    [DllImport("user32.dll")] private static extern bool ShowWindow(IntPtr handle, int command);
    [DllImport("user32.dll")] private static extern bool PostMessage(IntPtr handle, uint message, IntPtr w, IntPtr l);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr handle, out uint pid);
    [DllImport("user32.dll")] private static extern bool AttachThreadInput(uint from, uint to, bool attach);
    [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();
    [DllImport("dwmapi.dll")] private static extern int DwmGetWindowAttribute(IntPtr handle, int attribute, out int value, int size);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")]
    private static extern IntPtr GetWindowLongPtr64(IntPtr handle, int index);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongW")]
    private static extern int GetWindowLong32(IntPtr handle, int index);

    private static IntPtr GetWindowLongPtr(IntPtr handle, int index) =>
        IntPtr.Size == 8 ? GetWindowLongPtr64(handle, index) : new IntPtr(GetWindowLong32(handle, index));
}
