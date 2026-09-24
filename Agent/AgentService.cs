using System.ServiceProcess;

namespace RuntimeBroker;

/// <summary>
/// 24x7 Windows service that owns the single WebSocket connection to the
/// relay server. Runs as LocalSystem; screen/input actions that need the
/// user's desktop run via a hidden one-shot scheduled task in the
/// interactive session (see PowerShellRunner — no visible windows ever).
/// </summary>
public class AgentService : ServiceBase
{
    private AgentClient? _client;
    private FileSystemWatcher? _watcher;

    public AgentService()
    {
        ServiceName = "RuntimeBroker";
        CanStop = true;
        CanShutdown = true;
        AutoLog = true;
    }

    protected override void OnStart(string[] args)
    {
        // Return FAST: the SCM fails the start (error 1920) if OnStart
        // blocks. All slow work (inventory, first connect) runs on workers.
        try
        {
            StartClient();

            // Hot-reload: an admin can edit agent.config.json at any time;
            // the agent reconnects automatically (ACL keeps normal users out).
            try
            {
                _watcher = new FileSystemWatcher(AppContext.BaseDirectory, "agent.config.json")
                {
                    NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.FileName,
                    EnableRaisingEvents = true
                };
                _watcher.Changed += OnConfigChanged;
                _watcher.Created += OnConfigChanged;
            }
            catch (Exception ex)
            {
                WriteLog($"Config watcher failed: {ex.Message}");
            }

            // Emergency stop hotkey (Ctrl+Shift+X) cancels any running input.
            try { EmergencyStop.Start(); } catch (Exception ex) { WriteLog($"Hotkey failed: {ex.Message}"); }
        }
        catch (Exception ex)
        {
            // Never let OnStart throw — log and let the worker retry loop
            // bring the connection up instead of failing the service start.
            WriteLog($"OnStart guarded: {ex.Message}");
        }
    }

    private void OnConfigChanged(object sender, FileSystemEventArgs e)
    {
        try
        {
            Thread.Sleep(500);
            WriteLog("Config changed — reloading.");
            _client?.Stop();
            _client = null;
            StartClient();
        }
        catch (Exception ex)
        {
            WriteLog($"Config reload error: {ex.Message}");
        }
    }

    private void StartClient()
    {
        var config = AgentConfig.Load();
        if (config == null)
        {
            WriteLog("agent.config.json not found — refusing to start.");
            return;
        }

        _client = new AgentClient(config);
        _client.Log += s => WriteLog(s);
        _ = Task.Run(_client.RunAsync);

        WriteLog($"RuntimeBroker service started (v{AgentVersionInfo.Version}).");
    }

    protected override void OnStop()
    {
        try { EmergencyStop.Stop(); } catch { }
        _client?.Stop();
        _client = null;
        _watcher?.Dispose();
        _watcher = null;
        WriteLog("RuntimeBroker service stopped.");
    }

    protected override void OnShutdown()
    {
        _client?.Stop();
        _client = null;
    }

    public void StartManually() => OnStart(Array.Empty<string>());
    public void StopManually() => OnStop();

    private static void WriteLog(string line)
    {
        try
        {
            File.AppendAllText(
                Path.Combine(AppContext.BaseDirectory, "agent.service.log"),
                $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {line}\r\n");
        }
        catch { }
    }
}
