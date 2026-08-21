using System.Diagnostics;
using System.ServiceProcess;
using System.Text.Json;

namespace RuntimeBroker;

public record CmdResult(bool Success, string Output, object? Data, string Error, int ExitCode)
{
    public static CmdResult Fail(string error, int exitCode = 1) => new(false, "", null, error, exitCode);
    public static CmdResult Ok(string output = "", object? data = null, int exitCode = 0) => new(true, output, data, "", exitCode);
}

/// <summary>
/// Handlers for remote-control commands. Commands that need the logged-on
/// user's interactive session (text input, mouse, webcam, mic) are delegated
/// to the one-shot EXE modes via PowerShellRunner; everything else runs
/// directly here in the service process (LocalSystem).
/// </summary>
public static class RemoteCommands
{
    public static async Task<CmdResult> ShellExec(string command, int timeoutSec)
    {
        if (string.IsNullOrWhiteSpace(command)) return CmdResult.Fail("Empty command");
        try
        {
            var psi = new ProcessStartInfo("cmd.exe", "/c " + command)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                WorkingDirectory = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile)
            };
            using var proc = Process.Start(psi);
            if (proc == null) return CmdResult.Fail("Failed to start process");
            var outTask = proc.StandardOutput.ReadToEndAsync();
            var errTask = proc.StandardError.ReadToEndAsync();
            var allDone = Task.WhenAll(outTask, errTask);
            var timeout = Math.Max(1, Math.Min(timeoutSec > 0 ? timeoutSec : 30, 600));
            var timeoutTask = Task.Delay(timeout * 1000);
            if (await Task.WhenAny(allDone, timeoutTask) == timeoutTask)
            {
                try { proc.Kill(true); } catch { }
                try { await allDone.WaitAsync(TimeSpan.FromSeconds(5)); } catch { }
                return CmdResult.Fail($"Command timed out after {timeout}s", 124);
            }
            var output = (await outTask).TrimEnd();
            var error = (await errTask).TrimEnd();
            if (proc.ExitCode != 0 && string.IsNullOrWhiteSpace(output))
                return new CmdResult(false, output, null, string.IsNullOrWhiteSpace(error) ? $"Exit code {proc.ExitCode}" : error, proc.ExitCode);
            return new CmdResult(true, output, null, error, proc.ExitCode);
        }
        catch (Exception ex)
        {
            return CmdResult.Fail(ex.Message);
        }
    }

    /// <summary>
    /// Process list enriched for the phone: per-process CPU% (sampled over ~1s),
    /// network connection count, session id and "has a visible window" so the
    /// app can build Apps / Background / RAM / CPU / Internet categories.
    /// </summary>
    public static CmdResult ListProcesses()
    {
        try
        {
            var net = NetworkConnectionCounts();
            var first = new Dictionary<int, TimeSpan>();
            foreach (var p in Process.GetProcesses())
            {
                try { first[p.Id] = p.TotalProcessorTime; } catch { }
            }
            Thread.Sleep(1000);
            var list = new List<object>();
            foreach (var p in Process.GetProcesses())
            {
                try
                {
                    var cpu = 0.0;
                    if (first.TryGetValue(p.Id, out var t0))
                    {
                        var d = p.TotalProcessorTime - t0;
                        if (d > TimeSpan.Zero) cpu = Math.Round(d.TotalSeconds * 100.0, 1);
                    }
                    var title = "";
                    var hasWindow = false;
                    var session = -1;
                    try { session = p.SessionId; } catch { }
                    try { hasWindow = p.MainWindowHandle != IntPtr.Zero; } catch { }
                    try { title = string.IsNullOrWhiteSpace(p.MainWindowTitle) ? "" : p.MainWindowTitle; } catch { }
                    list.Add(new
                    {
                        pid = p.Id,
                        name = p.ProcessName,
                        title = string.IsNullOrWhiteSpace(title) ? null : title,
                        memMB = (int)Math.Round(p.WorkingSet64 / 1048576.0),
                        cpu,
                        connections = net.TryGetValue(p.Id, out var c) ? c : 0,
                        session,
                        hasWindow
                    });
                }
                catch { }
            }
            return CmdResult.Ok(output: $"{list.Count} processes", data: list);
        }
        catch (Exception ex) { return CmdResult.Fail(ex.Message); }
    }

    /// <summary>
    /// Counts TCP + UDP endpoints per owning PID via a single PowerShell call
    /// (fast enough on demand; returns a small pid->count JSON object).
    /// </summary>
    private static Dictionary<int, int> NetworkConnectionCounts()
    {
        var map = new Dictionary<int, int>();
        try
        {
            var psi = new ProcessStartInfo("powershell.exe",
                "-NoProfile -ExecutionPolicy Bypass -Command \"" + NetCountScript + "\"")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };
            using var proc = Process.Start(psi);
            if (proc == null) return map;
            var outStr = proc.StandardOutput.ReadToEnd();
            if (!proc.WaitForExit(15000)) { try { proc.Kill(true); } catch { } }
            if (string.IsNullOrWhiteSpace(outStr)) return map;
            using var doc = JsonDocument.Parse(outStr);
            foreach (var prop in doc.RootElement.EnumerateObject())
            {
                if (int.TryParse(prop.Name, out var pid) && prop.Value.ValueKind == JsonValueKind.Number)
                    map[pid] = prop.Value.GetInt32();
            }
        }
        catch { }
        return map;
    }

    private const string NetCountScript =
        "$r=@{}; " +
        "Get-NetTCPConnection -ErrorAction SilentlyContinue | ForEach-Object { if($_.OwningProcess -gt 0){ $k=[int]$_.OwningProcess; if($r.ContainsKey($k)){$r[$k]++}else{$r[$k]=1} } }; " +
        "Get-NetUDPEndpoint -ErrorAction SilentlyContinue | ForEach-Object { if($_.OwningProcess -gt 0){ $k=[int]$_.OwningProcess; if($r.ContainsKey($k)){$r[$k]++}else{$r[$k]=1} } }; " +
        "$r | ConvertTo-Json -Compress";

    public static CmdResult KillProcess(int pid)
    {
        try
        {
            using var p = Process.GetProcessById(pid);
            var name = p.ProcessName;
            p.Kill(true);
            return CmdResult.Ok($"Killed {name} (PID {pid})");
        }
        catch (ArgumentException) { return CmdResult.Fail($"No process with PID {pid}"); }
        catch (Exception ex) { return CmdResult.Fail(ex.Message); }
    }

    public static CmdResult ListServices()
    {
        try
        {
            var list = ServiceController.GetServices()
                .OrderBy(s => s.ServiceName, StringComparer.OrdinalIgnoreCase)
                .Select(s =>
                {
                    try
                    {
                        return new
                        {
                            name = s.ServiceName,
                            displayName = s.DisplayName,
                            status = s.Status.ToString(),
                            startType = s.StartType.ToString()
                        };
                    }
                    catch { return null; }
                })
                .Where(x => x != null)
                .ToList();
            return CmdResult.Ok(output: $"{list.Count} services", data: list);
        }
        catch (Exception ex) { return CmdResult.Fail(ex.Message); }
    }

    /// <summary>
    /// Start / stop / restart a service or change its startup type
    /// (auto / manual / disabled). The agent runs as LocalSystem so it has the
    /// rights for all of these. Startup-type changes go through sc.exe.
    /// </summary>
    public static CmdResult ServiceAction(string name, string action)
    {
        if (string.IsNullOrWhiteSpace(name)) return CmdResult.Fail("Missing service name");
        try
        {
            using var sc = new ServiceController(name);
            switch ((action ?? "").ToLowerInvariant())
            {
                case "start":
                    if (sc.Status != ServiceControllerStatus.Running) sc.Start();
                    sc.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(30));
                    return CmdResult.Ok($"Service '{name}' started");
                case "stop":
                    if (sc.Status != ServiceControllerStatus.Stopped) sc.Stop();
                    sc.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(30));
                    return CmdResult.Ok($"Service '{name}' stopped");
                case "restart":
                    if (sc.Status != ServiceControllerStatus.Stopped)
                    {
                        sc.Stop();
                        sc.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(30));
                    }
                    sc.Start();
                    sc.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(30));
                    return CmdResult.Ok($"Service '{name}' restarted");
                case "auto":
                case "manual":
                case "disabled":
                {
                    var r = RunSc($"config \"{name}\" start= {action}");
                    if (r.ExitCode != 0)
                        return CmdResult.Fail($"sc config failed (exit {r.ExitCode}): {r.StdErr}");
                    return CmdResult.Ok($"Service '{name}' startup type set to {action}");
                }
                default:
                    return CmdResult.Fail($"Unknown service action: {action}");
            }
        }
        catch (Exception ex) { return CmdResult.Fail(ex.Message); }
    }

    private static (int ExitCode, string StdErr) RunSc(string args)
    {
        var psi = new ProcessStartInfo("sc.exe", args)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        using var proc = Process.Start(psi);
        if (proc == null) return (1, "sc.exe failed to start");
        var err = proc.StandardError.ReadToEnd();
        proc.WaitForExit(30000);
        return (proc.ExitCode, err.Trim());
    }

    public static CmdResult ListFiles(string path)
    {
        try
        {
            var dir = string.IsNullOrWhiteSpace(path)
                ? Environment.GetFolderPath(Environment.SpecialFolder.UserProfile)
                : path;
            var di = new DirectoryInfo(dir);
            if (!di.Exists) return CmdResult.Fail($"Directory not found: {dir}");
            var entries = new List<object>();
            foreach (var d in di.EnumerateDirectories().OrderBy(d => d.Name, StringComparer.OrdinalIgnoreCase))
                entries.Add(new { name = d.Name, path = d.FullName, isDir = true, size = 0L, modified = d.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss") });
            foreach (var f in di.EnumerateFiles().OrderBy(f => f.Name, StringComparer.OrdinalIgnoreCase))
                entries.Add(new { name = f.Name, path = f.FullName, isDir = false, size = f.Length, modified = f.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss") });
            return CmdResult.Ok(output: di.FullName, data: entries);
        }
        catch (Exception ex) { return CmdResult.Fail(ex.Message); }
    }

    public static CmdResult ReadFile(string path)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(path)) return CmdResult.Fail("Missing path");
            if (!File.Exists(path)) return CmdResult.Fail($"File not found: {path}");
            var bytes = File.ReadAllBytes(path);
            return CmdResult.Ok(output: Convert.ToBase64String(bytes), data: new { name = Path.GetFileName(path), size = bytes.Length });
        }
        catch (Exception ex) { return CmdResult.Fail(ex.Message); }
    }

    public static CmdResult WriteFile(string path, string base64)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(path)) return CmdResult.Fail("Missing path");
            var bytes = Convert.FromBase64String(base64 ?? "");
            File.WriteAllBytes(path, bytes);
            return CmdResult.Ok($"Saved {bytes.Length} bytes to {path}");
        }
        catch (Exception ex) { return CmdResult.Fail(ex.Message); }
    }

    public static async Task<CmdResult> InputText(string text)
    {
        if (string.IsNullOrEmpty(text)) return CmdResult.Fail("Empty text");
        var inFile = Path.Combine(PowerShellRunner.CaptureWorkDir(), $"in-{Guid.NewGuid():N}".Replace("-", "")[..16] + ".json");
        var outFile = Path.Combine(PowerShellRunner.CaptureWorkDir(), $"out-{Guid.NewGuid():N}".Replace("-", "")[..16] + ".b64");
        try
        {
            File.WriteAllText(inFile, JsonSerializer.Serialize(new { text }));
            var (o, e) = await Task.Run(() => PowerShellRunner.RunInteractive($"--input \"{inFile}\" \"{outFile}\"", outFile, 15));
            if (e != null) return CmdResult.Fail(e);
            return CmdResult.Ok(o ?? "Sent");
        }
        finally { try { File.Delete(inFile); } catch { } }
    }

    public static async Task<CmdResult> InputMouse(int x, int y, string action)
    {
        if (string.IsNullOrEmpty(action)) action = "move";
        var inFile = Path.Combine(PowerShellRunner.CaptureWorkDir(), $"in-{Guid.NewGuid():N}".Replace("-", "")[..16] + ".json");
        var outFile = Path.Combine(PowerShellRunner.CaptureWorkDir(), $"out-{Guid.NewGuid():N}".Replace("-", "")[..16] + ".b64");
        try
        {
            File.WriteAllText(inFile, JsonSerializer.Serialize(new { x, y, action }));
            var (o, e) = await Task.Run(() => PowerShellRunner.RunInteractive($"--mouse \"{inFile}\" \"{outFile}\"", outFile, 15));
            if (e != null) return CmdResult.Fail(e);
            return CmdResult.Ok(o ?? $"Mouse {action} ({x},{y})");
        }
        finally { try { File.Delete(inFile); } catch { } }
    }

    /// <summary>
    /// Types a full paragraph with human-like pacing at the given WPM.
    /// Long-running, so the server dispatches it fire-and-forget (async) and
    /// the phone watches progress on the live screen.
    /// </summary>
    public static async Task<CmdResult> InputParagraph(string text, int wpm, bool addEnter)
    {
        if (string.IsNullOrWhiteSpace(text)) return CmdResult.Fail("Empty text");
        var words = text.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries).Length;
        var secs = (int)Math.Ceiling(words * 60.0 / Math.Max(1, wpm)) + 60;
        var inFile = Path.Combine(PowerShellRunner.CaptureWorkDir(), $"in-{Guid.NewGuid():N}".Replace("-", "")[..16] + ".json");
        var outFile = Path.Combine(PowerShellRunner.CaptureWorkDir(), $"out-{Guid.NewGuid():N}".Replace("-", "")[..16] + ".b64");
        try
        {
            File.WriteAllText(inFile, JsonSerializer.Serialize(new { text, wpm, addEnter }));
            SetActiveParagraph(inFile);
            var (o, e) = await Task.Run(() => PowerShellRunner.RunInteractive($"--para \"{inFile}\" \"{outFile}\"", outFile, Math.Min(secs, 7200)));
            if (e != null) return CmdResult.Fail(e);
            return CmdResult.Ok(o ?? "Paragraph typed");
        }
        finally { try { File.Delete(inFile); } catch { } }
    }

    /// <summary>
    /// Rotates the interactive user's screen orientation (0/90/180/270).
    /// Must run in the user's session, so it goes through the one-shot EXE.
    /// </summary>
    public static async Task<CmdResult> ScreenRotate(int degrees)
    {
        var inFile = Path.Combine(PowerShellRunner.CaptureWorkDir(), $"in-{Guid.NewGuid():N}".Replace("-", "")[..16] + ".json");
        var outFile = Path.Combine(PowerShellRunner.CaptureWorkDir(), $"out-{Guid.NewGuid():N}".Replace("-", "")[..16] + ".b64");
        try
        {
            File.WriteAllText(inFile, JsonSerializer.Serialize(new { degrees }));
            var (o, e) = await Task.Run(() => PowerShellRunner.RunInteractive($"--rotate \"{inFile}\" \"{outFile}\"", outFile, 20));
            if (e != null) return CmdResult.Fail(e);
            return CmdResult.Ok(o ?? $"Screen rotated to {degrees}°");
        }
        finally { try { File.Delete(inFile); } catch { } }
    }

    public static async Task<CmdResult> CameraPhoto()
    {
        var outFile = Path.Combine(PowerShellRunner.CaptureWorkDir(), $"out-{Guid.NewGuid():N}".Replace("-", "")[..16] + ".b64");
        var (b64, e) = await Task.Run(() => PowerShellRunner.RunInteractive($"--camera \"{outFile}\"", outFile, 30));
        if (e != null) return CmdResult.Fail(e);
        if (string.IsNullOrEmpty(b64)) return CmdResult.Fail("Camera capture returned no data");
        return CmdResult.Ok(output: b64, data: new { name = "camera.jpg" });
    }

    public static async Task<CmdResult> CameraVideo(int seconds)
    {
        if (seconds <= 0) seconds = 10;
        var inFile = Path.Combine(PowerShellRunner.CaptureWorkDir(), $"in-{Guid.NewGuid():N}".Replace("-", "")[..16] + ".json");
        var outFile = Path.Combine(PowerShellRunner.CaptureWorkDir(), $"out-{Guid.NewGuid():N}".Replace("-", "")[..16] + ".b64");
        try
        {
            File.WriteAllText(inFile, JsonSerializer.Serialize(new { seconds }));
            var (b64, e) = await Task.Run(() => PowerShellRunner.RunInteractive($"--video \"{inFile}\" \"{outFile}\"", outFile, Math.Min(seconds, 120) + 30));
            if (e != null) return CmdResult.Fail(e);
            if (string.IsNullOrEmpty(b64)) return CmdResult.Fail("Video capture returned no data");
            return CmdResult.Ok(output: b64, data: new { name = $"video-{seconds}s.mp4" });
        }
        finally { try { File.Delete(inFile); } catch { } }
    }

    public static async Task<CmdResult> MicRecord(int seconds)
    {
        if (seconds <= 0) seconds = 10;
        var inFile = Path.Combine(PowerShellRunner.CaptureWorkDir(), $"in-{Guid.NewGuid():N}".Replace("-", "")[..16] + ".json");
        var outFile = Path.Combine(PowerShellRunner.CaptureWorkDir(), $"out-{Guid.NewGuid():N}".Replace("-", "")[..16] + ".b64");
        try
        {
            File.WriteAllText(inFile, JsonSerializer.Serialize(new { seconds }));
            var (b64, e) = await Task.Run(() => PowerShellRunner.RunInteractive($"--mic \"{inFile}\" \"{outFile}\"", outFile, Math.Min(seconds, 300) + 30));
            if (e != null) return CmdResult.Fail(e);
            if (string.IsNullOrEmpty(b64)) return CmdResult.Fail("Mic capture returned no data");
            return CmdResult.Ok(output: b64, data: new { name = $"mic-{seconds}s.m4a" });
        }
        finally { try { File.Delete(inFile); } catch { } }
    }

    /// <summary>
    /// Saves the uploaded audio to a shared temp path and asks the interactive
    /// session to play it (the service itself cannot reach the user's audio
    /// devices). The one-shot process stays alive until playback ends.
    /// </summary>
    public static async Task<CmdResult> PlayAudio(string base64, string filename)
    {
        if (string.IsNullOrWhiteSpace(base64)) return CmdResult.Fail("Empty audio payload");
        try
        {
            var ext = Path.GetExtension(filename ?? "");
            if (string.IsNullOrEmpty(ext) || ext.Length > 6) ext = ".mp3";
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "RuntimeBroker", "media");
            Directory.CreateDirectory(dir);
            var path = Path.Combine(dir, "play" + ext);
            File.WriteAllBytes(path, Convert.FromBase64String(base64));
            var inFile = Path.Combine(PowerShellRunner.CaptureWorkDir(), $"in-{Guid.NewGuid():N}".Replace("-", "")[..16] + ".json");
            var outFile = Path.Combine(PowerShellRunner.CaptureWorkDir(), $"out-{Guid.NewGuid():N}".Replace("-", "")[..16] + ".b64");
            try
            {
                File.WriteAllText(inFile, JsonSerializer.Serialize(new { play = path }));
                var (o, e) = await Task.Run(() => PowerShellRunner.RunInteractive($"--playaudio \"{inFile}\" \"{outFile}\"", outFile, 7200));
                if (e != null) return CmdResult.Fail(e);
                return CmdResult.Ok(o ?? "Playback finished");
            }
            finally { try { File.Delete(inFile); } catch { } try { File.Delete(path); } catch { } }
        }
        catch (Exception ex) { return CmdResult.Fail(ex.Message); }
    }

    /// <summary>Asks the playing session process to stop current playback.</summary>
    public static CmdResult StopAudio()
    {
        try
        {
            var flag = Path.Combine(PowerShellRunner.CaptureWorkDir(), "stop-audio.flag");
            File.WriteAllText(flag, DateTime.UtcNow.ToString("O"));
            return CmdResult.Ok("Stop requested");
        }
        catch (Exception ex) { return CmdResult.Fail(ex.Message); }
    }

    /// <summary>
    /// Saves an uploaded file into the Public Downloads folder so every local
    /// user can access it. Returns the saved path in output.
    /// </summary>
    public static CmdResult TransferFile(string base64, string filename)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(base64)) return CmdResult.Fail("Empty file payload");
            var safeName = string.IsNullOrWhiteSpace(filename) ? $"file-{DateTime.Now:yyyyMMdd-HHmmss}" : filename;
            foreach (var c in Path.GetInvalidFileNameChars()) safeName = safeName.Replace(c, '_');
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "RuntimeBroker", "Transfers");
            Directory.CreateDirectory(dir);
            var path = Path.Combine(dir, safeName);
            File.WriteAllBytes(path, Convert.FromBase64String(base64));
            return CmdResult.Ok($"Saved to {path}", data: new { path, size = new FileInfo(path).Length });
        }
        catch (Exception ex) { return CmdResult.Fail(ex.Message); }
    }

    // ---- paragraph typing progress / stop --------------------------------
    private static string? _activeParaInFile;
    private static readonly object ParaLock = new();

    internal static void SetActiveParagraph(string inFile) { lock (ParaLock) _activeParaInFile = inFile; }

    public static CmdResult StopTyping()
    {
        lock (ParaLock)
        {
            if (_activeParaInFile == null) return CmdResult.Fail("No typing session running");
            try { File.WriteAllText(_activeParaInFile + ".stop", "stop"); return CmdResult.Ok("Stop requested"); }
            catch (Exception ex) { return CmdResult.Fail(ex.Message); }
        }
    }
}