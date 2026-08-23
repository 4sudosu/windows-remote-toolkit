# Windows Remote Toolkit

A complete remote monitoring and control solution for Windows machines. Control any Windows 10/11 PC from an Android app over the internet.

## Architecture

```
┌─────────────┐     WSS/HTTPS      ┌─────────────┐     WSS/HTTPS      ┌─────────────┐
│  Android    │ ◄─────────────────► │   Server    │ ◄─────────────────► │   Windows   │
│   App       │   (port 443/3001)  │  (Node.js)  │   (port 3001)       │   Agent     │
└─────────────┘                    └─────────────┘                    └─────────────┘
        ▲                                                               │
        │                                                               │
        └────────────────────────── Remote Control ─────────────────────┘
```

## Components

| Component | Technology | Purpose |
|-----------|------------|---------|
| **Windows Agent** | C# .NET 8 (WinForms, single-file) | Runs as Windows Service (LocalSystem) 24/7 |
| **Server** | Node.js + Express + ws | WebSocket relay, HTTP API, dashboard |
| **Android App** | Kotlin + XML layouts | Dashboard, live screen, full remote control |

---

## Features

### 🖥️ Screen & Display
| Feature | Description |
|---------|-------------|
| **Live Screen Streaming** | Real-time JPEG frames via WebSocket. Configurable interval (250ms–3s), quality (1–100), max width. Pinch-to-zoom, pan, rotate on phone. |
| **Full-Resolution Screenshot** | PNG capture on demand. DPI-aware virtual screen capture (multi-monitor support). |
| **Screen Rotation** | Rotate target display: 0°, 90°, 180°, 270°. Runs in user's interactive session. |

### ⌨️ Input Control
| Feature | Description |
|---------|-------------|
| **Text Input** | Send keystrokes to any focused window on target. |
| **Mouse Control** | Move, left/right click, double-click, drag, scroll. Coordinates relative to screen. |
| **Paragraph Typing** | Human-like typing with configurable WPM (words per minute). Optional Enter at end. Runs async — phone watches progress on live screen. |
| **Emergency Stop** | Global hotkey (Ctrl+Shift+X) on target stops all input immediately. Android "Stop All" button. |

### 💻 System Control
| Feature | Description |
|---------|-------------|
| **Shell Execution** | Run `cmd.exe` commands with output capture. Configurable timeout (1–600s). Runs as LocalSystem (elevated). |
| **Process Manager** | List all processes with: PID, name, window title, CPU% (sampled), RAM (MB), network connections, session ID, visible window flag. Kill by PID. |
| **Service Manager** | List all services: name, display name, status, startup type. Actions: start, stop, restart, set startup (auto/manual/disabled). |
| **File Operations** | Browse directories (recursive), read files (base64), write files (base64), transfer files to target's Downloads folder. |

### 📷 Media & Hardware
| Feature | Description |
|---------|-------------|
| **Camera Photo** | Capture single photo from default webcam. Returns base64 JPEG. |
| **Camera Video** | Record video (1–120 seconds). Returns base64 MP4. |
| **Microphone Recording** | Record audio (1–300 seconds). Returns base64 M4A. |
| **Audio Playback** | Upload MP3/WAV (base64), play on target's audio device. Stops on "Stop Audio" command. |

### 🤖 Agent Features
| Feature | Description |
|---------|-------------|
| **Auto-Reconnect** | Exponential backoff reconnection to server. Configurable delay. |
| **Machine Info** | Reports: hostname, model, serial, OS version, username, IP, agent version. |
| **Hot-Reload Config** | Reads `agent.config.json` on startup; no restart needed for config changes. |
| **ACL-Locked Config** | Installer sets permissions: SYSTEM/Admins RW, Users Read-only. |
| **Crash Recovery** | Windows service configured with failure actions: restart on crash (5s, 10s, 30s intervals). |

---

## Server API

