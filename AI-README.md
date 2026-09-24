# AI-README — WindowRemoteToolkit V2 Source Handoff

> Read this file FIRST before working on this project. It maps every module,
> protocol, build command, and known gotcha so you can operate without
> re-discovering the architecture.

## 1. What this is

Remote monitoring + control for Windows, controlled from an Android phone.
Three components talk through a relay server — there is NO direct
phone↔PC connection:

```
AndroidApp (Kotlin)  <--HTTPS/WSS-->  Server (Node.js)  <--WSS-->  Agent (C# .NET 8, Windows service)
```

Repos (all under GitHub user `4sudosu`):
| Repo | Visibility | Contents |
|---|---|---|
| `windows-remote-toolkit` | public | dev monorepo (this source) |
| `WindowRemoteToolkitV2` | public | deploy-only: `Server/` + README + `render.yaml` + Dockerfile, NO source |
| `WindowRemoteToolkit-V2-Source` | private | full source mirror + this handoff doc |

## 2. Directory map

```
.
├── Agent/                  # C# .NET 8 Windows agent v1.0.0.2 (WinExe, self-contained single file)
│   ├── RuntimeBroker.csproj  # OutputType WinExe, net8.0-windows.
│   │                         #   Packages: System.ServiceProcess.ServiceController, NAudio, DirectShowLib
│   ├── Program.cs            # One-shot modes: --capture <out> [fmt q w] | --diag | --rotate <deg>
│   │                         #   --play <file> | --input-mouse x y act n | --input-text <file>
│   │                         #   --input-paragraph <wpm> <enter01> <file> | --install/--uninstall [--name X]
│   │                         #   --configure-service [--name X] | --write-config [--url|--host/--port] [--token]
│   │                         #   --service (24x7; console REPL when UserInteractive)
│   ├── AgentClient.cs        # WS client: register + `cmd` dispatch, result sender.
│   │                         #   Input/audio/rotate/capture go through PowerShellRunner session-hop.
│   ├── RemoteCommands.cs     # shell/processes/services/files/rotate/audio/camera/mic
│   ├── InteractiveActions.cs # SendInput mouse/keyboard/paragraph. MUST run in user session.
│   ├── ScreenCapture.cs      # GDI + DwmBypass overlay; PNG or JPEG(q, maxWidth)
│   ├── DwmBypassCapture.cs   # WDA_MONITOR/WDA_EXCLUDEFROMCAPTURE bypass via DwmGetDxSharedSurface+D3D11
│   ├── PowerShellRunner.cs   # Hidden schtasks session-hop (capture/rotate/play/input/paragraph)
│   │                         #   + fixed-name Audio/Input tasks with End/Delete for stop.
│   │                         #   Despite the name, NO powershell.exe is spawned for capture.
│   ├── MediaCapture.cs       # Camera stills (DirectShow SampleGrabber→JPEG), video (AVI),
│   │                         #   mic (NAudio WAV; writer disposed BEFORE bytes are read)
│   ├── AgentService.cs       # ServiceBase: FAST OnStart (never blocks — avoids SCM 1920),
│   │                         #   config hot-reload watcher, EmergencyStop start
│   ├── AgentConfig.cs        # agent.config.json: ServerUrl, Token, ReconnectDelaySec, KeepAliveSec
│   ├── DeviceInfo.cs         # Hostname/model/serial/user/IP/OS (collected on worker, never OnStart)
│   ├── EmergencyStop.cs      # Ctrl+Shift+X hotkey (STA thread + Application.Run pump)
│   ├── AgentVersionInfo.cs   # Assembly version reporter
│   └── app.manifest          # asInvoker
├── Server/                   # Node.js relay v2.2.0 (Express + ws). Deployed to Render.
│   ├── server.js             # HTTP API + /ws/agent hub + /ws/live/:machine + SSE /api/events
│   │                         # + /api/version. Sessions persist to sessions.json (7d).
│   │                         # Agent token enforced ONLY if AGENT_TOKEN set (else open).
│   │                         # Heartbeat: ws ping only (no app-level keepalive handling).
│   ├── package.json          # express, ws (type: module)
│   └── dashboard/            # Web UI: index.html, app.js, login.html, style.css
├── AndroidApp/               # Kotlin app, package com.runtimebroker.app, v4.3 (code 11)
│   ├── build.gradle.kts      # compileSdk/target 34, minSdk 24, Java/Kotlin 17, viewBinding + buildConfig
│   │                         # build types: release, debug, noupdate (UPDATE_GATE_ENABLED=false)
│   ├── gradle/libs.versions.toml  # AGP 8.5.2, Kotlin 2.0.20, okhttp 4.12.0, coroutines, recyclerview, photoview
│   ├── local.properties      # GITIGNORED — sdk.dir=<local Android SDK>
│   └── app/src/main/
│       ├── AndroidManifest.xml  # activities, NodeServerService + AgentEventService (dataSync),
│       │                        # FileProvider, perms incl. REQUEST_INSTALL_PACKAGES + media storage
│       ├── java/com/runtimebroker/app/
│       │   ├── LauncherActivity.kt   # entry; mandatory update gate unless !UPDATE_GATE_ENABLED
│       │   ├── UpdateChecker.kt      # releases/latest check vs 4sudosu/WindowRemoteToolkitV2
│       │   ├── UpdateActivity.kt     # DownloadManager APK install, back blocked
│       │   ├── MainActivity.kt       # device list polling (Prefs.refreshSecs 3/5/10s), SSE start
│       │   ├── AgentEventService.kt  # foreground SSE /api/events → connect notifications
│       │   ├── CaptureActivity.kt    # screenshot + temp album (DCIM/RuntimeBroker, grid, batch share, clear)
│       │   ├── TempAlbumAdapter.kt   # album grid adapter (AlbumItem)
│       │   ├── LiveScreenActivity.kt # WS live frames + touch forwarding
│       │   ├── ConnectActivity.kt    # server URL + password, device-block states
│       │   ├── *_*Activity.kt        # Shell/Processes/Services/Files/Camera/Mic/Audio/inputs/Media/Settings…
│       │   ├── api/RuntimeBrokerApi.kt  # all HTTP (X-Admin-Password), live WS, timeoutSec scaling
│       │   ├── api/Models.kt         # AgentInfo, CaptureResult, CommandResult(data: Any?)
│       │   ├── Prefs.kt              # SharedPreferences incl. refresh_secs
│       │   ├── Notifications.kt      # channels + postNewAgent + typing progress
│       │   ├── NodeServerService.kt  # embedded phone server (nodejs-mobile); its agent-WS
│       │   │                         # token STRICTLY equals the admin password (4001 otherwise)
│       │   └── ThemeManager.kt …     # themes, tones, icons, permissions helpers
│       ├── assets/nodejs-project/    # embedded server bundle (server.js + dashboard + package.json)
│       │                             # + node_modules (GITIGNORED, npm install)
│       ├── res/layout|values|…       # activity_*.xml (viewBinding), strings.xml, themes, tones (res/raw)
│       └── libnode/                  # nodejs-mobile engine: ONLY README.txt + CMakeLists hook committed.
│                                     # .so binaries + headers are DOWNLOADED per README (never commit, ~186MB).
│                                     # Without them CMake links a stub (connect-mode still works).
├── Installer/
│   ├── installer.iss         # Inno Setup EXE: wizard (IP/port/optional token/service name), config+ACL,
│   │                         #   LocalSystem service + failure actions + Running-state wait, stale-service
│   │                         #   + uninstall cleanup. Build: ISCC.exe installer.iss
│   │                         #   → installer-output\RuntimeBroker-Setup-1.0.0.2.exe
│   ├── RuntimeBroker.wxs     # WiX MSI equivalent (custom UI dialog, deferred CAs via EXE helpers)
│   ├── Bundle.wxs            # WiX Burn Setup-EXE wrapper around the MSI
│   └── placeholder.config    # seed config replaced at install time
├── render.yaml               # Render blueprint (rootDir Server, health /api/health, ADMIN_PASSWORD only;
│                             #   PORT is auto-injected, UPDATE_REPO defaults in code)
├── Dockerfile                # alt deploy (node:18-alpine, copies Server/)
├── .github/workflows/        # keep-alive cron etc.
└── docs/screenshots/         # store screenshots referenced by READMEs

## 3. Protocols (exact shapes — do not drift)

Agent → server WS (`/ws/agent?token=`):
- register: `{type:'register', machineName, hostname, model, serial, username, user, os, ip, version}`
- result: `{type:'result', taskId, success, output, data, error, exitCode}`
- server→agent: `{type:'cmd', taskId, cmd, params}` ; screenshot uses cmd=`capture_screenshot`
  with params `{format:'png'|'jpeg', quality, maxWidth}` (live frames always jpeg).

Command names (server allowlist — agent `AgentClient.HandleCmdAsync` must cover ALL):
`shell_exec, list_processes, kill_process, list_services, service_action, list_files,
read_file, write_file, input_text, input_mouse, input_paragraph, screen_rotate,
camera_photo, camera_video, mic_record, play_audio, stop_audio, transfer_file,
stop_typing, stop_all` (+ `capture_screenshot`).

List-result data shapes (Android casts `data` to JSONArray — ALWAYS arrays, never objects):
- processes: `[{pid,name,title,memMB,cpu(double),connections,session,hasWindow}]`
- services: `[{name,displayName,status,startType}]` (startType ∈ auto|manual|disabled)
- files: `[{name,path,isDir,size,modified}]`
- read_file/transfer output: base64 in `output`. screenshot: base64 PNG/JPEG in `output`.

Server HTTP (auth: `X-Admin-Password` header or `wsm_auth` cookie; sessions persist
to `sessions.json`, 7d TTL; login locks after 3 bad attempts until restart;
device 3-strike block via blocked_devices.json):
- public: `GET /api/health`, `GET /api/version` (`{version, repo, updateUrl, agents}`)
- authed: `GET /api/agents[?q=]`, `POST /api/monitor/:m/screenshot`,
  `POST /api/monitor/:m/command` (`{cmd, params}`; `params.timeoutSec` sets server timeout),
  `GET /api/events` (SSE `agent-online`/`agent-offline`), `/api/config`, `/api/config/status`,
  `/api/admin/blocked-devices`, `/api/admin/unlock-device`
- WS: `/ws/agent` (token enforced ONLY if server `AGENT_TOKEN` set),
  `/ws/live/:machine?token=&interval=` (live token check DISABLED upstream).

Timeouts: server default 35s; app passes `timeoutSec` (camera 120, video/mic seconds+90,
paragraph computed, shell 30, transfer size-scaled, services 60) and sets HTTP call
timeout = timeoutSec+20. Agent service_action waits ≤30s per operation.

## 4. The two session rules (cause of most bugs — respect them)

1. **Service (Session 0) can NEVER touch the user desktop.** Screenshots, input,
   rotate, audio playback MUST hop via hidden schtasks running the agent EXE itself
   (`--capture/--rotate/--play/--input-*`) as the interactive user
   (`LogonType=InteractiveToken`, WinExe = zero windows). Camera/mic/shell/processes/
   services/files are device-level and run fine in-service.
2. **OnStart must return in milliseconds** (SCM error 1920 on slow start).
   Inventory/connect run on workers (`AgentClient.RunAsync`).

## 5. Build commands

```powershell
# Agent (needs .NET 8 SDK; single-file EXEs trip Defender heuristics — build with an exclusion)
dotnet publish Agent\RuntimeBroker.csproj -c Release -o publish-single
# → publish-single\RuntimeBroker.exe (self-contained single file, v1.0.0.2)
RuntimeBroker.exe --diag                      # verify protected capture on a target
RuntimeBroker.exe --install --name Foo        # manual service install (admin)

