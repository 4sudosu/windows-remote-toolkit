import { WebSocketServer } from 'ws';
import express from 'express';
import path from 'node:path';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 3001);
const SERVER_VERSION = '2.2.0';
// GitHub repo the Android update gate checks (releases/latest). Override via env.
const UPDATE_REPO = process.env.UPDATE_REPO || '4sudosu/WindowRemoteToolkitV2';

const APP_DIR = __dirname;
const AGENTS_FILE = path.join(APP_DIR, 'agents.json');
const CONFIG_FILE = path.join(APP_DIR, 'server.config.json');
const BLOCKED_DEVICES_FILE = path.join(APP_DIR, 'blocked_devices.json');
const MAX_LOGIN_ATTEMPTS = 3;
const MAX_DEVICE_ATTEMPTS = 3;
const SESSION_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const SESSION_COOKIE = 'wsm_auth';
const SESSIONS_FILE = path.join(APP_DIR, 'sessions.json');
// Sessions persist to disk so logins survive Render free-tier sleeps/restarts.
const sessions = new Map();
function loadSessions() {
  try {
    const saved = JSON.parse(fs.readFileSync(SESSIONS_FILE, 'utf8'));
    if (saved && typeof saved === 'object') {
      const now = Date.now();
      for (const [token, expiry] of Object.entries(saved)) {
        if (typeof token === 'string' && Number(expiry) > now) sessions.set(token, Number(expiry));
      }
    }
  } catch { /* first boot — no file yet */ }
}
let sessionsSaveTimer = null;
function saveSessions() {
  try {
    fs.writeFileSync(SESSIONS_FILE, JSON.stringify(Object.fromEntries(sessions)));
  } catch (e) { console.warn('Could not write sessions.json:', e.message); }
}
function saveSessionsSoon() {
  if (sessionsSaveTimer) return;
  sessionsSaveTimer = setTimeout(() => { sessionsSaveTimer = null; saveSessions(); }, 1000);
}
loadSessions();
setInterval(() => {
  let changed = false;
  const now = Date.now();
  for (const [token, expiry] of sessions) {
    if (expiry <= now) { sessions.delete(token); changed = true; }
  }
  if (changed) saveSessions();
}, 60 * 60 * 1000);
let failedLoginCount = 0;
let loginLocked = false;

function loadAdminPassword() {
  if (process.env.ADMIN_PASSWORD) return process.env.ADMIN_PASSWORD;
  try {
    const config = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8'));
    return String(config.adminPassword || config.password || '');
  } catch {
    return '';
  }
}

const ADMIN_PASSWORD = loadAdminPassword();
if (!ADMIN_PASSWORD) console.warn('ADMIN_PASSWORD is not configured; authenticated connections will be rejected.');

// Optional agent token for WebSocket authentication (separate from admin
// password). Unset/empty = any agent may connect (dashboard/API still need
// ADMIN_PASSWORD). Set AGENT_TOKEN to require it.
function loadAgentToken() {
  if (process.env.AGENT_TOKEN !== undefined) return String(process.env.AGENT_TOKEN);
  try {
    const config = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8'));
    if (config.agentToken !== undefined) return String(config.agentToken);
    return null;
  } catch {
    return null;
  }
}

// Token is enforced ONLY when explicitly set (and non-empty). Otherwise any
// agent may connect — ADMIN_PASSWORD still guards the dashboard/API.
let AGENT_TOKEN = loadAgentToken() || '';
if (!AGENT_TOKEN) console.warn('AGENT token validation is DISABLED — agents can connect without a token.');

// ── helpers ──────────────────────────────────────────────────────────────
const makeId = () => crypto.randomBytes(8).toString('hex');

function readJsonFile(file, fallback) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); }
  catch { return fallback; }
}

function writeJsonFile(file, value) {
  try { fs.writeFileSync(file, JSON.stringify(value, null, 2)); }
  catch (e) { console.warn(`Could not write ${path.basename(file)}:`, e.message); }
}

