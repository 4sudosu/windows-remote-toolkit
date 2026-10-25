# Mimo Pro — Premium Remote Control Server

> **Enterprise-grade remote device management** with Apple-style fluid animations, real-time live streaming, and comprehensive device control.

![Mimo Pro](https://img.shields.io/badge/Mimo-Pro-007AFF?style=for-the-badge&logo=apple&logoColor=white)
![Node.js](https://img.shields.io/badge/Node.js-20.x-339933?style=for-the-badge&logo=node.js&logoColor=white)
![Express](https://img.shields.io/badge/Express-4.x-000000?style=for-the-badge&logo=express&logoColor=white)
![WebSocket](https://img.shields.io/badge/WebSocket-Real--time-010101?style=for-the-badge&logo=websocket&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)

---

## ✨ Features

| Category | Capabilities |
|----------|--------------|
| **📱 Device Management** | Real-time device registry, online/offline status, device metadata |
| **🖥️ Live Streaming** | 60fps live screen streaming with touch/mouse forwarding |
| **📸 Screen Capture** | High-quality PNG/JPEG screenshots on demand |
| **💻 Shell Access** | Full administrator shell command execution |
| **📁 File Operations** | Browse, read, write, upload, download remote files |
| **⚙️ Process Control** | List processes, kill processes, monitor CPU/RAM |
| **🔧 Services** | List, start, stop, restart Windows services |
| **📷 Camera** | Remote photo/video capture |
| **🎤 Microphone** | Remote audio recording |
| **🔊 Audio Playback** | Play audio files on remote device |
| **📦 App Install** | Remote application installation |
| **🔄 File Transfer** | Bidirectional file transfer |
| **🎨 10 Premium Themes** | Midnight, Ocean, Aurora, Solar Flare, Cyberpunk, Royal Purple, Forest Calm, Slate Pro, Paper Light, Mint Fresh |
| **🔔 Smart Notifications** | Desktop notifications with custom tones |
| **🛡️ Security** | Admin password auth, device blocking, session persistence |

---

## 🚀 Quick Deploy

### Deploy to Render (Free Tier)

[![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com/deploy?repo=https://github.com/4sudosu/Mimo-PRO)

**Environment Variables Required:**
```env
ADMIN_PASSWORD=your_secure_password
AGENT_TOKEN=optional_agent_token
PORT=set_automatically_by_render
```

### Local Development

```bash
# Clone
git clone https://github.com/4sudosu/Mimo-PRO.git
cd Mimo-PRO

# Install
npm install

# Configure
cp server.config.example.json server.config.json
# Edit server.config.json with your settings

# Run
npm start
# Server: http://localhost:3001
```

---

## 📁 Project Structure

```
Mimo-PRO/
├── server.js              # Main Express + WebSocket server
├── package.json           # Dependencies
├── server.config.json     # Server configuration (not in git)
├── agents.json            # Persisted agent registry
├── blocked_devices.json   # Blocked device registry
├── sessions.json          # Persistent login sessions
├── .gitignore
├── README.md
└── dashboard/
    ├── index.html         # Main dashboard (Apple-style animations)
    ├── login.html         # Premium login page
    ├── style.css          # Fluid design system
    └── app.js             # Dashboard logic with spring animations
```

---

## 🎨 Design Philosophy

Built with **Apple's fluid interface principles**:

- **Response**: Instant feedback on pointer-down
- **Direct Manipulation**: 1:1 tracking with grab offset
- **Interruptibility**: All animations interruptible mid-flight
- **Springs over Timing**: Critically damped (damping: 1.0) for grace
- **Velocity Handoff**: Seamless drag → animation transition
- **Momentum Projection**: Flicks land where gesture intends
- **Spatial Consistency**: Symmetric enter/exit paths
- **Rubber-banding**: Soft boundaries with progressive resistance
- **Translucent Materials**: `backdrop-filter` layers with content scrolling underneath
- **Reduced Motion**: Cross-fades respecting `prefers-reduced-motion`

---

## 🔐 Security

- **Admin Authentication**: Required for dashboard/API access
- **Agent Token**: Optional WebSocket authentication
- **Device Blocking**: Auto-lock after failed attempts
- **Session Persistence**: Survives server restarts (Render free tier)
- **HTTPS Ready**: Works behind Render's TLS termination

---

## 📱 Android App

The server pairs with **Mimo Pro Android App** (`com.mimo.pro`):
- Live screen streaming
- Full remote control
- AI-powered screenshot analysis (Gemini)
- Offline capture storage

---

## 🛠 API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/health` | Server health + agent count |
| `GET` | `/api/version` | Version info for update gate |
| `GET` | `/api/agents` | List connected devices |
| `POST` | `/api/monitor/:machineName/screenshot` | Capture screenshot |
| `POST` | `/api/monitor/:machineName/command` | Execute remote command |
| `GET` | `/api/events` | SSE for live notifications |
| `WS` | `/ws/agent` | Agent WebSocket connection |
| `WS` | `/ws/live/:machineName` | Live screen WebSocket |

---

## 🎯 Commands Reference

```json
{
  "shell_exec": { "command": "string", "timeoutSec": 120, "admin": true },
  "list_processes": {},
  "kill_process": { "pid": 1234 },
  "list_services": {},
  "service_action": { "name": "wuauserv", "action": "restart" },
  "list_files": { "path": "C:\\Users" },
  "read_file": { "path": "C:\\file.txt" },
  "write_file": { "path": "C:\\file.txt", "base64": "..." },
  "input_text": { "text": "Hello" },
  "input_mouse": { "x": 100, "y": 200, "action": "click" },
  "input_paragraph": { "text": "Long text...", "wpm": 50, "async": true },
  "screen_rotate": { "degrees": 90 },
  "camera_photo": {},
  "camera_video": { "seconds": 10 },
  "mic_record": { "seconds": 10 },
  "play_audio": { "base64": "...", "mime": "audio/mpeg" },
  "stop_audio": {},
  "transfer_file": { "path": "C:\\dest.exe", "base64": "..." },
  "stop_typing": {},
  "stop_all": {}
}
```

---

## 🌐 Render Deployment

1. **Fork/Clone** this repo
2. **Connect** to Render → New Web Service
3. **Configure**:
   - Build: `npm install`
   - Start: `npm start`
   - Env Vars: `ADMIN_PASSWORD`, `AGENT_TOKEN`
4. **Deploy** → Served over TLS on port `443` at `https://your-app.onrender.com`

**Free Tier Notes:**
- Sleeps after 15min inactivity
- Sessions persist via `sessions.json`
- WebSocket works with Render's proxy
- Custom domains supported

---

## 📄 License

MIT License