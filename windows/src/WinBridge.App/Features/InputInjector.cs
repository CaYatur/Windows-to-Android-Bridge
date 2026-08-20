using System.Runtime.InteropServices;
using WinBridge.Core.Protocol;
using Forms = System.Windows.Forms;

namespace WinBridge.App.Features;

/// <summary>
/// Injects mouse, keyboard and text events from the phone.
///
/// <c>SendInput</c> rather than <c>mouse_event</c>/<c>keybd_event</c>: the older
/// pair cannot deliver a batch atomically, so a modifier chord can interleave
/// with real typing and land as the wrong shortcut. Text goes in as
/// <c>KEYEVENTF_UNICODE</c> scan codes, which types the same characters
/// regardless of the keyboard layout the machine happens to be using — mapping
/// characters to virtual keys would type Turkish text as gibberish on a US
/// layout, and vice versa.
/// </summary>
public sealed class InputInjector
{
    /// <summary>False while the workstation is locked, if the setting asks for that.</summary>
    public Func<bool>? Gate { get; set; }

    public event Action<string>? Log;

    public bool Available => true;

    private bool Allowed()
    {
        if (Gate is null) return true;
        return Gate();
    }

    // ---- mouse ------------------------------------------------------------

    public void MoveAbsolute(int screenX, int screenY)
    {
        if (!Allowed()) return;
        Send(new INPUT
        {
            type = INPUT_MOUSE,
            u = new INPUTUNION
            {
                mouse = new MOUSEINPUT
                {
                    dx = ToAbsoluteX(screenX),
                    dy = ToAbsoluteY(screenY),
                    dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK,
                },
            },
        });
    }

    public void MoveRelative(int dx, int dy)
    {
        if (!Allowed() || (dx == 0 && dy == 0)) return;
        Send(new INPUT
        {
            type = INPUT_MOUSE,
            u = new INPUTUNION { mouse = new MOUSEINPUT { dx = dx, dy = dy, dwFlags = MOUSEEVENTF_MOVE } },
        });
    }

    public void Button(string button, bool down)
    {
        if (!Allowed()) return;

        (uint flag, uint data) = button switch
        {
            "right" => (down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP, 0u),
            "middle" => (down ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP, 0u),
            "x1" => (down ? MOUSEEVENTF_XDOWN : MOUSEEVENTF_XUP, XBUTTON1),
            "x2" => (down ? MOUSEEVENTF_XDOWN : MOUSEEVENTF_XUP, XBUTTON2),
            _ => (down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP, 0u),
        };

        Send(new INPUT
        {
            type = INPUT_MOUSE,
            u = new INPUTUNION { mouse = new MOUSEINPUT { dwFlags = flag, mouseData = data } },
        });
    }

    public void Click(string button)
    {
        Button(button, down: true);
        Button(button, down: false);
    }

    public void Wheel(int delta, int horizontalDelta)
    {
        if (!Allowed()) return;

        if (delta != 0)
        {
            Send(new INPUT
            {
                type = INPUT_MOUSE,
                u = new INPUTUNION
                {
                    mouse = new MOUSEINPUT { dwFlags = MOUSEEVENTF_WHEEL, mouseData = unchecked((uint)delta) },
                },
            });
        }

        if (horizontalDelta != 0)
        {
            Send(new INPUT
            {
                type = INPUT_MOUSE,
                u = new INPUTUNION
                {
                    mouse = new MOUSEINPUT { dwFlags = MOUSEEVENTF_HWHEEL, mouseData = unchecked((uint)horizontalDelta) },
                },
            });
        }
    }

    // ---- keyboard ---------------------------------------------------------

