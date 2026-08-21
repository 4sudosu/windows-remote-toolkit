using System.ServiceProcess;

namespace RuntimeBroker;

/// <summary>
/// 24x7 Windows service that owns the single WebSocket connection to the
/// monitor server. Runs as LocalSystem so it can capture the logged-on user's
/// screen via the WTS interactive-session launch.
/// </summary>
public class AgentService : ServiceBase
{
    private AgentClient? _client;
    private FileSystemWatcher? _watcher;
    private string _configPath = "";

    public AgentService()
    {
        ServiceName = "RuntimeBroker";
        CanStop = true;
        CanShutdown = true;
        AutoLog = true;
    }

    protected override void OnStart(string[] args)
    {
        _configPath = Path.Combine(AppContext.BaseDirectory, "agent.config.json");
        StartClient();

        // Watch the config file so an admin can change the server IP/port/token
        // at any time without reinstalling. On change the agent reloads the
        // config and reconnects automatically.
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
    }

    private void OnConfigChanged(object sender, FileSystemEventArgs e)
    {
        try
        {
            Thread.Sleep(500); // wait for the write to finish
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