function readBlockedDevices() {
  return readJsonFile(BLOCKED_DEVICES_FILE, {});
}

function isDeviceBlocked(deviceId) {
  return Boolean(deviceId && readBlockedDevices()[deviceId]?.locked);
}

function recordFailedAttempt(deviceId) {
  if (!deviceId) return { attempts: 0, locked: false };
  const blocked = readBlockedDevices();
  const current = blocked[deviceId] || { attempts: 0, locked: false };
  current.attempts += 1;
  if (current.attempts >= MAX_DEVICE_ATTEMPTS) {
    current.locked = true;
    current.lockedAt = new Date().toISOString();
  }
  blocked[deviceId] = current;
  writeJsonFile(BLOCKED_DEVICES_FILE, blocked);
  return current;
}

function unlockDevice(deviceId) {
  const blocked = readBlockedDevices();
  delete blocked[deviceId];
  writeJsonFile(BLOCKED_DEVICES_FILE, blocked);
}

function parseCookies(req) {
  return Object.fromEntries(String(req.headers.cookie || '').split(';').map(part => {
    const [key, ...value] = part.trim().split('=');
    return [key, decodeURIComponent(value.join('='))];
  }).filter(([key]) => key));
}

function signSession() {
  const token = crypto.randomBytes(32).toString('hex');
  sessions.set(token, Date.now() + SESSION_TTL_MS);
  saveSessionsSoon();
  return token;
}

function isAuthed(req) {
  const headerPassword = String(req.headers['x-admin-password'] || '');
  if (ADMIN_PASSWORD && headerPassword === ADMIN_PASSWORD) return true;
  const token = parseCookies(req)[SESSION_COOKIE];
  const expiry = sessions.get(token);
  if (!expiry || expiry <= Date.now()) {
    if (token) sessions.delete(token);
    return false;
  }
  return true;
}

function requireAuth(req, res, next) {
  if (isAuthed(req)) return next();
  if (req.path.startsWith('/api/')) return res.status(401).json({ success: false, error: 'Authentication required' });
  return res.redirect('/login');
}

function readAgentsFile() {
  try { return JSON.parse(fs.readFileSync(AGENTS_FILE, 'utf8')); }
  catch { return []; }
}
function saveAgentsFile(agents) {
  try { fs.writeFileSync(AGENTS_FILE, JSON.stringify(agents, null, 2)); }
  catch (e) { console.warn('Could not write agents.json:', e.message); }
}

// ── Agent registry ───────────────────────────────────────────────────────
const agents = new Map(); // machineName -> { ws, info, lastSeen }

function upsertRegistry(info) {
  const list = readAgentsFile();
  const idx = list.findIndex(a => (a.machineName || '').toLowerCase() === (info.machineName || '').toLowerCase());
  const record = { ...info, lastSeen: new Date().toISOString() };
  if (idx >= 0) list[idx] = record;
  else list.push(record);
  saveAgentsFile(list);
}