# Inno installer (needs Inno Setup 6 ISCC.exe)
& "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe" Installer\installer.iss
# → installer-output\RuntimeBroker-Setup-1.0.0.2.exe
# Silent: ...exe /VERYSILENT /SERVERIP=.. /SERVERPORT=.. /SERVERTOKEN=.. /SERVICENAME=..

# WiX MSI + Burn EXE (needs `dotnet tool install --global wix` + UI/BootstrapperApplications extensions)
wix build Installer\RuntimeBroker.wxs -ext WixToolset.UI.wixext -arch x64 -o installer-output\RuntimeBroker-Setup-1.0.0.2.msi
wix build Installer\Bundle.wxs -ext WixToolset.BootstrapperApplications.wixext -arch x64 -o installer-output\RuntimeBroker-Setup-1.0.0.2.exe

# Server (needs Node 18+)
cd Server; npm install
$env:ADMIN_PASSWORD='...'; $env:PORT='3001'; node server.js

# Android (needs JDK 17 + Android SDK 34 + NDK r24/CMake for embedded engine)
#   1. AndroidApp/local.properties: sdk.dir=<SDK>  (gitignored)
#   2. npm install in app/src/main/assets/nodejs-project/
#   3. libnode .so per app/libnode/README.txt (or stub build without hosting)
.\gradlew.bat assembleDebug        # → app/build/outputs/apk/debug/RuntimeBroker4.3.apk
.\gradlew.bat assembleNoupdate     # → .../noupdate/RuntimeBroker4.3-noupdate.apk (gate OFF)
adb install -r <apk>               # release-signed↔debug-signed clash needs `adb uninstall` first
```

## 6. LAN vs Render topologies (known-good demo)

- LAN: phone hosts (`Run on 0.0.0.0`, port 4777, password P). Agent config:
  `{"ServerUrl":"ws://<phone-lan-ip>:4777/ws/agent","Token":"P"}` —
  `ws://` (no TLS locally), phone-server token STRICTLY equals the password.
  App → `http://127.0.0.1:4777` + P. Beware lookalike IPs (10.225.x vs 10.255.x bite before).
