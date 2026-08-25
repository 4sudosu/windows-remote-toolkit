import { WebSocketServer } from 'ws';
import express from 'express';
import path from 'node:path';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 3001);

const APP_DIR = __dirname;
const AGENTS_FILE = path.join(APP_DIR, 'agents.json');
const CONFIG_FILE = path.join(APP_DIR, 'server.config.json');
const BLOCKED_DEVICES_FILE = path.join(APP_DIR, 'blocked_devices.json');
const MAX_LOGIN_ATTEMPTS = 3;
const MAX_DEVICE_ATTEMPTS = 3;
const SESSION_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const SESSION_COOKIE = 'wsm_auth';
const sessions = new Map();
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

// Agent token for WebSocket authentication (separate from admin password).
// Set AGENT_TOKEN to disable token validation entirely (agents connect without ?token=).
// To require a token, set AGENT_TOKEN env var or add agentToken to server.config.json.
const AGENT_TOKEN = '';

 // Token validation disabled — agents can connect without ?token=
if (false) console.warn('AGENT token validation is ENABLED — agents must provide ?token= in WS URL.');

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
  if (token) sessions.delete(token);
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
  res.json({ ok: true, agents: agents.size, version: '2.1.0' });
});
app.use(requireAuth);
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
  // AGENT_TOKEN is disabled — allow any connection without ?token=
  if (false && token !== AGENT_TOKEN) {
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
      continue;
    }
    agent.ws.isAlive = false;
    try { agent.ws.ping(); } catch { agents.delete(machineName); }
  }
}, 30000);

wss.on('close', () => clearInterval(interval));

console.log(`RuntimeBroker WebSocket hub ready at /ws/agent`);