// ── HTTP app ─────────────────────────────────────────────────────────────
const app = express();
app.use(express.json({ limit: '200mb' }));
app.get('/login', (_req, res) => res.sendFile(path.join(APP_DIR, 'dashboard', 'login.html')));
app.get('/style.css', (_req, res) => res.sendFile(path.join(APP_DIR, 'dashboard', 'style.css')));
app.post('/api/login', (req, res) => {
  if (loginLocked) return res.status(423).json({ success: false, error: 'Login locked until server restart' });
  if (!ADMIN_PASSWORD || String(req.body?.password || '') !== ADMIN_PASSWORD) {
    failedLoginCount += 1;
    loginLocked = failedLoginCount >= MAX_LOGIN_ATTEMPTS;
    return res.status(loginLocked ? 423 : 403).json({
      success: false,
      error: loginLocked ? 'Login locked until server restart' : 'Invalid admin password',
      attemptsLeft: Math.max(0, MAX_LOGIN_ATTEMPTS - failedLoginCount)
    });
  }
  failedLoginCount = 0;
  const token = signSession();
  const secure = req.secure || req.headers['x-forwarded-proto'] === 'https';
  res.setHeader('Set-Cookie', `${SESSION_COOKIE}=${token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${SESSION_TTL_MS / 1000}${secure ? '; Secure' : ''}`);
  res.json({ success: true });
});
app.post('/api/logout', (req, res) => {
  const token = parseCookies(req)[SESSION_COOKIE];
  if (token && sessions.delete(token)) saveSessionsSoon();
  res.setHeader('Set-Cookie', `${SESSION_COOKIE}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0`);
  res.json({ success: true });
});
app.post('/api/config', (req, res) => {
  const deviceId = String(req.headers['x-device-id'] || req.body?.deviceId || '');
  const password = String(req.headers['x-admin-password'] || req.body?.password || '');
  if (!deviceId) return res.status(400).json({ success: false, error: 'Missing device ID' });
  if (isDeviceBlocked(deviceId)) return res.status(423).json({ success: false, error: 'Device blocked', deviceBlocked: true, unlockAt: 0 });
  if (!ADMIN_PASSWORD || password !== ADMIN_PASSWORD) {
    const state = recordFailedAttempt(deviceId);
    return res.status(state.locked ? 423 : 403).json({
      success: false,
      error: state.locked ? 'Device blocked' : 'Invalid admin password',
      authError: true,
      deviceBlocked: state.locked,
      attemptsLeft: Math.max(0, MAX_DEVICE_ATTEMPTS - state.attempts)
    });
  }
  unlockDevice(deviceId);
  res.json({ success: true, authError: false, deviceBlocked: false });
});
app.post(['/api/device-status', '/api/config/status'], (req, res) => {
  const deviceId = String(req.headers['x-device-id'] || req.body?.deviceId || '');
  if (!deviceId) return res.status(400).json({ success: false, error: 'Missing device ID' });
  if (isDeviceBlocked(deviceId)) return res.status(403).json({ success: false, error: 'Device is still blocked', deviceBlocked: true });
  res.json({ success: true, message: 'Device is allowed', deviceBlocked: false });
});
app.get('/api/health', (_req, res) => {
  res.json({ ok: true, agents: agents.size, version: SERVER_VERSION });
});
// Public version info for the Android update gate + dashboards (no secrets).
app.get('/api/version', (_req, res) => {
  res.json({
    version: SERVER_VERSION,
    repo: UPDATE_REPO,
    updateUrl: `https://github.com/${UPDATE_REPO}/releases/latest`,
    agents: agents.size
  });
});
app.use(requireAuth);

// ── SSE events (live device notifications) ─────────────────────────────
// Authed via requireAuth above (cookie session or X-Admin-Password header).
// The Android app streams this for instant connect notifications.
const sseClients = new Set();
function broadcastSSE(event, data) {
  const payload = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of sseClients) {
    try { res.write(payload); } catch { sseClients.delete(res); }
  }
}
app.get('/api/events', (req, res) => {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no'
  });
  res.write('retry: 3000\n\n');
  sseClients.add(res);
  const keep = setInterval(() => { try { res.write(': ping\n\n'); } catch { /* noop */ } }, 25000);
  req.on('close', () => { clearInterval(keep); sseClients.delete(res); });
});
app.use(express.static(path.join(APP_DIR, 'dashboard'), { index: false }));
app.get('/', (_req, res) => res.sendFile(path.join(APP_DIR, 'dashboard', 'index.html')));

const server = app.listen(PORT, '0.0.0.0', () => {
  console.log(`RuntimeBroker server running at: http://0.0.0.0:${PORT}`);
});

// ── WebSocket hub (agents) ───────────────────────────────────────────────
const wss = new WebSocketServer({ noServer: true });
const liveWss = new WebSocketServer({ noServer: true });
const pending = new Map(); // taskId -> { resolve }

