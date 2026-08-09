using System.Diagnostics;
using System.IO;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using WinBridge.Core.Protocol;

namespace WinBridge.App.Providers;

/// <summary>
/// CPU / RAM / GPU / network / disk / battery sampling.
///
/// GPU deserves a note: the PDH "GPU Engine" counter has one instance per
/// process *per engine type*, so naively summing every instance produces
/// numbers well over 100%. We keep only engtype_3D — the one users mean by
/// "GPU usage" — sum those, and clamp.
/// </summary>
public sealed class SystemMetricsProvider : IDisposable
{
    private PerformanceCounter? _cpuCounter;
    private PerformanceCounterCategory? _gpuCategory;

    private long _lastNetBytesSent;
    private long _lastNetBytesReceived;
    private DateTime _lastNetSample = DateTime.MinValue;

    public SystemMetricsProvider()
    {
        try
        {
            _cpuCounter = new PerformanceCounter("Processor Information", "% Processor Utility", "_Total", true);
            _cpuCounter.NextValue();
        }
        catch
        {
            // "% Processor Utility" is absent on some SKUs; the classic counter
            // is always there but caps at 100 even under turbo.
            try
            {
                _cpuCounter = new PerformanceCounter("Processor", "% Processor Time", "_Total", true);
                _cpuCounter.NextValue();
            }
            catch { _cpuCounter = null; }
        }

        try { _gpuCategory = new PerformanceCounterCategory("GPU Engine"); }
        catch { _gpuCategory = null; }
    }

    public SystemState Sample()
    {
        return new SystemState
        {
            Cpu = SampleCpu(),
            Ram = SampleRam(),
            Gpu = SampleGpu(),
            Net = SampleNetwork(),
            Disk = SampleDisks(),
            Battery = SampleBattery(),
        };
    }

    private double SampleCpu()
    {
        if (_cpuCounter is null) return 0;
        try { return Math.Clamp(Math.Round(_cpuCounter.NextValue(), 1), 0, 100); }
        catch { return 0; }
    }

    private static RamInfo SampleRam()
    {
        var status = new MEMORYSTATUSEX { dwLength = (uint)Marshal.SizeOf<MEMORYSTATUSEX>() };
        if (!GlobalMemoryStatusEx(ref status)) return new RamInfo();

        long totalMb = (long)(status.ullTotalPhys / (1024 * 1024));
        long availMb = (long)(status.ullAvailPhys / (1024 * 1024));
        return new RamInfo { UsedMb = totalMb - availMb, TotalMb = totalMb };
    }

    private List<GpuInfo> SampleGpu()
    {
        if (_gpuCategory is null) return [];
        try
        {
            double total = 0;
            foreach (var instance in _gpuCategory.GetInstanceNames())
            {
                // Instance names look like:
                //   pid_1234_luid_0x00000000_0x0000ABCD_phys_0_eng_0_engtype_3D
                if (!instance.EndsWith("engtype_3D", StringComparison.OrdinalIgnoreCase))
                    continue;

                foreach (var counter in _gpuCategory.GetCounters(instance))
                {
                    if (counter.CounterName != "Utilization Percentage") continue;
                    try { total += counter.NextValue(); }
                    catch { /* a process can vanish between enumeration and read */ }
                    finally { counter.Dispose(); }
                }
            }
            return [new GpuInfo { Name = "GPU", Pct = Math.Clamp(Math.Round(total, 1), 0, 100) }];
        }
        catch { return []; }
    }

    private NetInfo SampleNetwork()
    {
        long sent = 0, received = 0;
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
                    continue;

                var stats = nic.GetIPStatistics();
                sent += stats.BytesSent;
                received += stats.BytesReceived;
            }
        }
        catch { return new NetInfo(); }

        var now = DateTime.UtcNow;
        if (_lastNetSample == DateTime.MinValue)
        {
            _lastNetSample = now;
            _lastNetBytesSent = sent;
            _lastNetBytesReceived = received;
            return new NetInfo();
        }

        double seconds = (now - _lastNetSample).TotalSeconds;
        if (seconds <= 0.05) return new NetInfo();

        // Counters reset when an adapter is disabled or reconnects; a negative
        // delta means "start over" rather than a huge negative rate.
        long upDelta = Math.Max(0, sent - _lastNetBytesSent);
        long downDelta = Math.Max(0, received - _lastNetBytesReceived);

        _lastNetSample = now;
        _lastNetBytesSent = sent;
        _lastNetBytesReceived = received;

        return new NetInfo
        {
            UpBps = (long)(upDelta / seconds),
            DownBps = (long)(downDelta / seconds),
        };
    }

    private static List<DiskInfo> SampleDisks()
    {
        var list = new List<DiskInfo>();
        try
        {
            foreach (var drive in DriveInfo.GetDrives())
            {
                if (!drive.IsReady || drive.DriveType != DriveType.Fixed) continue;
                double totalGb = drive.TotalSize / 1024d / 1024 / 1024;
                double freeGb = drive.TotalFreeSpace / 1024d / 1024 / 1024;
                list.Add(new DiskInfo
                {
                    Name = drive.Name.TrimEnd('\\'),
                    UsedGb = Math.Round(totalGb - freeGb, 1),
                    TotalGb = Math.Round(totalGb, 1),
                });
            }
        }
        catch { /* a drive can disappear mid-enumeration */ }
        return list;
    }

    private static BatteryInfo SampleBattery()
    {
        if (!GetSystemPowerStatus(out SYSTEM_POWER_STATUS status))
            return new BatteryInfo();

        // BatteryFlag bit 7 means "no system battery" — a desktop.
        bool present = (status.BatteryFlag & 128) == 0 && status.BatteryLifePercent != 255;
        if (!present) return new BatteryInfo { Present = false, Status = "none" };

        bool charging = status.ACLineStatus == 1;
        int pct = status.BatteryLifePercent;

        string state = (status.BatteryFlag & 8) != 0 ? "charging"
            : (status.BatteryFlag & 4) != 0 ? "critical"
            : (status.BatteryFlag & 2) != 0 ? "low"
            : charging && pct >= 99 ? "full"
            : charging ? "charging"
            : "normal";

        return new BatteryInfo
        {
            Present = true,
            Pct = pct,
            Charging = charging,
            Status = state,
            MinutesLeft = status.BatteryLifeTime < 0 ? -1 : status.BatteryLifeTime / 60,
        };
    }

    public void Dispose() => _cpuCounter?.Dispose();

    [StructLayout(LayoutKind.Sequential)]
    private struct MEMORYSTATUSEX
    {
        public uint dwLength;
        public uint dwMemoryLoad;
        public ulong ullTotalPhys;
        public ulong ullAvailPhys;
        public ulong ullTotalPageFile;
        public ulong ullAvailPageFile;
        public ulong ullTotalVirtual;
        public ulong ullAvailVirtual;
        public ulong ullAvailExtendedVirtual;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SYSTEM_POWER_STATUS
    {
        public byte ACLineStatus;
        public byte BatteryFlag;
        public byte BatteryLifePercent;
        public byte SystemStatusFlag;
        public int BatteryLifeTime;
        public int BatteryFullLifeTime;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalMemoryStatusEx(ref MEMORYSTATUSEX lpBuffer);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetSystemPowerStatus(out SYSTEM_POWER_STATUS lpSystemPowerStatus);
}
