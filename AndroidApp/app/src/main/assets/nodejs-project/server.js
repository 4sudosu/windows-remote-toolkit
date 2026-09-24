/* Runtime Broker — embedded server for Android (CommonJS build).
 * Runs inside the phone via nodejs-mobile. Ported from Server/server.js.
 * Reads server-config.json (written by the app) for host/port/admin password.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const http = require('http');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');
const express = require('express');

const APP_DIR = __dirname;
const AGENTS_FILE = path.join(APP_DIR, 'agents.json');
const CONFIG_FILE = path.join(APP_DIR, 'server-config.json');
const BLOCKED_DEVICES_FILE = path.join(APP_DIR, 'blocked_devices.json');
const MAX_LOGIN_ATTEMPTS = 3;
const MAX_DEVICE_ATTEMPTS = 3;
const SESSION_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const sessions = new Map();
let failedLoginCount = 0;
let loginLocked = false;

let config = {};
try { config = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8')); } catch (e) { config = {}; }

const HOST = String(config.host || process.env.HOST || '0.0.0.0');
const PORT = Number(config.port || process.env.PORT || 4777);
const ADMIN_PASSWORD = String(config.adminPassword || process.env.ADMIN_PASSWORD || '.\\itdtpadmin');

function readJson(file, fallback) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch (e) { return fallback; }
}
function writeJson(file, value) {
  try { fs.writeFileSync(file, JSON.stringify(value, null, 2)); } catch (e) { console.warn('Could not write ' + file, e.message); }
}
function blockedDevices() { return readJson(BLOCKED_DEVICES_FILE, {}); }
function isBlocked(deviceId) { return Boolean(deviceId && blockedDevices()[deviceId]?.locked); }
function recordFailure(deviceId) {
  const all = blockedDevices();
  const item = all[deviceId] || { attempts: 0, locked: false };
  item.attempts += 1;
  if (item.attempts >= MAX_DEVICE_ATTEMPTS) { item.locked = true; item.lockedAt = new Date().toISOString(); }
  all[deviceId] = item;
  writeJson(BLOCKED_DEVICES_FILE, all);
  return item;
}
function unlockDevice(deviceId) {
  const all = blockedDevices();
  delete all[deviceId];
  writeJson(BLOCKED_DEVICES_FILE, all);
}
function cookies(req) {
  return Object.fromEntries(String(req.headers.cookie || '').split(';').map(part => {
    const [key, ...value] = part.trim().split('=');
    return [key, decodeURIComponent(value.join('='))];
  }).filter(([key]) => key));
}
function authenticated(req) {
  if (String(req.headers['x-admin-password'] || '') === ADMIN_PASSWORD) return true;
  const token = cookies(req).wsm_auth;
  const expires = sessions.get(token);
  if (!expires || expires <= Date.now()) { if (token) sessions.delete(token); return false; }
  return true;
}
function requireAuth(req, res, next) {
  if (authenticated(req)) return next();
  if (req.path.indexOf('/api/') === 0) return res.status(401).json({ success: false, error: 'Authentication required' });
  return res.redirect('/login');
}

const makeId = () => crypto.randomBytes(8).toString('hex');

function readAgentsFile() {
  try { return JSON.parse(fs.readFileSync(AGENTS_FILE, 'utf8')); }
  catch (e) { return []; }
}
function saveAgentsFile(agents) {
  try { fs.writeFileSync(AGENTS_FILE, JSON.stringify(agents, null, 2)); }
  catch (e) { console.warn('Could not write agents.json:', e.message); }
}

const agents = new Map();

function upsertRegistry(info) {
  const list = readAgentsFile();
  const idx = list.findIndex(a => (a.machineName || '').toLowerCase() === (info.machineName || '').toLowerCase());
  const record = Object.assign({}, info, { lastSeen: new Date().toISOString() });
  if (idx >= 0) list[idx] = record;
  else list.push(record);
  saveAgentsFile(list);
}

const app = express();
app.use(express.json({ limit: '200mb' }));
app.get('/login', (req, res) => res.sendFile(path.join(APP_DIR, 'dashboard', 'login.html')));
app.get('/style.css', (req, res) => res.sendFile(path.join(APP_DIR, 'dashboard', 'style.css')));
app.post('/api/login', (req, res) => {
  if (loginLocked) return res.status(423).json({ success: false, error: 'Login locked until server restart' });
  if (String(req.body?.password || '') !== ADMIN_PASSWORD) {
    failedLoginCount += 1;
    loginLocked = failedLoginCount >= MAX_LOGIN_ATTEMPTS;
    return res.status(loginLocked ? 423 : 403).json({ success: false, error: loginLocked ? 'Login locked until server restart' : 'Invalid admin password', attemptsLeft: Math.max(0, MAX_LOGIN_ATTEMPTS - failedLoginCount) });
  }
  const token = crypto.randomBytes(32).toString('hex');
  sessions.set(token, Date.now() + SESSION_TTL_MS);
  res.setHeader('Set-Cookie', 'wsm_auth=' + token + '; Path=/; HttpOnly; SameSite=Strict; Max-Age=' + (SESSION_TTL_MS / 1000));
  failedLoginCount = 0;
  res.json({ success: true });
});
app.post('/api/logout', (req, res) => {
  const token = cookies(req).wsm_auth;
  if (token) sessions.delete(token);
  res.setHeader('Set-Cookie', 'wsm_auth=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0');
  res.json({ success: true });
});
app.post('/api/config', (req, res) => {
  const deviceId = String(req.headers['x-device-id'] || req.body?.deviceId || '');
  const password = String(req.headers['x-admin-password'] || req.body?.password || '');
  if (!deviceId) return res.status(400).json({ success: false, error: 'Missing device ID' });
  if (isBlocked(deviceId)) return res.status(423).json({ success: false, error: 'Device blocked', deviceBlocked: true });
  if (password !== ADMIN_PASSWORD) {
    const state = recordFailure(deviceId);
    return res.status(state.locked ? 423 : 403).json({ success: false, error: state.locked ? 'Device blocked' : 'Invalid admin password', authError: true, deviceBlocked: state.locked, attemptsLeft: Math.max(0, MAX_DEVICE_ATTEMPTS - state.attempts) });
  }
  unlockDevice(deviceId);
  res.json({ success: true, authError: false, deviceBlocked: false });
});
app.post(['/api/config/status', '/api/device-status'], (req, res) => {
  const deviceId = String(req.headers['x-device-id'] || req.body?.deviceId || '');
  if (isBlocked(deviceId)) return res.status(403).json({ success: false, error: 'Device is still blocked', deviceBlocked: true });
  res.json({ success: true, message: 'Device is allowed', deviceBlocked: false });
});
app.use(requireAuth);
app.use(express.static(path.join(APP_DIR, 'dashboard'), { index: false }));
app.get('/', (req, res) => res.sendFile(path.join(APP_DIR, 'dashboard', 'index.html')));

const server = http.createServer(app);
const wss = new WebSocketServer({ noServer: true });
const liveWss = new WebSocketServer({ noServer: true });
const pending = new Map();

server.on('upgrade', (req, socket, head) => {
  let url;
  try { url = new URL(req.url, 'http://localhost'); } catch (e) { socket.destroy(); return; }
  if (url.pathname === '/ws/agent') {
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
  } else {
    const m = /^\/ws\/live\/([^/?]+)/.exec(url.pathname);
    if (m) liveWss.handleUpgrade(req, socket, head, (ws) => liveWss.emit('connection', ws, req, m[1], url));
    else socket.destroy();
  }
});

wss.on('connection', (ws, req) => {
  let url;
  try { url = new URL(req.url, 'http://localhost'); } catch (e) { return; }
  const token = url.searchParams.get('token') || '';
  if (token !== ADMIN_PASSWORD) { ws.close(4001, 'Unauthorized'); return; }

  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  ws.on('message', (data) => {
    let msg;
    try { msg = JSON.parse(data.toString()); } catch (e) { return; }
    handleMessage(ws, msg);
  });
  ws.on('close', () => {
    failPendingFor(ws);
    const machineName = ws.machineName;
    if (machineName) {
      agents.delete(machineName);
      console.log('[AGENT OFFLINE] ' + machineName);
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
        machineName: machineName,
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
      agents.set(machineName, { ws: ws, info: info, lastSeen: Date.now() });
      upsertRegistry(info);
      console.log('[AGENT ONLINE] ' + machineName + ' | ' + info.model + ' | ' + info.username + ' | ' + info.ip);
      ws.send(JSON.stringify({ type: 'registered', machineName: machineName }));
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

function sendTask(agent, cmd, params, timeoutMs) {
  if (!timeoutMs) timeoutMs = 35000;
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
    try { agent.ws.send(JSON.stringify({ type: 'cmd', taskId: taskId, cmd: cmd, params: params || {} })); }
    catch (e) { pending.delete(taskId); resolve({ success: false, error: 'Agent offline' }); }
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
    try { viewer.close(1000, 'Emergency stop'); } catch (e) {}
  }
}

function findAgent(req, res) {
  const agent = agents.get(String((req.params.machineName || '')).toLowerCase());
  if (!agent || agent.ws.readyState !== agent.ws.OPEN) {
    res.status(409).json({ success: false, error: 'Agent offline' });
    return null;
  }
  return agent;
}

app.get('/api/agents', (req, res) => {
  const q = String(req.query.q || '').toLowerCase();
  const list = [];
  for (const [machineName, agent] of agents) {
    if (agent.ws.readyState !== agent.ws.OPEN) continue;
    const info = agent.info;
    if (q && ![info.hostname, machineName, info.serial, info.ip, info.model]
      .some(v => String(v || '').toLowerCase().includes(q))) continue;
    list.push(Object.assign({}, info, { online: true, lastSeen: new Date().toISOString() }));
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
    console.log('[SCREENSHOT] ' + req.params.machineName);
    res.json({ success: true, image: result.output, at: new Date().toISOString() });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

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
  if (!allowed.has(cmd)) return res.status(400).json({ success: false, error: 'Unknown command: ' + cmd });

  if (cmd === 'stop_all') {
    cancelPendingFor(agent.ws);
    stopLiveFor(String(req.params.machineName || '').toLowerCase());
    try {
      agent.ws.send(JSON.stringify({ type: 'cmd', taskId: makeId(), cmd: cmd, params: {} }));
      console.log('[STOP ALL] ' + req.params.machineName);
      return res.json({ success: true, output: 'All automation stopped; input released' });
    } catch (e) {
      return res.status(409).json({ success: false, error: 'Agent offline' });
    }
  }

  if (params.async === true) {
    const rest = Object.assign({}, params);
    delete rest.async;
    agent.ws.send(JSON.stringify({ type: 'cmd', taskId: makeId(), cmd: cmd, params: rest }));
    console.log('[CMD] ' + req.params.machineName + ' -> ' + cmd + ' (async started)');
    return res.json({ success: true, output: 'Started', async: true });
  }

  try {
    const result = await sendTask(agent, cmd, params, timeoutMs);
    console.log('[CMD] ' + req.params.machineName + ' -> ' + cmd + ' (' + (result.success ? 'ok' : 'fail') + ')');
    if (!result.success) {
      const err = result.error === 'TIMEOUT' ? 'Command timed out' : (result.error || 'Command failed');
      return res.status(500).json({ success: false, error: err });
    }
    res.json({
      success: true,
      output: result.output || '',
      data: result.data || null,
      exitCode: result.exitCode === undefined ? 0 : result.exitCode
    });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

app.get('/api/health', (req, res) => {
  res.json({ ok: true, agents: agents.size, version: '2.1.0' });
});

app.get('/api/admin/blocked-devices', (req, res) => res.json(blockedDevices()));
app.post('/api/admin/unlock-device', (req, res) => {
  const deviceId = String(req.body?.deviceId || '');
  if (!deviceId) return res.status(400).json({ success: false, error: 'Missing device ID' });
  unlockDevice(deviceId);
  res.json({ success: true });
});

const liveViewers = new Map();

liveWss.on('connection', (ws, req, machineName, url) => {
  const token = url.searchParams.get('token') || '';
  if (token !== ADMIN_PASSWORD) { ws.close(4001, 'Unauthorized'); return; }
  const agent = agents.get(String(machineName || '').toLowerCase());
  if (!agent || agent.ws.readyState !== agent.ws.OPEN) { ws.close(4004, 'Agent offline'); return; }

  liveViewers.set(ws, { machineName: machineName.toLowerCase(), timer: null });
  console.log('[LIVE] ' + machineName + ' viewer connected');

  const frameInterval = Math.max(250, Math.min(3000, Number(url.searchParams.get('interval') || 500)));
  // Small fast JPEG frames keep the live stream smooth over WiFi; the
  // full-quality PNG is only used by the one-shot /screenshot API.
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
    } catch (e) { clearInterval(timer); }
  }, frameInterval);
  if (liveViewers.has(ws)) liveViewers.get(ws).timer = timer;

  ws.on('message', (data) => {
    if (ws.readyState !== ws.OPEN) return;
    let msg;
    try { msg = JSON.parse(data.toString()); } catch (e) { return; }
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
    console.log('[LIVE] ' + machineName + ' viewer disconnected');
  });
  ws.on('error', () => {});
});

const interval = setInterval(() => {
  for (const [machineName, agent] of agents) {
    if (!agent.ws.isAlive) {
      agent.ws.terminate();
      agents.delete(machineName);
      continue;
    }
    agent.ws.isAlive = false;
    try { agent.ws.ping(); } catch (e) { agents.delete(machineName); }
  }
}, 30000);

server.listen(PORT, HOST, () => {
  console.log('Runtime Broker server running at: http://' + HOST + ':' + PORT);
  console.log('Runtime Broker WebSocket hub ready at /ws/agent');
});