    public bool Key(InputKey command)
    {
        if (!Allowed()) return false;

        ushort? code = Map(command.Code);
        if (code is null)
        {
            Log?.Invoke($"unknown key name \"{command.Code}\"");
            return false;
        }

        var modifiers = command.Mods
            .Select(Map)
            .Where(vk => vk is not null)
            .Select(vk => vk!.Value)
            .ToArray();

        var batch = new List<INPUT>();

        if (command.Action is "tap" or "down")
            foreach (ushort modifier in modifiers) batch.Add(KeyInput(modifier, down: true));

        int repeat = Math.Clamp(command.Repeat, 1, 50);
        for (int n = 0; n < repeat; n++)
        {
            if (command.Action is "tap" or "down") batch.Add(KeyInput(code.Value, down: true));
            if (command.Action is "tap" or "up") batch.Add(KeyInput(code.Value, down: false));
        }

        if (command.Action is "tap" or "up")
            foreach (ushort modifier in modifiers.Reverse()) batch.Add(KeyInput(modifier, down: false));

        // One call: a chord split across several SendInput calls can have a real
        // keystroke interleaved between the modifier and the key.
        SendMany(batch);
        return true;
    }

    public void Text(string text)
    {
        if (!Allowed() || string.IsNullOrEmpty(text)) return;

        var batch = new List<INPUT>(text.Length * 2);
        foreach (char character in text)
        {
            if (character == '\n')
            {
                batch.Add(KeyInput(VK_RETURN, down: true));
                batch.Add(KeyInput(VK_RETURN, down: false));
                continue;
            }
            if (character == '\r') continue;

            batch.Add(UnicodeInput(character, down: true));
            batch.Add(UnicodeInput(character, down: false));

            // SendInput takes a fixed array; chunking keeps a long paste from
            // building one enormous marshalled block.
            if (batch.Count >= 256) { SendMany(batch); batch.Clear(); }
        }
        SendMany(batch);
    }

    private static INPUT KeyInput(ushort virtualKey, bool down) => new()
    {
        type = INPUT_KEYBOARD,
        u = new INPUTUNION
        {
            keyboard = new KEYBDINPUT
            {
                wVk = virtualKey,
                wScan = (ushort)MapVirtualKey(virtualKey, MAPVK_VK_TO_VSC),
                dwFlags = (down ? 0u : KEYEVENTF_KEYUP) | (IsExtended(virtualKey) ? KEYEVENTF_EXTENDEDKEY : 0u),
            },
        },
    };

    private static INPUT UnicodeInput(char character, bool down) => new()
    {
        type = INPUT_KEYBOARD,
        u = new INPUTUNION
        {
            keyboard = new KEYBDINPUT
            {
                wVk = 0,
                wScan = character,
                dwFlags = KEYEVENTF_UNICODE | (down ? 0u : KEYEVENTF_KEYUP),
            },
        },
    };

    /// <summary>
    /// Arrows, navigation and the right-hand modifiers live on the extended
    /// scan-code page. Sending them without the flag types a numeric-keypad key
    /// instead, which is a bug that only shows up with Num Lock in one state.
    /// </summary>
    private static bool IsExtended(ushort virtualKey) => virtualKey is
        0x21 or 0x22 or 0x23 or 0x24 or 0x25 or 0x26 or 0x27 or 0x28 or
        0x2D or 0x2E or 0x5B or 0x5C or 0x5D or 0xA3 or 0xA5;

    private static readonly Dictionary<string, ushort> Names = new(StringComparer.OrdinalIgnoreCase)
    {
        ["ctrl"] = 0x11, ["control"] = 0x11, ["alt"] = 0x12, ["shift"] = 0x10,
        ["win"] = 0x5B, ["meta"] = 0x5B, ["cmd"] = 0x5B,
        ["enter"] = 0x0D, ["return"] = 0x0D, ["tab"] = 0x09, ["escape"] = 0x1B, ["esc"] = 0x1B,
        ["space"] = 0x20, ["backspace"] = 0x08, ["delete"] = 0x2E, ["insert"] = 0x2D,
        ["home"] = 0x24, ["end"] = 0x23, ["pageup"] = 0x21, ["pagedown"] = 0x22,
        ["left"] = 0x25, ["up"] = 0x26, ["right"] = 0x27, ["down"] = 0x28,
        ["capslock"] = 0x14, ["numlock"] = 0x90, ["printscreen"] = 0x2C, ["pause"] = 0x13,
        ["volumeup"] = 0xAF, ["volumedown"] = 0xAE, ["volumemute"] = 0xAD,
        ["medianext"] = 0xB0, ["mediaprev"] = 0xB1, ["mediastop"] = 0xB2, ["mediaplay"] = 0xB3,
        ["browserback"] = 0xA6, ["browserforward"] = 0xA7, ["browserrefresh"] = 0xA8,
        ["apps"] = 0x5D, ["menu"] = 0x5D,
        ["plus"] = 0xBB, ["minus"] = 0xBD, ["comma"] = 0xBC, ["period"] = 0xBE,
        ["semicolon"] = 0xBA, ["slash"] = 0xBF, ["backslash"] = 0xDC, ["quote"] = 0xDE,
        ["backtick"] = 0xC0, ["lbracket"] = 0xDB, ["rbracket"] = 0xDD,
    };