server.on('upgrade', (req, socket, head) => {
  let url;
  try { url = new URL(req.url, 'http://localhost'); } catch { socket.destroy(); return; }
  if (url.pathname === '/ws/agent') {
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
  } else {
    const m = /^\/ws\/live\/([^/?]+)/.exec(url.pathname);
    if (m) liveWss.handleUpgrade(req, socket, head, (ws) => liveWss.emit('connection', ws, req, m[1], url));
    else socket.destroy();
  }
});

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://localhost');
  const token = url.searchParams.get('token') || '';
  // Token enforced only when AGENT_TOKEN is explicitly set; otherwise open.
  if (AGENT_TOKEN && token !== AGENT_TOKEN) {
    ws.close(4001, 'Unauthorized');
    return;
  }

  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (data) => {
    let msg;
    try { msg = JSON.parse(data.toString()); } catch { return; }
    handleMessage(ws, msg);
  });

  ws.on('close', () => {
    failPendingFor(ws);
    const machineName = ws.machineName;
    if (machineName) {
      agents.delete(machineName);
      try { broadcastSSE('agent-offline', { machineName }); } catch { /* noop */ }
      console.log(`[AGENT OFFLINE] ${machineName}`);
    }
  });

  ws.on('error', () => {});
  ws.send(JSON.stringify({ type: 'hello', message: 'connected' }));
});

function handleMessage(ws, msg) {
  switch (msg.type) {
    case 'register': {
      const machineName = (msg.machineName || '').toLowerCase();
      ws.machineName = machineName;
      const info = {
        machineName,
        hostname: msg.hostname || machineName,
        model: msg.model || '',
        serial: msg.serial || '',
        username: msg.username || msg.user || '',
        os: msg.os || '',
        user: msg.user || '',
        version: msg.version || '',
        ip: msg.ip || ''
      };
      ws.info = info;
      agents.set(machineName, { ws, info, lastSeen: Date.now() });
      upsertRegistry(info);
      try {
        broadcastSSE('agent-online', {
          machineName,
          hostname: info.hostname,
          model: info.model,
          ip: info.ip,
          serial: info.serial
        });
      } catch { /* noop */ }
      console.log(`[AGENT ONLINE] ${machineName} | ${info.model} | ${info.username} | ${info.ip}`);
      ws.send(JSON.stringify({ type: 'registered', machineName }));
      break;
    }
    case 'result': {
      const task = pending.get(msg.taskId);
      if (task) {
        pending.delete(msg.taskId);
        task.resolve(msg);
      }
      break;
    }
  }
}

// ── Task dispatch ────────────────────────────────────────────────────────
function sendTask(agent, cmd, params, timeoutMs = 35000) {
  return new Promise((resolve) => {
    const taskId = makeId();
    const timer = setTimeout(() => {
      if (pending.has(taskId)) {
        pending.delete(taskId);
        resolve({ success: false, error: 'TIMEOUT' });
      }
    }, timeoutMs);
    pending.set(taskId, {
      agentWs: agent.ws,
      resolve: (r) => { clearTimeout(timer); resolve(r); }
    });
    try { agent.ws.send(JSON.stringify({ type: 'cmd', taskId, cmd, params: params || {} })); }
    catch { pending.delete(taskId); resolve({ success: false, error: 'Agent offline' }); }
  });
}

// If an agent disconnects, immediately fail every task waiting on it so
// callers get an error instead of hanging until the full timeout.
function failPendingFor(ws) {
  for (const [taskId, task] of pending) {
    if (task.agentWs === ws) {
      pending.delete(taskId);
      task.resolve({ success: false, error: 'Agent offline' });
    }
  }
}

function cancelPendingFor(ws) {
  for (const [taskId, task] of pending) {
    if (task.agentWs === ws) {
      pending.delete(taskId);
      task.resolve({ success: false, error: 'Emergency stop requested' });
    }
  }
}