### Endpoints
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/health` | — | Server status, agent count, version |
| `GET` | `/api/agents` | — | List online agents (supports `?q=` search) |
| `POST` | `/api/monitor/:machine/screenshot` | Admin pwd | Full-res PNG screenshot |
| `POST` | `/api/monitor/:machine/command` | Admin pwd | Generic command dispatcher |
| `WS` | `/ws/agent` | Token | Agent registration + command/result channel |
| `WS` | `/ws/live/:machine` | Admin pwd | Live screen JPEG stream |

### Commands (via `/api/monitor/:machine/command`)
```json
{ "cmd": "shell_exec", "params": { "command": "dir", "timeoutSec": 30 } }
{ "cmd": "list_processes" }
{ "cmd": "kill_process", "params": { "pid": 1234 } }
{ "cmd": "list_services" }
{ "cmd": "service_action", "params": { "name": "wuauserv", "action": "restart" } }
{ "cmd": "list_files", "params": { "path": "C:\\Users" } }
{ "cmd": "read_file", "params": { "path": "C:\\file.txt" } }
{ "cmd": "write_file", "params": { "path": "C:\\file.txt", "base64": "..." } }
{ "cmd": "input_text", "params": { "text": "hello" } }
{ "cmd": "input_mouse", "params": { "x": 100, "y": 200, "action": "click" } }
{ "cmd": "input_paragraph", "params": { "text": "...", "wpm": 60, "addEnter": true, "async": true } }
{ "cmd": "screen_rotate", "params": { "degrees": 90 } }
{ "cmd": "camera_photo" }
{ "cmd": "camera_video", "params": { "seconds": 10 } }
{ "cmd": "mic_record", "params": { "seconds": 5 } }
{ "cmd": "play_audio", "params": { "base64": "...", "filename": "sound.mp3" } }
{ "cmd": "stop_audio" }
{ "cmd": "transfer_file", "params": { "base64": "...", "filename": "file.txt" } }
{ "cmd": "stop_typing" }
{ "cmd": "stop_all" }
```

---

## Android App Features

| Screen | Capabilities |
|--------|--------------|
| **Dashboard** | Agent list with auto-refresh (5s), search, online/offline status, machine info |
| **Live Screen** | Real-time stream, pinch/zoom/pan, rotate view, fit/fill mode, touch → mouse forwarding |
| **Shell** | Command history, output display, timeout config, admin toggle |
| **Processes** | Sortable list (CPU, RAM, PID), kill button, refresh |
| **Services** | Start/stop/restart, startup type dropdown, status badges |
| **File Manager** | Tree navigation, read/write/transfer, base64 preview |
| **Capture** | Screenshot button, save to gallery |
| **Camera** | Photo capture, video recording with duration picker |
| **Microphone** | Audio recording with duration picker |
| **Audio Player** | Upload + play, stop button, progress |
| **Input Text** | Send text, quick paste |
| **Paragraph Typing** | WPM slider, async start, stop button |
| **Screen Control** | Rotation buttons (0/90/180/270) |
| **Settings** | Themes (8 colors), app icons (7 variants), notification tones (5 + custom), server URL/password |

---

## Deployment

### Server → Render (Free Tier)
1. Push this repo to GitHub
2. Create **Web Service** on Render → Connect repo
3. Render auto-detects `render.yaml`:
   - Build: `cd Server && npm install`
   - Start: `cd Server && node server.js`
   - Health: `/api/health`
4. Add **Environment Variable**: `ADMIN_PASSWORD` = strong random string
5. Deploy → Get URL: `https://your-app.onrender.com`

### Agent → Windows Target
1. Download `RuntimeBroker-Setup-<version>.exe` from Releases
2. Run as **Administrator**
3. Enter server WebSocket URL: `wss://your-app.onrender.com/ws/agent`
4. Enter same `ADMIN_PASSWORD` as token
5. Complete → Service installs and starts automatically

