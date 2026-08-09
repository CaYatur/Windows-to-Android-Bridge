using System.Runtime.InteropServices;
using WinBridge.Core.Protocol;

namespace WinBridge.App.Providers;

/// <summary>
/// Master output volume through CoreAudio. Hand-rolled COM interop rather than a
/// NuGet wrapper: it is a small, stable surface and this keeps the shipped
/// binary free of a dependency we would otherwise only use for six calls.
///
/// The endpoint is re-resolved on demand so that changing the default output
/// device (headphones plugged in, monitor speakers selected) is picked up
/// without restarting.
/// </summary>
public sealed class VolumeProvider : IDisposable
{
    private IAudioEndpointVolume? _endpoint;
    private readonly Lock _gate = new();

    public VolumeState Read()
    {
        lock (_gate)
        {
            try
            {
                var ep = Endpoint();
                ep.GetMasterVolumeLevelScalar(out float level);
                ep.GetMute(out bool muted);
                return new VolumeState { Level = (int)Math.Round(level * 100), Muted = muted };
            }
            catch
            {
                Release();
                return new VolumeState { Level = 0, Muted = false };
            }
        }
    }

    public bool SetLevel(int percent)
    {
        lock (_gate)
        {
            try
            {
                Endpoint().SetMasterVolumeLevelScalar(Math.Clamp(percent, 0, 100) / 100f, Guid.Empty);
                return true;
            }
            catch { Release(); return false; }
        }
    }

    public bool SetMuted(bool muted)
    {
        lock (_gate)
        {
            try { Endpoint().SetMute(muted, Guid.Empty); return true; }
            catch { Release(); return false; }
        }
    }

    private IAudioEndpointVolume Endpoint()
    {
        if (_endpoint is not null) return _endpoint;

        var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
        enumerator.GetDefaultAudioEndpoint(EDataFlow.Render, ERole.Multimedia, out IMMDevice device);
        var iid = typeof(IAudioEndpointVolume).GUID;
        device.Activate(ref iid, CLSCTX.InprocServer, IntPtr.Zero, out object obj);
        _endpoint = (IAudioEndpointVolume)obj;
        return _endpoint;
    }

    private void Release()
    {
        if (_endpoint is not null)
        {
            try { Marshal.ReleaseComObject(_endpoint); } catch { }
            _endpoint = null;
        }
    }

    public void Dispose()
    {
        lock (_gate) Release();
    }

    private enum EDataFlow { Render, Capture, All }
    private enum ERole { Console, Multimedia, Communications }

    [Flags]
    private enum CLSCTX : uint { InprocServer = 0x1 }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class MMDeviceEnumerator { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        int EnumAudioEndpoints(EDataFlow dataFlow, int stateMask, out IntPtr devices);
        int GetDefaultAudioEndpoint(EDataFlow dataFlow, ERole role, out IMMDevice device);
        int GetDevice(string id, out IMMDevice device);
        int RegisterEndpointNotificationCallback(IntPtr client);
        int UnregisterEndpointNotificationCallback(IntPtr client);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        int Activate(ref Guid iid, CLSCTX clsCtx, IntPtr activationParams,
            [MarshalAs(UnmanagedType.IUnknown)] out object instance);
        int OpenPropertyStore(int access, out IntPtr store);
        int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        int GetState(out int state);
    }

    [ComImport, Guid("5CDF2C82-841E-4546-9722-0CF74078229A"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioEndpointVolume
    {
        int RegisterControlChangeNotify(IntPtr notify);
        int UnregisterControlChangeNotify(IntPtr notify);
        int GetChannelCount(out uint count);
        int SetMasterVolumeLevel(float levelDb, Guid eventContext);
        int SetMasterVolumeLevelScalar(float level, Guid eventContext);
        int GetMasterVolumeLevel(out float levelDb);
        int GetMasterVolumeLevelScalar(out float level);
        int SetChannelVolumeLevel(uint channel, float levelDb, Guid eventContext);
        int SetChannelVolumeLevelScalar(uint channel, float level, Guid eventContext);
        int GetChannelVolumeLevel(uint channel, out float levelDb);
        int GetChannelVolumeLevelScalar(uint channel, out float level);
        int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, Guid eventContext);
        int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
    }
}
