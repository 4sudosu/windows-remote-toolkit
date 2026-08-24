using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using Windows.Media.Capture;
using Windows.Media.MediaProperties;
using Windows.Storage;

namespace RuntimeBroker;

/// <summary>
/// One-shot modes launched by the scheduled task in the interactive user
/// session (the LocalSystem service cannot reach the user's desktop, webcam
/// or mic directly). Each method writes a plain-text result to <c>outFile</c>
/// ("OK" or base64 data, or "ERR: message") that PowerShellRunner reads back.
/// </summary>
public static class InteractiveActions
{
    public static int SendText(string inFile, string outFile)
    {
        try
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(inFile));
            var text = doc.RootElement.TryGetProperty("text", out var t) && t.ValueKind == JsonValueKind.String
                ? t.GetString() ?? "" : "";
            //
// STOP ALL must only stop paragraph typing, not other automation.
// The check is intentionally removed from SendText; paragraph typing
// has its own stop check in SendParagraph (line 101).
//
            System.Windows.Forms.SendKeys.SendWait(EscapeForSendKeys(text));
            File.WriteAllText(outFile, "OK");
            return 0;
        }
        catch (Exception ex) { return WriteErr(outFile, ex); }
    }

    public static int SendMouse(string inFile, string outFile)
    {
        try
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(inFile));
            var root = doc.RootElement;
            var x = GetInt(root, "x");
            var y = GetInt(root, "y");
            var action = GetStr(root, "action", "move");
            var delta = GetInt(root, "delta");

            SetCursorPos(x, y);
            switch (action)
            {
                case "left":
                    mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, UIntPtr.Zero);
                    mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
                    break;
                case "right":
                    mouse_event(MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, UIntPtr.Zero);
                    mouse_event(MOUSEEVENTF_RIGHTUP, 0, 0, 0, UIntPtr.Zero);
                    break;
                case "middle":
                    mouse_event(MOUSEEVENTF_MIDDLEDOWN, 0, 0, 0, UIntPtr.Zero);
                    mouse_event(MOUSEEVENTF_MIDDLEUP, 0, 0, 0, UIntPtr.Zero);
                    break;
                case "down":
                    mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, UIntPtr.Zero);
                    break;
                case "up":
                    mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
                    break;
                case "wheel":
                    mouse_event(MOUSEEVENTF_WHEEL, 0, 0, (uint)delta, UIntPtr.Zero);
                    break;
            }
            File.WriteAllText(outFile, "OK");
            return 0;
        }
        catch (Exception ex) { return WriteErr(outFile, ex); }
    }

    /// <summary>
    /// Types a whole paragraph with human-like timing: average pacing derived
    /// from WPM (5 chars â‰ˆ 1 word), random per-character jitter, longer pauses
    /// after punctuation and at newlines.
    /// </summary>
    public static int SendParagraph(string inFile, string outFile)
    {
        try
        {
            using (var trace = TraceLog())
                trace.WriteLine($"{DateTime.Now:HH:mm:ss.fff} para start pid={Environment.ProcessId}");

            using var doc = JsonDocument.Parse(File.ReadAllText(inFile));
            var root = doc.RootElement;
            var text = GetStr(root, "text", "");
            var wpm = GetInt(root, "wpm");
            if (wpm <= 0) wpm = 50;
            var addEnter = root.TryGetProperty("addEnter", out var ae) && ae.ValueKind == JsonValueKind.True;
            if (string.IsNullOrEmpty(text)) { File.WriteAllText(outFile, "ERR: Empty text"); return 1; }

            var rng = new Random();
            var baseMs = (int)Math.Max(10, Math.Round(60000.0 / wpm / 5.0));
            var stopFile = inFile + ".stop";
            var typed = 0;
            foreach (var c in text)
            {
                if (File.Exists(stopFile) || EmergencyStop.IsStopRequested)
                {
                    try { File.Delete(stopFile); } catch { }
                    using (var trace = TraceLog())
                        trace.WriteLine($"{DateTime.Now:HH:mm:ss.fff} para STOPPED at char {typed}/{text.Length}");
                    File.WriteAllText(outFile, "STOPPED");
                    return 0;
                }
                if (c == '\r') continue;
                double mult;
                if (",.!?;:â€¦".IndexOf(c) >= 0) mult = 3.0 + rng.NextDouble() * 2.5;
                else if (c == '\n') mult = 4.0 + rng.NextDouble() * 2.0;
                else if (char.IsWhiteSpace(c)) mult = 0.9 + rng.NextDouble() * 0.6;
                else mult = 0.55 + rng.NextDouble() * 0.9;
                var delay = (int)(baseMs * mult);
                SendKeys.SendWait(EscapeForSendKeys(c.ToString()));
                Thread.Sleep(delay);
            }
            if (addEnter) SendKeys.SendWait("{ENTER}");
            using (var trace = TraceLog())
                trace.WriteLine($"{DateTime.Now:HH:mm:ss.fff} para finished ({text.Length} chars)");
            File.WriteAllText(outFile, "OK");
            return 0;
        }
        catch (Exception ex) { return WriteErr(outFile, ex); }
    }

    /// <summary>
    /// Rotates the physical display to the requested orientation using
    /// ChangeDisplaySettingsEx. Swaps width/height for 90/270.
    /// </summary>
    public static int RotateScreen(string inFile, string outFile)
    {
        try
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(inFile));
            var degrees = GetInt(doc.RootElement, "degrees");
            var ok = TryRotate(degrees);
            if (!ok) { File.WriteAllText(outFile, "ERR: Screen rotation failed (driver may not support it)"); return 1; }
            File.WriteAllText(outFile, "OK");
            return 0;
        }
        catch (Exception ex) { return WriteErr(outFile, ex); }
    }

    private static bool TryRotate(int degrees)
    {
        var dm = new DEVMODE();
        dm.dmSize = (short)Marshal.SizeOf<DEVMODE>();
        if (!EnumDisplaySettings(null, ENUM_CURRENT_SETTINGS, ref dm)) return false;

        var norm = ((degrees % 360) + 360) % 360;
        var orient = norm switch
        {
            90 => DMDO_90,
            180 => DMDO_180,
            270 => DMDO_270,
            _ => DMDO_DEFAULT
        };
        dm.dmDisplayOrientation = orient;
        if (orient == DMDO_90 || orient == DMDO_270)
        {
            (dm.dmPelsWidth, dm.dmPelsHeight) = (dm.dmPelsHeight, dm.dmPelsWidth);
        }
        dm.dmFields = DM_DISPLAYORIENTATION | DM_PELSWIDTH | DM_PELSHEIGHT;
        var result = ChangeDisplaySettingsEx(null, ref dm, IntPtr.Zero, CDS_UPDATEREGISTRY, IntPtr.Zero);
        return result == DISP_CHANGE_SUCCESSFUL;
    }

    public static int CapturePhoto(string outFile)
    {
        try
        {
            var b64 = CapturePhotoAsync().GetAwaiter().GetResult();
            File.WriteAllText(outFile, b64);
            return 0;
        }
        catch (Exception ex) { return WriteErr(outFile, ex); }
    }

    public static int RecordVideo(string inFile, string outFile)
    {
        try
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(inFile));
            var seconds = GetInt(doc.RootElement, "seconds");
            if (seconds <= 0) seconds = 10;
            var b64 = RecordVideoAsync(seconds).GetAwaiter().GetResult();
            File.WriteAllText(outFile, b64);
            return 0;
        }
        catch (Exception ex) { return WriteErr(outFile, ex); }
    }

    public static int RecordAudio(string inFile, string outFile)
    {
        try
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(inFile));
            var seconds = GetInt(doc.RootElement, "seconds");
            if (seconds <= 0) seconds = 10;
            var b64 = RecordAudioAsync(seconds).GetAwaiter().GetResult();
            File.WriteAllText(outFile, b64);
            return 0;
        }
        catch (Exception ex) { return WriteErr(outFile, ex); }
    }

    private static async Task<string> CapturePhotoAsync()
    {
        using var cap = CreateInitializedCapture(StreamingCaptureMode.Video, PhotoCaptureSource.VideoPreview);
        var file = await CreateTempFile("wsm-camera.jpg");
        try
        {
            await WinRt(cap.CapturePhotoToStorageFileAsync(ImageEncodingProperties.CreateJpeg(), file), 30);
            return Convert.ToBase64String(File.ReadAllBytes(file.Path));
        }
        finally { try { await WinRt(file.DeleteAsync(), 5); } catch { } }
    }

    internal static StreamWriter TraceLog()
    {
        var path = Path.Combine(PowerShellRunner.CaptureWorkDir(), "trace.log");
        return new StreamWriter(path, append: true) { AutoFlush = true };
    }

    private static async Task<string> RecordVideoAsync(int seconds)
    {
        using var trace = TraceLog();
        trace.WriteLine($"{DateTime.Now:HH:mm:ss.fff} video start pid={Environment.ProcessId}");
        using var cap = CreateInitializedCapture(StreamingCaptureMode.AudioAndVideo, PhotoCaptureSource.VideoPreview);
        trace.WriteLine($"{DateTime.Now:HH:mm:ss.fff} init done");
        var file = await CreateTempFile("wsm-video.mp4");
        trace.WriteLine($"{DateTime.Now:HH:mm:ss.fff} tempfile {file.Path}");
        try
        {
            await WinRt(cap.StartRecordToStorageFileAsync(MediaEncodingProfile.CreateMp4(VideoEncodingQuality.HD720p), file), 20);
            trace.WriteLine($"{DateTime.Now:HH:mm:ss.fff} recording started");
            await Task.Delay(Math.Max(1, Math.Min(seconds, 120)) * 1000);
            await WinRt(cap.StopRecordAsync(), 20);
            trace.WriteLine($"{DateTime.Now:HH:mm:ss.fff} stopped");
            var b64 = Convert.ToBase64String(File.ReadAllBytes(file.Path));
            trace.WriteLine($"{DateTime.Now:HH:mm:ss.fff} encoded {b64.Length}");
            return b64;
        }
        finally { try { await WinRt(file.DeleteAsync(), 5); } catch { } }
    }

    internal static void ReleaseInput()
    {
        try
        {
            mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
            mouse_event(MOUSEEVENTF_RIGHTUP, 0, 0, 0, UIntPtr.Zero);
            mouse_event(MOUSEEVENTF_MIDDLEUP, 0, 0, 0, UIntPtr.Zero);
        }
        catch { }
    }

    public static int RunHotkeyListener()
    {
        using var hook = new KeyboardHook(() =>
        {
            // Observe ESC globally without suppressing it, while the service
            // stop command also releases any held mouse buttons.
            if (EmergencyStop.IsControlActive) EmergencyStop.Request();
        });
        hook.Run();
        return 0;
    }

    private sealed class KeyboardHook : IDisposable
    {
        private readonly Action _onEscape;
        private readonly NativeMethods.LowLevelKeyboardProc _callback;
        private IntPtr _hook;

        public KeyboardHook(Action onEscape)
        {
            _onEscape = onEscape;
            _callback = Callback;
        }

        public void Run()
        {
            _hook = NativeMethods.SetWindowsHookEx(13, _callback, NativeMethods.GetModuleHandle(null), 0);
            if (_hook == IntPtr.Zero) throw new InvalidOperationException("Could not install global ESC hook");
            NativeMethods.GetMessage(out _, IntPtr.Zero, 0, 0);
        }

        private IntPtr Callback(int code, IntPtr wParam, IntPtr lParam)
        {
            if (code >= 0 && wParam == (IntPtr)0x0100 && Marshal.ReadInt32(lParam) == 0x1B)
                _onEscape();
            return NativeMethods.CallNextHookEx(_hook, code, wParam, lParam);
        }

        public void Dispose()
        {
            if (_hook != IntPtr.Zero) NativeMethods.UnhookWindowsHookEx(_hook);
        }
    }

    private static class NativeMethods
    {
        internal delegate IntPtr LowLevelKeyboardProc(int code, IntPtr wParam, IntPtr lParam);
        [DllImport("user32.dll")] internal static extern IntPtr SetWindowsHookEx(int id, LowLevelKeyboardProc callback, IntPtr module, uint threadId);
        [DllImport("user32.dll")] internal static extern bool UnhookWindowsHookEx(IntPtr hook);
        [DllImport("user32.dll")] internal static extern IntPtr CallNextHookEx(IntPtr hook, int code, IntPtr wParam, IntPtr lParam);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] internal static extern IntPtr GetModuleHandle(string? name);
        [DllImport("user32.dll")] internal static extern sbyte GetMessage(out Message message, IntPtr window, uint min, uint max);
        internal struct Message { public IntPtr HWnd, MessageId, WParam, LParam; public uint Time; public int X, Y; }
    }

    private static async Task<string> RecordAudioAsync(int seconds)
    {
        using var cap = CreateInitializedCapture(StreamingCaptureMode.Audio, PhotoCaptureSource.VideoPreview);
        var file = await CreateTempFile("wsm-mic.m4a");
        try
        {
            await WinRt(cap.StartRecordToStorageFileAsync(MediaEncodingProfile.CreateM4a(AudioEncodingQuality.High), file), 20);
            await Task.Delay(Math.Max(1, Math.Min(seconds, 300)) * 1000);
            await WinRt(cap.StopRecordAsync(), 20);
            return Convert.ToBase64String(File.ReadAllBytes(file.Path));
        }
        finally { try { await WinRt(file.DeleteAsync(), 5); } catch { } }
    }

    /// <summary>
    /// Bounds ANY WinRT IAsyncOperation/Action so a deadlocked call becomes a
    /// fast error instead of a permanently hung capture process.
    /// </summary>
    private static async Task<T> WinRt<T>(Windows.Foundation.IAsyncOperation<T> op, int timeoutSec)
    {
        using var cancel = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSec));
        var task = op.AsTask(cancel.Token);
        var finished = await Task.WhenAny(task, Task.Delay(Timeout.InfiniteTimeSpan, cancel.Token));
        if (finished != task) throw new TimeoutException($"WinRT operation timed out after {timeoutSec}s");
        return await task;
    }

    private static async Task WinRt(Windows.Foundation.IAsyncAction action, int timeoutSec)
    {
        using var cancel = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSec));
        var task = action.AsTask(cancel.Token);
        var finished = await Task.WhenAny(task, Task.Delay(Timeout.InfiniteTimeSpan, cancel.Token));
        if (finished != task) throw new TimeoutException($"WinRT operation timed out after {timeoutSec}s");
        await task;
    }

    /// <summary>
    /// Initializes MediaCapture with a retry. Some machines fail the first init
    /// with "no more endpoints available from the endpoint mapper" because the
    /// camera frame-server mode is off; toggling EnableFrameServerMode in HKCU
    /// and retrying fixes it without a reboot.
    /// </summary>
    private static MediaCapture CreateInitializedCapture(StreamingCaptureMode mode, PhotoCaptureSource photoSource)
    {
        Exception? last = null;
        for (int attempt = 0; attempt < 2; attempt++)
        {
            if (attempt == 1) ToggleFrameServerMode();
            var cap = new MediaCapture();
            try
            {
                // InitializeAsync occasionally deadlocks â€” bound it so the
                // one-shot process can never hang around forever.
                var initTask = cap.InitializeAsync(new MediaCaptureInitializationSettings
                {
                    StreamingCaptureMode = mode,
                    PhotoCaptureSource = photoSource
                }).AsTask();
                var finished = Task.WhenAny(initTask, Task.Delay(TimeSpan.FromSeconds(15))).GetAwaiter().GetResult();
                if (finished != initTask)
                    throw new TimeoutException("Camera init timed out after 15s");
                initTask.GetAwaiter().GetResult();
                return cap;
            }
            catch (Exception ex)
            {
                last = ex;
                try { cap.Dispose(); } catch { }
            }
        }
        throw new InvalidOperationException($"Camera init failed: {last?.Message}");
    }

    private static void ToggleFrameServerMode()
    {
        try
        {
            using var key = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(
                @"Software\Microsoft\Windows Media Foundation\Platform");
            var cur = key.GetValue("EnableFrameServerMode");
            key.SetValue("EnableFrameServerMode", cur is int i && i == 1 ? 0 : 1, Microsoft.Win32.RegistryValueKind.DWord);
        }
        catch { }
        // Make sure "Let desktop apps access" is enabled for camera + mic,
        // otherwise MediaCapture init fails regardless of frame-server mode.
        foreach (var cap in new[] { "webcam", "microphone" })
        {
            try
            {
                using var k = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(
                    $@"Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\nonPackaged\{cap}");
                if (k.GetValue("Value")?.ToString() == "Deny")
                    k.SetValue("Value", "Allow");
            }
            catch { }
        }
    }

    private static async Task<StorageFile> CreateTempFile(string name)
    {
        // Unique name per call â€” a fixed name collides with any overlapping or
        // orphaned recording still holding the file open ("file in use").
        var unique = $"{Path.GetFileNameWithoutExtension(name)}-{Guid.NewGuid().ToString("N")[..8]}{Path.GetExtension(name)}";
        var folder = await WinRt(StorageFolder.GetFolderFromPathAsync(PowerShellRunner.CaptureWorkDir()), 15);
        return await WinRt(folder.CreateFileAsync(unique, CreationCollisionOption.ReplaceExisting), 10);
    }

    /// <summary>
    /// Plays an audio file (mp3/wav/wma/m4a) in the interactive session via the
    /// classic Windows MCI (winmm) API â€” no WinRT event pump needed, so it
    /// cannot hang. Blocks until playback ends, a stop-audio flag is seen, or
    /// 2h elapse.
    /// </summary>
    public static int PlayAudio(string inFile, string outFile)
    {
        string? alias = null;
        try
        {
            string path;
            using (var doc = JsonDocument.Parse(File.ReadAllText(inFile)))
            {
                path = doc.RootElement.TryGetProperty("play", out var p) && p.ValueKind == JsonValueKind.String
                    ? p.GetString() ?? "" : "";
            }
            if (string.IsNullOrEmpty(path) || !File.Exists(path))
            {
                File.WriteAllText(outFile, "ERR: Audio file missing");
                return 1;
            }

            var stopFlag = Path.Combine(PowerShellRunner.CaptureWorkDir(), "stop-audio.flag");
            try { File.Delete(stopFlag); } catch { }

            alias = "rbaudio" + Guid.NewGuid().ToString("N")[..6];
            var ext = Path.GetExtension(path).ToLowerInvariant();
            var type = ext == ".wav" ? "waveaudio" : "mpegvideo";
            Mci($"open \"{path}\" type {type} alias {alias}");
            Mci($"play {alias}");

            var sw = System.Diagnostics.Stopwatch.StartNew();
            while (sw.Elapsed < TimeSpan.FromHours(2))
            {
                if (File.Exists(stopFlag)) { try { File.Delete(stopFlag); } catch { } break; }
                var mode = Mci($"status {alias} mode", 260);
                if (mode.Contains("stopped", StringComparison.OrdinalIgnoreCase) ||
                    mode.Contains("not ready", StringComparison.OrdinalIgnoreCase) ||
                    mode.Length == 0) break;
                Thread.Sleep(200);
            }
            File.WriteAllText(outFile, "OK");
            return 0;
        }
        catch (Exception ex) { return WriteErr(outFile, ex); }
        finally
        {
            if (alias != null)
            {
                try { Mci($"stop {alias}"); } catch { }
                try { Mci($"close {alias}"); } catch { }
            }
        }
    }

    private static string Mci(string command, int bufferLen = 0)
    {
        var sb = bufferLen > 0 ? new StringBuilder(bufferLen) : null;
        mciSendString(command, sb, sb?.Capacity ?? 0, IntPtr.Zero);
        return sb?.ToString() ?? "";
    }

    [DllImport("winmm.dll", CharSet = CharSet.Auto)]
    private static extern int mciSendString(string command, StringBuilder? retBuffer, int retLen, IntPtr hwndCallback);

    private static string EscapeForSendKeys(string s)
    {
        var sb = new StringBuilder(s.Length + 8);
        foreach (var c in s)
        {
            switch (c)
            {
                case '\n': sb.Append("{ENTER}"); break;
                case '\r': break;
                case '+': sb.Append("{+}"); break;
                case '^': sb.Append("{^}"); break;
                case '%': sb.Append("{%}"); break;
                case '~': sb.Append("{~}"); break;
                case '(': sb.Append("{(}"); break;
                case ')': sb.Append("{)}"); break;
                case '{': sb.Append("{{}"); break;
                case '}': sb.Append("{}}"); break;
                default: sb.Append(c); break;
            }
        }
        return sb.ToString();
    }

    private static int GetInt(JsonElement root, string name)
        => root.TryGetProperty(name, out var p) && p.ValueKind == JsonValueKind.Number ? p.GetInt32() : 0;

    private static string GetStr(JsonElement root, string name, string def)
        => root.TryGetProperty(name, out var p) && p.ValueKind == JsonValueKind.String ? p.GetString() ?? def : def;

    private static int WriteErr(string outFile, Exception ex)
    {
        try { File.WriteAllText(outFile, "ERR: " + ex.Message); } catch { }
        return 1;
    }

    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
    private const uint MOUSEEVENTF_WHEEL = 0x0800;

    [DllImport("user32.dll")]
    private static extern bool SetCursorPos(int x, int y);

    [DllImport("user32.dll")]
    private static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);

    // â”€â”€ screen rotation (DEVMODE / ChangeDisplaySettingsEx) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private const int ENUM_CURRENT_SETTINGS = -1;
    private const int CDS_UPDATEREGISTRY = 0x00000001;
    private const int DISP_CHANGE_SUCCESSFUL = 0;
    private const int DM_DISPLAYORIENTATION = 0x00000080;
    private const int DM_PELSWIDTH = 0x00080000;
    private const int DM_PELSHEIGHT = 0x00100000;
    private const int DMDO_DEFAULT = 0;
    private const int DMDO_90 = 1;
    private const int DMDO_180 = 2;
    private const int DMDO_270 = 3;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Ansi)]
    private struct DEVMODE
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
        public short dmSpecVersion;
        public short dmDriverVersion;
        public short dmSize;
        public short dmDriverExtra;
        public int dmFields;
        public int dmPositionX;
        public int dmPositionY;
        public int dmDisplayOrientation;
        public int dmDisplayFixedOutput;
        public short dmColor;
        public short dmDuplex;
        public short dmYResolution;
        public short dmTTOption;
        public short dmCollate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
        public short dmLogPixels;
        public int dmBitsPerPel;
        public int dmPelsWidth;
        public int dmPelsHeight;
        public int dmDisplayFlags;
        public int dmDisplayFrequency;
        public int dmICMMethod;
        public int dmICMIntent;
        public int dmMediaType;
        public int dmDitherType;
        public int dmReserved1;
        public int dmReserved2;
        public int dmPanningWidth;
        public int dmPanningHeight;
    }

    [DllImport("user32.dll", CharSet = CharSet.Ansi)]
    private static extern bool EnumDisplaySettings(string? lpszDeviceName, int iModeNum, ref DEVMODE lpDevMode);

    [DllImport("user32.dll", CharSet = CharSet.Ansi)]
    private static extern int ChangeDisplaySettingsEx(string? lpszDeviceName, ref DEVMODE lpDevMode, IntPtr hwnd, int dwflags, IntPtr lParam);
}
