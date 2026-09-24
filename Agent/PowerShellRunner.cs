using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;

namespace RuntimeBroker;

/// <summary>
/// Runs THIS agent EXE inside the logged-on user's interactive session (the
/// LocalSystem service runs in Session 0 and cannot see the user's desktop).
/// One-shot interactive scheduled task created via schtasks.exe + an XML
/// definition. Despite the class name, no powershell.exe is ever spawned for
/// capture — the task runs the windowless (WinExe) agent itself, so the user
/// sees nothing: no console, no window, no popup.
/// </summary>
public static class PowerShellRunner
{
    public static (string? Output, string? Error) RunInInteractiveSession(
        string outFile, int timeoutSec = 15,
        string format = "png", int quality = 80, int maxWidth = 0)
    {
        var id = Guid.NewGuid().ToString("N")[..10];
        var dir = CaptureWorkDir();
        var taskName = $"RuntimeBrokerCapture{id}";
        var snapshot = ResultFilesSnapshot(dir);
        try
        {
            Directory.CreateDirectory(dir);

            if (!TryInteractiveUser(out var user))
                return (null, "No interactive user is logged on");
            var taskXml = Path.Combine(dir, $"t-{id}.xml");
            var args = $"--capture \"{outFile}\" {format} {quality} {maxWidth}";
            File.WriteAllText(taskXml, BuildTaskXml(user, taskName, args, "RuntimeBroker interactive screen capture"), Encoding.Unicode);

            var create = SchTasks($"/Create /TN \"{taskName}\" /XML \"{taskXml}\" /F");
            if (create.ExitCode != 0)
                return (null, $"Failed to create interactive task (exit {create.ExitCode})");
            var run = SchTasks($"/Run /TN \"{taskName}\"");
            if (run.ExitCode != 0)
                return (null, $"Failed to run interactive task (exit {run.ExitCode})");

            var deadline = DateTime.UtcNow.AddSeconds(timeoutSec);
            while (DateTime.UtcNow < deadline)
            {
                var result = ReadNewResult(dir, snapshot);
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
            try
            {
                foreach (var f in Directory.GetFiles(dir, "*.b64"))
                {
                    if (!snapshot.ContainsKey(f))
                        File.Delete(f);
                }
            }
            catch { }
        }
    }

    /// <summary>
    /// Runs the agent EXE in the interactive session and waits for the task
    /// to finish (used for --rotate). Returns the task's last result code.
    /// </summary>
    public static (bool Ok, string? Error) RunInteractiveWait(string args, int timeoutSec = 30)
    {
        var id = Guid.NewGuid().ToString("N")[..10];
        var dir = CaptureWorkDir();
        var taskName = $"RuntimeBrokerRun{id}";
        try
        {
            Directory.CreateDirectory(dir);
            if (!TryInteractiveUser(out var user))
                return (false, "No interactive user is logged on");
            var taskXml = Path.Combine(dir, $"t-{id}.xml");
            File.WriteAllText(taskXml, BuildTaskXml(user, taskName, args, "RuntimeBroker interactive action"), Encoding.Unicode);

            if (SchTasks($"/Create /TN \"{taskName}\" /XML \"{taskXml}\" /F").ExitCode != 0)
                return (false, "Failed to create interactive task");
            if (SchTasks($"/Run /TN \"{taskName}\"").ExitCode != 0)
                return (false, "Failed to run interactive task");

            var deadline = DateTime.UtcNow.AddSeconds(timeoutSec);
            while (DateTime.UtcNow < deadline)
            {
                Thread.Sleep(500);
                var state = QueryTaskState(taskName);
                if (state == "Ready")
                    return (true, null);
                if (state == "")
                    break;
            }
            return (false, "Interactive action timed out");
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
        finally
        {
            try { SchTasks($"/Delete /TN \"{taskName}\" /F"); } catch { }
            try { if (File.Exists(Path.Combine(dir, $"t-{id}.xml"))) File.Delete(Path.Combine(dir, $"t-{id}.xml")); } catch { }
        }
    }

    private const string AudioTaskName = "RuntimeBrokerAudio";
    private const string InputTaskName = "RuntimeBrokerInput";

    /// <summary>
    /// Runs an --input-* one-shot in the user session and waits for it.
    /// Used for mouse + instant text (fast, returns when done).
    /// </summary>
    public static (bool Ok, string? Error) RunInteractiveInput(string args, int timeoutSec = 30)
        => RunInteractiveWait(args, timeoutSec);

    /// <summary>
    /// Starts paced paragraph typing in the user session (fire-and-forget;
    /// the task stays Running until the text is done). Stopped by
    /// <see cref="EndInteractiveInput"/>.
    /// </summary>
    public static (bool Ok, string? Error) StartInteractiveParagraph(string textFile, int wpm, bool addEnter)
    {
        var dir = CaptureWorkDir();
        try
        {
            Directory.CreateDirectory(dir);
            if (!TryInteractiveUser(out var user))
                return (false, "No interactive user is logged on");
            try { SchTasks($"/End /TN \"{InputTaskName}\""); } catch { }
            try { SchTasks($"/Delete /TN \"{InputTaskName}\" /F"); } catch { }
            var taskXml = Path.Combine(dir, "t-input.xml");
            File.WriteAllText(taskXml, BuildTaskXml(user, InputTaskName,
                $"--input-paragraph {wpm} {(addEnter ? 1 : 0)} \"{textFile}\"",
                "RuntimeBroker paragraph typing"),
                System.Text.Encoding.Unicode);
            if (SchTasks($"/Create /TN \"{InputTaskName}\" /XML \"{taskXml}\" /F").ExitCode != 0)
                return (false, "Failed to create input task");
            if (SchTasks($"/Run /TN \"{InputTaskName}\"").ExitCode != 0)
                return (false, "Failed to start typing");
            return (true, null);
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
    }

    /// <summary>Ends user-session typing started by <see cref="StartInteractiveParagraph"/>.</summary>
    public static void EndInteractiveInput()
    {
        try { SchTasks("/End /TN \"" + InputTaskName + "\""); } catch { }
        try { SchTasks("/Delete /TN \"" + InputTaskName + "\" /F"); } catch { }
    }

    /// <summary>
    /// Plays audio in the user session (service session has no sound).
    /// Fixed task name so stop can end it mid-play. Fire-and-forget.
    /// </summary>
    public static (bool Ok, string? Error) PlayInteractiveAudio(string audioFile, int timeoutSec = 600)
    {
        var dir = CaptureWorkDir();
        try
        {
            Directory.CreateDirectory(dir);
            if (!TryInteractiveUser(out var user))
                return (false, "No interactive user is logged on");
            // Replace any previous playback.
            try { SchTasks($"/End /TN \"{AudioTaskName}\""); } catch { }
            try { SchTasks($"/Delete /TN \"{AudioTaskName}\" /F"); } catch { }
            var taskXml = Path.Combine(dir, "t-audio.xml");
            File.WriteAllText(taskXml, BuildTaskXml(user, AudioTaskName,
                $"--play \"{audioFile}\"", "RuntimeBroker audio playback"),
                System.Text.Encoding.Unicode);
            if (SchTasks($"/Create /TN \"{AudioTaskName}\" /XML \"{taskXml}\" /F").ExitCode != 0)
                return (false, "Failed to create audio task");
            if (SchTasks($"/Run /TN \"{AudioTaskName}\"").ExitCode != 0)
                return (false, "Failed to start audio playback");
            return (true, null);
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
    }

    /// <summary>Ends user-session playback started by <see cref="PlayInteractiveAudio"/>.</summary>
    public static void StopInteractiveAudio()
    {
        try { SchTasks("/End /TN \"" + AudioTaskName + "\""); } catch { }
        try { SchTasks("/Delete /TN \"" + AudioTaskName + "\" /F"); } catch { }
    }

    private static string QueryTaskState(string taskName)
    {
        try
        {
            var p = SchTasks($"/Query /TN \"{taskName}\" /FO CSV /NH");
            var line = p.StandardOutput.ReadToEnd();
            // CSV: "TaskName","Next Run Time","Status"
            var parts = line.Split(',');
            if (parts.Length >= 3)
                return parts[2].Trim().Trim('"', '\r', '\n', ' ');
            return "";
        }
        catch { return ""; }
    }

    /// <summary>
    /// Shared scratch dir for the SYSTEM service and the interactive user's
    /// scheduled task. C:\Windows\Temp grants BUILTIN\Users write access.
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

    private static Dictionary<string, DateTime> ResultFilesSnapshot(string dir)
    {
        var map = new Dictionary<string, DateTime>();
        try
        {
            foreach (var f in Directory.GetFiles(dir, "*.b64"))
                map[f] = File.GetLastWriteTimeUtc(f);
        }
        catch { }
        return map;
    }

    private static (string? Output, string? Error) ReadNewResult(string dir, Dictionary<string, DateTime> snapshot)
    {
        try
        {
            var candidate = Directory.GetFiles(dir, "*.b64")
                .Where(f => !snapshot.TryGetValue(f, out var t) || File.GetLastWriteTimeUtc(f) > t)
                .OrderByDescending(File.GetLastWriteTimeUtc)
                .FirstOrDefault();
            if (candidate == null) return (null, null!);
            try
            {
                var text = File.ReadAllText(candidate).Trim();
                if (text.Length == 0) return (null, "Capture script errored (no output)");
                if (text.StartsWith("ERR: ", StringComparison.OrdinalIgnoreCase))
                    return (null, text["ERR: ".Length..]);
                return (text, null);
            }
            finally
            {
                try { File.Delete(candidate); } catch { }
            }
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

    private static string BuildTaskXml(string user, string taskName, string args, string description)
    {
        var exe = Environment.ProcessPath;
        if (string.IsNullOrEmpty(exe)) throw new InvalidOperationException("Cannot determine agent EXE path");
        string E(string s) => s.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;")
                               .Replace("\"", "&quot;").Replace("'", "&apos;");
        return "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\r\n" +
            "<Task version=\"1.2\" xmlns=\"http://schemas.microsoft.com/windows/2004/02/mit/task\">\r\n" +
            "  <RegistrationInfo>\r\n" +
            $"    <Description>{E(description)}</Description>\r\n" +
            $"    <URI>\\{E(taskName)}</URI>\r\n" +
            "  </RegistrationInfo>\r\n" +
            "  <Triggers />\r\n" +
            "  <Principals>\r\n" +
            "    <Principal id=\"Author\">\r\n" +
            $"      <UserId>{E(user)}</UserId>\r\n" +
            "      <LogonType>InteractiveToken</LogonType>\r\n" +
            "      <RunLevel>LeastPrivilege</RunLevel>\r\n" +
            "    </Principal>\r\n" +
            "  </Principals>\r\n" +
            "  <Settings>\r\n" +
            "    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>\r\n" +
            "    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>\r\n" +
            "    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>\r\n" +
            "    <AllowHardTerminate>true</AllowHardTerminate>\r\n" +
            "    <StartWhenAvailable>false</StartWhenAvailable>\r\n" +
            "    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>\r\n" +
            "    <IdleSettings>\r\n" +
            "      <StopOnIdleEnd>true</StopOnIdleEnd>\r\n" +
            "      <RestartOnIdle>false</RestartOnIdle>\r\n" +
            "    </IdleSettings>\r\n" +
            "    <AllowStartOnDemand>true</AllowStartOnDemand>\r\n" +
            "    <Enabled>true</Enabled>\r\n" +
            "    <Hidden>false</Hidden>\r\n" +
            "    <RunOnlyIfIdle>false</RunOnlyIfIdle>\r\n" +
            "    <WakeToRun>false</WakeToRun>\r\n" +
            "    <ExecutionTimeLimit>PT1M</ExecutionTimeLimit>\r\n" +
            "    <Priority>7</Priority>\r\n" +
            "  </Settings>\r\n" +
            "  <Actions Context=\"Author\">\r\n" +
            "    <Exec>\r\n" +
            $"      <Command>{E(exe)}</Command>\r\n" +
            $"      <Arguments>{E(args)}</Arguments>\r\n" +
            "    </Exec>\r\n" +
            "  </Actions>\r\n" +
            "</Task>\r\n";
    }

    /// <summary>
    /// Returns the active console session's user (e.g. "DESKTOP-X\HP") using the
    /// native WTS API — no child process is spawned.
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