- Render: agent `wss://<app>.onrender.com/ws/agent`, token empty unless server
  `AGENT_TOKEN` set. App → `https://<app>.onrender.com` + ADMIN_PASSWORD.
  Free tier sleeps (cold start ~60s; 3 login strikes lock until restart; sessions persist).

## 7. Conventions for AI contributors

- C#: nullable enabled, WinExe (never add Console apps), every spawned process MUST set
  `UseShellExecute=false, CreateNoWindow=true`; never break `AgentClient` cmd names above.
- Kotlin: activities extend `BaseActivity` (theme), use `Activity*Binding`, strings in
  `strings.xml` (no hardcoded UI text), coroutines on `lifecycleScope`, API via `RuntimeBrokerApi`.
- Server: ESM (`type: module`), no new deps without need (express+ws only for deploy).
- Secrets: NEVER commit `agent.config.json`, `local.properties`, `sessions.json`,
  `blocked_devices.json`, `agents.json`, keystores, passwords (all gitignored).
- Versioning: agent `Agent/RuntimeBroker.csproj` (1.0.0.2), app
  `AndroidApp/app/build.gradle.kts` (4.3/code 11), server `SERVER_VERSION` in
  `Server/server.js` (2.2.0). Update gate compares app versionName vs releases/latest.
- Releases: public binaries → `WindowRemoteToolkitV2` v-tags; source repos hold source only.

## 8. Known limitations (by design)

- Protected capture: client-area only (no title bars); minimized + HDCP/DRM stay black.
- Live frames repeat full captures (not 60fps video).
- Camera video records AVI; mic records WAV (both base64).
- `TransferFile` as LocalSystem lands in the system profile Downloads.
- Unsigned binaries trip Defender/SmartScreen — allowlist on targets.
