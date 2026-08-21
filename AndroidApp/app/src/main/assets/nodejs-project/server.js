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

let config = {};
try { config = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8')); } catch (e) { config = {}; }

const HOST = String(config.host || process.env.HOST || '0.0.0.0');
const PORT = Number(config.port || process.env.PORT || 4777);
const ADMIN_PASSWORD = String(config.adminPassword || process.env.ADMIN_PASSWORD || '.\\itdtpadmin');

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
  if (!token) { ws.close(4001, 'Unauthorized'); return; }

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

function requireAuth(req, res) {
  const given = String((req.body && req.body.password) || req.headers['x-admin-password'] || '');
  if (given !== ADMIN_PASSWORD) {
    res.status(403).json({ success: false, error: 'Invalid admin password' });
    return true;
  }
  return false;
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
  if (requireAuth(req, res)) return;
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
  if (requireAuth(req, res)) return;
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
    'play_audio', 'stop_audio', 'transfer_file', 'stop_typing'
  ]);
  if (!allowed.has(cmd)) return res.status(400).json({ success: false, error: 'Unknown command: ' + cmd });

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
  res.json({ ok: true, agents: agents.size, version: '2.0.0' });
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