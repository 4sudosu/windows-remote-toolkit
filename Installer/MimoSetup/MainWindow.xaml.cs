using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.ServiceProcess;
using System.Windows;
using System.Windows.Forms;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;

namespace MimoSetup;

public partial class MainWindow : Window
{
    private Process? _msiProcess;

    public MainWindow()
    {
        InitializeComponent();
        TokenBox.Text = "";
    }

    private void Window_DragMove(object sender, MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed) DragMove();
    }

    private async void Install_Click(object sender, RoutedEventArgs e)
    {
        if (_msiProcess is { HasExited: false })
        {
            // Button doubles as Cancel while installing.
            try { _msiProcess.Kill(entireProcessTree: true); } catch { }
            StatusText.Text = "Installation canceled.";
            return;
        }

        var server = ServerBox.Text.Trim();
        var port = PortBox.Text.Trim();
        var token = TokenBox.Text.Trim();
        var service = ServiceBox.Text.Trim();
        var folder = FolderBox.Text.Trim();

        if (server.Length == 0)
        {
            StatusText.Text = "Please enter the server hostname (e.g. mimo-pro.onrender.com).";
            ServerBox.Focus();
            return;
        }
        if (port.Length == 0) port = "443";
        if (service.Length == 0 || service.Length > 80 ||
            service.Contains('\\') || service.Contains('/') || service.Contains('"') || service.Contains('\''))
        {
            StatusText.Text = "Service name must be 1–80 characters without \\ / \" or apostrophes.";
            ServiceBox.Focus();
            return;
        }

        if (folder.Length == 0)
        {
            StatusText.Text = "Please choose an install folder.";
            FolderBox.Focus();
            return;
        }

        InstallBtn.Content = "Cancel";
        InstallBtn.IsEnabled = true;
        ServiceBox.IsEnabled = false;
        ServerBox.IsEnabled = false;
        PortBox.IsEnabled = false;
        TokenBox.IsEnabled = false;
        FolderBox.IsEnabled = false;
        BrowseBtn.IsEnabled = false;
        Progress.Visibility = Visibility.Visible;
        Progress.IsIndeterminate = true;
        StatusText.Text = "Preparing…";

        try
        {
            // 1. Extract the embedded MSI engine to %TEMP%.
            StatusText.Text = "Extracting setup engine…";
            var msiPath = ExtractMsi();
            if (msiPath is null) throw new InvalidOperationException("Setup engine missing in package.");

            // 2. Run msiexec silently with the chosen values.
            //    Port 443 makes the agent use secure wss:// (handled by the agent itself).
            StatusText.Text = port == "443"
                ? $"Installing — connects to {server} over secure wss://…"
                : $"Installing — connects to {server}:{port}…";
            _msiProcess = RunMsi(msiPath, server, port, token, service, folder);
            await _msiProcess.WaitForExitAsync();

            var exit = _msiProcess.ExitCode;
            _msiProcess = null;
            if (exit == 0)
            {
                // Post-install steps (we're elevated here) — done AFTER the MSI
                // because running this EXE from a custom action hit error 1721,
                // and starting the service inside the install hit the SCM 30s
                // timeout with error 1920.

                // 1. Write agent.config.json at the install folder.
                StatusText.Text = "Writing agent configuration…";
                var configPath = Path.Combine(folder, "agent.config.json");
                var url = port == "443"
                    ? $"wss://{server}/ws/agent"
                    : $"ws://{server}:{port}/ws/agent";
                var json = System.Text.Json.JsonSerializer.Serialize(new Dictionary<string, object>
                {
                    ["ServerUrl"] = url,
                    ["Token"] = token,
                    ["ReconnectDelaySec"] = 5,
                    // 8s: Render's proxy drops WS connections idle ~20s, which
                    // read as the agent going "offline" again and again.
                    ["KeepAliveSec"] = 8
                }, new System.Text.Json.JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(configPath, json);

                // 2. Lock the config: only SYSTEM + Administrators can modify.
                RunHidden("icacls.exe",
                    $"\"{configPath}\" /inheritance:r /grant:r \"SYSTEM:(F)\" \"Administrators:(F)\" \"BUILTIN\\Users:(R)\"");

                // 3. 24×7 crash recovery for the service.
                StatusText.Text = "Enabling 24×7 crash recovery…";
                RunHidden("sc.exe",
                    $"failure \"{service}\" reset= 86400 actions= restart/5000/restart/10000/restart/30000");

                // 4. Start the service. First start extracts the self-contained
                //    agent (154 MB) and can take a while — the setup app waits
                //    patiently where the SCM's 30s timeout could not.
                StatusText.Text = "Starting the MimoPro service (first start can take a minute)…";
                var started = await StartServiceWithTimeout(service, 120);
                ShowResult(started,
                    started
                        ? $"{service} service installed and running — connected to {(port == "443" ? "wss://" : "ws://")}{server}."
                        : $"{service} service is installed but did not report Running within the wait time. It usually comes up on its own; check services.msc shortly. Log: %TEMP%\\MimoSetup\\install.log");
            }
            else if (exit == 1602)
            {
                Conclude("Installation canceled.", null);
            }
            else
            {
                Conclude($"Installer exited with code {exit}. A verbose log was kept in %TEMP%\\MimoSetup.", msiPath);
            }
        }
        catch (Exception ex)
        {
            Conclude("Error: " + ex.Message, null);
        }
    }

    private void Conclude(string status, string? msiPath)
    {
        _msiProcess = null;
        Progress.Visibility = Visibility.Collapsed;
        Progress.IsIndeterminate = false;
        InstallBtn.Content = "Try again";
        ServiceBox.IsEnabled = true;
        ServerBox.IsEnabled = true;
        PortBox.IsEnabled = true;
        TokenBox.IsEnabled = true;
        FolderBox.IsEnabled = true;
        BrowseBtn.IsEnabled = true;
        StatusText.Text = status;
    }

    /// <summary>Extract the embedded MSI engine to %TEMP% and return its path.
    /// Always overwrites — never reuse a stale engine from a previous run.</summary>
    private static string? ExtractMsi()
    {
        var path = ExtractionPath();
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        try { File.Delete(path); } catch { }
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("MimoPro-Setup.msi");
        if (s is null) return null;
        using var f = File.Create(path);
        s.CopyTo(f);
        return path;
    }

    private static string ExtractionPath() =>
        Path.Combine(Path.GetTempPath(), "MimoSetup", "MimoPro-Setup-1.0.0.2.msi");

    private static Process RunMsi(string msi, string server, string port, string token, string service, string folder)
    {
        // Property values are quoted to survive spaces (token, service name, folder).
        // MSI expects directory properties to end with a backslash.
        if (!folder.EndsWith('\\')) folder += "\\";
        string args =
            $"/i \"{msi}\" /qn /norestart " +
            $"/l*v \"{Path.Combine(Path.GetDirectoryName(msi)!, "install.log")}\" " +
            $"SERVERIP=\"{server}\" SERVERPORT=\"{port}\" SERVERTOKEN=\"{token}\" SERVICENAME=\"{service}\" " +
            $"INSTALLFOLDER=\"{folder}\"";
        var psi = new ProcessStartInfo("msiexec.exe", args)
        {
            UseShellExecute = false,
            WindowStyle = ProcessWindowStyle.Hidden
        };
        return Process.Start(psi)!;
    }

    private void Browse_Click(object sender, RoutedEventArgs e)
    {
        using var dlg = new System.Windows.Forms.FolderBrowserDialog
        {
            SelectedPath = FolderBox.Text,
            ShowNewFolderButton = true,
            Description = "Where should Mimo Pro be installed?"
        };
        if (dlg.ShowDialog() == System.Windows.Forms.DialogResult.OK)
        {
            FolderBox.Text = dlg.SelectedPath;
        }
    }

    private static void RunHidden(string exe, string args)
    {
        var psi = new ProcessStartInfo(exe, args)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden
        };
        try
        {
            var p = Process.Start(psi);
            p?.WaitForExit(15000);
        }
        catch { /* non-fatal: config ACL / recovery settings are best effort */ }
    }

    private static async Task<bool> StartServiceWithTimeout(string name, int seconds)
    {
        try
        {
            using var sc = new ServiceController(name);
            if (sc.Status == ServiceControllerStatus.Running) return true;
            sc.Start();
        }
        catch (InvalidOperationException)
        {
            return false; // service does not exist — MSI reporting failure
        }
        catch
        {
            // already running / starting — fall through and poll
        }
        for (int i = 0; i < seconds; i++)
        {
            try
            {
                using var sc = new ServiceController(name);
                if (sc.Status == ServiceControllerStatus.Running) return true;
            }
            catch { }
            await Task.Delay(1000);
        }
        return false;
    }

    private async void CloseResult_Click(object sender, RoutedEventArgs e) => Close();

    /// <summary>Reflect install success or failure in the dedicated result state.</summary>
    private void ShowResult(bool ok, string detail)
    {
        FormPanel.Visibility = Visibility.Collapsed;
        ResultPanel.Visibility = Visibility.Visible;
        ResultTitle.Text = ok ? "Mimo Pro is installed and running" : "Mimo Pro setup finished with a warning";
        ResultDetail.Text = detail;

        var green = new LinearGradientBrush();
        green.GradientStops.Add(new GradientStop(System.Windows.Media.Color.FromRgb(0x30, 0xD1, 0x58), 0));
        green.GradientStops.Add(new GradientStop(System.Windows.Media.Color.FromRgb(0x0A, 0x84, 0xFF), 1));
        var amber = new LinearGradientBrush();
        amber.GradientStops.Add(new GradientStop(System.Windows.Media.Color.FromRgb(0xF5, 0x9E, 0x0B), 0));
        amber.GradientStops.Add(new GradientStop(System.Windows.Media.Color.FromRgb(0xF9, 0x73, 0x16), 1));
        ResultIconBorder.Background = ok ? green : amber;
        ResultIcon.Text = ok ? "\u2713" : "!";
        StatusText.Text = "";
        Progress.Visibility = Visibility.Collapsed;
    }
}
