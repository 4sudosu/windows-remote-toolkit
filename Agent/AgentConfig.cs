using System.Text.Json;

namespace RuntimeBroker;

public class AgentConfig
{
    public string ServerUrl { get; set; } = "ws://127.0.0.1:3001/ws/agent";
    public string Token { get; set; } = "";
    public int ReconnectDelaySec { get; set; } = 5;

    public static string ConfigPath()
    {
        var candidates = new[]
        {
            Path.Combine(AppContext.BaseDirectory, "agent.config.json"),
            Path.Combine(Path.GetDirectoryName(Environment.ProcessPath ?? "") ?? "", "agent.config.json"),
            Path.Combine(Path.GetDirectoryName(System.Diagnostics.Process.GetCurrentProcess().MainModule?.FileName ?? "") ?? "", "agent.config.json")
        };
        return candidates.FirstOrDefault(File.Exists) ?? candidates[0];
    }

    public static AgentConfig? Load()
    {
        var path = ConfigPath();
        if (!File.Exists(path)) return null;
        try
        {
            var json = File.ReadAllText(path);
            return JsonSerializer.Deserialize<AgentConfig>(json, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
        }
        catch (Exception ex)
        {
            Console.WriteLine($"Could not read agent.config.json: {ex.Message}");
            return null;
        }
    }
}
