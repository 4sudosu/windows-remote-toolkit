using System.Text.Json;

namespace RuntimeBroker;

public class AgentConfig
{
    public string ServerUrl { get; set; } = "ws://127.0.0.1:4777/ws/agent";
    public string Token { get; set; } = "";
    public int ReconnectDelaySec { get; set; } = 5;

    /// <summary>Seconds between app-level keepalive frames. 0 disables.</summary>
    public int KeepAliveSec { get; set; } = 20;

    public static AgentConfig? Load()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "agent.config.json");
        if (!File.Exists(path)) return null;
        try
        {
            var json = File.ReadAllText(path);
            return JsonSerializer.Deserialize<AgentConfig>(json, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
        }
        catch
        {
            return null;
        }
    }
}
