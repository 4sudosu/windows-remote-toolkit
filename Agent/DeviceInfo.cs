using System.Runtime.InteropServices;

namespace RuntimeBroker;

public class DeviceInfo
{
    public string Hostname { get; set; } = "";
    public string Model { get; set; } = "";
    public string Serial { get; set; } = "";
    public string Username { get; set; } = "";
    public string Ip { get; set; } = "";
    public string Os { get; set; } = "";

    public static DeviceInfo Collect()
    {
        var fullUser = CmdValue("echo %USERDOMAIN%\\%USERNAME%") ?? "";
        var sep = fullUser.LastIndexOf('\\');

        return new DeviceInfo
        {
            Hostname = Environment.MachineName,
            Username = sep >= 0 ? fullUser[(sep + 1)..] : (Environment.UserName ?? "").Trim(),
            Serial = CmdValue("wmic bios get serialnumber") ?? PsValue("(Get-CimInstance Win32_BIOS).SerialNumber"),
            Model = CmdValue("wmic csproduct get name") ?? PsValue("(Get-CimInstance Win32_ComputerSystem).Model"),
            Ip = GetIp(),
            Os = GetOs()
        };
    }

    private static string? CmdValue(string command)
    {
        try
        {
            var psi = new System.Diagnostics.ProcessStartInfo
            {
                FileName = "cmd.exe",
                Arguments = "/c " + command,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                CreateNoWindow = true,
                WindowStyle = System.Diagnostics.ProcessWindowStyle.Hidden
            };
            using var proc = System.Diagnostics.Process.Start(psi);
            if (proc == null) return null;
            var output = proc.StandardOutput.ReadToEnd();
            proc.WaitForExit(10000);
            foreach (var raw in output.Split('\n'))
            {
                var line = raw.Trim();
                if (line.Length > 0 && !line.Contains("serialnumber") && !line.Contains("SerialNumber") && !line.Contains("Name"))
                    return line.Trim('\r', '\u200b');
            }
            return null;
        }
        catch
        {
            return null;
        }
    }

    private static string? PsValue(string expression)
    {
        var script = $"{expression}";
        var encoded = Convert.ToBase64String(System.Text.Encoding.Unicode.GetBytes(script));
        try
        {
            var psi = new System.Diagnostics.ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = $"-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand {encoded}",
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
                WindowStyle = System.Diagnostics.ProcessWindowStyle.Hidden
            };
            using var proc = System.Diagnostics.Process.Start(psi);
            if (proc == null) return null;
            var output = proc.StandardOutput.ReadToEnd();
            proc.WaitForExit(15000);
            foreach (var raw in output.Split('\n'))
            {
                var line = raw.Trim();
                if (line.Length > 0) return line.Trim('\r');
            }
            return null;
        }
        catch
        {
            return null;
        }
    }

    private static string GetIp()
    {
        try
        {
            var candidates = new List<string>();
            foreach (var ni in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                if (ni.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback) continue;
                var props = ni.GetIPProperties();
                bool hasGateway = props.GatewayAddresses.Count > 0;
                foreach (var ua in props.UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork) continue;
                    var ip = ua.Address.ToString();
                    if (System.Net.IPAddress.IsLoopback(ua.Address)) continue;
                    if (ip.StartsWith("169.254.")) continue;
                    if (hasGateway) candidates.Insert(0, ip);
                    else candidates.Add(ip);
                }
            }
            if (candidates.Count > 0) return candidates[0];
        }
        catch
        {
        }
        return "";
    }

    private static string GetOs()
    {
        try
        {
            return $"{RuntimeInformation.OSDescription} ({RuntimeInformation.OSArchitecture})";
        }
        catch
        {
            return Environment.OSVersion.VersionString;
        }
    }
}