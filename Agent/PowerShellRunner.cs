using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;

namespace RuntimeBroker;

/// <summary>
/// Runs PowerShell capture scripts inside the logged-on user's interactive
/// session (the LocalSystem service runs in Session 0 and cannot see the user's
/// desktop directly). Tries the WTS active-console launch first, then falls back
/// to a one-shot interactive scheduled task.
/// </summary>
public static class PowerShellRunner
{
    public static (string? Output, string? Error) RunInInteractiveSession(string outFile, int timeoutSec = 15)
        => RunInteractive($"--capture \"{outFile}\"", outFile, timeoutSec);

    /// <summary>Live-stream variant: asks the child for a small JPEG frame.</summary>
    public static (string? Output, string? Error) RunLiveCaptureSession(string outFile, int maxWidth, int quality, int timeoutSec = 15)
        => RunInteractive($"--capture \"{outFile}\" {maxWidth} {quality}", outFile, timeoutSec);

    private const string HotkeyTaskName = "RuntimeBrokerEmergencyHotkey";

    public static void StartHotkeyListener()
    {
        if (!TryInteractiveUser(out var user)) return;
        if (!TryRegisterInteractiveTask(user, HotkeyTaskName, "--hotkey", out _)) return;
        try { SchTasks($"/Run /TN \"{HotkeyTaskName}\""); } catch { }
    }

    public static void StopHotkeyListener()
    {
        try { SchTasks($"/End /TN \"{HotkeyTaskName}\""); } catch { }
        try { SchTasks($"/Delete /TN \"{HotkeyTaskName}\" /F"); } catch { }
    }

    /// <summary>
    /// Runs THIS agent EXE inside the interactive user session with the given
    /// command-line action, writing its result to <paramref name="outFile"/>
    /// (the same outFile is passed to the child so it knows where to write).
    /// </summary>
    public static (string? Output, string? Error) RunInteractive(string actionArgs, string outFile, int timeoutSec = 15)
    {
        var id = Guid.NewGuid().ToString("N")[..10];
        var dir = CaptureWorkDir();
        var taskName = $"RuntimeBrokerCapture{id}";
        try
        {
            Directory.CreateDirectory(dir);

            // One-shot interactive scheduled task, registered via the Task
            // Scheduler COM API (Register-ScheduledTask) with RunLevel=Highest
            // so input reaches ELEVATED windows (admin cmd). The plain
            // schtasks.exe /XML parser rejects the Highest run level on some
            // machines ("value incorrectly formatted or out of range").
            if (!TryInteractiveUser(out var user))
                return (null, "No interactive user is logged on");

            if (!TryRegisterInteractiveTask(user, taskName, actionArgs, out var createError))
                return (null, $"Failed to create interactive task: {createError}");

            var run = SchTasks($"/Run /TN \"{taskName}\"");
            if (run.ExitCode != 0)
                return (null, $"Failed to run interactive task (exit {run.ExitCode})");

            var deadline = DateTime.UtcNow.AddSeconds(timeoutSec);
            while (DateTime.UtcNow < deadline)
            {
                
                var result = ReadResultFile(outFile);
                if (result.Output != null || result.Error != null)
                    return result;
                Thread.Sleep(300);
            }
            return (null, $"Interactive task did not complete within {timeoutSec}s (user '{user}')");
        }
        catch (Exception ex)
        {
            return (null, $"Interactive capture failed: {ex.Message}");
        }
        finally
        {
            try { SchTasks($"/Delete /TN \"{taskName}\" /F"); } catch { }
            try { if (File.Exists(Path.Combine(dir, $"t-{id}.xml"))) File.Delete(Path.Combine(dir, $"t-{id}.xml")); } catch { }
            // Remove ONLY this call's result file — a generic sweep would race
            // with other concurrent captures and delete their results.
            try { if (File.Exists(outFile)) File.Delete(outFile); } catch { }
        }
    }