function stopLiveFor(machineName) {
  for (const [viewer, state] of liveViewers) {
    if (state.machineName !== machineName) continue;
    if (state.timer) clearInterval(state.timer);
    liveViewers.delete(viewer);
    try { viewer.close(1000, 'Emergency stop'); } catch {}
  }
}

function findAgent(req, res) {
  const agent = agents.get((req.params.machineName || '').toLowerCase());
  if (!agent || agent.ws.readyState !== agent.ws.OPEN) {
    res.status(409).json({ success: false, error: 'Agent offline' });
    return null;
  }
  return agent;
}

// ── HTTP API ─────────────────────────────────────────────────────────────
app.get('/api/agents', (req, res) => {
  const q = String(req.query.q || '').toLowerCase();
  const list = [];
  for (const [machineName, agent] of agents) {
    if (agent.ws.readyState !== agent.ws.OPEN) continue;
    const { info } = agent;
    if (q && ![info.hostname, machineName, info.serial, info.ip, info.model]
      .some(v => String(v || '').toLowerCase().includes(q))) continue;
    list.push({ ...info, online: true, lastSeen: new Date().toISOString() });
  }
  res.json(list);
});

app.post('/api/monitor/:machineName/screenshot', async (req, res) => {
  const agent = findAgent(req, res);
  if (!agent) return;
  try {
    const result = await sendTask(agent, 'capture_screenshot', {}, 35000);
    if (!result.success) {
      const err = result.error === 'TIMEOUT' ? 'Capture timed out' : (result.error || 'Capture failed');
      return res.status(500).json({ success: false, error: err });
    }
    console.log(`[SCREENSHOT] ${req.params.machineName}`);
    res.json({ success: true, image: result.output, at: new Date().toISOString() });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

// Generic command endpoint — all remote-control features go through here.
app.post('/api/monitor/:machineName/command', async (req, res) => {
  const agent = findAgent(req, res);
  if (!agent) return;

  const cmd = String(req.body.cmd || '');
  const params = req.body.params || {};
  const timeoutMs = Number(params.timeoutSec || 0) * 1000 || 35000;
  if (!cmd) return res.status(400).json({ success: false, error: 'Missing cmd' });

  const allowed = new Set([
    'shell_exec', 'list_processes', 'kill_process', 'list_services', 'service_action',
    'list_files', 'read_file', 'write_file', 'input_text', 'input_mouse',
    'input_paragraph', 'screen_rotate',
    'camera_photo', 'camera_video', 'mic_record',
    'play_audio', 'stop_audio', 'transfer_file', 'stop_typing', 'stop_all'
  ]);
  if (!allowed.has(cmd)) return res.status(400).json({ success: false, error: `Unknown command: ${cmd}` });

  if (cmd === 'stop_all') {
    cancelPendingFor(agent.ws);
    stopLiveFor(req.params.machineName.toLowerCase());
    try {
      agent.ws.send(JSON.stringify({ type: 'cmd', taskId: makeId(), cmd, params: {} }));
      console.log(`[STOP ALL] ${req.params.machineName}`);
      return res.json({ success: true, output: 'All automation stopped; input released' });
    } catch {
      return res.status(409).json({ success: false, error: 'Agent offline' });
    }
  }

  // Fire-and-forget for long-running commands (e.g. paragraph typing). The
  // phone watches progress on the live screen instead of waiting.
  if (params.async === true) {
    const { async: _drop, ...rest } = params;
    agent.ws.send(JSON.stringify({ type: 'cmd', taskId: makeId(), cmd, params: rest }));
    console.log(`[CMD] ${req.params.machineName} -> ${cmd} (async started)`);
    return res.json({ success: true, output: 'Started', async: true });
  }

  try {
    const result = await sendTask(agent, cmd, params, timeoutMs);
    console.log(`[CMD] ${req.params.machineName} -> ${cmd} (${result.success ? 'ok' : 'fail'})`);
    if (!result.success) {
      const err = result.error === 'TIMEOUT' ? 'Command timed out' : (result.error || 'Command failed');
      return res.status(500).json({ success: false, error: err });
    }
    res.json({
      success: true,
      output: result.output || '',
      data: result.data || null,
      exitCode: result.exitCode ?? 0
    });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

app.get('/api/admin/blocked-devices', (_req, res) => {
  res.json(readBlockedDevices());
});

app.post('/api/admin/unlock-device', (req, res) => {
  const deviceId = String(req.body?.deviceId || '');
  if (!deviceId) return res.status(400).json({ success: false, error: 'Missing device ID' });
  unlockDevice(deviceId);
  res.json({ success: true });
});

// ── Live screen WebSocket (phone → server → agent) ──────────────────────
const liveViewers = new Map(); // viewerWs -> { machineName, timer, lastAt }

liveWss.on('connection', (ws, req, machineName, url) => {
  // Token check removed — phone can connect without ?token= in URL
  // if (token !== ADMIN_PASSWORD) { ws.close(4001, 'Unauthorized'); return; }
  const token = url.searchParams.get('token') || '';
  const agent = agents.get((machineName || '').toLowerCase());
  if (!agent || agent.ws.readyState !== agent.ws.OPEN) { ws.close(4004, 'Agent offline'); return; }

  liveViewers.set(ws, { machineName: machineName.toLowerCase(), timer: null });
  console.log(`[LIVE] ${machineName} viewer connected`);

  const frameInterval = Math.max(250, Math.min(3000, Number(url.searchParams.get('interval') || 500)));
  // Small fast JPEG frames keep the live stream smooth; /screenshot stays PNG.
  const liveParams = { format: 'jpeg', quality: 72, maxWidth: 1600 };
  const timer = setInterval(async () => {
    const viewer = liveViewers.get(ws);
    if (!viewer || ws.readyState !== ws.OPEN) { clearInterval(timer); return; }
    try {
      const result = await sendTask(agent, 'capture_screenshot', liveParams, 12000);
      if (ws.readyState !== ws.OPEN) return;
      if (!result.success) {
        ws.send(JSON.stringify({ type: 'error', error: result.error === 'TIMEOUT' ? 'Capture timed out' : (result.error || 'Capture failed') }));
        clearInterval(timer);
        return;
      }
      ws.send(JSON.stringify({ type: 'frame', image: result.output, at: new Date().toISOString() }));
    } catch { clearInterval(timer); }
  }, frameInterval);
  liveViewers.get(ws).timer = timer;

  // Touch / cursor events from the phone → forward to the agent (fire and forget).
  ws.on('message', (data) => {
    if (ws.readyState !== ws.OPEN) return;
    let msg;
    try { msg = JSON.parse(data.toString()); } catch { return; }
    if (msg.type === 'mouse') {
      sendTask(agent, 'input_mouse', {
        x: Number(msg.x), y: Number(msg.y), action: String(msg.action || 'move')
      }, 5000).catch(() => {});
    }
  });

  ws.on('close', () => {
    const viewer = liveViewers.get(ws);
    if (viewer && viewer.timer) clearInterval(viewer.timer);
    liveViewers.delete(ws);
    console.log(`[LIVE] ${machineName} viewer disconnected`);
  });

  ws.on('error', () => {});
});

// ── heartbeat ────────────────────────────────────────────────────────────
const interval = setInterval(() => {
  for (const [machineName, agent] of agents) {
    if (!agent.ws.isAlive) {
      agent.ws.terminate();
      agents.delete(machineName);
      console.log(`[HEARTBEAT] terminated stale ${machineName}`);
      continue;
    }
    agent.ws.isAlive = false;
    try {
      agent.ws.ping();
      const sock = agent.ws._socket;
      if (sock && sock.setKeepAlive) sock.setKeepAlive(true, 15000);
    } catch { agents.delete(machineName); }
  }
}, 20000);

wss.on('close', () => clearInterval(interval));

console.log(`RuntimeBroker WebSocket hub ready at /ws/agent`);
