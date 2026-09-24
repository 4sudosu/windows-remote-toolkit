using System.Runtime.InteropServices;

namespace RuntimeBroker;

/// <summary>
/// Mouse / keyboard input via SendInput. Paragraph typing is paced by WPM.
/// These methods must execute INSIDE the user's interactive session (via the
/// hidden --input-* scheduled-task children): SendInput from the Session-0
/// service never reaches the interactive desktop. stop_typing / stop_all end
/// the typing task; Ctrl+Shift+X cancels in-process typing.
/// </summary>
internal static class InteractiveActions
{
    private static CancellationTokenSource _typingCts = new();
    private static readonly object _typingLock = new();

    public static void CancelAll()
    {
        lock (_typingLock)
        {
            try { _typingCts.Cancel(); } catch { }
            _typingCts = new CancellationTokenSource();
        }
    }

    // ── mouse ────────────────────────────────────────────────────────────
    public static string Mouse(int x, int y, string action, int amount = 120)
    {
        try
        {
            var a = (action ?? "move").ToLowerInvariant();
            switch (a)
            {
                case "move":
                    SetCursorPos(x, y);
                    break;
                case "click":
                case "left":
                    SetCursorPos(x, y);
                    MouseEvent(MOUSEEVENTF_LEFTDOWN, 0, 0);
                    MouseEvent(MOUSEEVENTF_LEFTUP, 0, 0);
                    break;
                case "double":
                case "doubleclick":
                    SetCursorPos(x, y);
                    for (int i = 0; i < 2; i++)
                    {
                        MouseEvent(MOUSEEVENTF_LEFTDOWN, 0, 0);
                        MouseEvent(MOUSEEVENTF_LEFTUP, 0, 0);
                        Thread.Sleep(60);
                    }
                    break;
                case "right":
                    SetCursorPos(x, y);
                    MouseEvent(MOUSEEVENTF_RIGHTDOWN, 0, 0);
                    MouseEvent(MOUSEEVENTF_RIGHTUP, 0, 0);
                    break;
                case "middle":
                    SetCursorPos(x, y);
                    MouseEvent(MOUSEEVENTF_MIDDLEDOWN, 0, 0);
                    MouseEvent(MOUSEEVENTF_MIDDLEUP, 0, 0);
                    break;
                case "down":
                    SetCursorPos(x, y);
                    MouseEvent(MOUSEEVENTF_LEFTDOWN, 0, 0);
                    break;
                case "up":
                    SetCursorPos(x, y);
                    MouseEvent(MOUSEEVENTF_LEFTUP, 0, 0);
                    break;
                case "scroll":
                    SetCursorPos(x, y);
                    MouseEvent(MOUSEEVENTF_WHEEL, 0, 0, amount);
                    break;
                default:
                    return $"Unknown mouse action: {action}";
            }
            return "ok";
        }
        catch (Exception ex)
        {
            return "Mouse failed: " + ex.Message;
        }
    }

    // ── text (one shot) ──────────────────────────────────────────────────
    public static string TypeText(string text)
    {
        try
        {
            SendUnicode(text ?? "");
            return "ok";
        }
        catch (Exception ex)
        {
            return "Type failed: " + ex.Message;
        }
    }

    // ── paragraph (paced, cancellable) ───────────────────────────────────
    public static async Task<string> TypeParagraphAsync(string text, int wpm, bool addEnter)
    {
        CancellationToken token;
        lock (_typingLock)
        {
            try { _typingCts.Cancel(); } catch { }
            _typingCts = new CancellationTokenSource();
            token = _typingCts.Token;
        }
        try
        {
            int cps = Math.Clamp(wpm, 10, 200) * 5 / 60; // chars per second
            int delayMs = cps > 0 ? Math.Max(5, 1000 / cps) : 50;
            foreach (char ch in text ?? "")
            {
                token.ThrowIfCancellationRequested();
                if (ch == '\n') PressKey(VK_RETURN);
                else if (ch == '\t') PressKey(VK_TAB);
                else SendUnicode(ch.ToString());
                await Task.Delay(delayMs, token);
            }
            if (addEnter)
            {
                token.ThrowIfCancellationRequested();
                PressKey(VK_RETURN);
            }
            return "ok";
        }
        catch (OperationCanceledException)
        {
            return "cancelled";
        }
        catch (Exception ex)
        {
            return "Type failed: " + ex.Message;
        }
    }

    // ── low level ────────────────────────────────────────────────────────
    private static void MouseEvent(uint flags, int dx, int dy, int wheel = 0)
    {
        var input = new INPUT
        {
            type = INPUT_MOUSE,
            u = new InputUnion
            {
                mi = new MOUSEINPUT
                {
                    dwFlags = flags,
                    dx = dx,
                    dy = dy,
                    mouseData = (uint)wheel,
                    time = 0,
                    dwExtraInfo = IntPtr.Zero
                }
            }
        };
        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    private static void SendUnicode(string text)
    {
        var inputs = new List<INPUT>();
        foreach (char ch in text)
        {
            inputs.Add(new INPUT
            {
                type = INPUT_KEYBOARD,
                u = new InputUnion
                {
                    ki = new KEYBDINPUT { wVk = 0, wScan = (ushort)ch, dwFlags = KEYEVENTF_UNICODE, time = 0, dwExtraInfo = IntPtr.Zero }
                }
            });
            inputs.Add(new INPUT
            {
                type = INPUT_KEYBOARD,
                u = new InputUnion
                {
                    ki = new KEYBDINPUT { wVk = 0, wScan = (ushort)ch, dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP, time = 0, dwExtraInfo = IntPtr.Zero }
                }
            });
        }
        if (inputs.Count > 0)
            SendInput((uint)inputs.Count, inputs.ToArray(), Marshal.SizeOf<INPUT>());
    }

    private static void PressKey(ushort vk)
    {
        var down = new INPUT { type = INPUT_KEYBOARD, u = new InputUnion { ki = new KEYBDINPUT { wVk = vk } } };
        var up = new INPUT { type = INPUT_KEYBOARD, u = new InputUnion { ki = new KEYBDINPUT { wVk = vk, dwFlags = KEYEVENTF_KEYUP } } };
        SendInput(1, new[] { down }, Marshal.SizeOf<INPUT>());
        Thread.Sleep(20);
        SendInput(1, new[] { up }, Marshal.SizeOf<INPUT>());
    }

    private const int INPUT_MOUSE = 0;
    private const int INPUT_KEYBOARD = 1;
    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
    private const uint MOUSEEVENTF_WHEEL = 0x0800;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const uint KEYEVENTF_UNICODE = 0x0004;
    private const ushort VK_RETURN = 0x0D;
    private const ushort VK_TAB = 0x09;

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public int type;
        public InputUnion u;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

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

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll")]
    private static extern bool SetCursorPos(int X, int Y);
}
