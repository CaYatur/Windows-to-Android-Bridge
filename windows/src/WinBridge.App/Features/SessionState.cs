using System.Runtime.InteropServices;

namespace WinBridge.App.Features;

/// <summary>
/// Whether anyone is actually sitting in front of this machine.
///
/// There is no "is the workstation locked" API. The reliable test is whether the
/// input desktop can be opened: on the secure desktop (lock screen, UAC prompt)
/// that fails for a normal-integrity process, which is exactly the condition
/// worth refusing remote input and automations on.
/// </summary>
public static class SessionState
{
    private const uint DESKTOP_SWITCHDESKTOP = 0x0100;

    public static bool IsLocked()
    {
        IntPtr desktop = OpenInputDesktop(0, false, DESKTOP_SWITCHDESKTOP);
        if (desktop == IntPtr.Zero) return true;

        CloseDesktop(desktop);
        return false;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool CloseDesktop(IntPtr desktop);
}
