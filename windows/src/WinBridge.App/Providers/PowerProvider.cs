using System.Diagnostics;
using System.Runtime.InteropServices;
using WinBridge.Core.Protocol;

namespace WinBridge.App.Providers;

/// <summary>
/// Power actions on the local machine.
///
/// Capabilities are probed rather than assumed: hibernate is frequently disabled
/// (it is off by default on many OEM laptops and always off when Fast Startup is
/// disabled), and "turn the display off" does nothing on some desktop
/// configurations. The phone greys out what this reports as unavailable instead
/// of offering a button that silently does nothing.
/// </summary>
public sealed class PowerProvider
{
    public PowerCaps Caps { get; }

    public PowerProvider()
    {
        GetPwrCapabilities(out SYSTEM_POWER_CAPABILITIES caps);
        Caps = new PowerCaps
        {
            Lock = true,
            Sleep = caps.SystemS1 || caps.SystemS2 || caps.SystemS3,
            Hibernate = caps.SystemS4 && caps.HiberFilePresent,
            Shutdown = true,
            Restart = true,
            Logoff = true,
            DisplayOff = true,
        };
    }

    public bool Execute(string action, int delaySeconds, out string? error)
    {
        error = null;
        try
        {
            if (delaySeconds > 0)
            {
                // Deliberately fire-and-forget: the caller is a network handler
                // and must not block for the length of a user-chosen delay.
                _ = Task.Run(async () =>
                {
                    await Task.Delay(TimeSpan.FromSeconds(delaySeconds));
                    Perform(action, out _);
                });
                return true;
            }
            return Perform(action, out error);
        }
        catch (Exception ex)
        {
            error = ex.Message;
            return false;
        }
    }

    private bool Perform(string action, out string? error)
    {
        error = null;
        switch (action)
        {
            case "lock":
                return LockWorkStation() || Fail(out error, "LockWorkStation failed");

            case "sleep":
                if (!Caps.Sleep) { error = "sleep is not supported on this machine"; return false; }
                return SetSuspendState(false, false, false) || Fail(out error, "SetSuspendState failed");

            case "hibernate":
                if (!Caps.Hibernate) { error = "hibernate is disabled on this machine"; return false; }
                return SetSuspendState(true, false, false) || Fail(out error, "SetSuspendState failed");

            case "display_off":
                // Broadcasting to HWND_BROADCAST is the documented route and works
                // from a normal windowed process; SC_MONITORPOWER with 2 == off.
                SendMessage(HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER, MONITOR_OFF);
                return true;

            case "shutdown":
                return RunShutdown("/s /t 0", out error);

            case "restart":
                return RunShutdown("/r /t 0", out error);

            case "logoff":
                return RunShutdown("/l", out error);

            default:
                error = $"unknown power action '{action}'";
                return false;
        }
    }

    private static bool Fail(out string? error, string message)
    {
        error = message;
        return false;
    }

    /// <summary>
    /// shutdown.exe rather than ExitWindowsEx: it handles acquiring
    /// SeShutdownPrivilege and the "an app is blocking shutdown" case for us,
    /// and matches what the user would get from the Start menu.
    /// </summary>
    private static bool RunShutdown(string arguments, out string? error)
    {
        error = null;
        try
        {
            var psi = new ProcessStartInfo("shutdown.exe", arguments)
            {
                CreateNoWindow = true,
                UseShellExecute = false,
                RedirectStandardError = true,
            };
            using var process = Process.Start(psi);
            if (process is null) { error = "could not start shutdown.exe"; return false; }

            if (process.WaitForExit(5000) && process.ExitCode != 0)
            {
                error = process.StandardError.ReadToEnd().Trim();
                if (string.IsNullOrEmpty(error)) error = $"shutdown.exe exited {process.ExitCode}";
                return false;
            }
            return true;
        }
        catch (Exception ex)
        {
            error = ex.Message;
            return false;
        }
    }

    private const int WM_SYSCOMMAND = 0x0112;
    private const int SC_MONITORPOWER = 0xF170;
    private const int MONITOR_OFF = 2;
    private static readonly IntPtr HWND_BROADCAST = new(0xFFFF);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool LockWorkStation();

    [DllImport("powrprof.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetSuspendState(
        [MarshalAs(UnmanagedType.Bool)] bool hibernate,
        [MarshalAs(UnmanagedType.Bool)] bool forceCritical,
        [MarshalAs(UnmanagedType.Bool)] bool disableWakeEvent);

    [DllImport("user32.dll")]
    private static extern IntPtr SendMessage(IntPtr hWnd, int msg, int wParam, int lParam);

    [DllImport("powrprof.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetPwrCapabilities(out SYSTEM_POWER_CAPABILITIES caps);

    [StructLayout(LayoutKind.Sequential)]
    private struct SYSTEM_POWER_CAPABILITIES
    {
        [MarshalAs(UnmanagedType.U1)] public bool PowerButtonPresent;
        [MarshalAs(UnmanagedType.U1)] public bool SleepButtonPresent;
        [MarshalAs(UnmanagedType.U1)] public bool LidPresent;
        [MarshalAs(UnmanagedType.U1)] public bool SystemS1;
        [MarshalAs(UnmanagedType.U1)] public bool SystemS2;
        [MarshalAs(UnmanagedType.U1)] public bool SystemS3;
        [MarshalAs(UnmanagedType.U1)] public bool SystemS4;
        [MarshalAs(UnmanagedType.U1)] public bool SystemS5;
        [MarshalAs(UnmanagedType.U1)] public bool HiberFilePresent;
        [MarshalAs(UnmanagedType.U1)] public bool FullWake;
        [MarshalAs(UnmanagedType.U1)] public bool VideoDimPresent;
        [MarshalAs(UnmanagedType.U1)] public bool ApmPresent;
        [MarshalAs(UnmanagedType.U1)] public bool UpsPresent;
        [MarshalAs(UnmanagedType.U1)] public bool ThermalControl;
        [MarshalAs(UnmanagedType.U1)] public bool ProcessorThrottle;
        public byte ProcessorMinThrottle;
        public byte ProcessorMaxThrottle;
        [MarshalAs(UnmanagedType.U1)] public bool FastSystemS4;
        [MarshalAs(UnmanagedType.U1)] public bool Hiberboot;
        [MarshalAs(UnmanagedType.U1)] public bool WakeAlarmPresent;
        [MarshalAs(UnmanagedType.U1)] public bool AoAc;
        [MarshalAs(UnmanagedType.U1)] public bool DiskSpinDown;
        public byte HiberFileType;
        [MarshalAs(UnmanagedType.U1)] public bool AoAcConnectivitySupported;
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 6)] public byte[] spare3;
        [MarshalAs(UnmanagedType.U1)] public bool SystemBatteriesPresent;
        [MarshalAs(UnmanagedType.U1)] public bool BatteriesAreShortTerm;
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 3)] public BATTERY_REPORTING_SCALE[] BatteryScale;
        public int AcOnLineWake;
        public int SoftLidWake;
        public int RtcWake;
        public int MinDeviceWakeState;
        public int DefaultLowLatencyWake;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BATTERY_REPORTING_SCALE
    {
        public uint Granularity;
        public uint Capacity;
    }
}