    /// <summary>
    /// Shared scratch dir used by BOTH the SYSTEM service (writes the script,
    /// reads the result) and the interactive user's scheduled task (runs the
    /// capture script, writes the result). The service temp (SystemTemp) is
    /// SYSTEM-only, so the interactive user cannot write results there.
    /// C:\Windows\Temp grants BUILTIN\Users write access, so it works for both.
    /// </summary>
    internal static string CaptureWorkDir()
    {
        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "Temp", "RuntimeBroker");
        Directory.CreateDirectory(dir);
        try
        {
            var di = new DirectoryInfo(dir);
            var sec = di.GetAccessControl();
            var users = new NTAccount(@"BUILTIN\Users");
            sec.AddAccessRule(new FileSystemAccessRule(
                users, FileSystemRights.Modify,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                PropagationFlags.None, AccessControlType.Allow));
            di.SetAccessControl(sec);
        }
        catch { }
        return dir;
    }

    private static (string? Output, string? Error) ReadResultFile(string outFile)
    {
        try
        {
            if (!File.Exists(outFile)) return (null, null!);
            // The child may still be mid-write; require the file to be stable.
            var len1 = new FileInfo(outFile).Length;
            Thread.Sleep(120);
            var len2 = new FileInfo(outFile).Length;
            if (len1 != len2) return (null, null!);
            var text = File.ReadAllText(outFile).Trim();
            try { File.Delete(outFile); } catch { }
            if (text.Length == 0) return (null, "Capture script errored (no output)");
            if (text.StartsWith("ERR: ", StringComparison.OrdinalIgnoreCase))
                return (null, text["ERR: ".Length..]);
            return (text, null);
        }
        catch (Exception ex)
        {
            return (null, $"Could not read capture result: {ex.Message}");
        }
    }

    private static Process SchTasks(string args)
    {
        var psi = new ProcessStartInfo("schtasks.exe", args)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        var p = Process.Start(psi) ?? throw new InvalidOperationException("schtasks failed to start");
        p.WaitForExit(30000);
        return p;
    }

    /// <summary>
    /// Registers THIS agent EXE as a one-shot interactive scheduled task via
    /// the Task Scheduler COM API (Register-ScheduledTask cmdlet) with
    /// RunLevel=Highest, so keystrokes reach ELEVATED windows (admin cmd).
    /// The schtasks.exe /XML parser rejects 'Highest' on some machines.
    /// </summary>
    private static bool TryRegisterInteractiveTask(string user, string taskName, string actionArgs, out string error)
    {
        error = null!;
        try
        {
            string Esc(string s) => s.Replace("'", "''");
            var exe = Environment.ProcessPath;
            if (string.IsNullOrEmpty(exe)) throw new InvalidOperationException("Cannot determine agent EXE path");
            var ps =
                "$ErrorActionPreference='Stop';" +
                "try{" +
                "$a=New-ScheduledTaskAction -Execute '" + Esc(exe) + "' -Argument '" + Esc(actionArgs) + "';" +
                "$p=New-ScheduledTaskPrincipal -UserId '" + Esc(user) + "' -LogonType Interactive -RunLevel Highest;" +
                "$s=New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Minutes 180);" +
                "Register-ScheduledTask -TaskName '" + Esc(taskName) + "' -Action $a -Principal $p -Settings $s -Force | Out-Null;" +
                "exit 0}catch{ exit 1 }";
            var psi = new ProcessStartInfo("powershell.exe",
                "-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"" + ps + "\"")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };
            using var proc = Process.Start(psi)!;
            proc.WaitForExit(30000);
            if (proc.ExitCode != 0)
            {
                var errOut = proc.StandardOutput.ReadToEnd().Trim();
                error = errOut.Length > 0 ? errOut : "powershell exited " + proc.ExitCode;
                return false;
            }
            return true;
        }
        catch (Exception ex) { error = ex.Message; return false; }
    }

    /// <summary>
    /// Returns the active console session's user (e.g. "DESKTOP-X\\HP") using the
    /// native WTS API — no PowerShell / child process is spawned.
    /// </summary>
    internal static bool TryInteractiveUser(out string user)
    {
        user = "";
        try
        {
            var sessionId = WTSGetActiveConsoleSessionId();
            if (sessionId == 0xFFFFFFFF) return false;
            if (!WTSQuerySessionInformation(IntPtr.Zero, sessionId, WTSUserName, out var userBuf, out var userLen))
                return false;
            try
            {
                var name = Marshal.PtrToStringUni(userBuf, (int)userLen / 2)?.TrimEnd('\0').Trim() ?? "";
                if (!WTSQuerySessionInformation(IntPtr.Zero, sessionId, WTSDomainName, out var domBuf, out var domLen))
                {
                    user = name;
                    return name.Length > 0 && !name.EndsWith("$", StringComparison.Ordinal);
                }
                try
                {
                    var domain = Marshal.PtrToStringUni(domBuf, (int)domLen / 2)?.TrimEnd('\0').Trim() ?? "";
                    user = (domain.Length > 0 && name.Length > 0) ? domain + "\\" + name : name;
                    return name.Length > 0 && !name.EndsWith("$", StringComparison.Ordinal);
                }
                finally { WTSFreeMemory(domBuf); }
            }
            finally { WTSFreeMemory(userBuf); }
        }
        catch
        {
            return false;
        }
    }

    private const int WTSUserName = 5;
    private const int WTSDomainName = 7;

    [DllImport("kernel32.dll")]
    private static extern uint WTSGetActiveConsoleSessionId();

    [DllImport("wtsapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool WTSQuerySessionInformation(IntPtr hServer, uint sessionId, int wtsInfoClass, out IntPtr ppBuffer, out uint pBytesReturned);

    [DllImport("wtsapi32.dll")]
    private static extern void WTSFreeMemory(IntPtr pMemory);
}
