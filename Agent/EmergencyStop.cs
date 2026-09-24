using System.Runtime.InteropServices;

namespace RuntimeBroker;

/// <summary>
/// Global emergency-stop hotkey: Ctrl+Shift+X cancels any running input
/// (paragraph typing etc.). The Android "Stop All" button sends stop_all.
/// </summary>
internal static class EmergencyStop
{
    private static Thread? _thread;
    private static readonly int _hotkeyId = 0xB807;

    public static void Start()
    {
        if (_thread != null) return;
        _thread = new Thread(Loop) { IsBackground = true, Name = "EmergencyStop" };
        _thread.SetApartmentState(ApartmentState.STA);
        _thread.Start();
    }

    public static void Stop()
    {
        try { System.Windows.Forms.Application.ExitThread(); } catch { }
        _thread = null;
    }

    private static void Loop()
    {
        HotkeyWindow? wnd = null;
        try
        {
            // STA thread with a real message pump to receive WM_HOTKEY.
            wnd = new HotkeyWindow(_hotkeyId);
            RegisterHotKey(wnd.Handle, _hotkeyId, MOD_CONTROL | MOD_SHIFT, (uint)'X');
            System.Windows.Forms.Application.Run();
        }
        catch { }
        finally
        {
            try
            {
                if (wnd != null)
                {
                    UnregisterHotKey(wnd.Handle, _hotkeyId);
                    wnd.DestroyHandle();
                }
            }
            catch { }
        }
    }

    private sealed class HotkeyWindow : System.Windows.Forms.NativeWindow
    {
        private readonly int _id;
        public HotkeyWindow(int id)
        {
            _id = id;
            var cp = new System.Windows.Forms.CreateParams { Caption = "RBStop" };
            CreateHandle(cp);
        }
        protected override void WndProc(ref System.Windows.Forms.Message m)
        {
            const int WM_HOTKEY = 0x0312;
            if (m.Msg == WM_HOTKEY && m.WParam.ToInt32() == _id)
            {
                try { InteractiveActions.CancelAll(); } catch { }
            }
            base.WndProc(ref m);
        }
    }

    private const uint MOD_CONTROL = 0x0002;
    private const uint MOD_SHIFT = 0x0004;

    [DllImport("user32.dll")]
    private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

    [DllImport("user32.dll")]
    private static extern bool UnregisterHotKey(IntPtr hWnd, int id);
}