    private static ushort? Map(string name)
    {
        if (string.IsNullOrWhiteSpace(name)) return null;
        if (Names.TryGetValue(name, out ushort known)) return known;

        if (name.Length == 1)
        {
            char c = char.ToUpperInvariant(name[0]);
            if (c is >= 'A' and <= 'Z') return c;
            if (c is >= '0' and <= '9') return c;
        }

        if (name.Length >= 2 && (name[0] is 'f' or 'F') &&
            int.TryParse(name[1..], out int function) && function is >= 1 and <= 24)
            return (ushort)(0x70 + function - 1);

        if (name.StartsWith("num", StringComparison.OrdinalIgnoreCase) &&
            int.TryParse(name[3..], out int pad) && pad is >= 0 and <= 9)
            return (ushort)(0x60 + pad);

        return null;
    }

    /// <summary>
    /// SendInput absolute coordinates are 0..65535 across the virtual desktop,
    /// not pixels, and the mapping has to account for a left or top monitor at a
    /// negative origin — otherwise a second display to the left is unreachable.
    /// </summary>
    private static int ToAbsoluteX(int screenX)
    {
        var virtualScreen = Forms.SystemInformation.VirtualScreen;
        return (int)Math.Round((screenX - virtualScreen.Left) * 65535.0 / Math.Max(1, virtualScreen.Width - 1));
    }

    private static int ToAbsoluteY(int screenY)
    {
        var virtualScreen = Forms.SystemInformation.VirtualScreen;
        return (int)Math.Round((screenY - virtualScreen.Top) * 65535.0 / Math.Max(1, virtualScreen.Height - 1));
    }

    private void Send(INPUT input) => SendMany([input]);

    private void SendMany(IReadOnlyList<INPUT> batch)
    {
        if (batch.Count == 0) return;
        var array = batch.ToArray();
        uint sent = SendInput((uint)array.Length, array, Marshal.SizeOf<INPUT>());
        if (sent != array.Length)
            Log?.Invoke($"SendInput delivered {sent}/{array.Length} (error {Marshal.GetLastWin32Error()})");
    }

    // ---- interop ----------------------------------------------------------

    private const int INPUT_MOUSE = 0;
    private const int INPUT_KEYBOARD = 1;

    private const uint MOUSEEVENTF_MOVE = 0x0001;
    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
    private const uint MOUSEEVENTF_XDOWN = 0x0080;
    private const uint MOUSEEVENTF_XUP = 0x0100;
    private const uint MOUSEEVENTF_WHEEL = 0x0800;
    private const uint MOUSEEVENTF_HWHEEL = 0x1000;
    private const uint MOUSEEVENTF_ABSOLUTE = 0x8000;
    private const uint MOUSEEVENTF_VIRTUALDESK = 0x4000;
    private const uint XBUTTON1 = 0x0001;
    private const uint XBUTTON2 = 0x0002;

    private const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const uint KEYEVENTF_UNICODE = 0x0004;
    private const uint MAPVK_VK_TO_VSC = 0;
    private const ushort VK_RETURN = 0x0D;

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct INPUTUNION
    {
        [FieldOffset(0)] public MOUSEINPUT mouse;
        [FieldOffset(0)] public KEYBDINPUT keyboard;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public int type;
        public INPUTUNION u;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, INPUT[] inputs, int size);

    [DllImport("user32.dll")]
    private static extern uint MapVirtualKey(uint code, uint mapType);
}
