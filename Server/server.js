import { WebSocketServer } from 'ws';
import express from 'express';
import path from 'node:path';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 3001);
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || '';

const APP_DIR = __dirname;
const AGENTS_FILE = path.join(APP_DIR, 'agents.json');

// ── helpers ──────────────────────────────────────────────────────────────
const makeId = () => crypto.randomBytes(8).toString('hex');

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
app.use(express.static(path.join(APP_DIR, 'dashboard')));

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
  if (!token) {
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

// ── auth + agent lookup helpers ──────────────────────────────────────────
function requireAuth(req, res) {
  const expected = ADMIN_PASSWORD;
  const given = String((req.body && req.body.password) || req.headers['x-admin-password'] || '');
  if (given !== expected) return res.status(403).json({ success: false, error: 'Invalid admin password' });
  return null;
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
  if (requireAuth(req, res)) return;
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

app.get('/api/health', (req, res) => {
  res.json({ ok: true, agents: agents.size, version: '2.0.0' });
});

// ── Live screen WebSocket (phone → server → agent) ──────────────────────
const liveViewers = new Map(); // viewerWs -> { machineName, timer, lastAt }

liveWss.on('connection', (ws, req, machineName, url) => {
  const token = url.searchParams.get('token') || '';
  if (token !== ADMIN_PASSWORD) { ws.close(4001, 'Unauthorized'); return; }
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