### Android App
- Download `RuntimeBroker.apk` from Releases
- Or build from source: Open `AndroidApp/` in Android Studio → Build APK
- Open app → Enter server URL + password → Connect

---

## Configuration

### Agent (`Agent/agent.config.json`)
```json
{
  "ServerUrl": "wss://your-app.onrender.com/ws/agent",
  "Token": "your-admin-password",
  "ReconnectDelaySec": 5
}
```

### Server (Environment Variables)
| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `PORT` | No | `3001` | HTTP/WS port (Render sets automatically) |
| `ADMIN_PASSWORD` | **Yes** | — | Auth token for all API/WS calls |
| `NODE_ENV` | No | `production` | Runtime mode |

### Android App
Set in-app via **Settings → Server Setup**:
- Server URL (e.g., `https://your-app.onrender.com`)
- Admin password

---

## Security

- **Change `ADMIN_PASSWORD`** — Use a strong random string (32+ chars)
- **Use HTTPS/WSS** — Render provides TLS automatically
- **Agent runs as LocalSystem** — Full machine access; only install on trusted machines
- **Config ACL-locked** — Only SYSTEM/Admins can modify `agent.config.json`
- **No hardcoded secrets** — All credentials via env vars or user input

---

## Project Structure

```
Runtime Broker/
├── Agent/                    # C# .NET 8 Windows Agent
│   ├── Program.cs            # Entry point (service + one-shot modes)
│   ├── RemoteCommands.cs     # All command implementations
│   ├── ScreenCapture.cs      # DPI-aware screen capture
│   ├── PowerShellRunner.cs   # Interactive session launcher (WTS + Scheduled Tasks)
│   ├── AgentService.cs       # Windows Service wrapper
│   ├── AgentClient.cs        # WebSocket client for server
│   ├── AgentConfig.cs        # Config loading
│   ├── EmergencyStop.cs      # Global hotkey handler
│   ├── InteractiveActions.cs # Input actions (text, mouse, paragraph, etc.)
│   ├── DeviceInfo.cs         # Machine info collection
│   ├── RuntimeBroker.csproj  # Project file
│   └── app.manifest          # UAC manifest
├── Server/                   # Node.js WebSocket/HTTP Server
│   ├── server.js             # Main server (Express + ws)
│   ├── package.json          # Dependencies (express, ws)
│   └── dashboard/            # Web dashboard (HTML/JS/CSS)
├── AndroidApp/               # Kotlin Android App
│   ├── app/src/main/...      # Activities, API, Adapters, Resources
│   └── build.gradle.kts      # Gradle config
├── Installer/                # Inno Setup Installer
│   └── installer.iss         # Installer script (admin, service, ACL)
├── scripts/                  # Utility scripts
│   └── relocate-runtimebroker.ps1
├── build-agent.ps1           # Version bump + publish + installer build
├── deploy-agent.ps1          # Deploy to target machine (admin)
├── render.yaml               # Render deployment config
└── README.md                 # This file
```

---

## Building from Source

### Server
```bash
cd Server
npm install
npm start
```

### Agent (Windows, requires .NET 8 SDK)
```powershell
cd Agent
dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
```

### Android App
Open `AndroidApp/` in Android Studio → Build → Build Bundle(s)/APK(s)

### Installer (requires Inno Setup 6)
```powershell
.\build-agent.ps1
# Outputs:
#   Ready to Push\RuntimeBroker.<version>.exe
#   installer-output\RuntimeBroker-Setup-<version>.exe
```

---

## License

MIT License — Free for personal and commercial use.

---

## Links

- **Releases**: [GitHub Releases](https://github.com/4sudosu/windows-remote-toolkit/releases)
- **Issues**: [GitHub Issues](https://github.com/4sudosu/windows-remote-toolkit/issues)
- **Deploy to Render**: [One-click deploy](https://render.com/deploy?repo=https://github.com/4sudosu/windows-remote-toolkit)

---

**Deploy server to Render → Install agent on Windows → Control from phone.**